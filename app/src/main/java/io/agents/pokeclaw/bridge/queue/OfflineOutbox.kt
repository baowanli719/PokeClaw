package io.agents.pokeclaw.bridge.queue

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.FrameCodec
import java.io.File

private const val TAG = "OfflineOutbox"
private const val MAX_SIZE = 200

/**
 * Disk-backed FIFO queue for terminal task frames that could not be sent
 * while the connection was down. Stored as JSON Lines at
 * `filesDir/bridge/outbox.jsonl`.
 *
 * All operations MUST be called on the [BridgeDispatcher] thread.
 */
class OfflineOutbox(
    filesDir: File,
    private val logger: BridgeLogger,
    private val dispatcher: BridgeDispatcher,
    private val sendFrame: (Frame) -> Boolean,
) {

    private val outboxFile: File = File(filesDir, "bridge/outbox.jsonl")
    private val gson: Gson = Gson()

    /**
     * In-memory mirror of the on-disk queue. Each entry is a [QueueEntry].
     * Kept in sync with the file at all times.
     */
    private val entries: MutableList<QueueEntry> = mutableListOf()

    @Volatile
    var authenticated: Boolean = false
        private set

    init {
        loadFromDisk()
    }

    // ─── Public API ──────────────────────────────────────────────────────

    /**
     * Called by CloudBridgeClient when connection state changes.
     */
    fun setAuthenticated(authenticated: Boolean) {
        this.authenticated = authenticated
    }

    /**
     * Enqueue a terminal frame and attempt to send it immediately.
     *
     * - `task.progress` frames are **never** persisted (silently dropped).
     * - If [authenticated] is true and the outbox is empty, attempts direct send;
     *   only persists if send fails.
     * - After persisting, enforces the 200-entry cap via drop-oldest.
     */
    fun enqueueAndSend(frame: Frame) {
        // task.progress is never persisted
        if (frame is Frame.TaskProgress) return

        // Fast path: authenticated + empty queue → try direct send
        if (authenticated && entries.isEmpty()) {
            if (sendFrame(frame)) return
            // Send failed; fall through to persist
        }

        // Persist to disk
        val entry = QueueEntry(
            enqueuedAt = System.currentTimeMillis(),
            frameJson = FrameCodec.encode(frame),
        )
        entries.add(entry)

        // Enforce cap: drop oldest entries
        while (entries.size > MAX_SIZE) {
            entries.removeAt(0)
        }

        flushToDisk()
    }

    /**
     * Drain the outbox by sending frames in FIFO order.
     * Stops immediately on the first send failure, preserving remaining entries.
     */
    fun drain() {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val frame = FrameCodec.decode(entry.frameJson)
            if (frame is Frame.ParseError) {
                logger.w(TAG, "Skipping unparseable queued frame: ${entry.frameJson.take(100)}")
                iterator.remove()
                continue
            }
            if (!sendFrame(frame)) {
                // Send failed; stop draining, keep remaining
                break
            }
            iterator.remove()
        }
        flushToDisk()
    }

    /**
     * Returns the current number of entries in the outbox.
     */
    fun size(): Int = entries.size

    /**
     * Returns a snapshot of the current entries (for testing).
     */
    internal fun snapshot(): List<QueueEntry> = entries.toList()

    // ─── Internal ────────────────────────────────────────────────────────

    private fun loadFromDisk() {
        entries.clear()
        if (!outboxFile.exists()) return
        try {
            outboxFile.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                try {
                    val obj = gson.fromJson(line, JsonObject::class.java)
                    val enqueuedAt = obj.get("enqueued_at")?.asLong ?: 0L
                    val frameJson = obj.get("frame")?.asString ?: return@forEach
                    entries.add(QueueEntry(enqueuedAt, frameJson))
                } catch (e: Exception) {
                    logger.w(TAG, "Skipping malformed outbox line: ${line.take(100)}", e)
                }
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to read outbox file", e)
        }
    }

    private fun flushToDisk() {
        try {
            outboxFile.parentFile?.mkdirs()
            outboxFile.writeText(
                entries.joinToString("\n") { entry ->
                    val obj = JsonObject()
                    obj.addProperty("enqueued_at", entry.enqueuedAt)
                    obj.addProperty("frame", entry.frameJson)
                    gson.toJson(obj)
                } + if (entries.isNotEmpty()) "\n" else ""
            )
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write outbox file", e)
        }
    }

    // ─── Data ────────────────────────────────────────────────────────────

    internal data class QueueEntry(
        val enqueuedAt: Long,
        val frameJson: String,
    )
}
