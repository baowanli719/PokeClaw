package io.agents.pokeclaw.bridge.task

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.bridge.api.CapabilityProvider
import io.agents.pokeclaw.bridge.api.TaskExecutor
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.Clock
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.TaskAcceptedPayload
import io.agents.pokeclaw.bridge.protocol.TaskErrorPayload
import io.agents.pokeclaw.bridge.protocol.TaskProgressPayload
import io.agents.pokeclaw.bridge.protocol.TaskResultPayload

private const val TAG = "TaskBridge"

/**
 * Bridges inbound task.dispatch frames to the injected [TaskExecutor] and
 * converts executor callbacks back into outbound frames.
 *
 * All internal state is accessed exclusively on the bridge dispatcher thread.
 *
 * Validates: Requirements 5.1–5.8, 6.4, 9.3
 */
internal class TaskBridge(
    private val dispatcher: BridgeDispatcher,
    private val capabilityProvider: CapabilityProvider,
    private val taskExecutor: TaskExecutor,
    private val clock: Clock,
    private val logger: BridgeLogger,
    private val sendFrame: (Frame) -> Boolean,
    private val enqueueAndSend: (Frame) -> Unit,
    private val currentCapabilities: () -> List<String>,
) {

    /** The single in-flight task, or null if idle. */
    private var inFlight: InFlightTask? = null

    // ═══════════════════════════════════════════════════════════════════════
    // 7.2 — Dispatch routing & precondition checks
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles an inbound task.dispatch frame. Must be called on the dispatcher thread.
     */
    fun onDispatchFrame(frame: Frame.TaskDispatch) {
        val payload = frame.payload
        val requestId = payload.request_id
        val kind = payload.kind

        // Busy check — single task invariant (Requirement 9.3)
        if (inFlight != null) {
            logger.w(TAG, "Device busy, rejecting dispatch request_id=$requestId")
            enqueueAndSend(
                Frame.TaskError(
                    id = null,
                    ts = clock.nowMillis(),
                    payload = TaskErrorPayload(
                        request_id = requestId,
                        code = "internal",
                        message = "device busy",
                        retryable = true,
                    ),
                )
            )
            return
        }

        // Capability precondition checks
        val snapshot = capabilityProvider.currentSnapshot()
        val capabilities = currentCapabilities()

        // Check kind is supported
        if (kind !in capabilities) {
            logger.w(TAG, "Unsupported capability: $kind")
            enqueueAndSend(
                Frame.TaskError(
                    id = null,
                    ts = clock.nowMillis(),
                    payload = TaskErrorPayload(
                        request_id = requestId,
                        code = "internal",
                        message = "unsupported capability: $kind",
                        retryable = false,
                    ),
                )
            )
            return
        }

        // Check target app installed
        if (snapshot.installedTargetApps[kind] == false) {
            logger.w(TAG, "Target app not installed for kind=$kind")
            enqueueAndSend(
                Frame.TaskError(
                    id = null,
                    ts = clock.nowMillis(),
                    payload = TaskErrorPayload(
                        request_id = requestId,
                        code = "app_not_installed",
                        message = "app_not_installed",
                        retryable = false,
                    ),
                )
            )
            return
        }

        // Check accessibility ready
        if (!snapshot.accessibilityReady) {
            logger.w(TAG, "Accessibility not ready for kind=$kind")
            enqueueAndSend(
                Frame.TaskError(
                    id = null,
                    ts = clock.nowMillis(),
                    payload = TaskErrorPayload(
                        request_id = requestId,
                        code = "accessibility_not_ready",
                        message = "accessibility_not_ready",
                        retryable = false,
                    ),
                )
            )
            return
        }

        // All checks passed — accept the task
        val nowMs = clock.nowMillis()
        logger.i(TAG, "Accepting task request_id=$requestId kind=$kind")

        enqueueAndSend(
            Frame.TaskAccepted(
                id = null,
                ts = nowMs,
                payload = TaskAcceptedPayload(request_id = requestId),
            )
        )

        val task = InFlightTask(
            requestId = requestId,
            kind = kind,
            params = payload.params,
            deadlineTsMillis = payload.deadline_ts,
            acceptedAtMs = nowMs,
        )
        inFlight = task

        // 7.4 — Schedule deadline timer if deadline_ts is present
        if (payload.deadline_ts != null) {
            val delayMs = (payload.deadline_ts - nowMs).coerceAtLeast(0L)
            task.deadlineFuture = dispatcher.schedule(delayMs) {
                onDeadlineExpired(requestId)
            }
        }

        // Execute via TaskExecutor with bridging callback
        val callback = BridgingCallback(requestId)
        taskExecutor.execute(requestId, kind, payload.params, payload.deadline_ts, callback)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7.3 — Executor callback → frame stream bridging
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Internal callback that bridges TaskExecutor events back to the dispatcher
     * thread and converts them into outbound frames.
     */
    private inner class BridgingCallback(private val requestId: String) : TaskExecutorCallback {

        override fun onAccepted(requestId: String) {
            dispatcher.execute {
                // Idempotent — accepted was already sent in onDispatchFrame
                logger.d(TAG, "onAccepted (idempotent) request_id=$requestId")
            }
        }

        override fun onProgress(requestId: String, step: String, ratio: Double?) {
            dispatcher.execute {
                val current = inFlight
                if (current == null || current.requestId != requestId) {
                    logger.w(TAG, "onProgress for unknown/stale request_id=$requestId")
                    return@execute
                }
                // Progress frames are sent directly; failure is silently discarded
                val frame = Frame.TaskProgress(
                    id = null,
                    ts = clock.nowMillis(),
                    payload = TaskProgressPayload(
                        request_id = requestId,
                        step = step,
                        ratio = ratio,
                    ),
                )
                sendFrame(frame) // discard result — progress is soft info
            }
        }

        override fun onResult(requestId: String, kind: String, result: JsonObject) {
            dispatcher.execute {
                val current = inFlight
                if (current == null || current.requestId != requestId) {
                    logger.w(TAG, "onResult for unknown/stale request_id=$requestId")
                    return@execute
                }
                if (current.terminalSent) {
                    logger.w(TAG, "Duplicate terminal event (result) for request_id=$requestId, discarding")
                    return@execute
                }
                current.terminalSent = true
                enqueueAndSend(
                    Frame.TaskResult(
                        id = null,
                        ts = clock.nowMillis(),
                        payload = TaskResultPayload(
                            request_id = requestId,
                            kind = kind,
                            result = result,
                        ),
                    )
                )
                clearInFlight()
            }
        }

        override fun onError(requestId: String, code: String, message: String, retryable: Boolean) {
            dispatcher.execute {
                val current = inFlight
                if (current == null || current.requestId != requestId) {
                    logger.w(TAG, "onError for unknown/stale request_id=$requestId")
                    return@execute
                }
                if (current.terminalSent) {
                    logger.w(TAG, "Duplicate terminal event (error) for request_id=$requestId, discarding")
                    return@execute
                }
                current.terminalSent = true
                enqueueAndSend(
                    Frame.TaskError(
                        id = null,
                        ts = clock.nowMillis(),
                        payload = TaskErrorPayload(
                            request_id = requestId,
                            code = code,
                            message = message,
                            retryable = retryable,
                        ),
                    )
                )
                clearInFlight()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7.4 — Deadline monitoring
    // ═══════════════════════════════════════════════════════════════════════

    private fun onDeadlineExpired(requestId: String) {
        val current = inFlight
        if (current == null || current.requestId != requestId) return
        if (current.terminalSent) return

        logger.w(TAG, "Deadline expired for request_id=$requestId")
        current.terminalSent = true
        taskExecutor.cancel(requestId)
        enqueueAndSend(
            Frame.TaskError(
                id = null,
                ts = clock.nowMillis(),
                payload = TaskErrorPayload(
                    request_id = requestId,
                    code = "deadline_exceeded",
                    message = "deadline_exceeded",
                    retryable = false,
                ),
            )
        )
        clearInFlight()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7.5 — Inbound task.cancel handling
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Handles an inbound task.cancel frame. Must be called on the dispatcher thread.
     * Does not directly emit an error frame — waits for executor callback onError(code="cancelled").
     */
    fun onCancelFrame(frame: Frame.TaskCancel) {
        val current = inFlight
        if (current != null && current.requestId == frame.payload.request_id) {
            logger.i(TAG, "Cancel requested for request_id=${frame.payload.request_id}")
            taskExecutor.cancel(frame.payload.request_id)
        } else {
            logger.d(TAG, "Cancel for non-matching request_id=${frame.payload.request_id}, ignoring")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7.6 — State exposure for HeartbeatScheduler
    // ═══════════════════════════════════════════════════════════════════════

    /** Returns true if a task is currently in-flight. Called on dispatcher thread. */
    fun currentBusy(): Boolean = inFlight != null

    /** Returns the current in-flight request ID, or null. Called on dispatcher thread. */
    fun currentRequestId(): String? = inFlight?.requestId

    // ═══════════════════════════════════════════════════════════════════════
    // Internal helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun clearInFlight() {
        inFlight?.deadlineFuture?.cancel(false)
        inFlight = null
    }
}
