package io.agents.pokeclaw.bridge.internal

/**
 * Abstraction over time sources, enabling deterministic testing.
 */
interface Clock {
    /** Wall-clock time in milliseconds since epoch. */
    fun nowMillis(): Long

    /** Monotonic time in milliseconds (suitable for measuring elapsed time). */
    fun monotonicMillis(): Long
}

/**
 * Production implementation using system time sources.
 */
object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
}
