// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.llm

import android.os.Build
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog

object LocalBackendHealth {

    private const val TAG = "LocalBackendHealth"
    private const val CRASH_MARKER_MAX_AGE_MS = 1000L * 60L * 60L * 24L * 30L

    fun currentDeviceKey(): String {
        val fingerprint = Build.FINGERPRINT?.trim().orEmpty()
        if (fingerprint.isNotEmpty()) return fingerprint
        return listOf(Build.MANUFACTURER, Build.MODEL, Build.DEVICE, Build.HARDWARE)
            .filter { !it.isNullOrBlank() }
            .joinToString("|")
    }

    fun shouldForceCpu(preferCpu: Boolean): Boolean {
        recoverPendingGpuCrashIfNeeded()
        return preferCpu ||
            KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true) ||
            isCpuSafeModeEnabled()
    }

    fun isCpuSafeModeEnabled(): Boolean {
        return KVUtils.getLocalCpuSafeDevice() == currentDeviceKey()
    }

    fun cpuSafeReason(): String = KVUtils.getLocalCpuSafeReason()

    fun debugStateSummary(): String {
        val pendingDevice = KVUtils.getPendingLocalGpuInitDevice().ifBlank { "-" }
        val pendingModel = KVUtils.getPendingLocalGpuInitModel().ifBlank { "-" }
        val pendingAt = KVUtils.getPendingLocalGpuInitAt()
        val cpuSafeDevice = KVUtils.getLocalCpuSafeDevice().ifBlank { "-" }
        val backendPreference = KVUtils.getLocalBackendPreference().ifBlank { "-" }
        val reason = cpuSafeReason().ifBlank { "-" }
        return buildString {
            append("device=")
            append(currentDeviceKey())
            append(", cpuSafe=")
            append(isCpuSafeModeEnabled())
            append(", cpuSafeDevice=")
            append(cpuSafeDevice)
            append(", backendPreference=")
            append(backendPreference)
            append(", reason=")
            append(reason)
            append(", pendingDevice=")
            append(pendingDevice)
            append(", pendingModel=")
            append(pendingModel)
            append(", pendingAt=")
            append(pendingAt)
        }
    }

    fun debugForceCpuSafe(reason: String = "debug") {
        enableCpuSafeMode(reason)
    }

    fun debugClearCpuSafeMode() {
        KVUtils.clearLocalCpuSafeMode()
        if (KVUtils.getLocalBackendPreference().equals("CPU", ignoreCase = true)) {
            KVUtils.setLocalBackendPreference("")
        }
    }

    fun debugMarkPendingGpuInit(modelPath: String) {
        markGpuInitStarted(modelPath)
    }

    fun debugClearPendingGpuInit() {
        KVUtils.clearPendingLocalGpuInit()
    }

    fun noteRecoverableGpuFailure(modelPath: String, error: Throwable?) {
        val reason = buildReason("gpu_failure", modelPath, error?.message)
        enableCpuSafeMode(reason)
        KVUtils.clearPendingLocalGpuInit()
        XLog.w(TAG, "GPU backend marked unsafe for this device: $reason")
    }

    fun markGpuInitStarted(modelPath: String) {
        KVUtils.setPendingLocalGpuInitDevice(currentDeviceKey())
        KVUtils.setPendingLocalGpuInitModel(modelPath)
        KVUtils.setPendingLocalGpuInitAt(System.currentTimeMillis())
        XLog.i(TAG, "Marked GPU init pending for ${modelPath.substringAfterLast('/')}")
    }

    fun markGpuInitFinished() {
        KVUtils.clearPendingLocalGpuInit()
    }

    fun recoverPendingGpuCrashIfNeeded(): Boolean {
        val pendingDevice = KVUtils.getPendingLocalGpuInitDevice()
        val pendingAt = KVUtils.getPendingLocalGpuInitAt()
        if (!shouldPromotePendingGpuCrash(currentDeviceKey(), pendingDevice, pendingAt, System.currentTimeMillis())) {
            return false
        }

        val modelPath = KVUtils.getPendingLocalGpuInitModel()
        val reason = buildReason("gpu_init_crash", modelPath, "previous GPU engine init died before cleanup")
        enableCpuSafeMode(reason)
        KVUtils.clearPendingLocalGpuInit()
        XLog.w(TAG, "Recovered pending GPU init crash; forcing CPU-safe mode for this device")
        return true
    }

    internal fun shouldPromotePendingGpuCrash(
        currentDeviceKey: String,
        pendingDeviceKey: String?,
        pendingAtMs: Long,
        nowMs: Long,
        maxAgeMs: Long = CRASH_MARKER_MAX_AGE_MS,
    ): Boolean {
        if (pendingDeviceKey.isNullOrBlank()) return false
        if (pendingDeviceKey != currentDeviceKey) return false
        if (pendingAtMs <= 0L) return false
        return nowMs - pendingAtMs <= maxAgeMs
    }

    private fun enableCpuSafeMode(reason: String) {
        KVUtils.setLocalCpuSafeDevice(currentDeviceKey())
        KVUtils.setLocalCpuSafeReason(reason)
        KVUtils.setLocalBackendPreference("CPU")
    }

    private fun buildReason(prefix: String, modelPath: String, detail: String?): String {
        val modelName = modelPath.substringAfterLast('/')
        return listOf(prefix, modelName, detail?.take(120))
            .filter { !it.isNullOrBlank() }
            .joinToString(": ")
    }
}
