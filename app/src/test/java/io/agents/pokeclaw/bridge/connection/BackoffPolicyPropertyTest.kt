package io.agents.pokeclaw.bridge.connection

import net.jqwik.api.*
import org.assertj.core.api.Assertions.assertThat

/**
 * Property 4: Backoff is monotonic, bounded, and resettable
 * Validates: Requirements 4.1, 4.4, 4.6
 */
class BackoffPolicyPropertyTest {

    @Property(tries = 100)
    fun `backoff sequence is monotonic non-decreasing and bounded at 60000`(
        @ForAll("callCounts") n: Int,
    ) {
        val policy = BackoffPolicy()
        var prev = 0L
        repeat(n) {
            val delay = policy.nextDelayMs()
            assertThat(delay).isGreaterThanOrEqualTo(prev)
            assertThat(delay).isLessThanOrEqualTo(60_000L)
            prev = delay
        }
    }

    @Property(tries = 100)
    fun `reset restores initial delay`(
        @ForAll("callCounts") callsBefore: Int,
    ) {
        val policy = BackoffPolicy()
        repeat(callsBefore) { policy.nextDelayMs() }
        policy.reset()
        assertThat(policy.nextDelayMs()).isEqualTo(1000L)
    }

    @Provide
    fun callCounts(): Arbitrary<Int> = Arbitraries.integers().between(0, 200)
}
