package io.agents.pokeclaw.bridge.protocol

import com.google.gson.JsonObject
import net.jqwik.api.*
import net.jqwik.api.Combinators.combine
import org.assertj.core.api.Assertions.assertThat

/**
 * Property-based tests for [FrameCodec].
 *
 * **Validates: Requirements 8.1, 8.3, 8.4, 8.5, 9.5**
 */
class FrameCodecPropertyTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Property 1: Frame round-trip
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * For any valid Frame instance (excluding Unknown/ParseError),
     * decode(encode(f)) == f.
     *
     * **Validates: Requirements 8.1, 8.5**
     */
    @Property(tries = 200)
    fun `round-trip - decode of encode equals original frame`(
        @ForAll("validFrames") frame: Frame,
    ) {
        val encoded = FrameCodec.encode(frame)
        val decoded = FrameCodec.decode(encoded)
        assertThat(decoded).isEqualTo(frame)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Property 2: Codec never throws
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * For any arbitrary string (including non-ASCII, control characters,
     * null bytes, and strings up to 10k characters), FrameCodec.decode(s)
     * never throws an exception. The result is always a Frame subclass
     * (concrete type, Unknown, or ParseError).
     *
     * **Validates: Requirements 8.3, 8.4, 9.5**
     */
    @Property(tries = 500)
    fun `codec never throws - decode always returns a Frame`(
        @ForAll("arbitraryStrings") input: String,
    ) {
        val result = FrameCodec.decode(input)
        assertThat(result).isInstanceOf(Frame::class.java)
        // Result must be one of: a concrete Frame subclass, Unknown, or ParseError
        assertThat(result).isNotNull
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Generators
    // ═══════════════════════════════════════════════════════════════════════

    @Provide
    fun validFrames(): Arbitrary<Frame> {
        return Arbitraries.oneOf(
            helloFrames(),
            heartbeatFrames(),
            taskAcceptedFrames(),
            taskProgressFrames(),
            taskResultFrames(),
            taskErrorFrames(),
            pongFrames(),
            helloAckFrames(),
            taskDispatchFrames(),
            taskCancelFrames(),
            pingFrames(),
        )
    }

    @Provide
    fun arbitraryStrings(): Arbitrary<String> {
        return Arbitraries.oneOf(
            // Normal ASCII strings
            Arbitraries.strings().ofMinLength(0).ofMaxLength(200),
            // Strings with control characters and null bytes
            Arbitraries.strings()
                .withChars('\u0000', '\u0001', '\n', '\r', '\t', '\u001B')
                .ofMinLength(1).ofMaxLength(500),
            // Non-ASCII / Unicode strings
            Arbitraries.strings()
                .withCharRange('\u0080', '\uFFFF')
                .ofMinLength(1).ofMaxLength(1000),
            // Large strings up to 10k characters
            Arbitraries.strings().ofMinLength(5000).ofMaxLength(10000),
            // JSON-like but malformed
            Arbitraries.of(
                "",
                "null",
                "{}",
                "[]",
                "{\"type\":}",
                "{\"type\":\"unknown\"}",
                "{\"type\":\"task.dispatch\",\"ts\":0,\"payload\":{}}",
                "{\"type\":123,\"ts\":0}",
                "\u0000\u0000\u0000",
                "not json at all",
                "{\"type\":\"hello\",\"ts\":0,\"payload\":null}",
            ),
        )
    }

    // ─── Individual frame generators ─────────────────────────────────────

    private fun alphaStrings(): Arbitrary<String> =
        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(50)

    private fun optionalIds(): Arbitrary<String?> =
        alphaStrings().injectNull(0.3)

    @Suppress("UNCHECKED_CAST")
    private fun optionalBools(): Arbitrary<Boolean?> =
        Arbitraries.of(true, false).injectNull(0.3) as Arbitrary<Boolean?>

    private fun timestamps(): Arbitrary<Long> =
        Arbitraries.longs().between(0, Long.MAX_VALUE / 2)

    private fun helloFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(), // device_id
            alphaStrings(), // app_version
            alphaStrings().injectNull(0.3), // os_version
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                .list().ofMaxSize(5), // capabilities
            Arbitraries.doubles().between(0.0, 1.0).injectNull(0.3), // battery
            optionalBools(), // charging
        ).`as` { id: String?, ts: Long, deviceId: String, appVersion: String, osVersion: String?, caps: List<String>, battery: Double?, charging: Boolean? ->
            Frame.Hello(
                id = id,
                ts = ts,
                payload = HelloPayload(
                    device_id = deviceId,
                    app_version = appVersion,
                    os = "android",
                    os_version = osVersion,
                    capabilities = caps,
                    battery = battery,
                    charging = charging,
                ),
            )
        }
    }

    private fun heartbeatFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            Arbitraries.of(true, false),
            alphaStrings().injectNull(0.5),
        ).`as` { id: String?, ts: Long, busy: Boolean, requestId: String? ->
            Frame.Heartbeat(
                id = id,
                ts = ts,
                payload = HeartbeatPayload(busy = busy, current_request_id = requestId),
            )
        }
    }

    private fun taskAcceptedFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(),
        ).`as` { id: String?, ts: Long, requestId: String ->
            Frame.TaskAccepted(
                id = id,
                ts = ts,
                payload = TaskAcceptedPayload(request_id = requestId),
            )
        }
    }

    private fun taskProgressFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(), // request_id
            alphaStrings(), // step
            Arbitraries.doubles().between(0.0, 1.0).injectNull(0.3),
        ).`as` { id: String?, ts: Long, requestId: String, step: String, ratio: Double? ->
            Frame.TaskProgress(
                id = id,
                ts = ts,
                payload = TaskProgressPayload(
                    request_id = requestId,
                    step = step,
                    ratio = ratio,
                ),
            )
        }
    }

    private fun taskResultFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(), // request_id
            alphaStrings(), // kind
        ).`as` { id: String?, ts: Long, requestId: String, kind: String ->
            val result = JsonObject().apply {
                addProperty("status", "ok")
                addProperty("value", kind)
            }
            Frame.TaskResult(
                id = id,
                ts = ts,
                payload = TaskResultPayload(
                    request_id = requestId,
                    kind = kind,
                    result = result,
                ),
            )
        }
    }

    private fun taskErrorFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(), // request_id
            Arbitraries.of("internal", "deadline_exceeded", "cancelled", "app_not_installed"),
            alphaStrings(), // message
            Arbitraries.of(true, false),
        ).`as` { id: String?, ts: Long, requestId: String, code: String, message: String, retryable: Boolean ->
            Frame.TaskError(
                id = id,
                ts = ts,
                payload = TaskErrorPayload(
                    request_id = requestId,
                    code = code,
                    message = message,
                    retryable = retryable,
                ),
            )
        }
    }

    private fun pongFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
        ).`as` { id: String?, ts: Long ->
            Frame.Pong(id = id, ts = ts)
        }
    }

    private fun helloAckFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(), // server_time
            Arbitraries.integers().between(5, 300),
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                .list().ofMaxSize(5),
        ).`as` { id: String?, ts: Long, serverTime: String, heartbeatSec: Int, acceptedCaps: List<String> ->
            Frame.HelloAck(
                id = id,
                ts = ts,
                payload = HelloAckPayload(
                    server_time = serverTime,
                    heartbeat_sec = heartbeatSec,
                    accepted_capabilities = acceptedCaps,
                ),
            )
        }
    }

    private fun taskDispatchFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(), // request_id
            alphaStrings(), // kind
            timestamps().injectNull(0.3), // deadline_ts
        ).`as` { id: String?, ts: Long, requestId: String, kind: String, deadlineTs: Long? ->
            val params = JsonObject().apply {
                addProperty("account", "main")
            }
            Frame.TaskDispatch(
                id = id,
                ts = ts,
                payload = TaskDispatchPayload(
                    request_id = requestId,
                    kind = kind,
                    params = params,
                    deadline_ts = deadlineTs,
                ),
            )
        }
    }

    private fun taskCancelFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
            alphaStrings(),
        ).`as` { id: String?, ts: Long, requestId: String ->
            Frame.TaskCancel(
                id = id,
                ts = ts,
                payload = TaskCancelPayload(request_id = requestId),
            )
        }
    }

    private fun pingFrames(): Arbitrary<Frame> {
        return combine(
            optionalIds(),
            timestamps(),
        ).`as` { id: String?, ts: Long ->
            Frame.Ping(id = id, ts = ts)
        }
    }
}
