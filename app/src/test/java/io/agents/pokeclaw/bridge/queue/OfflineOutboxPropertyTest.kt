package io.agents.pokeclaw.bridge.queue

import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.FrameCodec
import io.agents.pokeclaw.bridge.protocol.TaskAcceptedPayload
import io.agents.pokeclaw.bridge.protocol.TaskErrorPayload
import io.agents.pokeclaw.bridge.protocol.TaskProgressPayload
import io.agents.pokeclaw.bridge.protocol.TaskResultPayload
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.lifecycle.AfterProperty
import net.jqwik.api.lifecycle.BeforeProperty
import org.assertj.core.api.Assertions.assertThat
import java.io.File
import java.nio.file.Files

/**
 * Property 8: Offline outbox is FIFO, bounded, and drained on reconnect
 *
 * Validates: Requirements 9.1, 9.4
 */
class OfflineOutboxPropertyTest {

    private lateinit var tempDir: File
    private lateinit var logger: CapturingBridgeLogger
    private lateinit var dispatcher: BridgeDispatcher

    @BeforeProperty
    fun setUp() {
        tempDir = Files.createTempDirectory("outbox-pbt").toFile()
        logger = CapturingBridgeLogger()
        dispatcher = BridgeDispatcher(logger)
    }

    @AfterProperty
    fun tearDown() {
        tempDir.deleteRecursively()
        dispatcher.shutdown()
    }

    // ─── Generators ──────────────────────────────────────────────────────

    /**
     * Generates a terminal frame (task.result, task.error, or task.accepted)
     * with a unique request_id.
     */
    @Provide
    fun terminalFrames(): Arbitrary<List<Frame>> {
        return Arbitraries.integers().between(0, 500).flatMap { n ->
            Arbitraries.just((1..n).map { i -> makeTerminalFrame("req_$i", i) })
        }
    }

    /**
     * Generates a mix of terminal frames and task.progress frames.
     */
    @Provide
    fun mixedFrames(): Arbitrary<List<Frame>> {
        return Arbitraries.integers().between(0, 500).flatMap { n ->
            Arbitraries.just((1..n).map { i ->
                if (i % 7 == 0) {
                    // Every 7th frame is a progress frame
                    Frame.TaskProgress(
                        id = null,
                        ts = i.toLong(),
                        payload = TaskProgressPayload(
                            request_id = "req_$i",
                            step = "step",
                            ratio = 0.5,
                        ),
                    )
                } else {
                    makeTerminalFrame("req_$i", i)
                }
            })
        }
    }

    private fun makeTerminalFrame(requestId: String, seed: Int): Frame {
        return when (seed % 3) {
            0 -> Frame.TaskResult(
                id = null,
                ts = seed.toLong(),
                payload = TaskResultPayload(
                    request_id = requestId,
                    kind = "test.kind",
                    result = JsonObject().apply { addProperty("i", seed) },
                ),
            )
            1 -> Frame.TaskError(
                id = null,
                ts = seed.toLong(),
                payload = TaskErrorPayload(
                    request_id = requestId,
                    code = "internal",
                    message = "error $seed",
                    retryable = false,
                ),
            )
            else -> Frame.TaskAccepted(
                id = null,
                ts = seed.toLong(),
                payload = TaskAcceptedPayload(request_id = requestId),
            )
        }
    }

    // ─── Properties ──────────────────────────────────────────────────────

