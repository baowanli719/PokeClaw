package io.agents.pokeclaw.bridge.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

/**
 * Encodes [Frame] instances to JSON strings and decodes JSON strings back to [Frame].
 *
 * Design invariants:
 * - [decode] NEVER throws; any failure is wrapped in [Frame.ParseError].
 * - round-trip: decode(encode(f)) == f for all valid (non-Unknown/ParseError) frames.
 * - A single shared [Gson] instance is reused across all operations.
 */
object FrameCodec {

    private val gson: Gson = Gson()

    // ─── Internal envelope ───────────────────────────────────────────────

    internal data class RawFrame(
        val type: String,
        val id: String? = null,
        val ts: Long = 0L,
        val payload: JsonObject = JsonObject(),
    )

    // ─── Encode ──────────────────────────────────────────────────────────

    /**
     * Serializes a [Frame] to its canonical JSON envelope string.
     * [Frame.Unknown] and [Frame.ParseError] are not meant to be serialized
     * back to the wire; calling encode on them produces a best-effort JSON.
     */
    fun encode(frame: Frame): String {
        val envelope = JsonObject()
        envelope.addProperty("type", frame.type)
        if (frame.id != null) {
            envelope.addProperty("id", frame.id)
        }
        envelope.addProperty("ts", frame.ts)

        val payload: JsonObject = when (frame) {
            is Frame.Hello -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.Heartbeat -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.TaskAccepted -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.TaskProgress -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.TaskResult -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.TaskError -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.Pong -> JsonObject() // no payload
            is Frame.HelloAck -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.TaskDispatch -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.TaskCancel -> gson.toJsonTree(frame.payload).asJsonObject
            is Frame.Ping -> JsonObject() // no payload
            is Frame.Unknown -> frame.payload
            is Frame.ParseError -> JsonObject()
        }

        envelope.add("payload", payload)
        return gson.toJson(envelope)
    }

    // ─── Decode ──────────────────────────────────────────────────────────

    /**
     * Parses a JSON text into a [Frame]. Never throws.
     * - JSON parse failure → [Frame.ParseError]
     * - Unknown type → [Frame.Unknown]
     * - Payload field mismatch → [Frame.ParseError]
     */
    fun decode(text: String): Frame {
        return try {
            decodeInternal(text)
        } catch (e: Exception) {
            Frame.ParseError(raw = text, cause = e)
        }
    }

    private fun decodeInternal(text: String): Frame {
        val jsonObj: JsonObject = try {
            gson.fromJson(text, JsonObject::class.java)
                ?: return Frame.ParseError(raw = text, cause = JsonSyntaxException("null root"))
        } catch (e: JsonSyntaxException) {
            return Frame.ParseError(raw = text, cause = e)
        }

        val type = jsonObj.get("type")?.asString
            ?: return Frame.ParseError(
                raw = text,
                cause = IllegalArgumentException("missing 'type' field")
            )

        val id = jsonObj.get("id")?.let {
            if (it.isJsonNull) null else it.asString
        }
        val ts = jsonObj.get("ts")?.asLong ?: 0L
        val payload = jsonObj.getAsJsonObject("payload") ?: JsonObject()

        return when (type) {
            "hello" -> Frame.Hello(
                id = id,
                ts = ts,
                payload = parsePayload(payload, HelloPayload::class.java, text),
            )
            "heartbeat" -> Frame.Heartbeat(
                id = id,
                ts = ts,
                payload = parsePayload(payload, HeartbeatPayload::class.java, text),
            )
            "task.accepted" -> Frame.TaskAccepted(
                id = id,
                ts = ts,
                payload = parsePayload(payload, TaskAcceptedPayload::class.java, text),
            )
            "task.progress" -> Frame.TaskProgress(
                id = id,
                ts = ts,
                payload = parsePayload(payload, TaskProgressPayload::class.java, text),
            )
            "task.result" -> Frame.TaskResult(
                id = id,
                ts = ts,
                payload = parsePayload(payload, TaskResultPayload::class.java, text),
            )
            "task.error" -> Frame.TaskError(
                id = id,
                ts = ts,
                payload = parsePayload(payload, TaskErrorPayload::class.java, text),
            )
            "pong" -> Frame.Pong(id = id, ts = ts)
            "hello.ack" -> Frame.HelloAck(
                id = id,
                ts = ts,
                payload = parsePayload(payload, HelloAckPayload::class.java, text),
            )
            "task.dispatch" -> Frame.TaskDispatch(
                id = id,
                ts = ts,
                payload = parsePayload(payload, TaskDispatchPayload::class.java, text),
            )
            "task.cancel" -> Frame.TaskCancel(
                id = id,
                ts = ts,
                payload = parsePayload(payload, TaskCancelPayload::class.java, text),
            )
            "ping" -> Frame.Ping(id = id, ts = ts)
            else -> Frame.Unknown(type = type, id = id, ts = ts, payload = payload)
        }
    }

