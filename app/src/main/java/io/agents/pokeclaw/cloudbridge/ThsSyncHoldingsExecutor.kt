// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback
import io.agents.pokeclaw.bridge.api.TaskHandle
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deterministic executor for ths.sync_holdings.
 *
 * It intentionally fails fast on login / captcha screens instead of falling
 * back to the general UI agent, because guessing credentials or captcha flows
 * is both brittle and unsafe.
 */
class ThsSyncHoldingsExecutor(
    private val runner: ThsAutomationRunner = ToolRegistryThsAutomationRunner(),
) {

    fun execute(
        requestId: String,
        params: JsonObject,
        callback: TaskExecutorCallback,
    ): TaskHandle {
        val cancelled = AtomicBoolean(false)
        active[requestId] = cancelled
        val accountAlias = params.stringOrNull("account_alias")
            ?: params.stringOrNull("account")
            ?: "main"

        callback.onAccepted(requestId)
        Thread({
            try {
                runSync(requestId, accountAlias, callback, cancelled)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                if (!cancelled.get()) {
                    callback.onError(requestId, "cancelled", "Task interrupted", false)
                }
            } catch (e: ThsTaskFailure) {
                if (!cancelled.get()) {
                    callback.onError(requestId, e.code, e.message ?: e.code, e.retryable)
                }
            } catch (e: Exception) {
                XLog.e(TAG, "ths.sync_holdings failed", e)
                if (!cancelled.get()) {
                    callback.onError(
                        requestId,
                        "internal",
                        e.message ?: "ths.sync_holdings failed",
                        retryable = true,
                    )
                }
            } finally {
                active.remove(requestId)
            }
        }, "ths-sync-holdings-$requestId").start()

        return object : TaskHandle {
            override val requestId: String = requestId
            override fun isActive(): Boolean = active[requestId]?.get() == false
        }
    }

    fun cancel(requestId: String) {
        active[requestId]?.set(true)
    }

    private fun runSync(
        requestId: String,
        accountAlias: String,
        callback: TaskExecutorCallback,
        cancelled: AtomicBoolean,
    ) {
        checkpoint(cancelled)
        callback.onProgress(requestId, "opening_ths", 0.1)
        runner.openThs().requireSuccess("app_not_installed")

        checkpoint(cancelled)
        callback.onProgress(requestId, "checking_login_state", 0.25)
        var screen = runner.readScreen().requireSuccess("ui_changed")
        if (ThsScreenClassifier.isLoginRequired(screen)) {
            callback.onError(
                requestId,
                "login_required",
                "TongHuaShun requires sign-in or captcha before holdings can be read.",
                retryable = false,
            )
            return
        }

        if (!ThsScreenClassifier.isHoldingsScreen(screen)) {
            callback.onProgress(requestId, "navigating_to_holdings", 0.45)
            navigateTowardHoldings()
            checkpoint(cancelled)
            screen = runner.readScreen().requireSuccess("ui_changed")
        }

        if (ThsScreenClassifier.isLoginRequired(screen)) {
            callback.onError(
                requestId,
                "login_required",
                "TongHuaShun requires sign-in or captcha before holdings can be read.",
                retryable = false,
            )
            return
        }

        if (!ThsScreenClassifier.isHoldingsScreen(screen)) {
            callback.onError(
                requestId,
                "ui_changed",
                "Could not reach the TongHuaShun holdings screen without user login.",
                retryable = true,
            )
            return
        }

        callback.onProgress(requestId, "reading_holdings", 0.8)
        callback.onResult(
            requestId,
            CloudBridgeCapabilities.THS_SYNC_HOLDINGS,
            ThsHoldingsPayloadParser.toResult(accountAlias, screen),
        )
    }

    private fun navigateTowardHoldings() {
        for (target in NAVIGATION_TARGETS) {
            runner.tapText(target)
            runner.waitMs(700)
            val screen = runner.readScreen().data.orEmpty()
            if (ThsScreenClassifier.isLoginRequired(screen) ||
                ThsScreenClassifier.isHoldingsScreen(screen)
            ) {
                return
            }
        }
    }

    private fun checkpoint(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw InterruptedException("cancelled")
    }

    private fun ToolResult.requireSuccess(code: String): String {
        if (isSuccess && data != null) return data
        throw ThsTaskFailure(code, "$code: ${error ?: "unknown error"}")
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "ThsSyncHoldingsExecutor"
        private val active = ConcurrentHashMap<String, AtomicBoolean>()
        private val NAVIGATION_TARGETS = listOf("交易", "持仓", "资产", "我的")
    }
}

private class ThsTaskFailure(
    val code: String,
    override val message: String,
    val retryable: Boolean = true,
) : RuntimeException(message)

interface ThsAutomationRunner {
    fun openThs(): ToolResult
    fun readScreen(): ToolResult
    fun tapText(text: String): ToolResult
    fun waitMs(durationMs: Long)
}

class ToolRegistryThsAutomationRunner : ThsAutomationRunner {
    override fun openThs(): ToolResult =
        ToolRegistry.executeTool(
            "open_app",
            mapOf("package_name" to THS_PACKAGE, "wait_after" to 3000),
        )

