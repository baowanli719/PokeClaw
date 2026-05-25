// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.bridge.protocol.Frame
import io.agents.pokeclaw.bridge.protocol.FrameCodec
import io.agents.pokeclaw.bridge.protocol.HelloPayload
import io.agents.pokeclaw.cloudbridge.CloudBridgeCapabilities
import io.agents.pokeclaw.cloudbridge.CloudBridgeDeviceId
import io.agents.pokeclaw.cloudbridge.CloudBridgeHolder
import io.agents.pokeclaw.cloudbridge.KVUtilsConfigSource
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Cloud Bridge config screen for the cloud service URL and device token.
 */
class CloudBridgeConfigActivity : BaseActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etDeviceToken: EditText
    private lateinit var btnConnectTest: KButton
    private lateinit var tvConnectionLog: TextView
    private lateinit var tvConnectionLogLabel: TextView
    private lateinit var cardConnectionLog: View
    private var isTokenVisible = false
    private var isTestingConnection = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_channel_config)
        initToolbar()
        initViews()
    }

    private fun initToolbar() {
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle("Cloud Bridge")
            showBackButton(true) { finish() }
        }
    }

    private fun initViews() {
        findViewById<TextView>(R.id.tvTip).text =
            "Connect this phone to your PokeClaw Cloud Bridge service for remote task dispatch."

        findViewById<TextView>(R.id.tvLabel1).text = "Server URL"
        findViewById<TextView>(R.id.tvLabel2).text = "Device Token"

        etServerUrl = findViewById(R.id.etInput1)
        etDeviceToken = findViewById(R.id.etInput2)

        etServerUrl.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        etServerUrl.hint = "wss://example.com/ws/device"
        etServerUrl.setText(KVUtilsConfigSource.getConfiguredUrl())

        etDeviceToken.hint = "Bearer token from DEVICE_TOKENS"
        etDeviceToken.transformationMethod = PasswordTransformationMethod.getInstance()
        etDeviceToken.setText(KVUtilsConfigSource.getConfiguredDeviceToken())

        val ivTogglePassword = findViewById<ImageView>(R.id.ivTogglePassword)
        ivTogglePassword.setOnClickListener {
            isTokenVisible = !isTokenVisible
            if (isTokenVisible) {
                etDeviceToken.transformationMethod = HideReturnsTransformationMethod.getInstance()
                ivTogglePassword.setImageResource(R.drawable.ic_visibility_on)
            } else {
                etDeviceToken.transformationMethod = PasswordTransformationMethod.getInstance()
                ivTogglePassword.setImageResource(R.drawable.ic_visibility_off)
            }
            etDeviceToken.setSelection(etDeviceToken.text.length)
        }

        findViewById<View>(R.id.layoutLanHint).visibility = View.GONE
        initConnectionTestViews()

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            save()
        }
    }

    private fun initConnectionTestViews() {
        btnConnectTest = findViewById(R.id.btnConnectTest)
        tvConnectionLogLabel = findViewById(R.id.tvConnectionLogLabel)
        tvConnectionLog = findViewById(R.id.tvConnectionLog)
        cardConnectionLog = findViewById(R.id.cardConnectionLog)

        btnConnectTest.visibility = View.VISIBLE
        tvConnectionLog.typeface = Typeface.MONOSPACE
        btnConnectTest.text = "Connect / Test"
        btnConnectTest.setOnClickListener {
            testConnection()
        }
    }

    private fun save() {
        val serverUrl = normalizeServerUrl(etServerUrl.text.toString())
        val deviceToken = etDeviceToken.text.toString().trim()

        if (serverUrl == null) {
            Toast.makeText(this, "Enter a valid ws:// or wss:// server URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (deviceToken.isEmpty()) {
            Toast.makeText(this, "Enter the device token", Toast.LENGTH_SHORT).show()
            return
        }

        KVUtils.putString(KVUtilsConfigSource.KEY_CLOUD_BRIDGE_URL, serverUrl)
        KVUtils.putString(KVUtilsConfigSource.KEY_CLOUD_BRIDGE_DEVICE_TOKEN, deviceToken)

        val bridgeStarted = applyCloudBridgeConfig()
        if (!bridgeStarted) {
            Toast.makeText(
                this,
                "Cloud Bridge saved, but notification permission is required to keep it running",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        setResult(RESULT_OK)
        Toast.makeText(this, "Cloud Bridge config saved and connected", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        if (isTestingConnection) return

        val serverUrl = normalizeServerUrl(etServerUrl.text.toString())
        val deviceToken = etDeviceToken.text.toString().trim()

        if (serverUrl == null) {
            Toast.makeText(this, "Enter a valid ws:// or wss:// server URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (deviceToken.isEmpty()) {
            Toast.makeText(this, "Enter the device token", Toast.LENGTH_SHORT).show()
            return
        }

        showConnectionLog()
        tvConnectionLog.text = ""
        setTestingState(true)
        appendConnectionLog("Testing ${serverUrl.maskTokenSafe()}")
        appendConnectionLog("Token ${deviceToken.maskedSecret()}")

        lifecycleScope.launch {
            val result = runConnectionTest(serverUrl, deviceToken)
            appendConnectionLog(result.message)
            setTestingState(false)
            Toast.makeText(
                this@CloudBridgeConfigActivity,
                if (result.success) "Cloud Bridge connected" else "Cloud Bridge test failed",
                Toast.LENGTH_SHORT,
            ).show()
            if (result.success) {
                KVUtils.putString(KVUtilsConfigSource.KEY_CLOUD_BRIDGE_URL, serverUrl)
                KVUtils.putString(KVUtilsConfigSource.KEY_CLOUD_BRIDGE_DEVICE_TOKEN, deviceToken)
                appendConnectionLog("Saved config")
                if (applyCloudBridgeConfig()) {
                    appendConnectionLog("Persistent bridge started/reconfigured")
                } else {
                    appendConnectionLog("Persistent bridge not started: notification permission required")
                }
                setResult(RESULT_OK)
            }
        }
    }

    private fun applyCloudBridgeConfig(): Boolean {
        return if (ForegroundService.isRunning()) {
            CloudBridgeHolder.client?.reconfigure()
            ForegroundService.showCloudBridgeStatus(this)
            true
        } else {
            ForegroundService.start(
                this,
                title = "PokeClaw · Cloud Bridge",
                text = "Connected to Cloud Bridge",
            )
        }
    }

    private suspend fun runConnectionTest(
        serverUrl: String,
        deviceToken: String,
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
        val deferred = CompletableDeferred<ConnectionTestResult>()
        var webSocket: WebSocket? = null

        val request = Request.Builder()
            .url(buildBridgeTestUrl(serverUrl))
            .header("Authorization", "Bearer $deviceToken")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                appendConnectionLog("WebSocket opened: HTTP ${response.code}")
                val hello = Frame.Hello(
                    id = null,
                    ts = System.currentTimeMillis(),
                    payload = HelloPayload(
                        device_id = CloudBridgeDeviceId.getTest(this@CloudBridgeConfigActivity),
                        app_version = BuildConfig.VERSION_NAME,
                        os = "android",
                        os_version = Build.VERSION.RELEASE ?: "unknown",
                        capabilities = CloudBridgeCapabilities.SUPPORTED_KINDS,
                    ),
                )
                val sent = webSocket.send(FrameCodec.encode(hello))
                appendConnectionLog(if (sent) "hello sent" else "hello send failed")
                if (!sent) {
                    deferred.complete(ConnectionTestResult(false, "Send failed: WebSocket rejected hello frame"))
                    webSocket.close(1001, "hello_send_failed")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val frame = FrameCodec.decode(text)) {
                    is Frame.HelloAck -> {
                        appendConnectionLog(
                            "hello.ack received: heartbeat=${frame.payload.heartbeat_sec}s, accepted=${frame.payload.accepted_capabilities}"
                        )
                        deferred.complete(ConnectionTestResult(true, "Connection test passed"))
                        webSocket.close(1000, "test_done")
                    }
                    is Frame.ParseError -> {
                        appendConnectionLog("Frame parse error: ${frame.cause.message}")
                        deferred.complete(ConnectionTestResult(false, "Server returned an invalid frame"))
                        webSocket.close(1002, "invalid_frame")
                    }
                    else -> {
                        appendConnectionLog("Frame received: ${frame.type}")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                appendConnectionLog("WebSocket closed: code=$code reason=$reason")
                if (!deferred.isCompleted && code != 1000) {
                    deferred.complete(ConnectionTestResult(false, "Connection closed before hello.ack: code=$code reason=$reason"))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val httpStatus = response?.let { " HTTP ${it.code}" }.orEmpty()
                appendConnectionLog("WebSocket failure:$httpStatus ${t.message ?: t.javaClass.simpleName}")
                deferred.complete(
                    ConnectionTestResult(
                        success = false,
                        message = "Connection test failed:$httpStatus ${t.message ?: t.javaClass.simpleName}",
                    )
                )
            }
        }

        try {
            appendConnectionLog("Opening WebSocket")
            webSocket = client.newWebSocket(request, listener)
            withTimeout(TEST_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (_: TimeoutCancellationException) {
            appendConnectionLog("Timed out after ${TEST_TIMEOUT_MS / 1000}s waiting for hello.ack")
            ConnectionTestResult(false, "Connection test timed out")
        } catch (t: Throwable) {
            appendConnectionLog("Test exception: ${t.message ?: t.javaClass.simpleName}")
            ConnectionTestResult(false, "Connection test failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            webSocket?.cancel()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun buildBridgeTestUrl(serverUrl: String): String {
        val separator = if (serverUrl.contains('?')) '&' else '?'
        return "${serverUrl}${separator}device_id=${Uri.encode(CloudBridgeDeviceId.getTest(this))}&app_version=${Uri.encode(BuildConfig.VERSION_NAME)}"
    }

    private fun showConnectionLog() {
        tvConnectionLogLabel.visibility = View.VISIBLE
        cardConnectionLog.visibility = View.VISIBLE
    }

    private fun appendConnectionLog(line: String) {
        runOnUiThread {
            val prefix = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            val nextLine = "$prefix  $line"
            tvConnectionLog.text = if (tvConnectionLog.text.isNullOrEmpty()) {
                nextLine
            } else {
                "${tvConnectionLog.text}\n$nextLine"
            }
        }
    }

    private fun setTestingState(testing: Boolean) {
        isTestingConnection = testing
        btnConnectTest.isEnabled = !testing
        btnConnectTest.alpha = if (testing) 0.65f else 1f
        btnConnectTest.text = if (testing) "Testing..." else "Connect / Test"
    }

    private fun normalizeServerUrl(raw: String): String? {
        var url = raw.trim()
        if (url.isEmpty()) return null
        url = when {
            url.startsWith("https://", ignoreCase = true) -> "wss://" + url.substringAfter("://")
            url.startsWith("http://", ignoreCase = true) -> "ws://" + url.substringAfter("://")
            url.startsWith("ws://", ignoreCase = true) || url.startsWith("wss://", ignoreCase = true) -> url
            else -> "wss://$url"
        }.trimEnd('/')

        val lower = url.lowercase()
        if (!lower.startsWith("ws://") && !lower.startsWith("wss://")) return null

        val afterScheme = url.substringAfter("://")
        if (afterScheme.isBlank()) return null
        if (!afterScheme.contains("/")) {
            url += "/ws/device"
        }
        return url
    }

    private fun String.maskedSecret(): String =
        if (isBlank()) "<empty>" else "***${takeLast(4)}"

    private fun String.maskTokenSafe(): String =
        replace(Regex("([?&]token=)[^&]+", RegexOption.IGNORE_CASE)) {
            "${it.groupValues[1]}***"
        }

    class Contract : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, CloudBridgeConfigActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == RESULT_OK
    }

    private data class ConnectionTestResult(
        val success: Boolean,
        val message: String,
    )

    companion object {
        private const val TEST_TIMEOUT_MS = 12_000L

        fun registerLauncher(
            caller: ActivityResultCaller,
            onResult: (Boolean) -> Unit,
        ): ActivityResultLauncher<Unit> {
            return caller.registerForActivityResult(Contract()) { saved ->
                onResult(saved)
            }
        }
    }
}