    /**
     * Property 8: Offline outbox is FIFO, bounded, and drained on reconnect
     *
     * For any sequence of terminal frames F = [f1..fn]:
     * 1. |outbox| <= 200
     * 2. n <= 200 → outbox contents = F in order
     * 3. n > 200 → outbox contents = last 200 of F in order
     *
     * **Validates: Requirements 9.1, 9.4**
     */
    @Property(tries = 200)
    fun `outbox is bounded at 200 and preserves FIFO order`(
        @ForAll("terminalFrames") frames: List<Frame>,
    ) {
        val sentFrames = mutableListOf<Frame>()
        val outbox = OfflineOutbox(
            filesDir = tempDir,
            logger = logger,
            dispatcher = dispatcher,
            sendFrame = { false }, // all sends fail → everything persisted
        )

        frames.forEach { outbox.enqueueAndSend(it) }

        // 1. Bounded
        assertThat(outbox.size()).isLessThanOrEqualTo(200)

        val n = frames.size
        val snapshot = outbox.snapshot()

        if (n <= 200) {
            // 2. Contents = F in insertion order
            assertThat(snapshot).hasSize(n)
            snapshot.forEachIndexed { index, entry ->
                val decoded = FrameCodec.decode(entry.frameJson)
                assertThat(FrameCodec.encode(decoded))
                    .isEqualTo(FrameCodec.encode(frames[index]))
            }
        } else {
            // 3. Contents = last 200 of F
            assertThat(snapshot).hasSize(200)
            val expectedTail = frames.takeLast(200)
            snapshot.forEachIndexed { index, entry ->
                val decoded = FrameCodec.decode(entry.frameJson)
                assertThat(FrameCodec.encode(decoded))
                    .isEqualTo(FrameCodec.encode(expectedTail[index]))
            }
        }

        // Clean up for next iteration
        tempDir.deleteRecursively()
        tempDir.mkdirs()
    }

    /**
     * Property 8 (drain): After drain with all sends succeeding,
     * outbox is empty and frames are received in FIFO order.
     *
     * **Validates: Requirements 9.1, 9.4**
     */
    @Property(tries = 200)
    fun `drain empties outbox and delivers in FIFO order`(
        @ForAll("terminalFrames") frames: List<Frame>,
    ) {
        val receivedFrames = mutableListOf<Frame>()
        val outbox = OfflineOutbox(
            filesDir = tempDir,
            logger = logger,
            dispatcher = dispatcher,
            sendFrame = { false }, // enqueue phase: all fail
        )

        frames.forEach { outbox.enqueueAndSend(it) }

        // Now create a new outbox that can send successfully (simulates reconnect)
        val drainOutbox = OfflineOutbox(
            filesDir = tempDir,
            logger = logger,
            dispatcher = dispatcher,
            sendFrame = { frame ->
                receivedFrames.add(frame)
                true
            },
        )

        drainOutbox.drain()

        // After drain, outbox should be empty
        assertThat(drainOutbox.size()).isEqualTo(0)

        // Received frames should match the expected tail
        val expectedCount = minOf(frames.size, 200)
        assertThat(receivedFrames).hasSize(expectedCount)

        if (frames.isNotEmpty()) {
            val expectedTail = frames.takeLast(200)
            receivedFrames.forEachIndexed { index, received ->
                assertThat(FrameCodec.encode(received))
                    .isEqualTo(FrameCodec.encode(expectedTail[index]))
            }
        }

        // Clean up for next iteration
        tempDir.deleteRecursively()
        tempDir.mkdirs()
    }

    /**
     * Property 8 (progress exclusion): task.progress frames never enter the outbox.
     *
     * **Validates: Requirements 9.1, 9.4**
     */
    @Property(tries = 200)
    fun `task progress frames are never persisted in outbox`(
        @ForAll("mixedFrames") frames: List<Frame>,
    ) {
        val outbox = OfflineOutbox(
            filesDir = tempDir,
            logger = logger,
            dispatcher = dispatcher,
            sendFrame = { false }, // all sends fail
        )

        frames.forEach { outbox.enqueueAndSend(it) }

        // Count how many non-progress frames were in the input
        val terminalCount = frames.count { it !is Frame.TaskProgress }
        val expectedSize = minOf(terminalCount, 200)

        assertThat(outbox.size()).isEqualTo(expectedSize)

        // Verify no progress frames in the snapshot
        val snapshot = outbox.snapshot()
        snapshot.forEach { entry ->
            val decoded = FrameCodec.decode(entry.frameJson)
            assertThat(decoded).isNotInstanceOf(Frame.TaskProgress::class.java)
        }

        // Clean up for next iteration
        tempDir.deleteRecursively()
        tempDir.mkdirs()
    }
}
