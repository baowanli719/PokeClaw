package io.agents.pokeclaw.bridge.integration

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.CloudBridgeClient
import io.agents.pokeclaw.bridge.ConnectionState
import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.CapabilitySnapshot
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.api.FakeCapabilityProvider
import io.agents.pokeclaw.bridge.api.FakeConfigSource
import io.agents.pokeclaw.bridge.api.FakeTaskExecutor
import io.agents.pokeclaw.bridge.protocol.FrameCodec
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Base class for Cloud Bridge integration tests using OkHttp MockWebServer.
 *
 * Provides:
 * - MockWebServer with WebSocket upgrade support
 * - Real CloudBridgeClient with fake adapters
 * - Outbound frame capture and assertion helpers
 * - Helper methods: waitForState(), sendServerFrame()
 */
abstract class BridgeIntegrationTestBase {

    protected lateinit var server: MockWebServer
    protected lateinit var client: CloudBridgeClient
    protected lateinit var configSource: FakeConfigSource
    protected lateinit var capabilityProvider: FakeCapabilityProvider
    protected lateinit var taskExecutor: FakeTaskExecutor
    protected lateinit var logger: CapturingBridgeLogger
    protected lateinit var tempDir: File
    protected lateinit var serverListener: ServerWebSocketListener

    protected val gson = Gson()

    /**
     * Sets up MockWebServer with WebSocket upgrade and creates a real CloudBridgeClient.
     * Call this in @BeforeEach of subclasses.
     */
    protected fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "bridge_integ_${System.nanoTime()}")
        tempDir.mkdirs()

        logger = CapturingBridgeLogger()
        capabilityProvider = FakeCapabilityProvider(
            snapshot = CapabilitySnapshot(
                supportedKinds = listOf("ths.sync_holdings"),
                accessibilityReady = true,
                installedTargetApps = mapOf("ths.sync_holdings" to true),
                batteryLevel = 0.85,
                charging = false,
            )
        )
        taskExecutor = FakeTaskExecutor()

        serverListener = ServerWebSocketListener()
        server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()

        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        configSource = FakeConfigSource(
            BridgeConfig(
                serverUrl = wsUrl,
                deviceToken = "test_token_1234",
                advertisedCapabilities = listOf("ths.sync_holdings"),
            )
        )

        client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "test_device_001",
            appVersion = "1.0.0-test",
            filesDir = tempDir,
            osVersion = "14",
        )
    }

    /**
     * Tears down server and client. Call in @AfterEach.
     */
    protected fun tearDown() {
        try { client.stop() } catch (_: Throwable) {}
        try { server.shutdown() } catch (_: Throwable) {}
        tempDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: wait for a specific ConnectionState
    // ═══════════════════════════════════════════════════════════════════════

    protected fun waitForState(
        expected: ConnectionState,
        timeoutMs: Long = 5_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (client.currentState() == expected) return true
            Thread.sleep(50)
        }
        return client.currentState() == expected
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: send a frame from the server side
    // ═══════════════════════════════════════════════════════════════════════

    protected fun sendServerFrame(frame: Any) {
        val json = when (frame) {
            is String -> frame
            else -> gson.toJson(frame)
        }
        serverListener.send(json)
    }

    protected fun sendHelloAck(heartbeatSec: Int = 30) {
        val ack = JsonObject().apply {
            addProperty("type", "hello.ack")
            addProperty("ts", System.currentTimeMillis())
            add("payload", JsonObject().apply {
                addProperty("server_time", "2025-01-01T00:00:00Z")
                addProperty("heartbeat_sec", heartbeatSec)
                add("accepted_capabilities", gson.toJsonTree(listOf("ths.sync_holdings")))
            })
        }
        serverListener.send(gson.toJson(ack))
    }

    protected fun sendTaskDispatch(
        requestId: String = "req_001",
        kind: String = "ths.sync_holdings",
        params: JsonObject = JsonObject(),
        deadlineTs: Long? = null,
    ) {
        val dispatch = JsonObject().apply {
            addProperty("type", "task.dispatch")
            addProperty("ts", System.currentTimeMillis())
            add("payload", JsonObject().apply {
                addProperty("request_id", requestId)
                addProperty("kind", kind)
                add("params", params)
                if (deadlineTs != null) addProperty("deadline_ts", deadlineTs)
            })
        }
        serverListener.send(gson.toJson(dispatch))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper: wait for outbound frames matching a type
    // ═══════════════════════════════════════════════════════════════════════

    protected fun waitForOutboundFrame(
        type: String,
        timeoutMs: Long = 5_000L,
    ): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = serverListener.receivedMessages.firstOrNull { msg ->
                try {
                    val obj = gson.fromJson(msg, JsonObject::class.java)
                    obj.get("type")?.asString == type
                } catch (_: Exception) { false }
            }
            if (found != null) return gson.fromJson(found, JsonObject::class.java)
            Thread.sleep(50)
        }
        return null
    }

    protected fun waitForOutboundFrameAfter(
        type: String,
        afterIndex: Int,
        timeoutMs: Long = 5_000L,
    ): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val messages = serverListener.receivedMessages
            for (i in afterIndex until messages.size) {
                try {
                    val obj = gson.fromJson(messages[i], JsonObject::class.java)
                    if (obj.get("type")?.asString == type) return obj
                } catch (_: Exception) {}
            }
            Thread.sleep(50)
        }
        return null
    }

    protected fun outboundFrameCount(): Int = serverListener.receivedMessages.size

    // ═══════════════════════════════════════════════════════════════════════
    // Server-side WebSocket listener that captures client messages
    // ═══════════════════════════════════════════════════════════════════════

    class ServerWebSocketListener : WebSocketListener() {
        val receivedMessages = CopyOnWriteArrayList<String>()
        val openLatch = CountDownLatch(1)
        @Volatile var serverSocket: WebSocket? = null
        @Volatile var onMessageHandler: ((String) -> Unit)? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            serverSocket = webSocket
            openLatch.countDown()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            receivedMessages.add(text)
            onMessageHandler?.invoke(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        fun send(text: String) {
            serverSocket?.send(text)
        }

        fun close(code: Int, reason: String = "") {
            serverSocket?.close(code, reason)
        }

        fun awaitOpen(timeoutMs: Long = 5_000L): Boolean {
            return openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }

    /**
     * Enqueues a new WebSocket upgrade response on the MockWebServer
     * (useful for reconnection scenarios).
     */
    protected fun enqueueWebSocketUpgrade() {
        val newListener = ServerWebSocketListener()
        serverListener = newListener
        server.enqueue(MockResponse().withWebSocketUpgrade(newListener))
    }
}
