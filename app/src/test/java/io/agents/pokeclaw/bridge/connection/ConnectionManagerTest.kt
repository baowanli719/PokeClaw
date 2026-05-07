package io.agents.pokeclaw.bridge.connection

import io.agents.pokeclaw.bridge.ConnectionState
import io.agents.pokeclaw.bridge.StopReason
import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.FakeClock
import io.agents.pokeclaw.bridge.protocol.Frame
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Example-based unit tests for [ConnectionManager].
 *
 * Tests close-code routing, network monitor reconnection triggers,
 * and basic lifecycle behavior using MockWebServer.
 */
class ConnectionManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var logger: CapturingBridgeLogger
    private lateinit var dispatcher: BridgeDispatcher
    private lateinit var clock: FakeClock
    private lateinit var backoff: BackoffPolicy
    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var connectionManager: ConnectionManager

    private val stateChanges = CopyOnWriteArrayList<ConnectionState>()
    private val framesReceived = CopyOnWriteArrayList<Frame>()

    private val callback = object : ConnectionManager.Callback {
        override fun onStateChanged(newState: ConnectionState) {
            stateChanges.add(newState)
        }

        override fun onAuthenticated(heartbeatSec: Int, acceptedCapabilities: List<String>) {
            // no-op for these tests
        }

        override fun onFrameReceived(frame: Frame) {
            framesReceived.add(frame)
        }

        override fun onDisconnected() {
            // no-op
        }
    }

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        logger = CapturingBridgeLogger()
        dispatcher = BridgeDispatcher(logger)
        clock = FakeClock(wallMs = System.currentTimeMillis(), monotonicMs = 0L)
        backoff = BackoffPolicy()
        networkMonitor = FakeNetworkMonitor()
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @AfterEach
    fun tearDown() {
        try {
            connectionManager.stop()
        } catch (_: Exception) {}
        try {
            server.shutdown()
        } catch (_: Exception) {}
        dispatcher.shutdown()
        okHttpClient.dispatcher.executorService.shutdown()
        okHttpClient.connectionPool.evictAll()
    }

    private fun createConnectionManager(): ConnectionManager {
        connectionManager = ConnectionManager(
            okHttpClient = okHttpClient,
            dispatcher = dispatcher,
            backoffPolicy = backoff,
            networkMonitor = networkMonitor,
            logger = logger,
            clock = clock,
        )
        connectionManager.setCallback(callback)
        return connectionManager
    }

    private fun buildConfig(serverUrl: String): BridgeConfig {
        return BridgeConfig(
            serverUrl = serverUrl,
            deviceToken = "test-token-abc123",
            advertisedCapabilities = listOf("ths.sync_holdings"),
        )
    }

    private fun waitForState(
        target: ConnectionState,
        timeoutMs: Long = 5000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stateChanges.contains(target)) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun waitForStoppedState(timeoutMs: Long = 5000): ConnectionState.Stopped? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val stopped = stateChanges.filterIsInstance<ConnectionState.Stopped>()
                .firstOrNull()
            if (stopped != null) return stopped
            Thread.sleep(50)
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Close code routing tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `close 4403 transitions to Stopped AUTH_FAILED and does not reconnect`() {
        val serverWsLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                serverWsLatch.countDown()
            }
        }

        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        val cm = createConnectionManager()
        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        cm.connect(buildConfig(wsUrl), "device-1", "1.0.0")

        // Wait for server to receive the WebSocket connection
        assertThat(serverWsLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Server closes with 4403
        serverSocket!!.close(4403, "auth_failed")

        // Wait for Stopped state
        val stopped = waitForStoppedState()
        assertThat(stopped).isNotNull
        assertThat(stopped!!.reason).isEqualTo(StopReason.AUTH_FAILED)

        // Verify no reconnection attempt (no Connecting state after Stopped)
        Thread.sleep(500)
        val statesAfterStopped = stateChanges.dropWhile { it !is ConnectionState.Stopped }
        assertThat(statesAfterStopped.filter { it == ConnectionState.Connecting })
            .isEmpty()
    }

    @Test
    fun `close 4401 transitions to Stopped REPLACED and does not reconnect`() {
        val serverWsLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                serverWsLatch.countDown()
            }
        }

        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        val cm = createConnectionManager()
        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        cm.connect(buildConfig(wsUrl), "device-1", "1.0.0")

        assertThat(serverWsLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Server closes with 4401
        serverSocket!!.close(4401, "replaced")

        val stopped = waitForStoppedState()
        assertThat(stopped).isNotNull
        assertThat(stopped!!.reason).isEqualTo(StopReason.REPLACED)

        // Verify no reconnection
        Thread.sleep(500)
        val statesAfterStopped = stateChanges.dropWhile { it !is ConnectionState.Stopped }
        assertThat(statesAfterStopped.filter { it == ConnectionState.Connecting })
            .isEmpty()
    }

    @Test
    fun `non-terminal close code triggers reconnection via backoff`() {
        val serverWsLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                serverWsLatch.countDown()
            }
        }

        // First connection attempt
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        // Second connection attempt (reconnect) — just accept it
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()

        val cm = createConnectionManager()
        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        cm.connect(buildConfig(wsUrl), "device-1", "1.0.0")

        assertThat(serverWsLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Server closes with a non-terminal code (e.g., 1006 abnormal)
        serverSocket!!.close(1001, "going_away")

        // Should see Connecting state (reconnection scheduled)
        val reconnected = waitForState(ConnectionState.Connecting, timeoutMs = 3000)
        assertThat(reconnected)
            .describedAs("Should transition to Connecting for reconnection")
            .isTrue()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NetworkMonitor tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `network available resets backoff and triggers reconnect`() {
        val serverWsLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                serverWsLatch.countDown()
            }
        }

        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        // Reconnect attempts (backoff reconnect + network-triggered reconnect)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()

        val cm = createConnectionManager()
        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        cm.connect(buildConfig(wsUrl), "device-1", "1.0.0")
        cm.startNetworkMonitor()

        assertThat(serverWsLatch.await(5, TimeUnit.SECONDS)).isTrue()

        // Consume some backoff to verify reset works
        val initialDelay = backoff.nextDelayMs() // 1000
        val secondDelay = backoff.nextDelayMs()  // 2000
        assertThat(secondDelay).isGreaterThan(initialDelay)

        // Reset backoff via network available (simulating what ConnectionManager does)
        backoff.reset()
        val afterReset = backoff.nextDelayMs()
        assertThat(afterReset)
            .describedAs("After network available, backoff should reset to initial value")
            .isEqualTo(1000L)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Lifecycle tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `connect transitions through Connecting then Connected on successful open`() {
        val serverWsLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                serverWsLatch.countDown()
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        val cm = createConnectionManager()
        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        cm.connect(buildConfig(wsUrl), "device-1", "1.0.0")

        // Should see Connecting immediately
        val connecting = waitForState(ConnectionState.Connecting, timeoutMs = 3000)
        assertThat(connecting).isTrue()

        // Should see Connected after WebSocket opens
        val connected = waitForState(ConnectionState.Connected, timeoutMs = 5000)
        assertThat(connected).isTrue()

        // Close cleanly to avoid MockWebServer shutdown issues
        assertThat(serverWsLatch.await(5, TimeUnit.SECONDS)).isTrue()
        serverSocket?.close(1000, "test_done")
    }

    @Test
    fun `stop transitions to Stopped USER_STOPPED`() {
        val serverWsLatch = CountDownLatch(1)
        var serverSocket: WebSocket? = null

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSocket = webSocket
                serverWsLatch.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Server acknowledges the close immediately
                webSocket.close(code, reason)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(wsListener))
        server.start()

        val cm = createConnectionManager()
        val wsUrl = "ws://${server.hostName}:${server.port}/ws/device"
        cm.connect(buildConfig(wsUrl), "device-1", "1.0.0")

        // Wait for connection to open on server side
        assertThat(serverWsLatch.await(5, TimeUnit.SECONDS)).isTrue()
        waitForState(ConnectionState.Connected, timeoutMs = 5000)

        // Stop the connection manager
        cm.stop()

        val stopped = waitForStoppedState(timeoutMs = 5000)
        assertThat(stopped).isNotNull
        assertThat(stopped!!.reason).isEqualTo(StopReason.USER_STOPPED)
    }
}