    override fun readScreen(): ToolResult =
        ToolRegistry.executeTool("get_screen_info", emptyMap())

    override fun tapText(text: String): ToolResult =
        ToolRegistry.executeTool(
            "find_and_tap",
            mapOf("text" to text, "max_scrolls" to 2, "wait_after" to 1000),
        )

    override fun waitMs(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
    }

    companion object {
        const val THS_PACKAGE = "com.hexin.plat.android"
    }
}

object ThsScreenClassifier {
    private val loginMarkers = listOf(
        "登录", "登陆", "手机号", "手机号码", "验证码", "短信验证码",
        "密码", "忘记密码", "一键登录", "注册", "获取验证码",
        "login", "password", "verification code", "captcha",
    )

    private val holdingsMarkers = listOf(
        "持仓", "证券市值", "总资产", "可用资金", "浮动盈亏",
        "当日盈亏", "持仓盈亏", "成本价", "现价", "可用",
    )

    fun isLoginRequired(screenText: String): Boolean {
        val normalized = screenText.lowercase()
        return loginMarkers.count { normalized.contains(it.lowercase()) } >= 2 ||
            (normalized.contains("验证码") && normalized.contains("登录"))
    }

    fun isHoldingsScreen(screenText: String): Boolean {
        val normalized = screenText.lowercase()
        return normalized.contains("持仓") &&
            holdingsMarkers.count { normalized.contains(it.lowercase()) } >= 2
    }
}

object ThsHoldingsPayloadParser {
    private val numberPattern = Regex("""[-+]?\d+(?:,\d{3})*(?:\.\d+)?%?""")
    private val stockCodePattern = Regex("""\b(?:[036]\d{5}|[89]\d{5})\b""")

    fun toResult(accountAlias: String, screenText: String): JsonObject {
        return JsonObject().apply {
            addProperty("kind", CloudBridgeCapabilities.THS_SYNC_HOLDINGS)
            addProperty("captured_at", OffsetDateTime.now().toString())
            addProperty("account_alias", accountAlias)
            add("summary", parseSummary(screenText))
            add("positions", parsePositions(screenText))
        }
    }

    private fun parseSummary(screenText: String): JsonObject {
        return JsonObject().apply {
            addNullableNumber("total_asset", valueNearLabel(screenText, "总资产", "资产总额"))
            addNullableNumber("market_value", valueNearLabel(screenText, "证券市值", "股票市值", "持仓市值"))
            addNullableNumber("cash", valueNearLabel(screenText, "可用资金", "可取资金", "现金"))
            addNullableNumber("profit_loss", valueNearLabel(screenText, "持仓盈亏", "浮动盈亏", "当日盈亏"))
            addNullableNumber("profit_loss_ratio", valueNearLabel(screenText, "盈亏比例", "收益率"))
            addProperty("currency", "CNY")
        }
    }

    private fun parsePositions(screenText: String): JsonArray {
        val positions = JsonArray()
        val lines = screenText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for ((index, line) in lines.withIndex()) {
            val code = stockCodePattern.find(line)?.value ?: continue
            val window = lines.drop(index).take(4).joinToString(" ")
            positions.add(JsonObject().apply {
                addProperty("stock_code", code)
                addProperty("stock_name", inferStockName(line, code))
                addNullableNumber("quantity", valueNearLabel(window, "持仓", "数量")?.toInt())
                addNullableNumber("available", valueNearLabel(window, "可用")?.toInt())
                addNullableNumber("cost_price", valueNearLabel(window, "成本", "成本价"))
                addNullableNumber("current_price", valueNearLabel(window, "现价", "最新价"))
                addNullableNumber("market_value", valueNearLabel(window, "市值"))
                addNullableNumber("profit_loss", valueNearLabel(window, "盈亏"))
                addNullableNumber("profit_loss_ratio", valueNearLabel(window, "盈亏比例", "收益率"))
            })
        }
        return positions
    }

    private fun valueNearLabel(text: String, vararg labels: String): Double? {
        for (label in labels) {
            val index = text.indexOf(label)
            if (index < 0) continue
            val after = text.substring(index + label.length).take(80)
            val raw = numberPattern.find(after)?.value ?: continue
            return raw.toNumberOrNull()
        }
        return null
    }

    private fun inferStockName(line: String, code: String): String {
        val beforeCode = line.substringBefore(code).trim()
        val afterCode = line.substringAfter(code).trim()
        val candidate = beforeCode.ifEmpty { afterCode }
            .split(Regex("""\s+"""))
            .firstOrNull { it.any { ch -> !ch.isDigit() } }
        return candidate?.take(24) ?: code
    }

    private fun String.toNumberOrNull(): Double? =
        trim().removeSuffix("%").replace(",", "").toDoubleOrNull()

    private fun JsonObject.addNullableNumber(name: String, value: Number?) {
        if (value == null) {
            add(name, JsonNull.INSTANCE)
        } else {
            addProperty(name, value)
        }
    }
}
