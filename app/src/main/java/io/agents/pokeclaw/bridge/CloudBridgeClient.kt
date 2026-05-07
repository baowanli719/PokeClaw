package io.agents.pokeclaw.bridge

import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.bridge.api.CapabilityProvider
import io.agents.pokeclaw.bridge.api.ConfigSource
import io.agents.pokeclaw.bridge.api.TaskExecutor
import io.agents.pokeclaw.bridge.connection.BackoffPolicy
import io.agents.pokeclaw.bridge.connection.ConnectionManager
import io.agents.pokeclaw.bridge.connection.HeartbeatScheduler
import io.agents.pokeclaw.bridge.connection.NetworkMonitor
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.Clock
import io.agents.pokeclaw.bridge.internal.SystemClock
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.HeartbeatPayload
import io.agents.pokeclaw.bridge.protocol.HelloPayload
import io.agents.pokeclaw.bridge.queue.OfflineOutbox
import io.agents.pokeclaw.bridge.task.TaskBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

private const val TAG = "CloudBridgeClient"

/**
 * Public facade for the Cloud Bridge WebSocket client.
 *
 * Composes all internal components (ConnectionManager, HeartbeatScheduler,
 * TaskBridge, OfflineOutbox) and exposes a simple start/stop/reconfigure API.
 *
 * All internal state mutations happen on the single bridge dispatcher thread.
 * External callers see synchronous semantics via Future.get().
 *
 * Validates: Requirements 7.1, 7.5, 9.5, 10.4, 11.1–11.5
 */
