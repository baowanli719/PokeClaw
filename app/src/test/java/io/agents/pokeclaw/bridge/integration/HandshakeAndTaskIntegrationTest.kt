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
 * Integration tests for the full handshake + task dispatch flow
 * using MockWebServer with real WebSocket connections.
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 5.1, 5.4, 5.5
 */
class HandshakeAndTaskIntegrationTest : BridgeIntegrationTestBase() {

    @BeforeEach
    fun init() = setUp()

    @AfterEach
    fun cleanup() = tearDown()

    // ═══════════════════════════════════════════════════════════════════════
    // Test: Full handshake flow
    // client start → WS opens → client sends hello → server sends hello.ack
    // → client enters Authenticated
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `handshake - client sends hello and transitions to Authenticated on hello_ack`() {
        // Set up auto-reply: when server receives hello, respond with hello.ack
        val helloReceived = CountDownLatch(1)
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            if (obj.get("type")?.asString == "hello") {
                helloReceived.countDown()
                sendHelloAck(heartbeatSec = 30)
            }
        }

        // Start the client
        client.start()

        // Wait for hello to be received by server
        assertThat(helloReceived.await(5, TimeUnit.SECONDS)).isTrue()

        // Verify hello frame content
        val helloFrame = waitForOutboundFrame("hello")
        assertThat(helloFrame).isNotNull
        val payload = helloFrame!!.getAsJsonObject("payload")
        assertThat(payload.get("device_id")?.asString).isEqualTo("test_device_001")
        assertThat(payload.get("app_version")?.asString).isEqualTo("1.0.0-test")
        assertThat(payload.get("os")?.asString).isEqualTo("android")

        // Wait for Authenticated state
        assertThat(waitForState(ConnectionState.Authenticated)).isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: Task dispatch end-to-end
    // server sends task.dispatch → client sends task.accepted →
    // fake executor calls onResult → client sends task.result
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `task dispatch - accepted then result sent back to server`() {
        // Auto-reply hello.ack on hello
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            if (obj.get("type")?.asString == "hello") {
                sendHelloAck(heartbeatSec = 60)
            }
        }

        client.start()
        assertThat(waitForState(ConnectionState.Authenticated)).isTrue()

        // Now dispatch a task from server
        val taskAcceptedLatch = CountDownLatch(1)
        val taskResultLatch = CountDownLatch(1)

        // Update handler to track task.accepted and task.result
        serverListener.onMessageHandler = { msg ->
            val obj = gson.fromJson(msg, JsonObject::class.java)
            when (obj.get("type")?.asString) {
                "task.accepted" -> taskAcceptedLatch.countDown()
                "task.result" -> taskResultLatch.countDown()
                else -> {}
            }
        }

        sendTaskDispatch(requestId = "req_task_001", kind = "ths.sync_holdings")

        // Wait for task.accepted
        assertThat(taskAcceptedLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Verify task.accepted frame
        val acceptedFrame = waitForOutboundFrame("task.accepted")
        assertThat(acceptedFrame).isNotNull
        assertThat(
            acceptedFrame!!.getAsJsonObject("payload").get("request_id")?.asString
        ).isEqualTo("req_task_001")

        // Simulate executor producing a result
        val execCall = taskExecutor.executeCalls.first()
        assertThat(execCall.requestId).isEqualTo("req_task_001")
        assertThat(execCall.kind).isEqualTo("ths.sync_holdings")

        // Call onResult from the executor callback
        val resultPayload = JsonObject().apply {
            addProperty("captured_at", "2025-01-01T00:00:00Z")
            addProperty("account_alias", "main")
        }
        execCall.callback.onResult("req_task_001", "ths.sync_holdings", resultPayload)

        // Wait for task.result
        assertThat(taskResultLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Verify task.result frame
        val resultFrame = waitForOutboundFrame("task.result")
        assertThat(resultFrame).isNotNull
        val resultFramePayload = resultFrame!!.getAsJsonObject("payload")
        assertThat(resultFramePayload.get("request_id")?.asString).isEqualTo("req_task_001")
        assertThat(resultFramePayload.get("kind")?.asString).isEqualTo("ths.sync_holdings")
    }
}
