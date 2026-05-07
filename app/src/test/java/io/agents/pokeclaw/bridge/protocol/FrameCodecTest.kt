package io.agents.pokeclaw.bridge.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Example-based unit tests for [FrameCodec].
 *
 * Validates: Requirements 8.1, 8.2
 */
class FrameCodecTest {

    private val gson = Gson()

    // ═══════════════════════════════════════════════════════════════════════
    // Outbound Frame serialization examples
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `encode Hello produces correct JSON structure`() {
        val frame = Frame.Hello(
            id = null,
            ts = 1000L,
            payload = HelloPayload(
                device_id = "dev-001",
                app_version = "1.2.3",
                os = "android",
                os_version = "14",
                capabilities = listOf("ths.sync_holdings"),
                battery = 0.85,
                charging = true,
            ),
        )
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("hello")
        assertThat(obj.get("ts").asLong).isEqualTo(1000L)
        assertThat(obj.has("id")).isFalse()
        val payload = obj.getAsJsonObject("payload")
        assertThat(payload.get("device_id").asString).isEqualTo("dev-001")
        assertThat(payload.get("app_version").asString).isEqualTo("1.2.3")
        assertThat(payload.get("os").asString).isEqualTo("android")
        assertThat(payload.get("os_version").asString).isEqualTo("14")
        assertThat(payload.getAsJsonArray("capabilities").size()).isEqualTo(1)
        assertThat(payload.get("battery").asDouble).isEqualTo(0.85)
        assertThat(payload.get("charging").asBoolean).isTrue()
    }

    @Test
    fun `encode Heartbeat produces correct JSON structure`() {
        val frame = Frame.Heartbeat(
            id = "hb-1",
            ts = 2000L,
            payload = HeartbeatPayload(busy = true, current_request_id = "req-42"),
        )
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("heartbeat")
        assertThat(obj.get("id").asString).isEqualTo("hb-1")
        assertThat(obj.get("ts").asLong).isEqualTo(2000L)
        val payload = obj.getAsJsonObject("payload")
        assertThat(payload.get("busy").asBoolean).isTrue()
        assertThat(payload.get("current_request_id").asString).isEqualTo("req-42")
    }

    @Test
    fun `encode TaskAccepted produces correct JSON structure`() {
        val frame = Frame.TaskAccepted(
            id = null,
            ts = 3000L,
            payload = TaskAcceptedPayload(request_id = "req-100"),
        )
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("task.accepted")
        assertThat(obj.get("ts").asLong).isEqualTo(3000L)
        val payload = obj.getAsJsonObject("payload")
        assertThat(payload.get("request_id").asString).isEqualTo("req-100")
    }

    @Test
    fun `encode TaskProgress produces correct JSON structure`() {
        val frame = Frame.TaskProgress(
            id = null,
            ts = 4000L,
            payload = TaskProgressPayload(
                request_id = "req-100",
                step = "opening_app",
                ratio = 0.5,
            ),
        )
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("task.progress")
        assertThat(obj.get("ts").asLong).isEqualTo(4000L)
        val payload = obj.getAsJsonObject("payload")
        assertThat(payload.get("request_id").asString).isEqualTo("req-100")
        assertThat(payload.get("step").asString).isEqualTo("opening_app")
        assertThat(payload.get("ratio").asDouble).isEqualTo(0.5)
    }

    @Test
    fun `encode TaskResult produces correct JSON structure`() {
        val resultObj = JsonObject().apply { addProperty("captured_at", "2026-05-06T10:15:22+08:00") }
        val frame = Frame.TaskResult(
            id = null,
            ts = 5000L,
            payload = TaskResultPayload(
                request_id = "req-100",
                kind = "ths.sync_holdings",
                result = resultObj,
            ),
        )
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("task.result")
        assertThat(obj.get("ts").asLong).isEqualTo(5000L)
        val payload = obj.getAsJsonObject("payload")
        assertThat(payload.get("request_id").asString).isEqualTo("req-100")
        assertThat(payload.get("kind").asString).isEqualTo("ths.sync_holdings")
        assertThat(payload.getAsJsonObject("result").get("captured_at").asString)
            .isEqualTo("2026-05-06T10:15:22+08:00")
    }

