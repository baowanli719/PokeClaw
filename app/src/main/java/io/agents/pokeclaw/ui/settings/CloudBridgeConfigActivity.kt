// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.settings

import android.content.Context
import android.content.Intent
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
import io.agents.pokeclaw.R
import io.agents.pokeclaw.base.BaseActivity
import io.agents.pokeclaw.cloudbridge.CloudBridgeHolder
import io.agents.pokeclaw.cloudbridge.KVUtilsConfigSource
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.widget.CommonToolbar
import io.agents.pokeclaw.widget.KButton

/**
 * Cloud Bridge config screen for the cloud service URL and device token.
 */
class CloudBridgeConfigActivity : BaseActivity() {

    private lateinit var etServerUrl: EditText
    private lateinit var etDeviceToken: EditText
    private var isTokenVisible = false

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
        etServerUrl.setText(KVUtils.getString(KVUtilsConfigSource.KEY_CLOUD_BRIDGE_URL, ""))

        etDeviceToken.hint = "Bearer token from DEVICE_TOKENS"
        etDeviceToken.transformationMethod = PasswordTransformationMethod.getInstance()
        etDeviceToken.setText(KVUtils.getString(KVUtilsConfigSource.KEY_CLOUD_BRIDGE_DEVICE_TOKEN, ""))

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

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            save()
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

        if (ForegroundService.isRunning()) {
            CloudBridgeHolder.client?.reconfigure()
        }

        setResult(RESULT_OK)
        Toast.makeText(this, "Cloud Bridge config saved", Toast.LENGTH_SHORT).show()
        finish()
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

    class Contract : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, CloudBridgeConfigActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == RESULT_OK
    }

    companion object {
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
