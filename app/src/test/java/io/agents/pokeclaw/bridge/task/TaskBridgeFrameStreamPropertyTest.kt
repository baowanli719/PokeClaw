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
import net.jqwik.api.*
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Property 7: Task frame stream mirrors executor callbacks
 *
 * For any task.dispatch that passes precondition checks, and any legal callback
 * sequence from TaskExecutor of the form onAccepted?, onProgress*, (onResult | onError),
 * the frames emitted by Bridge shall be exactly:
 * task.accepted, task.progress*, (task.result | task.error) with matching request_id
 * and payload fields.
 *
 * **Validates: Requirements 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 6.4**
 */
class TaskBridgeFrameStreamPropertyTest {

    @Property(tries = 100)
    fun `frame stream mirrors executor callback sequence`(
        @ForAll("callbackSequences") scenario: CallbackScenario,
    ) {
        val logger = CapturingBridgeLogger()
        val dispatcher = BridgeDispatcher(logger)
        val clock = FakeClock(wallMs = 1_000_000L)
        val executor = FakeTaskExecutor()
        val capabilityProvider = FakeCapabilityProvider(
            snapshot = CapabilitySnapshot(
                supportedKinds = listOf("ths.sync_holdings"),
                accessibilityReady = true,
                installedTargetApps = mapOf("ths.sync_holdings" to true),
                batteryLevel = 0.8,
                charging = false,
            )
        )

        val sentFrames = mutableListOf<Frame>()
        val enqueuedFrames = mutableListOf<Frame>()

        val taskBridge = TaskBridge(
            dispatcher = dispatcher,
            capabilityProvider = capabilityProvider,
            taskExecutor = executor,
            clock = clock,
            logger = logger,
            sendFrame = { frame -> sentFrames.add(frame); true },
            enqueueAndSend = { frame -> enqueuedFrames.add(frame) },
            currentCapabilities = { listOf("ths.sync_holdings") },
        )

        try {
            val requestId = "req_prop7"

            // Dispatch the task
            val dispatchLatch = CountDownLatch(1)
            dispatcher.execute {
                taskBridge.onDispatchFrame(
                    Frame.TaskDispatch(
                        id = null,
                        ts = clock.nowMillis(),
                        payload = TaskDispatchPayload(
                            request_id = requestId,
                            kind = "ths.sync_holdings",
                            params = JsonObject(),
                            deadline_ts = null,
                        ),
                    )
                )
                dispatchLatch.countDown()
            }
            dispatchLatch.await(2, TimeUnit.SECONDS)

            // Verify accepted was emitted
            assertThat(enqueuedFrames).hasSize(1)
            assertThat(enqueuedFrames[0]).isInstanceOf(Frame.TaskAccepted::class.java)
            assertThat((enqueuedFrames[0] as Frame.TaskAccepted).payload.request_id)
                .isEqualTo(requestId)

            val callback = executor.executeCalls[0].callback

            // Execute the callback sequence
            for (event in scenario.events) {
                val eventLatch = CountDownLatch(1)
                dispatcher.execute {
                    when (event) {
                        is CallbackEvent.Progress -> {
                            callback.onProgress(requestId, event.step, event.ratio)
                        }
                        is CallbackEvent.Result -> {
                            callback.onResult(requestId, "ths.sync_holdings", JsonObject())
                        }
                        is CallbackEvent.Error -> {
                            callback.onError(requestId, event.code, event.message, event.retryable)
                        }
                    }
                    eventLatch.countDown()
                }
                eventLatch.await(2, TimeUnit.SECONDS)
                // Allow inner dispatch to settle
                val settleLatch = CountDownLatch(1)
                dispatcher.execute { settleLatch.countDown() }
                settleLatch.await(2, TimeUnit.SECONDS)
            }

            // Verify frame stream structure:
            // enqueuedFrames: [TaskAccepted, ..., (TaskResult | TaskError)]
            // sentFrames: [TaskProgress*]

            // 1. First enqueued frame is always TaskAccepted
            assertThat(enqueuedFrames[0]).isInstanceOf(Frame.TaskAccepted::class.java)

            // 2. All progress frames are in sentFrames (not enqueued)
            val progressCount = scenario.events.count { it is CallbackEvent.Progress }
            val actualProgressFrames = sentFrames.filterIsInstance<Frame.TaskProgress>()
            assertThat(actualProgressFrames).hasSize(progressCount)

            // 3. Verify progress payloads match
            val expectedProgresses = scenario.events.filterIsInstance<CallbackEvent.Progress>()
            for ((i, expected) in expectedProgresses.withIndex()) {
                val actual = actualProgressFrames[i]
                assertThat(actual.payload.request_id).isEqualTo(requestId)
                assertThat(actual.payload.step).isEqualTo(expected.step)
                assertThat(actual.payload.ratio).isEqualTo(expected.ratio)
            }

            // 4. Terminal frame is the last enqueued frame (after accepted)
            val terminalFrame = enqueuedFrames.last()
            val terminalEvent = scenario.events.last()
            when (terminalEvent) {
                is CallbackEvent.Result -> {
                    assertThat(terminalFrame).isInstanceOf(Frame.TaskResult::class.java)
                    val result = terminalFrame as Frame.TaskResult
                    assertThat(result.payload.request_id).isEqualTo(requestId)
                }
                is CallbackEvent.Error -> {
                    assertThat(terminalFrame).isInstanceOf(Frame.TaskError::class.java)
                    val error = terminalFrame as Frame.TaskError
                    assertThat(error.payload.request_id).isEqualTo(requestId)
                    assertThat(error.payload.code).isEqualTo(terminalEvent.code)
                }
                else -> {} // Progress can't be terminal in our generator
            }
        } finally {
            dispatcher.shutdown()
        }
    }

