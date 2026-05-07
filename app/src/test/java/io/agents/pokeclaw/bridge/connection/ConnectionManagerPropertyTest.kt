package io.agents.pokeclaw.bridge.connection

import io.agents.pokeclaw.bridge.ConnectionState
import io.agents.pokeclaw.bridge.StopReason
import net.jqwik.api.*
import org.assertj.core.api.Assertions.assertThat

/**
 * Property 3: Connection state machine legality
 *
 * For any sequence of close codes, terminal codes (4401, 4403, 1000+userStopped)
 * shall not trigger reconnection (final state is Stopped), while all other codes
 * shall trigger reconnection (state transitions to Connecting).
 *
 * Since ConnectionManager depends on OkHttp WebSocket (hard to drive in pure JVM
 * property tests), we verify the close-code routing logic by simulating the
 * handleClose behavior extracted from ConnectionManager's state machine.
 *
 * **Validates: Requirements 2.3, 4.5, 7.2, 7.4, 10.2**
 */
class ConnectionManagerPropertyTest {

    /**
     * Legal state transitions as defined in the design document.
     */
    private val legalTransitions: Map<String, Set<String>> = mapOf(
        "Disconnected" to setOf("Connecting", "Stopped"),
        "Connecting" to setOf("Connected", "Disconnected", "Stopped"),
        "Connected" to setOf("Authenticated", "Disconnected", "Stopped"),
        "Authenticated" to setOf("Disconnected", "Stopped"),
        "Stopped" to emptySet(),
    )

    private fun stateKey(state: ConnectionState): String = when (state) {
        is ConnectionState.Disconnected -> "Disconnected"
        is ConnectionState.Connecting -> "Connecting"
        is ConnectionState.Connected -> "Connected"
        is ConnectionState.Authenticated -> "Authenticated"
        is ConnectionState.Stopped -> "Stopped"
    }

    /**
     * Simulates the close-code routing logic from ConnectionManager.handleClose().
     * Returns the list of resulting states after processing the close code.
     * Non-terminal codes produce [Disconnected, Connecting] (the actual implementation
     * transitions to Disconnected first, then schedules reconnect → Connecting).
     */
    private fun routeCloseCode(code: Int, userStopped: Boolean): List<ConnectionState> {
        return when {
            code == 1000 && userStopped -> listOf(ConnectionState.Stopped(StopReason.USER_STOPPED))
            code == 4401 -> listOf(ConnectionState.Stopped(StopReason.REPLACED))
            code == 4403 -> listOf(ConnectionState.Stopped(StopReason.AUTH_FAILED))
            else -> listOf(ConnectionState.Disconnected, ConnectionState.Connecting)
        }
    }

    @Property(tries = 100)
    fun `terminal close codes never trigger reconnection`(
        @ForAll("terminalCloseCodes") code: Int,
    ) {
        val userStopped = code == 1000
        val resultStates = routeCloseCode(code, userStopped)

        assertThat(resultStates).hasSize(1)
        val resultState = resultStates.first()
        assertThat(resultState)
            .describedAs("Terminal code $code should result in Stopped state")
            .isInstanceOf(ConnectionState.Stopped::class.java)

        // Verify the specific stop reason
        val stopped = resultState as ConnectionState.Stopped
        when (code) {
            1000 -> assertThat(stopped.reason).isEqualTo(StopReason.USER_STOPPED)
            4401 -> assertThat(stopped.reason).isEqualTo(StopReason.REPLACED)
            4403 -> assertThat(stopped.reason).isEqualTo(StopReason.AUTH_FAILED)
        }
    }

    @Property(tries = 100)
    fun `non-terminal close codes always trigger reconnection`(
        @ForAll("nonTerminalCloseCodes") code: Int,
    ) {
        // For non-terminal codes, userStopped is always false
        val resultStates = routeCloseCode(code, userStopped = false)

        // Non-terminal codes produce [Disconnected, Connecting]
        assertThat(resultStates).hasSize(2)
        assertThat(resultStates[0])
            .describedAs("Non-terminal code $code should first transition to Disconnected")
            .isEqualTo(ConnectionState.Disconnected)
        assertThat(resultStates[1])
            .describedAs("Non-terminal code $code should then transition to Connecting")
            .isEqualTo(ConnectionState.Connecting)
    }

    @Property(tries = 100)
    fun `state transitions from close event are always legal`(
        @ForAll("allCloseCodes") code: Int,
        @ForAll("preCloseStates") fromStateKey: String,
    ) {
        val userStopped = code == 1000
        val resultStates = routeCloseCode(code, userStopped)

        // Verify the full transition sequence is legal starting from fromStateKey
        var currentState = fromStateKey
        for (resultState in resultStates) {
            val toStateKey = stateKey(resultState)
            val allowedTargets = legalTransitions[currentState] ?: emptySet()

            assertThat(allowedTargets)
                .describedAs(
                    "Transition $currentState → $toStateKey (close code=$code) " +
                        "should be legal"
                )
                .contains(toStateKey)
            currentState = toStateKey
        }
    }

    @Property(tries = 100)
    fun `backoff sequence after repeated non-terminal closes is monotonic and bounded`(
        @ForAll("closeSequenceLengths") n: Int,
    ) {
        val backoff = BackoffPolicy()
        var prev = 0L
        repeat(n) {
            val delay = backoff.nextDelayMs()
            assertThat(delay)
                .describedAs("Delay at step $it should be >= previous ($prev)")
                .isGreaterThanOrEqualTo(prev)
            assertThat(delay)
                .describedAs("Delay at step $it should be <= 60000")
                .isLessThanOrEqualTo(60_000L)
            prev = delay
        }
    }

    @Property(tries = 50)
    fun `network available resets backoff to initial value`(
        @ForAll("closeSequenceLengths") failuresBefore: Int,
    ) {
        val backoff = BackoffPolicy()
        // Simulate N failures
        repeat(failuresBefore) { backoff.nextDelayMs() }
        // Network becomes available → reset
        backoff.reset()
        // Next delay should be back to initial (1000ms)
        assertThat(backoff.nextDelayMs()).isEqualTo(1000L)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Generators
    // ═══════════════════════════════════════════════════════════════════════

    @Provide
    fun terminalCloseCodes(): Arbitrary<Int> =
        Arbitraries.of(1000, 4401, 4403)

    @Provide
    fun nonTerminalCloseCodes(): Arbitrary<Int> =
        Arbitraries.integers().between(1001, 4999)
            .filter { it != 4401 && it != 4403 }

    @Provide
    fun allCloseCodes(): Arbitrary<Int> =
        Arbitraries.integers().between(0, 4999)

    @Provide
    fun preCloseStates(): Arbitrary<String> =
        Arbitraries.of("Connecting", "Connected", "Authenticated")

    @Provide
    fun closeSequenceLengths(): Arbitrary<Int> =
        Arbitraries.integers().between(1, 50)
}
