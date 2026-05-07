package io.agents.pokeclaw.bridge.connection

/**
 * Exponential backoff policy for reconnection attempts.
 *
 * Sequence: 1s → 2s → 4s → 8s → 16s → 32s → 60s → 60s → …
 * Resets to [initialMs] on successful connection or network availability event.
 */
internal class BackoffPolicy(
    private val initialMs: Long = 1_000L,
    private val maxMs: Long = 60_000L,
) {
    private var currentMs: Long = initialMs

    /**
     * Returns the current delay and advances to the next value (doubled, capped at [maxMs]).
     */
    fun nextDelayMs(): Long {
        val d = currentMs
        currentMs = (currentMs * 2).coerceAtMost(maxMs)
        return d
    }

    /** Resets the delay back to [initialMs]. */
    fun reset() {
        currentMs = initialMs
    }
}
