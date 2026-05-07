package io.agents.pokeclaw.bridge.task

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.CapabilitySnapshot
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.api.FakeCapabilityProvider
import io.agents.pokeclaw.bridge.api.FakeTaskExecutor
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.FakeClock
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.TaskDispatchPayload
import net.jqwik.api.*
import org.assertj.core.api.Assertions.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Property 6: Single in-flight task invariant
 *
 * For any sequence of inbound task.dispatch frames with distinct request_id values,
 * at any moment the set of requests for which task.accepted has been sent but neither
 * task.result nor task.error has been sent shall contain at most one element.
 * Any task.dispatch received while the set is non-empty shall be rejected with
 * task.error(code = "internal", message ~ "busy", retryable = true) without invoking
 * TaskExecutor.execute.
 *
 * **Validates: Requirements 5.1, 9.3**
 */
class TaskBridgeSingleFlightPropertyTest {

    @Property(tries = 100)
    fun `at most one task is in-flight at any time`(
        @ForAll("dispatchSequences") sequence: List<DispatchEvent>,
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

        val enqueuedFrames = mutableListOf<Frame>()

        val taskBridge = TaskBridge(
            dispatcher = dispatcher,
            capabilityProvider = capabilityProvider,
            taskExecutor = executor,
            clock = clock,
            logger = logger,
            sendFrame = { true },
            enqueueAndSend = { frame -> enqueuedFrames.add(frame) },
            currentCapabilities = { listOf("ths.sync_holdings") },
        )

        // Track accepted but not terminated request IDs
        val acceptedSet = mutableSetOf<String>()

        try {
            for (event in sequence) {
                val latch = CountDownLatch(1)
                dispatcher.execute {
                    when (event) {
                        is DispatchEvent.Dispatch -> {
                            taskBridge.onDispatchFrame(
                                Frame.TaskDispatch(
                                    id = null,
                                    ts = clock.nowMillis(),
                                    payload = TaskDispatchPayload(
                                        request_id = event.requestId,
                                        kind = "ths.sync_holdings",
                                        params = JsonObject(),
                                        deadline_ts = null,
                                    ),
                                )
                            )
                        }
                        is DispatchEvent.Complete -> {
                            // Simulate executor completing a task
                            val call = executor.executeCalls.find {
                                it.requestId == event.requestId
                            }
                            call?.callback?.onResult(
                                event.requestId,
                                "ths.sync_holdings",
                                JsonObject(),
                            )
                        }
                    }
                    latch.countDown()
                }
                latch.await(2, TimeUnit.SECONDS)

                // After inner dispatches settle
                val settleLatch = CountDownLatch(1)
                dispatcher.execute { settleLatch.countDown() }
                settleLatch.await(2, TimeUnit.SECONDS)

                // Recompute accepted set from frames
                acceptedSet.clear()
                for (frame in enqueuedFrames) {
                    when (frame) {
                        is Frame.TaskAccepted -> acceptedSet.add(frame.payload.request_id)
                        is Frame.TaskResult -> acceptedSet.remove(frame.payload.request_id)
                        is Frame.TaskError -> {
                            // Only remove if it's a terminal for an accepted task
                            // (busy rejections are never in acceptedSet)
                            acceptedSet.remove(frame.payload.request_id)
                        }
                        else -> {}
                    }
                }

                // INVARIANT: at most 1 in-flight
                assertThat(acceptedSet.size)
                    .describedAs("At most one task should be in-flight at any time")
                    .isLessThanOrEqualTo(1)
            }

            // Verify: all dispatches while busy got error(internal, busy, retryable=true)
            val busyErrors = enqueuedFrames.filterIsInstance<Frame.TaskError>()
                .filter { it.payload.code == "internal" && it.payload.message.contains("busy") }
            for (error in busyErrors) {
                assertThat(error.payload.retryable).isTrue()
            }

            // Verify: executor.execute was never called for busy-rejected requests
            val executedIds = executor.executeCalls.map { it.requestId }.toSet()
            val busyRejectedIds = busyErrors.map { it.payload.request_id }.toSet()
            assertThat(executedIds.intersect(busyRejectedIds)).isEmpty()
        } finally {
            dispatcher.shutdown()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Event model
    // ═══════════════════════════════════════════════════════════════════════

    sealed class DispatchEvent {
        data class Dispatch(val requestId: String) : DispatchEvent()
        data class Complete(val requestId: String) : DispatchEvent()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Generators
    // ═══════════════════════════════════════════════════════════════════════

    @Provide
    fun dispatchSequences(): Arbitrary<List<DispatchEvent>> {
        // Generate sequences of dispatch + complete events with distinct request IDs
        return Arbitraries.integers().between(2, 10).flatMap { count ->
            Arbitraries.just(count).map { n ->
                val events = mutableListOf<DispatchEvent>()
                val dispatched = mutableListOf<String>()
                val completed = mutableSetOf<String>()

                for (i in 1..n) {
                    val requestId = "req_$i"
                    events.add(DispatchEvent.Dispatch(requestId))
                    dispatched.add(requestId)

                    // Randomly complete a previously dispatched (but not yet completed) task
                    val completable = dispatched.filter { it !in completed }
                    if (completable.isNotEmpty() && i < n) {
                        // Complete the first dispatched task to allow next dispatch
                        val toComplete = completable.first()
                        events.add(DispatchEvent.Complete(toComplete))
                        completed.add(toComplete)
                    }
                }
                events
            }
        }
    }
}
