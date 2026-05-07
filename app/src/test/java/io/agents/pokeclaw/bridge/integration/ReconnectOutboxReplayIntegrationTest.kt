package io.agents.pokeclaw.bridge.integration

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.ConnectionState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integration test: disconnect + reconnect + outbox replay.
 *
 * Scenario:
 * 1. Connection succeeds, authenticated
 * 2. Server dispatches a task
 * 3. Executor produces result while server abruptly closes
 * 4. Client reconnects with backoff
 * 5. New connection handshake succeeds
 * 6. Outbox replays the task.result on the new connection
 *
 * Validates: Requirements 4.1, 4.6, 9.1, 9.4
 */
class ReconnectOutboxReplayIntegrationTest : BridgeIntegrationTestBase() {

    @BeforeEach
    fun init() = setUp()

    @AfterEach
    fun cleanup() = tearDown()

    @Test
    fun `disconnect and reconnect replays outbox task_result`() {
        // Phase 1: Establish connection and authenticate
        val helloReceived = CountDownLatch(1)
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            if (obj.get("type")?.asString == "hello") {
                helloReceived.countDown()
                sendHelloAck(heartbeatSec = 60)
            }
        }

        client.start()
        assertThat(helloReceived.await(5, TimeUnit.SECONDS)).isTrue()
        assertThat(waitForState(ConnectionState.Authenticated)).isTrue()

        // Phase 2: Dispatch a task
        val taskAcceptedLatch = CountDownLatch(1)
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            if (obj.get("type")?.asString == "task.accepted") {
                taskAcceptedLatch.countDown()
            }
        }

        sendTaskDispatch(requestId = "req_replay_001", kind = "ths.sync_holdings")
        assertThat(taskAcceptedLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Phase 3: Prepare reconnection BEFORE closing
        val reconnectHelloLatch = CountDownLatch(1)
        val taskResultLatch = CountDownLatch(1)

        // Save reference to old listener to close it
        val oldListener = serverListener

        // Enqueue a new WebSocket upgrade for the reconnection
        enqueueWebSocketUpgrade()

        // Set up handler on the NEW serverListener BEFORE closing old connection
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            when (obj.get("type")?.asString) {
                "hello" -> {
                    reconnectHelloLatch.countDown()
                    sendHelloAck(heartbeatSec = 60)
                }
                "task.result" -> {
                    taskResultLatch.countDown()
                }
                else -> {}
            }
        }

        // Abruptly close the old server connection (non-terminal code)
        oldListener.close(1001, "server_restart")

        // Wait for client to detect disconnection
        Thread.sleep(500)

        // Now the executor produces a result while disconnected/reconnecting
        val execCall = taskExecutor.executeCalls.first()
        val resultPayload = JsonObject().apply {
            addProperty("captured_at", "2025-01-01T12:00:00Z")
            addProperty("account_alias", "main")
        }
        execCall.callback.onResult("req_replay_001", "ths.sync_holdings", resultPayload)

        // Phase 4: Wait for reconnection hello
        assertThat(reconnectHelloLatch.await(10, TimeUnit.SECONDS)).isTrue()

        // Phase 5: Wait for Authenticated state on new connection
        assertThat(waitForState(ConnectionState.Authenticated, timeoutMs = 5_000L)).isTrue()

        // Phase 6: Outbox should replay the task.result
        assertThat(taskResultLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Verify the replayed task.result
        val resultFrame = waitForOutboundFrame("task.result")
        assertThat(resultFrame).isNotNull
        val payload = resultFrame!!.getAsJsonObject("payload")
        assertThat(payload.get("request_id")?.asString).isEqualTo("req_replay_001")
        assertThat(payload.get("kind")?.asString).isEqualTo("ths.sync_holdings")
    }
}