    @Test
    fun `encode TaskError produces correct JSON structure`() {
        val frame = Frame.TaskError(
            id = null,
            ts = 6000L,
            payload = TaskErrorPayload(
                request_id = "req-100",
                code = "deadline_exceeded",
                message = "Task timed out",
                retryable = false,
            ),
        )
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("task.error")
        assertThat(obj.get("ts").asLong).isEqualTo(6000L)
        val payload = obj.getAsJsonObject("payload")
        assertThat(payload.get("request_id").asString).isEqualTo("req-100")
        assertThat(payload.get("code").asString).isEqualTo("deadline_exceeded")
        assertThat(payload.get("message").asString).isEqualTo("Task timed out")
        assertThat(payload.get("retryable").asBoolean).isFalse()
    }

    @Test
    fun `encode Pong produces correct JSON structure`() {
        val frame = Frame.Pong(id = "ping-99", ts = 7000L)
        val json = FrameCodec.encode(frame)
        val obj = gson.fromJson(json, JsonObject::class.java)

        assertThat(obj.get("type").asString).isEqualTo("pong")
        assertThat(obj.get("id").asString).isEqualTo("ping-99")
        assertThat(obj.get("ts").asLong).isEqualTo(7000L)
        assertThat(obj.getAsJsonObject("payload")).isNotNull
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Inbound Frame deserialization examples
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `decode valid hello_ack returns Frame_HelloAck`() {
        val json = """{"type":"hello.ack","ts":100,"payload":{"server_time":"2026-01-01T00:00:00Z","heartbeat_sec":30,"accepted_capabilities":["ths.sync_holdings"]}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.HelloAck::class.java)
        val ack = frame as Frame.HelloAck
        assertThat(ack.ts).isEqualTo(100L)
        assertThat(ack.payload.server_time).isEqualTo("2026-01-01T00:00:00Z")
        assertThat(ack.payload.heartbeat_sec).isEqualTo(30)
        assertThat(ack.payload.accepted_capabilities).containsExactly("ths.sync_holdings")
    }

    @Test
    fun `decode valid task_dispatch returns Frame_TaskDispatch`() {
        val json = """{"type":"task.dispatch","ts":123,"payload":{"request_id":"r1","kind":"ths.sync_holdings"}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.TaskDispatch::class.java)
        val dispatch = frame as Frame.TaskDispatch
        assertThat(dispatch.ts).isEqualTo(123L)
        assertThat(dispatch.payload.request_id).isEqualTo("r1")
        assertThat(dispatch.payload.kind).isEqualTo("ths.sync_holdings")
        assertThat(dispatch.payload.params).isNotNull
        assertThat(dispatch.payload.deadline_ts).isNull()
    }

    @Test
    fun `decode task_dispatch with deadline and params`() {
        val json = """{"type":"task.dispatch","ts":200,"payload":{"request_id":"r2","kind":"ths.sync_holdings","params":{"account":"main"},"deadline_ts":9999}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.TaskDispatch::class.java)
        val dispatch = frame as Frame.TaskDispatch
        assertThat(dispatch.payload.deadline_ts).isEqualTo(9999L)
        assertThat(dispatch.payload.params.get("account").asString).isEqualTo("main")
    }

    @Test
    fun `decode valid task_cancel returns Frame_TaskCancel`() {
        val json = """{"type":"task.cancel","ts":300,"payload":{"request_id":"r1"}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.TaskCancel::class.java)
        val cancel = frame as Frame.TaskCancel
        assertThat(cancel.payload.request_id).isEqualTo("r1")
    }

    @Test
    fun `decode valid ping returns Frame_Ping`() {
        val json = """{"type":"ping","id":"p-1","ts":400,"payload":{}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.Ping::class.java)
        val ping = frame as Frame.Ping
        assertThat(ping.id).isEqualTo("p-1")
        assertThat(ping.ts).isEqualTo(400L)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Unknown type handling
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `decode unknown type returns Frame_Unknown`() {
        val json = """{"type":"future.feature","id":"x","ts":500,"payload":{"foo":"bar"}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.Unknown::class.java)
        val unknown = frame as Frame.Unknown
        assertThat(unknown.type).isEqualTo("future.feature")
        assertThat(unknown.id).isEqualTo("x")
        assertThat(unknown.ts).isEqualTo(500L)
        assertThat(unknown.payload.get("foo").asString).isEqualTo("bar")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Parse error handling
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `decode invalid JSON returns Frame_ParseError`() {
        val frame = FrameCodec.decode("not valid json {{{")

        assertThat(frame).isInstanceOf(Frame.ParseError::class.java)
        val error = frame as Frame.ParseError
        assertThat(error.raw).isEqualTo("not valid json {{{")
        assertThat(error.cause).isNotNull
    }

    @Test
    fun `decode empty string returns Frame_ParseError`() {
        val frame = FrameCodec.decode("")

        assertThat(frame).isInstanceOf(Frame.ParseError::class.java)
    }

    @Test
    fun `decode JSON missing type field returns Frame_ParseError`() {
        val json = """{"ts":100,"payload":{}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.ParseError::class.java)
    }

    @Test
    fun `decode task_dispatch with missing required payload field returns Frame_ParseError`() {
        // missing request_id which is required
        val json = """{"type":"task.dispatch","ts":100,"payload":{"kind":"ths.sync_holdings"}}"""
        val frame = FrameCodec.decode(json)

        assertThat(frame).isInstanceOf(Frame.ParseError::class.java)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Round-trip tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `round-trip Hello`() {
        val frame = Frame.Hello(
            id = "h1",
            ts = 1000L,
            payload = HelloPayload(
                device_id = "dev-001",
                app_version = "2.0.0",
                os = "android",
                os_version = "13",
                capabilities = listOf("ths.sync_holdings"),
                battery = 0.5,
                charging = false,
            ),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip Heartbeat`() {
        val frame = Frame.Heartbeat(
            id = null,
            ts = 2000L,
            payload = HeartbeatPayload(busy = false, current_request_id = null),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip TaskAccepted`() {
        val frame = Frame.TaskAccepted(
            id = null,
            ts = 3000L,
            payload = TaskAcceptedPayload(request_id = "req-abc"),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip TaskProgress`() {
        val frame = Frame.TaskProgress(
            id = null,
            ts = 4000L,
            payload = TaskProgressPayload(
                request_id = "req-abc",
                step = "navigating",
                ratio = 0.75,
            ),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip TaskResult`() {
        val resultObj = JsonObject().apply {
            addProperty("kind", "ths.sync_holdings")
            addProperty("captured_at", "2026-05-06T10:15:22+08:00")
        }
        val frame = Frame.TaskResult(
            id = null,
            ts = 5000L,
            payload = TaskResultPayload(
                request_id = "req-abc",
                kind = "ths.sync_holdings",
                result = resultObj,
            ),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip TaskError`() {
        val frame = Frame.TaskError(
            id = null,
            ts = 6000L,
            payload = TaskErrorPayload(
                request_id = "req-abc",
                code = "internal",
                message = "something went wrong",
                retryable = true,
            ),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip Pong`() {
        val frame = Frame.Pong(id = "ping-1", ts = 7000L)
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip HelloAck`() {
        val frame = Frame.HelloAck(
            id = null,
            ts = 8000L,
            payload = HelloAckPayload(
                server_time = "2026-01-01T00:00:00Z",
                heartbeat_sec = 45,
                accepted_capabilities = listOf("ths.sync_holdings"),
            ),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip TaskDispatch`() {
        val params = JsonObject().apply { addProperty("account", "main") }
        val frame = Frame.TaskDispatch(
            id = "d-1",
            ts = 9000L,
            payload = TaskDispatchPayload(
                request_id = "req-xyz",
                kind = "ths.sync_holdings",
                params = params,
                deadline_ts = 99999L,
            ),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip TaskCancel`() {
        val frame = Frame.TaskCancel(
            id = null,
            ts = 10000L,
            payload = TaskCancelPayload(request_id = "req-xyz"),
        )
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }

    @Test
    fun `round-trip Ping`() {
        val frame = Frame.Ping(id = "p-42", ts = 11000L)
        val decoded = FrameCodec.decode(FrameCodec.encode(frame))
        assertThat(decoded).isEqualTo(frame)
    }
}
