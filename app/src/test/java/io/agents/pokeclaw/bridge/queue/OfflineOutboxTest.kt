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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OfflineOutboxTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var logger: CapturingBridgeLogger
    private lateinit var dispatcher: BridgeDispatcher
    private val sentFrames = mutableListOf<Frame>()
    private var sendShouldSucceed = true

    private fun createOutbox(): OfflineOutbox {
        return OfflineOutbox(
            filesDir = tempDir,
            logger = logger,
            dispatcher = dispatcher,
            sendFrame = { frame ->
                if (sendShouldSucceed) {
                    sentFrames.add(frame)
                    true
                } else {
                    false
                }
            },
        )
    }

    @BeforeEach
    fun setUp() {
        logger = CapturingBridgeLogger()
        dispatcher = BridgeDispatcher(logger)
        sentFrames.clear()
        sendShouldSucceed = true
    }

    // ─── Helper factories ────────────────────────────────────────────────

    private fun makeTaskResult(requestId: String): Frame.TaskResult {
        return Frame.TaskResult(
            id = null,
            ts = System.currentTimeMillis(),
            payload = TaskResultPayload(
                request_id = requestId,
                kind = "test.kind",
                result = JsonObject().apply { addProperty("data", requestId) },
            ),
        )
    }

    private fun makeTaskError(requestId: String): Frame.TaskError {
        return Frame.TaskError(
            id = null,
            ts = System.currentTimeMillis(),
            payload = TaskErrorPayload(
                request_id = requestId,
                code = "internal",
                message = "test error",
                retryable = false,
            ),
        )
    }

    private fun makeTaskProgress(requestId: String): Frame.TaskProgress {
        return Frame.TaskProgress(
            id = null,
            ts = System.currentTimeMillis(),
            payload = TaskProgressPayload(
                request_id = requestId,
                step = "step1",
                ratio = 0.5,
            ),
        )
    }

    private fun makeTaskAccepted(requestId: String): Frame.TaskAccepted {
        return Frame.TaskAccepted(
            id = null,
            ts = System.currentTimeMillis(),
            payload = TaskAcceptedPayload(request_id = requestId),
        )
    }

    // ─── Test: FIFO order ────────────────────────────────────────────────

    @Test
    fun `drain sends frames in FIFO insertion order`() {
        sendShouldSucceed = false
        val outbox = createOutbox()

        // Enqueue 5 frames while offline
        val frames = (1..5).map { makeTaskResult("req_$it") }
        frames.forEach { outbox.enqueueAndSend(it) }

        assertThat(outbox.size()).isEqualTo(5)

        // Now go online and drain
        sendShouldSucceed = true
        outbox.drain()

        assertThat(outbox.size()).isEqualTo(0)
        assertThat(sentFrames).hasSize(5)

        // Verify FIFO order by checking request_id
        sentFrames.forEachIndexed { index, frame ->
            assertThat(frame).isInstanceOf(Frame.TaskResult::class.java)
            val result = frame as Frame.TaskResult
            assertThat(result.payload.request_id).isEqualTo("req_${index + 1}")
        }
    }

    // ─── Test: drop-oldest boundary ─────────────────────────────────────

    @Test
    fun `enqueue 201 frames drops oldest and keeps last 200`() {
        sendShouldSucceed = false
        val outbox = createOutbox()

        // Enqueue 201 frames
        (1..201).forEach { i ->
            outbox.enqueueAndSend(makeTaskResult("req_$i"))
        }

        assertThat(outbox.size()).isEqualTo(200)

        // Verify file has exactly 200 lines
        val outboxFile = File(tempDir, "bridge/outbox.jsonl")
        val lines = outboxFile.readLines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(200)

        // The first entry should be req_2 (req_1 was dropped)
        val snapshot = outbox.snapshot()
        val firstFrame = FrameCodec.decode(snapshot.first().frameJson)
        assertThat(firstFrame).isInstanceOf(Frame.TaskResult::class.java)
        assertThat((firstFrame as Frame.TaskResult).payload.request_id).isEqualTo("req_2")

        // The last entry should be req_201
        val lastFrame = FrameCodec.decode(snapshot.last().frameJson)
        assertThat(lastFrame).isInstanceOf(Frame.TaskResult::class.java)
        assertThat((lastFrame as Frame.TaskResult).payload.request_id).isEqualTo("req_201")
    }

    // ─── Test: cross-process persistence ─────────────────────────────────

    @Test
    fun `new outbox instance reads persisted entries from same directory`() {
        sendShouldSucceed = false
        val outbox1 = createOutbox()

        val frames = (1..3).map { makeTaskResult("persist_$it") }
        frames.forEach { outbox1.enqueueAndSend(it) }
        assertThat(outbox1.size()).isEqualTo(3)

        // Create a new outbox from the same directory (simulates process restart)
        val outbox2 = createOutbox()
        assertThat(outbox2.size()).isEqualTo(3)

        // Drain the second outbox and verify content matches
        sendShouldSucceed = true
        outbox2.drain()

        assertThat(sentFrames).hasSize(3)
        sentFrames.forEachIndexed { index, frame ->
            assertThat(frame).isInstanceOf(Frame.TaskResult::class.java)
            val result = frame as Frame.TaskResult
            assertThat(result.payload.request_id).isEqualTo("persist_${index + 1}")
        }
    }

    // ─── Test: drain stops on send failure ───────────────────────────────

    @Test
    fun `drain stops on first send failure and preserves remaining`() {
        sendShouldSucceed = false
        val outbox = createOutbox()

        // Enqueue 5 frames
        (1..5).forEach { outbox.enqueueAndSend(makeTaskResult("req_$it")) }
        assertThat(outbox.size()).isEqualTo(5)

        // Allow only first 2 sends to succeed
        var sendCount = 0
        val outbox2 = OfflineOutbox(
            filesDir = tempDir,
            logger = logger,
            dispatcher = dispatcher,
            sendFrame = { frame ->
                sendCount++
                if (sendCount <= 2) {
                    sentFrames.add(frame)
                    true
                } else {
                    false
                }
            },
        )

        outbox2.drain()

        // 2 sent successfully, 3 remain
        assertThat(sentFrames).hasSize(2)
        assertThat(outbox2.size()).isEqualTo(3)

        // Remaining should be req_3, req_4, req_5
        val snapshot = outbox2.snapshot()
        val remainingIds = snapshot.map {
            (FrameCodec.decode(it.frameJson) as Frame.TaskResult).payload.request_id
        }
        assertThat(remainingIds).containsExactly("req_3", "req_4", "req_5")
    }

    // ─── Test: task.progress is ignored ──────────────────────────────────

    @Test
    fun `task progress frames are never persisted`() {
        sendShouldSucceed = false
        val outbox = createOutbox()

        // Enqueue a progress frame
        outbox.enqueueAndSend(makeTaskProgress("req_1"))

        // Should not be stored
        assertThat(outbox.size()).isEqualTo(0)

        // File should not exist or be empty
        val outboxFile = File(tempDir, "bridge/outbox.jsonl")
        if (outboxFile.exists()) {
            assertThat(outboxFile.readText().trim()).isEmpty()
        }
    }

    // ─── Test: authenticated + empty queue → direct send ─────────────────

    @Test
    fun `authenticated with empty queue sends directly without persisting`() {
        val outbox = createOutbox()
        outbox.setAuthenticated(true)

        val frame = makeTaskResult("direct_1")
        outbox.enqueueAndSend(frame)

        // Should have been sent directly
        assertThat(sentFrames).hasSize(1)
        assertThat((sentFrames[0] as Frame.TaskResult).payload.request_id)
            .isEqualTo("direct_1")

        // Nothing persisted
        assertThat(outbox.size()).isEqualTo(0)
    }

    @Test
    fun `authenticated but send fails persists to disk`() {
        sendShouldSucceed = false
        val outbox = createOutbox()
        outbox.setAuthenticated(true)

        outbox.enqueueAndSend(makeTaskResult("fail_1"))

        // Send failed, so it should be persisted
        assertThat(outbox.size()).isEqualTo(1)
    }
}
