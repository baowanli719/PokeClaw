// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import com.google.gson.JsonObject

/**
 * Maps a cloud task `kind` + params to a task text string
 * that [io.agents.pokeclaw.TaskOrchestrator] can execute.
 *
 * v1 supports a single kind: `ths.sync_holdings`.
 * The registry pattern allows future kinds to be added without modifying this class.
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
            "ths.sync_holdings" -> "/ths sync_holdings $params"
            else -> null
        }
    }
}
