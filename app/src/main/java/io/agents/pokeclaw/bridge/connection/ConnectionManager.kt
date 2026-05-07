package io.agents.pokeclaw.bridge.connection

import io.agents.pokeclaw.bridge.ConnectionState
import io.agents.pokeclaw.bridge.StopReason
import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.Clock
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.FrameCodec
import io.agents.pokeclaw.bridge.protocol.HelloPayload
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ScheduledFuture

private const val TAG = "ConnectionManager"
private const val HELLO_TIMEOUT_MS = 10_000L
private const val STALE_THRESHOLD_MS = 90_000L
private const val STALE_CHECK_INTERVAL_MS = 5_000L

/**
 * Manages a single WebSocket connection lifecycle including:
 * - Connection establishment with auth header
 * - Hello handshake with 10s timeout
 * - Stale connection detection (90s no server frames)
 * - Close code routing and reconnection scheduling
 *
 * All state mutations happen on the [BridgeDispatcher] thread.
 *
 * Validates: Requirements 2.1, 2.2, 2.3, 2.4, 2.5, 3.4, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6
 */
internal class ConnectionManager(
    private val okHttpClient: OkHttpClient,
    private val dispatcher: BridgeDispatcher,
    private val backoffPolicy: BackoffPolicy,
    private val networkMonitor: NetworkMonitor,
    private val logger: BridgeLogger,
    private val clock: Clock,
) {

    interface Callback {
        fun onStateChanged(newState: ConnectionState)
        fun onAuthenticated(heartbeatSec: Int, acceptedCapabilities: List<String>)
        fun onFrameReceived(frame: Frame)
        fun onDisconnected()
    }

    // ─── Internal state (only mutated on dispatcher thread) ─────────────

    private var webSocket: WebSocket? = null
    private var config: BridgeConfig? = null
    private var deviceId: String = ""
    private var appVersion: String = ""
    private var userStopped: Boolean = false
    private var lastServerFrameMs: Long = 0L
    private var state: ConnectionState = ConnectionState.Disconnected
    private var callback: Callback? = null

    // ─── Timers ─────────────────────────────────────────────────────────

    private var helloTimeoutFuture: ScheduledFuture<*>? = null
    private var staleCheckFuture: ScheduledFuture<*>? = null
    private var reconnectFuture: ScheduledFuture<*>? = null

    // ─── Public API ─────────────────────────────────────────────────────

    fun setCallback(callback: Callback) {
        this.callback = callback
    }

    /**
     * Initiates a WebSocket connection.
     * Constructs the request URL with query params and Authorization header.
     */
    fun connect(config: BridgeConfig, deviceId: String, appVersion: String) {
        this.config = config
        this.deviceId = deviceId
        this.appVersion = appVersion
        this.userStopped = false

        reconnectFuture?.cancel(false)
        reconnectFuture = null

        val url = buildUrl(config.serverUrl, deviceId, appVersion)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.deviceToken}")
            .build()

        logger.i(TAG, "Connecting to ${config.serverUrl}")
        transitionTo(ConnectionState.Connecting)
        webSocket = okHttpClient.newWebSocket(request, wsListener)
    }

    /**
     * Sends a hello frame and starts the 10s timeout timer.
     */
    fun sendHello(payload: HelloPayload) {
        val frame = Frame.Hello(id = null, ts = clock.nowMillis(), payload = payload)
        val sent = sendFrame(frame)
        if (sent) {
            logger.d(TAG, "Hello sent, starting ${HELLO_TIMEOUT_MS}ms timeout")
            helloTimeoutFuture?.cancel(false)
            helloTimeoutFuture = dispatcher.schedule(HELLO_TIMEOUT_MS) {
                if (state != ConnectionState.Authenticated) {
                    logger.w(TAG, "Hello timeout — closing connection")
                    close(1001, "hello_timeout")
                }
            }
        }
    }

    /**
     * Encodes and sends a frame over the WebSocket.
     * @return true if the send was successful, false if connection is closed.
     */
    fun sendFrame(frame: Frame): Boolean {
        val ws = webSocket ?: return false
        val text = FrameCodec.encode(frame)
        return ws.send(text)
    }

    /**
     * Actively closes the WebSocket with the given code and reason.
     */
    fun close(code: Int, reason: String) {
        logger.d(TAG, "Closing WebSocket: code=$code reason=$reason")
        webSocket?.close(code, reason)
    }

    /**
     * Stops the connection manager entirely.
     * Sets userStopped flag, cancels all timers, closes the socket, and stops network monitor.
     */
    fun stop() {
        userStopped = true
        cancelAllTimers()
        close(1000, "user_stopped")
        networkMonitor.stop()
    }

    /**
     * Starts the network monitor to detect connectivity changes.
     * On network available: resets backoff and reconnects immediately (if disconnected).
     */
    fun startNetworkMonitor() {
        networkMonitor.start(object : NetworkMonitor.Listener {
            override fun onAvailable() {
                if (state == ConnectionState.Disconnected ||
                    state == ConnectionState.Connecting
                ) {
                    logger.i(TAG, "Network available — resetting backoff and reconnecting")
                    backoffPolicy.reset()
                    reconnectFuture?.cancel(false)
                    reconnectFuture = null
                    val cfg = config ?: return
                    connect(cfg, deviceId, appVersion)
                }
            }

            override fun onLost() {
                logger.d(TAG, "Network lost")
            }
        })
    }

    fun currentState(): ConnectionState = state

    // ─── WebSocket Listener ─────────────────────────────────────────────

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            dispatcher.execute {
                logger.i(TAG, "WebSocket opened")
                transitionTo(ConnectionState.Connected)
                lastServerFrameMs = clock.monotonicMillis()
                startStaleCheck()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            dispatcher.execute {
                lastServerFrameMs = clock.monotonicMillis()
                val frame = FrameCodec.decode(text)
                when (frame) {
                    is Frame.HelloAck -> handleHelloAck(frame)
                    is Frame.ParseError -> {
                        logger.w(TAG, "Received unparseable frame", frame.cause)
                    }
                    is Frame.Unknown -> {
                        logger.w(TAG, "Received unknown frame type: ${frame.type}")
                    }
                    else -> callback?.onFrameReceived(frame)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            dispatcher.execute {
                logger.d(TAG, "WebSocket closing: code=$code reason=$reason")
                webSocket.close(code, reason)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            dispatcher.execute {
                logger.i(TAG, "WebSocket closed: code=$code reason=$reason")
                handleClose(code, reason)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            dispatcher.execute {
                logger.e(TAG, "WebSocket failure: ${t.message}", t)
                handleClose(0, t.message ?: "unknown_failure")
            }
        }
    }

    // ─── Internal handlers ──────────────────────────────────────────────

    private fun handleHelloAck(frame: Frame.HelloAck) {
        logger.i(TAG, "Received hello.ack — authenticated")
        helloTimeoutFuture?.cancel(false)
        helloTimeoutFuture = null
        backoffPolicy.reset()
        transitionTo(ConnectionState.Authenticated)
        callback?.onAuthenticated(
            frame.payload.heartbeat_sec,
            frame.payload.accepted_capabilities,
        )
    }

    private fun handleClose(code: Int, reason: String) {
        this.webSocket = null
        cancelAllTimers()

        when {
            code == 1000 && userStopped -> {
                transitionTo(ConnectionState.Stopped(StopReason.USER_STOPPED))
            }
            code == 4401 -> {
                transitionTo(ConnectionState.Stopped(StopReason.REPLACED))
            }
            code == 4403 -> {
                transitionTo(ConnectionState.Stopped(StopReason.AUTH_FAILED))
            }
            else -> {
                // Non-terminal close — schedule reconnection
                transitionTo(ConnectionState.Disconnected)
                callback?.onDisconnected()
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        val cfg = config ?: return
        val delayMs = backoffPolicy.nextDelayMs()
        logger.i(TAG, "Scheduling reconnect in ${delayMs}ms")
        transitionTo(ConnectionState.Connecting)
        reconnectFuture = dispatcher.schedule(delayMs) {
            connect(cfg, deviceId, appVersion)
        }
    }

    // ─── Stale connection detection ─────────────────────────────────────

    private fun startStaleCheck() {
        staleCheckFuture?.cancel(false)
        staleCheckFuture = dispatcher.schedule(STALE_CHECK_INTERVAL_MS) {
            checkStale()
        }
    }

    private fun checkStale() {
        val elapsed = clock.monotonicMillis() - lastServerFrameMs
        if (elapsed > STALE_THRESHOLD_MS) {
            logger.w(TAG, "Connection stale (${elapsed}ms since last server frame)")
            close(1001, "stale")
        } else {
            // Schedule next check
            staleCheckFuture = dispatcher.schedule(STALE_CHECK_INTERVAL_MS) {
                checkStale()
            }
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private fun transitionTo(newState: ConnectionState) {
        if (state == newState) return
        logger.d(TAG, "State: $state → $newState")
        state = newState
        callback?.onStateChanged(newState)
    }

    private fun cancelAllTimers() {
        helloTimeoutFuture?.cancel(false)
        helloTimeoutFuture = null
        staleCheckFuture?.cancel(false)
        staleCheckFuture = null
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun buildUrl(
        serverUrl: String,
        deviceId: String,
        appVersion: String,
    ): String {
        val separator = if (serverUrl.contains('?')) '&' else '?'
        return "${serverUrl}${separator}device_id=${deviceId}&app_version=${appVersion}"
    }
}