    @Property(tries = 50)
    fun `cancel triggers executor cancel and terminal frame is error`(
        @ForAll("progressCounts") progressCount: Int,
    ) {
        val logger = CapturingBridgeLogger()
        val dispatcher = BridgeDispatcher(logger)
        val clock = FakeClock(wallMs = 1_000_000L)
        val executor = FakeTaskExecutor()
        val capabilityProvider = FakeCapabilityProvider(
            snapshot = CapabilitySnapshot(
                supportedKinds = listOf("ths.sync_holdings"),
                accessibilityReady = true,
                installedTargetApps = mapOf("ths.sync_holdings" to true),
                batteryLevel = null,
                charging = null,
            )
        )

        val sentFrames = mutableListOf<Frame>()
        val enqueuedFrames = mutableListOf<Frame>()

        val taskBridge = TaskBridge(
            dispatcher = dispatcher,
            capabilityProvider = capabilityProvider,
            taskExecutor = executor,
            clock = clock,
            logger = logger,
            sendFrame = { frame -> sentFrames.add(frame); true },
            enqueueAndSend = { frame -> enqueuedFrames.add(frame) },
            currentCapabilities = { listOf("ths.sync_holdings") },
        )

        try {
            val requestId = "req_cancel_prop"

            // Dispatch
            val dispatchLatch = CountDownLatch(1)
            dispatcher.execute {
                taskBridge.onDispatchFrame(
                    Frame.TaskDispatch(
                        id = null,
                        ts = clock.nowMillis(),
                        payload = TaskDispatchPayload(
                            request_id = requestId,
                            kind = "ths.sync_holdings",
                            params = JsonObject(),
                            deadline_ts = null,
                        ),
                    )
                )
                dispatchLatch.countDown()
            }
            dispatchLatch.await(2, TimeUnit.SECONDS)

            val callback = executor.executeCalls[0].callback

            // Send some progress events
            for (i in 0 until progressCount) {
                val pLatch = CountDownLatch(1)
                dispatcher.execute {
                    callback.onProgress(requestId, "step_$i", i.toDouble() / progressCount)
                    pLatch.countDown()
                }
                pLatch.await(2, TimeUnit.SECONDS)
                val sLatch = CountDownLatch(1)
                dispatcher.execute { sLatch.countDown() }
                sLatch.await(2, TimeUnit.SECONDS)
            }

            // Send cancel
            val cancelLatch = CountDownLatch(1)
            dispatcher.execute {
                taskBridge.onCancelFrame(
                    Frame.TaskCancel(
                        id = null,
                        ts = clock.nowMillis(),
                        payload = TaskCancelPayload(request_id = requestId),
                    )
                )
                cancelLatch.countDown()
            }
            cancelLatch.await(2, TimeUnit.SECONDS)

            // Executor should have been cancelled
            assertThat(executor.cancelCalls).contains(requestId)

            // Simulate executor responding with cancelled error
            val errLatch = CountDownLatch(1)
            dispatcher.execute {
                callback.onError(requestId, "cancelled", "cancelled by user", false)
                errLatch.countDown()
            }
            errLatch.await(2, TimeUnit.SECONDS)
            val settleLatch = CountDownLatch(1)
            dispatcher.execute { settleLatch.countDown() }
            settleLatch.await(2, TimeUnit.SECONDS)

            // Terminal frame should be task.error with code "cancelled"
            val errors = enqueuedFrames.filterIsInstance<Frame.TaskError>()
            assertThat(errors).hasSize(1)
            assertThat(errors[0].payload.code).isEqualTo("cancelled")
            assertThat(errors[0].payload.request_id).isEqualTo(requestId)
        } finally {
            dispatcher.shutdown()
        }
    }

