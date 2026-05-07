package io.agents.pokeclaw.bridge.protocol

import com.google.gson.JsonObject

sealed class Frame {
    abstract val type: String
    abstract val id: String?
    abstract val ts: Long

    // ---------- Outbound (device → cloud) ----------

    data class Hello(
        override val id: String?,
        override val ts: Long,
        val payload: HelloPayload,
    ) : Frame() { override val type: String = "hello" }

    data class Heartbeat(
        override val id: String?,
        override val ts: Long,
        val payload: HeartbeatPayload,
    ) : Frame() { override val type: String = "heartbeat" }

    data class TaskAccepted(
        override val id: String?,
        override val ts: Long,
        val payload: TaskAcceptedPayload,
    ) : Frame() { override val type: String = "task.accepted" }

    data class TaskProgress(
        override val id: String?,
        override val ts: Long,
        val payload: TaskProgressPayload,
    ) : Frame() { override val type: String = "task.progress" }

    data class TaskResult(
        override val id: String?,
        override val ts: Long,
        val payload: TaskResultPayload,
    ) : Frame() { override val type: String = "task.result" }

    data class TaskError(
        override val id: String?,
        override val ts: Long,
        val payload: TaskErrorPayload,
    ) : Frame() { override val type: String = "task.error" }

    data class Pong(
        override val id: String?,
        override val ts: Long,
    ) : Frame() { override val type: String = "pong" }

    // ---------- Inbound (cloud → device) ----------

    data class HelloAck(
        override val id: String?,
        override val ts: Long,
        val payload: HelloAckPayload,
    ) : Frame() { override val type: String = "hello.ack" }

    data class TaskDispatch(
        override val id: String?,
        override val ts: Long,
        val payload: TaskDispatchPayload,
    ) : Frame() { override val type: String = "task.dispatch" }

    data class TaskCancel(
        override val id: String?,
        override val ts: Long,
        val payload: TaskCancelPayload,
    ) : Frame() { override val type: String = "task.cancel" }

    data class Ping(
        override val id: String?,
        override val ts: Long,
    ) : Frame() { override val type: String = "ping" }

    // ---------- Fallbacks (never serialized back to the wire) ----------

    data class Unknown(
        override val type: String,
        override val id: String?,
        override val ts: Long,
        val payload: JsonObject,
    ) : Frame()

    data class ParseError(
        val raw: String,
        val cause: Throwable,
    ) : Frame() {
        override val type: String = "<parse-error>"
        override val id: String? = null
        override val ts: Long = 0L
    }
}