class CloudBridgeClient(
    private val configSource: ConfigSource,
    private val capabilityProvider: CapabilityProvider,
    private val taskExecutor: TaskExecutor,
    private val logger: BridgeLogger,
    private val deviceId: String,
    private val appVersion: String,
    private val filesDir: File,
    private val networkMonitor: NetworkMonitor = NoOpNetworkMonitor(),
    private val clock: Clock = SystemClock,
    private val osVersion: String = android.os.Build.VERSION.RELEASE ?: "unknown",
) {

    // ═══════════════════════════════════════════════════════════════════════
    // Internal state
    // ═══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private var currentConfig: BridgeConfig? = null
    private var started = false

    // ═══════════════════════════════════════════════════════════════════════
    // Internal components
    // ═══════════════════════════════════════════════════════════════════════

    private val dispatcher = BridgeDispatcher(logger)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    private val backoffPolicy = BackoffPolicy()

    private val connectionManager = ConnectionManager(
        okHttpClient = okHttpClient,
        dispatcher = dispatcher,
        backoffPolicy = backoffPolicy,
        networkMonitor = networkMonitor,
        logger = logger,
        clock = clock,
    )

    private val offlineOutbox = OfflineOutbox(
        filesDir = filesDir,
        logger = logger,
        dispatcher = dispatcher,
        sendFrame = { frame -> connectionManager.sendFrame(frame) },
    )

    private val taskBridge = TaskBridge(
        dispatcher = dispatcher,
        capabilityProvider = capabilityProvider,
        taskExecutor = taskExecutor,
        clock = clock,
        logger = logger,
        sendFrame = { frame -> connectionManager.sendFrame(frame) },
        enqueueAndSend = { frame -> offlineOutbox.enqueueAndSend(frame) },
        currentCapabilities = { currentConfig?.advertisedCapabilities ?: emptyList() },
    )

    private val heartbeatScheduler = HeartbeatScheduler(
        dispatcher = dispatcher,
        clock = clock,
        onTick = { sendHeartbeat() },
        onPong = { id -> sendPong(id) },
    )

    // ═══════════════════════════════════════════════════════════════════════
    // 10.3 — ConnectionManager callback (defined before init to avoid order issues)
    // ═══════════════════════════════════════════════════════════════════════

    private val connectionCallback = object : ConnectionManager.Callback {

        override fun onStateChanged(newState: ConnectionState) {
            val previousState = _state.value
            logger.i(TAG, "Connection state: $previousState → $newState")
            _state.value = newState

            // Exiting Authenticated → stop heartbeat & mark outbox unauthenticated
            if (previousState == ConnectionState.Authenticated &&
                newState != ConnectionState.Authenticated
            ) {
                heartbeatScheduler.stop()
                offlineOutbox.setAuthenticated(false)
            }

            // 10.4 — When Connected (TCP+WS handshake done), assemble and send hello
            if (newState == ConnectionState.Connected) {
                assembleAndSendHello()
            }
        }

        override fun onAuthenticated(heartbeatSec: Int, acceptedCapabilities: List<String>) {
            logger.i(TAG, "Authenticated — heartbeat_sec=$heartbeatSec, accepted=$acceptedCapabilities")
            _state.value = ConnectionState.Authenticated
            offlineOutbox.setAuthenticated(true)
            heartbeatScheduler.start(heartbeatSec)
            offlineOutbox.drain()
        }

        override fun onFrameReceived(frame: Frame) {
            logger.d(TAG, "Frame received: type=${frame.type}")
            when (frame) {
                is Frame.TaskDispatch -> {
                    logger.i(TAG, "Task dispatch: request_id=${frame.payload.request_id}, kind=${frame.payload.kind}")
                    taskBridge.onDispatchFrame(frame)
                }
                is Frame.TaskCancel -> {
                    logger.i(TAG, "Task cancel: request_id=${frame.payload.request_id}")
                    taskBridge.onCancelFrame(frame)
                }
                is Frame.Ping -> {
                    logger.d(TAG, "Ping received: id=${frame.id}")
                    heartbeatScheduler.onPing(frame.id)
                }
                is Frame.Unknown -> {
                    logger.w(TAG, "Unknown frame type: ${frame.type} — discarding")
                }
                is Frame.ParseError -> {
                    logger.w(TAG, "Parse error frame — discarding")
                }
                else -> {
                    logger.d(TAG, "Unhandled frame type: ${frame.type}")
                }
            }
        }

        override fun onDisconnected() {
            logger.i(TAG, "Disconnected — scheduling reconnect via backoff")
            heartbeatScheduler.stop()
            offlineOutbox.setAuthenticated(false)
        }
    }

    init {
        connectionManager.setCallback(connectionCallback)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10.2 — Public API: start / stop / reconfigure
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Idempotent. Reads config and initiates the first connection attempt.
     * If config is null or has empty critical fields, stays Disconnected.
     */
    fun start() {
        dispatcher.submit(Callable {
            try {
                if (started) {
                    logger.d(TAG, "start() called but already started, ignoring")
                    return@Callable
                }
                started = true
                logger.i(TAG, "Starting CloudBridgeClient")

                val config = configSource.load()
                if (config == null || config.serverUrl.isBlank() || config.deviceToken.isBlank()) {
                    logger.w(TAG, "Config missing or incomplete — staying Disconnected")
                    return@Callable
                }

                currentConfig = config
                logger.i(TAG, "Config loaded: url=${config.serverUrl}, token=${config.deviceToken.masked()}")
                connectionManager.startNetworkMonitor()
                connectionManager.connect(config, deviceId, appVersion)
            } catch (t: Throwable) {
                try { logger.e(TAG, "Exception in start(): ${t.message}", t) } catch (_: Throwable) {}
            }
        }).get()
    }

    /**
     * Idempotent. Cancels any in-flight task, closes the socket with code 1000,
     * stops heartbeat & reconnect timers, and transitions to Stopped.
     */
    fun stop() {
        dispatcher.submit(Callable {
            try {
                if (!started) {
                    logger.d(TAG, "stop() called but not started, ignoring")
                    return@Callable
                }
                started = false
                logger.i(TAG, "Stopping CloudBridgeClient")

                // Cancel in-flight task if any
                val currentRequestId = taskBridge.currentRequestId()
                if (currentRequestId != null) {
                    logger.i(TAG, "Cancelling in-flight task: $currentRequestId")
                    taskExecutor.cancel(currentRequestId)
                }

                heartbeatScheduler.stop()
                connectionManager.stop()
                offlineOutbox.setAuthenticated(false)
                _state.value = ConnectionState.Stopped(StopReason.USER_STOPPED)
            } catch (t: Throwable) {
                try { logger.e(TAG, "Exception in stop(): ${t.message}", t) } catch (_: Throwable) {}
            }
        }).get()
    }

    /**
     * Re-reads config from ConfigSource. If server URL, token, or capabilities
     * changed, closes the current connection and reconnects with new parameters.
     */
    fun reconfigure() {
        dispatcher.submit(Callable {
            try {
                logger.i(TAG, "Reconfiguring CloudBridgeClient")
                val newConfig = configSource.load()

                if (newConfig == null || newConfig.serverUrl.isBlank() || newConfig.deviceToken.isBlank()) {
                    logger.w(TAG, "New config missing or incomplete — closing connection")
                    if (currentConfig != null) {
                        heartbeatScheduler.stop()
                        connectionManager.stop()
                        offlineOutbox.setAuthenticated(false)
                        currentConfig = null
                        _state.value = ConnectionState.Disconnected
                    }
                    return@Callable
                }

                val oldConfig = currentConfig
                if (oldConfig != null &&
                    oldConfig.serverUrl == newConfig.serverUrl &&
                    oldConfig.deviceToken == newConfig.deviceToken &&
                    oldConfig.advertisedCapabilities == newConfig.advertisedCapabilities
                ) {
                    logger.d(TAG, "Config unchanged, no reconnection needed")
                    return@Callable
                }

                logger.i(TAG, "Config changed — reconnecting (token=${newConfig.deviceToken.masked()})")
                currentConfig = newConfig
                heartbeatScheduler.stop()
                offlineOutbox.setAuthenticated(false)
                connectionManager.close(1000, "reconfigure")
                connectionManager.connect(newConfig, deviceId, appVersion)
            } catch (t: Throwable) {
                try { logger.e(TAG, "Exception in reconfigure(): ${t.message}", t) } catch (_: Throwable) {}
            }
        }).get()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Public state observation
    // ═══════════════════════════════════════════════════════════════════════

    /** Hot state stream for UI observation. Thread-safe. */
    fun observeState(): StateFlow<ConnectionState> = _state.asStateFlow()

    /** Snapshot read; safe from any thread. */
    fun currentState(): ConnectionState = _state.value

    // ═══════════════════════════════════════════════════════════════════════
    // 10.4 — Hello assembly
    // ═══════════════════════════════════════════════════════════════════════

    private fun assembleAndSendHello() {
        val config = currentConfig ?: return
        val snapshot = capabilityProvider.currentSnapshot()

        val capabilities = config.advertisedCapabilities.toMutableList().apply {
            snapshot.supportedKinds.forEach { kind ->
                if (kind !in this) add(kind)
            }
        }

        val payload = HelloPayload(
            device_id = deviceId,
            app_version = appVersion,
            os = "android",
            os_version = osVersion,
            capabilities = capabilities,
            battery = snapshot.batteryLevel,
            charging = snapshot.charging,
        )

        logger.i(TAG, "Sending hello: capabilities=$capabilities, battery=${snapshot.batteryLevel}")
        connectionManager.sendHello(payload)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers — heartbeat & pong
    // ═══════════════════════════════════════════════════════════════════════

    private fun sendHeartbeat() {
        val busy = taskBridge.currentBusy()
        val requestId = taskBridge.currentRequestId()
        val frame = Frame.Heartbeat(
            id = null,
            ts = clock.nowMillis(),
            payload = HeartbeatPayload(
                busy = busy,
                current_request_id = requestId,
            ),
        )
        logger.d(TAG, "Sending heartbeat: busy=$busy, request_id=$requestId")
        connectionManager.sendFrame(frame)
    }

    private fun sendPong(id: String?) {
        val frame = Frame.Pong(id = id, ts = clock.nowMillis())
        logger.d(TAG, "Sending pong: id=$id")
        connectionManager.sendFrame(frame)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10.5 — Token masking
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Masks a sensitive string, showing only the last 4 characters.
     * Used for deviceToken in all log output.
     */
    private fun String.masked(): String = "***${takeLast(4)}"
}

// ═══════════════════════════════════════════════════════════════════════════
// NoOpNetworkMonitor — default when no Context is available
// ═══════════════════════════════════════════════════════════════════════════

/**
 * A no-op [NetworkMonitor] that never fires connectivity events.
 * Used as the default when the caller does not provide an Android-backed monitor.
 */
class NoOpNetworkMonitor : NetworkMonitor {
    override fun start(listener: NetworkMonitor.Listener) {
        // No-op: no connectivity observation without Android Context
    }

    override fun stop() {
        // No-op
    }
}