    @Property(tries = 50)
    fun `deadline expiry produces deadline_exceeded terminal frame`(
        @ForAll("progressCounts") progressCount: Int,
    ) {
        val logger = CapturingBridgeLogger()
        val dispatcher = BridgeDispatcher(logger)
        val clock = FakeClock(wallMs = 1_000_000L)
        val executor = FakeTaskExecutor()
        val capabilityProvider = FakeCapabilityProvider(
            snapshot = CapabilitySnapshot(
                supportedKinds = listOf("ths.sync_holdings"),
                accessibilityReady = true,
                installedTargetApps = mapOf("ths.sync_holdings" to true),
                batteryLevel = null,
                charging = null,
            )
        )

        val sentFrames = mutableListOf<Frame>()
        val enqueuedFrames = mutableListOf<Frame>()

        val taskBridge = TaskBridge(
            dispatcher = dispatcher,
            capabilityProvider = capabilityProvider,
            taskExecutor = executor,
            clock = clock,
            logger = logger,
            sendFrame = { frame -> sentFrames.add(frame); true },
            enqueueAndSend = { frame -> enqueuedFrames.add(frame) },
            currentCapabilities = { listOf("ths.sync_holdings") },
        )

        try {
            val requestId = "req_deadline_prop"
            // Deadline 200ms from now
            val deadlineTs = clock.nowMillis() + 200L

            val dispatchLatch = CountDownLatch(1)
            dispatcher.execute {
                taskBridge.onDispatchFrame(
                    Frame.TaskDispatch(
                        id = null,
                        ts = clock.nowMillis(),
                        payload = TaskDispatchPayload(
                            request_id = requestId,
                            kind = "ths.sync_holdings",
                            params = JsonObject(),
                            deadline_ts = deadlineTs,
                        ),
                    )
                )
                dispatchLatch.countDown()
            }
            dispatchLatch.await(2, TimeUnit.SECONDS)

            // Wait for deadline to fire
            Thread.sleep(400)

            // Drain dispatcher
            val settleLatch = CountDownLatch(1)
            dispatcher.execute { settleLatch.countDown() }
            settleLatch.await(2, TimeUnit.SECONDS)

            // Terminal frame should be deadline_exceeded
            val errors = enqueuedFrames.filterIsInstance<Frame.TaskError>()
            assertThat(errors).hasSize(1)
            assertThat(errors[0].payload.code).isEqualTo("deadline_exceeded")
            assertThat(errors[0].payload.request_id).isEqualTo(requestId)
            assertThat(errors[0].payload.retryable).isFalse()

            // Executor should have been cancelled
            assertThat(executor.cancelCalls).contains(requestId)
        } finally {
            dispatcher.shutdown()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Event model
    // ═══════════════════════════════════════════════════════════════════════

    sealed class CallbackEvent {
        data class Progress(val step: String, val ratio: Double?) : CallbackEvent()
        data class Result(val kind: String = "ths.sync_holdings") : CallbackEvent()
        data class Error(
            val code: String,
            val message: String,
            val retryable: Boolean,
        ) : CallbackEvent()
    }

    data class CallbackScenario(val events: List<CallbackEvent>)

    // ═══════════════════════════════════════════════════════════════════════
    // Generators
    // ═══════════════════════════════════════════════════════════════════════

    @Provide
    fun callbackSequences(): Arbitrary<CallbackScenario> {
        val progressArb = Arbitraries.integers().between(0, 5).flatMap { count ->
            Arbitraries.just(count).map { n ->
                (0 until n).map { i ->
                    CallbackEvent.Progress("step_$i", i.toDouble() / (n.coerceAtLeast(1)))
                }
            }
        }

        val terminalArb: Arbitrary<CallbackEvent> = Arbitraries.oneOf(
            Arbitraries.just(CallbackEvent.Result()),
            Arbitraries.of("internal", "cancelled", "timeout").flatMap { code ->
                Arbitraries.of(true, false).map { retryable ->
                    CallbackEvent.Error(code, "error: $code", retryable)
                }
            },
        )

        return Combinators.combine(progressArb, terminalArb).`as` { progresses, terminal ->
            CallbackScenario(progresses + terminal)
        }
    }

    @Provide
    fun progressCounts(): Arbitrary<Int> =
        Arbitraries.integers().between(0, 5)
}
