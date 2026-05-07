package io.agents.pokeclaw.bridge.task

import com.google.gson.JsonObject
import java.util.concurrent.ScheduledFuture

/**
 * Represents a single in-flight task being executed.
 * Only accessed on the bridge dispatcher thread — no synchronization needed.
 */
internal data class InFlightTask(
    val requestId: String,
    val kind: String,
    val params: JsonObject,
    val deadlineTsMillis: Long?,
    val acceptedAtMs: Long,
    var deadlineFuture: ScheduledFuture<*>? = null,
    var terminalSent: Boolean = false,
)
