// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject
import io.agents.pokeclaw.tool.ToolResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ThsSyncHoldingsExecutorTest {

    @Test
    fun `login screen returns login_required without tapping`() {
        val runner = FakeThsRunner("同花顺 登录 手机号 密码 验证码 获取验证码")
        val callback = RecordingCallback()
        val executor = ThsSyncHoldingsExecutor(runner)

        executor.execute("req_login", JsonObject(), callback)

        waitUntil { callback.errors.isNotEmpty() }
        assertThat(callback.acceptedIds).containsExactly("req_login")
        assertThat(callback.errors[0].code).isEqualTo("login_required")
        assertThat(callback.errors[0].retryable).isFalse()
        assertThat(runner.tappedTexts).isEmpty()
    }

    @Test
    fun `holdings screen returns structured result`() {
        val screen = """
            同花顺 持仓
            总资产 1000.50
            证券市值 800.25
            可用资金 200.25
            000001 平安银行 持仓 100 可用 100 成本价 10.00 现价 11.00 市值 1100.00 盈亏 100.00
        """.trimIndent()
        val runner = FakeThsRunner(screen)
        val callback = RecordingCallback()
        val executor = ThsSyncHoldingsExecutor(runner)
        val params = JsonObject().apply { addProperty("account_alias", "main") }

        executor.execute("req_ok", params, callback)

        waitUntil { callback.results.isNotEmpty() }
        val result = callback.results[0].result
        assertThat(result.get("kind").asString).isEqualTo("ths.sync_holdings")
        assertThat(result.get("account_alias").asString).isEqualTo("main")
        assertThat(result.getAsJsonObject("summary").get("total_asset").asDouble).isEqualTo(1000.50)
        assertThat(result.getAsJsonArray("positions")).hasSize(1)
        assertThat(result.getAsJsonArray("positions")[0].asJsonObject.get("stock_code").asString)
            .isEqualTo("000001")
    }

    @Test
    fun `unknown page returns ui_changed instead of falling back to agent`() {
        val runner = FakeThsRunner("同花顺 首页 自选 行情 资讯")
        val callback = RecordingCallback()
        val executor = ThsSyncHoldingsExecutor(runner)

        executor.execute("req_unknown", JsonObject(), callback)

        waitUntil { callback.errors.isNotEmpty() }
        assertThat(callback.errors[0].code).isEqualTo("ui_changed")
        assertThat(callback.errors[0].retryable).isTrue()
        assertThat(runner.tappedTexts).contains("交易", "持仓")
    }

    private fun waitUntil(predicate: () -> Boolean) {
        repeat(50) {
            if (predicate()) return
            Thread.sleep(20)
        }
        error("Timed out waiting for async callback")
    }

    private class FakeThsRunner(vararg screens: String) : ThsAutomationRunner {
        private val screenQueue = screens.toMutableList()
        val tappedTexts = mutableListOf<String>()

        override fun openThs(): ToolResult = ToolResult.success("opened")

        override fun readScreen(): ToolResult {
            val screen = when {
                screenQueue.size > 1 -> screenQueue.removeAt(0)
                screenQueue.isNotEmpty() -> screenQueue[0]
                else -> ""
            }
            return ToolResult.success(screen)
        }

        override fun tapText(text: String): ToolResult {
            tappedTexts.add(text)
            return ToolResult.success("tapped $text")
        }

        override fun waitMs(durationMs: Long) = Unit
    }
}
