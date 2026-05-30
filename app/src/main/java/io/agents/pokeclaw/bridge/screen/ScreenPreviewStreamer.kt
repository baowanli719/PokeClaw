package io.agents.pokeclaw.bridge.screen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher
import io.agents.pokeclaw.bridge.internal.Clock
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.ScreenFramePayload
import io.agents.pokeclaw.bridge.protocol.ScreenPreviewStatusPayload
import io.agents.pokeclaw.bridge.protocol.ScreenPreviewStartPayload
import io.agents.pokeclaw.service.ClawAccessibilityService
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ScreenPreviewStreamer"
private const val MIN_INTERVAL_MS = 500L
private const val MAX_INTERVAL_MS = 5_000L
private const val MIN_QUALITY = 20
private const val MAX_QUALITY = 80
private const val MIN_WIDTH = 240
private const val MAX_WIDTH = 1080

internal class ScreenPreviewStreamer(
    private val dispatcher: BridgeDispatcher,
    private val clock: Clock,
    private val logger: BridgeLogger,
    private val sendFrame: (Frame) -> Boolean,
) {

    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "screen-preview-capture").apply { isDaemon = true }
    }

    private var activeConfig: ActiveConfig? = null
    private var scheduledCapture: ScheduledFuture<*>? = null
    private var captureInFlight = false

    fun start(payload: ScreenPreviewStartPayload) {
        val config = ActiveConfig(
            sessionId = payload.session_id,
            intervalMs = payload.interval_ms.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
            jpegQuality = payload.jpeg_quality.coerceIn(MIN_QUALITY, MAX_QUALITY),
            maxWidth = payload.max_width.coerceIn(MIN_WIDTH, MAX_WIDTH),
        )
        logger.i(
            TAG,
            "Starting screen preview: session=${config.sessionId}, interval=${config.intervalMs}, quality=${config.jpegQuality}, maxWidth=${config.maxWidth}",
        )
        scheduledCapture?.cancel(false)
        activeConfig = config
        captureInFlight = false
        sendStatus(config.sessionId, "started", "Preview command received")
        scheduleNext(delayMs = 0L)
    }

    fun stop(sessionId: String? = null) {
        val active = activeConfig
        if (active == null) {
            return
        }
        if (sessionId != null && sessionId != active.sessionId) {
            logger.d(TAG, "Ignoring stop for inactive preview session: $sessionId")
            return
        }
        logger.i(TAG, "Stopping screen preview: session=${active.sessionId}")
        scheduledCapture?.cancel(false)
        scheduledCapture = null
        activeConfig = null
        captureInFlight = false
        sendStatus(active.sessionId, "stopped", "Preview stopped")
    }

    private fun scheduleNext(delayMs: Long) {
        scheduledCapture?.cancel(false)
        scheduledCapture = dispatcher.schedule(delayMs) {
            val config = activeConfig ?: return@schedule
            if (captureInFlight) {
                scheduleNext(config.intervalMs)
                return@schedule
            }
            captureInFlight = true
            captureExecutor.execute { captureAndSend(config) }
        }
    }

    private fun captureAndSend(config: ActiveConfig) {
        var shouldContinue = true
        try {
            val frame = buildFrame(config)
            dispatcher.execute {
                captureInFlight = false
                val active = activeConfig
                if (active == null || active.sessionId != config.sessionId) {
                    return@execute
                }
                if (frame != null) {
                    sendFrame(frame)
                }
                scheduleNext(active.intervalMs)
            }
            shouldContinue = false
        } catch (t: Throwable) {
            logger.w(TAG, "Screen preview capture failed: ${t.message}", t)
            dispatcher.execute {
                val active = activeConfig
                if (active != null && active.sessionId == config.sessionId) {
                    sendStatus(config.sessionId, "error", t.message ?: "Capture failed")
                }
            }
        } finally {
            if (shouldContinue) {
                dispatcher.execute {
                    captureInFlight = false
                    val active = activeConfig
                    if (active != null && active.sessionId == config.sessionId) {
                        scheduleNext(active.intervalMs)
                    }
                }
            }
        }
    }

    private fun buildFrame(config: ActiveConfig): Frame.ScreenFrame? {
        var captured: Bitmap? = null
        var soft: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val capture = captureScreen()
            captured = capture.bitmap
            val source = captured
            if (source == null) {
                sendStatus(config.sessionId, "error", capture.message)
                return null
            }
            soft = source.copy(Bitmap.Config.ARGB_8888, false) ?: source
            val target = scaleIfNeeded(soft, config.maxWidth)
            scaled = if (target === soft) null else target

            val output = ByteArrayOutputStream()
            target.compress(Bitmap.CompressFormat.JPEG, config.jpegQuality, output)
            val imageBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            val frameSeq = config.sequence.incrementAndGet()
            Frame.ScreenFrame(
                id = UUID.randomUUID().toString(),
                ts = clock.nowMillis(),
                payload = ScreenFramePayload(
                    session_id = config.sessionId,
                    seq = frameSeq,
                    captured_at = clock.nowMillis(),
                    width = target.width,
                    height = target.height,
                    image_format = "jpeg",
                    image_base64 = imageBase64,
                ),
            )
        } finally {
            if (scaled != null && !scaled.isRecycled) scaled.recycle()
            if (soft != null && soft !== captured && !soft.isRecycled) soft.recycle()
            if (captured != null && !captured.isRecycled) captured.recycle()
        }
    }

    private fun captureScreen(): CaptureResult {
        val service = ClawAccessibilityService.getConnectedInstance(1000L)
        val accessibilityMessage = if (service == null) {
            "Accessibility service unavailable"
        } else {
            val bitmap = service.takeScreenshot(2500L)
            if (bitmap != null) {
                return CaptureResult(bitmap = bitmap, message = "Captured via Accessibility")
            }
            "Accessibility screenshot returned empty frame"
        }

        val shellCapture = captureWithScreencap()
        if (shellCapture.bitmap != null) {
            logger.i(TAG, "Accessibility screenshot failed; using screencap fallback")
            return shellCapture
        }
        return CaptureResult(
            bitmap = null,
            message = "$accessibilityMessage; ${shellCapture.message}",
        )
    }

    private fun captureWithScreencap(): CaptureResult {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/bin/screencap", "-p"))
            val bytes = process.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            val finished = process.waitFor(2500L, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroy()
                return CaptureResult(bitmap = null, message = "screencap timed out")
            }
            val exitCode = process.exitValue()
            if (exitCode != 0 || bytes.isEmpty()) {
                val stderr = process.errorStream.bufferedReader().use { it.readText() }.trim()
                return CaptureResult(
                    bitmap = null,
                    message = "screencap failed: exit=$exitCode ${stderr.take(120)}".trim(),
                )
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap == null) {
                CaptureResult(bitmap = null, message = "screencap returned undecodable image")
            } else {
                CaptureResult(bitmap = bitmap, message = "Captured via screencap")
            }
        } catch (t: Throwable) {
            CaptureResult(bitmap = null, message = "screencap unavailable: ${t.message}")
        } finally {
            process?.destroy()
        }
    }

    private fun scaleIfNeeded(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) {
            return source
        }
        val ratio = maxWidth.toFloat() / source.width.toFloat()
        val targetHeight = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, targetHeight, true)
    }

    private fun sendStatus(sessionId: String, status: String, message: String? = null) {
        sendFrame(
            Frame.ScreenPreviewStatus(
                id = UUID.randomUUID().toString(),
                ts = clock.nowMillis(),
                payload = ScreenPreviewStatusPayload(
                    session_id = sessionId,
                    status = status,
                    message = message,
                ),
            ),
        )
    }

    private data class ActiveConfig(
        val sessionId: String,
        val intervalMs: Long,
        val jpegQuality: Int,
        val maxWidth: Int,
        val sequence: AtomicLong = AtomicLong(0L),
    )

    private data class CaptureResult(
        val bitmap: Bitmap?,
        val message: String,
    )
}
