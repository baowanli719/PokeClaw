package io.agents.pokeclaw.bridge.connection

import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.Clock
import java.util.concurrent.ScheduledFuture

/**
 * Schedules periodic heartbeat ticks and handles inbound ping → pong responses.
 *
 * Validates: Requirements 3.1, 3.2, 3.3
 */
internal class HeartbeatScheduler(
    private val dispatcher: BridgeDispatcher,
    private val clock: Clock,
    private val onTick: () -> Unit,
    private val onPong: (id: String?) -> Unit,
) {
    private var heartbeatFuture: ScheduledFuture<*>? = null
    private var heartbeatIntervalMs: Long = 0L

    /**
     * Starts periodic heartbeat ticks at [heartbeatSec] intervals.
     * Safe to call multiple times; previous schedule is cancelled.
     */
    fun start(heartbeatSec: Int) {
        stop()
        heartbeatIntervalMs = heartbeatSec * 1000L
        scheduleNext()
    }

    /** Stops the heartbeat scheduler. Cancels any pending tick. */
    fun stop() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = null
    }

    /**
     * Handles an inbound ping frame. Immediately schedules a pong response.
     */
    fun onPing(id: String?) {
        dispatcher.execute { onPong(id) }
    }

    private fun scheduleNext() {
        heartbeatFuture = dispatcher.schedule(heartbeatIntervalMs) {
            onTick()
            scheduleNext()
        }
    }
}
