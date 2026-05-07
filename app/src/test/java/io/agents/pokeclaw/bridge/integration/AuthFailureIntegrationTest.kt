package io.agents.pokeclaw.bridge.integration

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.ConnectionState
import io.agents.pokeclaw.bridge.StopReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration test: server closes with 4403 after hello → client enters
 * Stopped(AUTH_FAILED) and does NOT attempt reconnection.
 *
 * Validates: Requirements 4.2
 */
class AuthFailureIntegrationTest : BridgeIntegrationTestBase() {

    @BeforeEach
    fun init() = setUp()

    @AfterEach
    fun cleanup() = tearDown()

    @Test
    fun `server close 4403 after hello transitions to Stopped AUTH_FAILED no reconnect`() {
        // When server receives hello, close with 4403
        val helloReceived = CountDownLatch(1)
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            if (obj.get("type")?.asString == "hello") {
                helloReceived.countDown()
                // Close with auth failure code
                serverListener.close(4403, "auth_failed")
            }
        }

        client.start()

        // Wait for hello to be received
        assertThat(helloReceived.await(5, TimeUnit.SECONDS)).isTrue()

        // Client should transition to Stopped(AUTH_FAILED)
        val expected = ConnectionState.Stopped(StopReason.AUTH_FAILED)
        assertThat(waitForState(expected)).isTrue()

        // Wait a bit and verify no reconnection attempt (state stays terminal)
        Thread.sleep(2_000)
        assertThat(client.currentState()).isEqualTo(expected)
    }
}
