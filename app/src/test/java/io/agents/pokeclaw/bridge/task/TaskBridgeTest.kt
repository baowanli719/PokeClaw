package io.agents.pokeclaw.bridge.task

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.CapabilitySnapshot
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.api.FakeCapabilityProvider
import io.agents.pokeclaw.bridge.api.FakeTaskExecutor
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.FakeClock
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.TaskCancelPayload
import io.agents.pokeclaw.bridge.protocol.TaskDispatchPayload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Example unit tests for TaskBridge.
 *
 * Validates: Requirements 5.1, 5.2, 5.3, 5.7, 5.8, 6.4, 9.3
 */
class TaskBridgeTest {

    private lateinit var logger: CapturingBridgeLogger
    private lateinit var dispatcher: BridgeDispatcher
    private lateinit var clock: FakeClock
    private lateinit var executor: FakeTaskExecutor
    private lateinit var capabilityProvider: FakeCapabilityProvider
    private lateinit var taskBridge: TaskBridge

    private val sentFrames = CopyOnWriteArrayList<Frame>()
    private val enqueuedFrames = CopyOnWriteArrayList<Frame>()

    @BeforeEach
    fun setUp() {
        logger = CapturingBridgeLogger()
        dispatcher = BridgeDispatcher(logger)
        clock = FakeClock(wallMs = 1_000_000L)
        executor = FakeTaskExecutor()
        capabilityProvider = FakeCapabilityProvider(
            snapshot = CapabilitySnapshot(
                supportedKinds = listOf("ths.sync_holdings"),
                accessibilityReady = true,
                installedTargetApps = mapOf("ths.sync_holdings" to true),
                batteryLevel = 0.8,
                charging = false,
            )
        )

        taskBridge = TaskBridge(
            dispatcher = dispatcher,
            capabilityProvider = capabilityProvider,
            taskExecutor = executor,
            clock = clock,
            logger = logger,
            sendFrame = { frame -> sentFrames.add(frame); true },
            enqueueAndSend = { frame -> enqueuedFrames.add(frame) },
            currentCapabilities = { capabilityProvider.snapshot.supportedKinds },
        )
    }

