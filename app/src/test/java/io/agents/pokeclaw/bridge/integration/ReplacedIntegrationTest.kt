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
 * Integration test: server sends close(4401) → client enters
 * Stopped(REPLACED) and does NOT attempt reconnection.
 *
 * Validates: Requirements 4.3
 */
class ReplacedIntegrationTest : BridgeIntegrationTestBase() {

    @BeforeEach
    fun init() = setUp()

    @AfterEach
    fun cleanup() = tearDown()

    @Test
    fun `server close 4401 transitions to Stopped REPLACED no reconnect`() {
        // Authenticate first, then server closes with 4401
        val authenticated = CountDownLatch(1)
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            if (obj.get("type")?.asString == "hello") {
                sendHelloAck(heartbeatSec = 30)
            }
        }

        client.start()
        assertThat(waitForState(ConnectionState.Authenticated)).isTrue()

        // Server sends close(4401) — device replaced
        serverListener.close(4401, "replaced")

        // Client should transition to Stopped(REPLACED)
        val expected = ConnectionState.Stopped(StopReason.REPLACED)
        assertThat(waitForState(expected)).isTrue()

        // Wait and verify no reconnection attempt
        Thread.sleep(2_000)
        assertThat(client.currentState()).isEqualTo(expected)
    }
}
