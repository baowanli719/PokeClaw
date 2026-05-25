// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject

/**
 * Maps a cloud task `kind` + params to a task text string
 * that [io.agents.pokeclaw.TaskOrchestrator] can execute.
 *
 * Maps Cloud Bridge kinds to existing app task text.
 */
interface KindMapper {
    /**
     * Convert a kind + params into a task text string for the orchestrator.
     * Returns null if the kind is not supported.
     */
    fun toTaskText(kind: String, params: JsonObject): String?
}

object CloudKindMapper : KindMapper {

    override fun toTaskText(kind: String, params: JsonObject): String? {
        return when (kind) {
            CloudBridgeCapabilities.THS_SYNC_HOLDINGS -> "/ths sync_holdings $params"
            CloudBridgeCapabilities.ANDROID_OPEN_URL -> mapOpenUrl(params)
            CloudBridgeCapabilities.AGENT_RUN_TASK -> mapAgentTask(params)
            else -> null
        }
    }

    private fun mapOpenUrl(params: JsonObject): String? {
        val url = params.stringOrNull("url") ?: params.stringOrNull("uri") ?: return null
        val normalizedUrl = when {
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> url
            else -> "https://$url"
        }
        return "open $normalizedUrl"
    }

    private fun mapAgentTask(params: JsonObject): String? {
        return params.stringOrNull("task")
            ?: params.stringOrNull("prompt")
            ?: params.stringOrNull("instruction")
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return value.asString.trim().takeIf { it.isNotEmpty() }
    }
}
