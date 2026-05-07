package io.agents.pokeclaw.bridge.internal

/**
 * Controllable clock for use in unit tests.
 * Both wall-clock and monotonic time are manually advanced.
 */
class FakeClock(
    private var wallMs: Long = 0L,
    private var monotonicMs: Long = 0L,
) : Clock {
    override fun nowMillis(): Long = wallMs
    override fun monotonicMillis(): Long = monotonicMs

    fun advanceWall(ms: Long) { wallMs += ms }
    fun advanceMonotonic(ms: Long) { monotonicMs += ms }
    fun advance(ms: Long) { advanceWall(ms); advanceMonotonic(ms) }
    fun setWall(ms: Long) { wallMs = ms }
    fun setMonotonic(ms: Long) { monotonicMs = ms }
}
