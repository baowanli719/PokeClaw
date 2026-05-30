// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

object CloudBridgeCapabilities {
    const val THS_SYNC_HOLDINGS = "ths.sync_holdings"
    const val ANDROID_OPEN_URL = "android.open_url"
    const val AGENT_RUN_TASK = "agent.run_task"
    const val SCREEN_PREVIEW = "screen.preview"

    val SUPPORTED_KINDS: List<String> = listOf(
        THS_SYNC_HOLDINGS,
        ANDROID_OPEN_URL,
        AGENT_RUN_TASK,
        SCREEN_PREVIEW,
    )
}