    @AfterEach
    fun tearDown() {
        dispatcher.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helper
    // ═══════════════════════════════════════════════════════════════════════

    private fun dispatchOnDispatcher(frame: Frame.TaskDispatch) {
        val latch = CountDownLatch(1)
        dispatcher.execute {
            taskBridge.onDispatchFrame(frame)
            latch.countDown()
        }
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
    }

    private fun cancelOnDispatcher(frame: Frame.TaskCancel) {
        val latch = CountDownLatch(1)
        dispatcher.execute {
            taskBridge.onCancelFrame(frame)
            latch.countDown()
        }
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue()
    }

    private fun makeDispatch(
        requestId: String = "req_001",
        kind: String = "ths.sync_holdings",
        deadlineTs: Long? = null,
    ) = Frame.TaskDispatch(
        id = null,
        ts = clock.nowMillis(),
        payload = TaskDispatchPayload(
            request_id = requestId,
            kind = kind,
            params = JsonObject(),
            deadline_ts = deadlineTs,
        ),
    )

    // ═══════════════════════════════════════════════════════════════════════
    // Test: busy rejects new dispatch
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `busy device rejects new dispatch with retryable error`() {
        // First dispatch is accepted
        dispatchOnDispatcher(makeDispatch(requestId = "req_001"))
        assertThat(enqueuedFrames).hasSize(1)
        assertThat((enqueuedFrames[0] as Frame.TaskAccepted).payload.request_id).isEqualTo("req_001")

        // Second dispatch while first is in-flight → rejected
        dispatchOnDispatcher(makeDispatch(requestId = "req_002"))
        assertThat(enqueuedFrames).hasSize(2)
        val error = enqueuedFrames[1] as Frame.TaskError
        assertThat(error.payload.code).isEqualTo("internal")
        assertThat(error.payload.message).contains("busy")
        assertThat(error.payload.retryable).isTrue()

        // Executor should only have been called once
        assertThat(executor.executeCalls).hasSize(1)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: accessibility_not_ready
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `accessibility not ready returns accessibility_not_ready error`() {
        capabilityProvider.snapshot = capabilityProvider.snapshot.copy(accessibilityReady = false)

        dispatchOnDispatcher(makeDispatch())
        assertThat(enqueuedFrames).hasSize(1)
        val error = enqueuedFrames[0] as Frame.TaskError
        assertThat(error.payload.code).isEqualTo("accessibility_not_ready")
        assertThat(error.payload.retryable).isFalse()
        assertThat(executor.executeCalls).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: app_not_installed
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `target app not installed returns app_not_installed error`() {
        capabilityProvider.snapshot = capabilityProvider.snapshot.copy(
            installedTargetApps = mapOf("ths.sync_holdings" to false)
        )

        dispatchOnDispatcher(makeDispatch())
        assertThat(enqueuedFrames).hasSize(1)
        val error = enqueuedFrames[0] as Frame.TaskError
        assertThat(error.payload.code).isEqualTo("app_not_installed")
        assertThat(error.payload.retryable).isFalse()
        assertThat(executor.executeCalls).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: unsupported capability
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `unsupported capability returns internal error with kind name`() {
        dispatchOnDispatcher(makeDispatch(kind = "unknown.kind"))
        assertThat(enqueuedFrames).hasSize(1)
        val error = enqueuedFrames[0] as Frame.TaskError
        assertThat(error.payload.code).isEqualTo("internal")
        assertThat(error.payload.message).contains("unsupported capability")
        assertThat(error.payload.message).contains("unknown.kind")
        assertThat(error.payload.retryable).isFalse()
        assertThat(executor.executeCalls).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: normal dispatch flow — accepted → progress → result
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `normal dispatch emits accepted then progress then result`() {
        dispatchOnDispatcher(makeDispatch(requestId = "req_001"))

        // Accepted should be enqueued
        assertThat(enqueuedFrames).hasSize(1)
        assertThat(enqueuedFrames[0]).isInstanceOf(Frame.TaskAccepted::class.java)

        // Simulate executor callbacks
        val callback = executor.executeCalls[0].callback

        // Progress
        val progressLatch = CountDownLatch(1)
        dispatcher.execute {
            callback.onProgress("req_001", "step1", 0.5)
            progressLatch.countDown()
        }
        // Wait for the inner dispatcher.execute from BridgingCallback
        progressLatch.await(2, TimeUnit.SECONDS)
        Thread.sleep(100) // allow inner dispatch to complete
        assertThat(sentFrames).hasSize(1)
        val progress = sentFrames[0] as Frame.TaskProgress
        assertThat(progress.payload.request_id).isEqualTo("req_001")
        assertThat(progress.payload.step).isEqualTo("step1")
        assertThat(progress.payload.ratio).isEqualTo(0.5)

        // Result
        val resultJson = JsonObject().apply { addProperty("data", "value") }
        val resultLatch = CountDownLatch(1)
        dispatcher.execute {
            callback.onResult("req_001", "ths.sync_holdings", resultJson)
            resultLatch.countDown()
        }
        resultLatch.await(2, TimeUnit.SECONDS)
        Thread.sleep(100)
        assertThat(enqueuedFrames).hasSize(2)
        val result = enqueuedFrames[1] as Frame.TaskResult
        assertThat(result.payload.request_id).isEqualTo("req_001")
        assertThat(result.payload.kind).isEqualTo("ths.sync_holdings")
        assertThat(result.payload.result).isEqualTo(resultJson)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: deadline expired emits deadline_exceeded
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `deadline expiry emits deadline_exceeded error and cancels executor`() {
        // Set deadline 500ms from now
        val deadlineTs = clock.nowMillis() + 500L
        dispatchOnDispatcher(makeDispatch(requestId = "req_deadline", deadlineTs = deadlineTs))

        assertThat(enqueuedFrames).hasSize(1)
        assertThat(enqueuedFrames[0]).isInstanceOf(Frame.TaskAccepted::class.java)

        // Wait for deadline timer to fire (it's scheduled at ~500ms)
        Thread.sleep(800)

        // Drain dispatcher
        val latch = CountDownLatch(1)
        dispatcher.execute { latch.countDown() }
        latch.await(2, TimeUnit.SECONDS)

        // Should have emitted deadline_exceeded error
        val errors = enqueuedFrames.filterIsInstance<Frame.TaskError>()
        assertThat(errors).hasSize(1)
        assertThat(errors[0].payload.code).isEqualTo("deadline_exceeded")
        assertThat(errors[0].payload.retryable).isFalse()

        // Executor should have been cancelled
        assertThat(executor.cancelCalls).contains("req_deadline")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: cancel frame triggers executor.cancel
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `cancel frame triggers executor cancel`() {
        dispatchOnDispatcher(makeDispatch(requestId = "req_cancel"))
        assertThat(executor.executeCalls).hasSize(1)

        cancelOnDispatcher(
            Frame.TaskCancel(
                id = null,
                ts = clock.nowMillis(),
                payload = TaskCancelPayload(request_id = "req_cancel"),
            )
        )

        assertThat(executor.cancelCalls).contains("req_cancel")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: cancel for non-matching request is ignored
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `cancel for non-matching request id is ignored`() {
        dispatchOnDispatcher(makeDispatch(requestId = "req_active"))

        cancelOnDispatcher(
            Frame.TaskCancel(
                id = null,
                ts = clock.nowMillis(),
                payload = TaskCancelPayload(request_id = "req_other"),
            )
        )

        assertThat(executor.cancelCalls).isEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test: currentBusy / currentRequestId
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `currentBusy and currentRequestId reflect in-flight state`() {
        val busyLatch = CountDownLatch(1)
        dispatcher.execute {
            assertThat(taskBridge.currentBusy()).isFalse()
            assertThat(taskBridge.currentRequestId()).isNull()
            busyLatch.countDown()
        }
        busyLatch.await(2, TimeUnit.SECONDS)

        dispatchOnDispatcher(makeDispatch(requestId = "req_busy"))

        val checkLatch = CountDownLatch(1)
        dispatcher.execute {
            assertThat(taskBridge.currentBusy()).isTrue()
            assertThat(taskBridge.currentRequestId()).isEqualTo("req_busy")
            checkLatch.countDown()
        }
        checkLatch.await(2, TimeUnit.SECONDS)
    }
}