    /**
     * Parses a [JsonObject] payload into the specified [clazz].
     * Throws on failure so that the outer try/catch converts it to [Frame.ParseError].
     */
    private fun <T> parsePayload(payload: JsonObject, clazz: Class<T>, rawText: String): T {
        val result = gson.fromJson(payload, clazz)
            ?: throw IllegalArgumentException("payload deserialized to null for ${clazz.simpleName}")
        // Validate required fields for payloads that have non-nullable String fields
        validateRequiredFields(result, rawText)
        @Suppress("UNCHECKED_CAST")
        return normalizePayload(result) as T
    }

    /**
     * Gson bypasses Kotlin default values: a missing JSON field on a non-nullable Kotlin
     * property becomes `null` at runtime, even though the type says it cannot be null.
     * This replaces those surprise-nulls with the intended defaults so downstream code
     * (and equality comparisons in round-trip tests) behave sanely.
     */
    private fun <T> normalizePayload(payload: T): T {
        @Suppress("UNCHECKED_CAST")
        return when (payload) {
            is HelloPayload -> payload.copy(
                os = payload.os ?: "android",
                capabilities = payload.capabilities ?: emptyList(),
            ) as T
            is HelloAckPayload -> payload.copy(
                accepted_capabilities = payload.accepted_capabilities ?: emptyList(),
            ) as T
            is TaskDispatchPayload -> payload.copy(
                params = payload.params ?: JsonObject(),
            ) as T
            else -> payload
        }
    }

    /**
     * Validates that non-nullable String fields in payload data classes are not null.
     * Gson silently sets missing non-nullable Kotlin fields to null, so we check manually.
     */
    private fun <T> validateRequiredFields(payload: T, rawText: String) {
        when (payload) {
            is HelloPayload -> {
                requireField(payload.device_id, "device_id", rawText)
                requireField(payload.app_version, "app_version", rawText)
            }
            is HelloAckPayload -> {
                requireField(payload.server_time, "server_time", rawText)
            }
            is TaskDispatchPayload -> {
                requireField(payload.request_id, "request_id", rawText)
                requireField(payload.kind, "kind", rawText)
            }
            is TaskAcceptedPayload -> {
                requireField(payload.request_id, "request_id", rawText)
            }
            is TaskProgressPayload -> {
                requireField(payload.request_id, "request_id", rawText)
                requireField(payload.step, "step", rawText)
            }
            is TaskResultPayload -> {
                requireField(payload.request_id, "request_id", rawText)
                requireField(payload.kind, "kind", rawText)
            }
            is TaskErrorPayload -> {
                requireField(payload.request_id, "request_id", rawText)
                requireField(payload.code, "code", rawText)
                requireField(payload.message, "message", rawText)
            }
            is TaskCancelPayload -> {
                requireField(payload.request_id, "request_id", rawText)
            }
        }
    }

    @Suppress("SENSELESS_COMPARISON")
    private fun requireField(value: String?, fieldName: String, rawText: String) {
        // Gson can set non-nullable Kotlin String fields to null when the JSON field is missing
        if (value == null) {
            throw IllegalArgumentException(
                "required field '$fieldName' is missing or null in frame: ${rawText.take(200)}"
            )
        }
    }
}
