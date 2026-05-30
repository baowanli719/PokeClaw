package io.agents.pokeclaw.bridge.protocol

import com.google.gson.JsonObject

data class HelloPayload(
    val device_id: String,
    val app_version: String,
    val os: String = "android",
    val os_version: String? = null,
    val capabilities: List<String> = emptyList(),
    val battery: Double? = null,
    val charging: Boolean? = null,
)

data class HelloAckPayload(
    val server_time: String,
    val heartbeat_sec: Int,
    val accepted_capabilities: List<String> = emptyList(),
)

data class HeartbeatPayload(
    val busy: Boolean,
    val current_request_id: String? = null,
)

data class TaskDispatchPayload(
    val request_id: String,
    val kind: String,
    val params: JsonObject = JsonObject(),
    val deadline_ts: Long? = null,
)

data class TaskAcceptedPayload(val request_id: String)

data class TaskProgressPayload(
    val request_id: String,
    val step: String,
    val ratio: Double? = null,
)

data class TaskResultPayload(
    val request_id: String,
    val kind: String,
    val result: JsonObject,
)

data class TaskErrorPayload(
    val request_id: String,
    val code: String,
    val message: String,
    val retryable: Boolean = false,
)

data class TaskCancelPayload(val request_id: String)

data class ScreenPreviewStartPayload(
    val session_id: String,
    val interval_ms: Long = 1000L,
    val jpeg_quality: Int = 45,
    val max_width: Int = 720,
)

data class ScreenPreviewStopPayload(
    val session_id: String? = null,
)

data class ScreenFramePayload(
    val session_id: String,
    val seq: Long,
    val captured_at: Long,
    val width: Int,
    val height: Int,
    val image_format: String = "jpeg",
    val image_base64: String,
)

data class ScreenPreviewStatusPayload(
    val session_id: String,
    val status: String,
    val message: String? = null,
)
