// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/**
 * Stable Cloud Bridge device identity.
 *
 * Build.SERIAL commonly returns "unknown" on modern Android, so use ANDROID_ID.
 */
object CloudBridgeDeviceId {

    fun get(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ).orEmpty()

        if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            return "android-$androidId"
        }

        val fallback = listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            Build.DEVICE,
            Build.FINGERPRINT,
        ).joinToString("|")
        return "android-${sha256(fallback).take(16)}"
    }

    fun getTest(context: Context): String = "${get(context)}-test"

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
