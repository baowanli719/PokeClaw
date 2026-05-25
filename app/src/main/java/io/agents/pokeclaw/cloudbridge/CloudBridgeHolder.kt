// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import android.content.Context
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.bridge.CloudBridgeClient
import io.agents.pokeclaw.bridge.connection.NetworkMonitor
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.utils.XLog

/**
 * Singleton holder for the [CloudBridgeClient] instance.
 *
 * Assembled during [ClawApplication.onCreate] async-init.
 * [client] is null until [init] is called.
 *
 * ForegroundService calls [client?.start()] / [client?.stop()] on its lifecycle.
 */
object CloudBridgeHolder {

    private const val TAG = "CloudBridgeHolder"

    @Volatile
    var client: CloudBridgeClient? = null
        private set

    /**
     * Assemble and store the [CloudBridgeClient].
     * Does NOT call start() — that's ForegroundService's responsibility.
     */
    fun init(context: Context, orchestrator: io.agents.pokeclaw.TaskOrchestrator) {
        val configSource = KVUtilsConfigSource()
        val logger = XLogBridgeLogger()
        val supportedKinds = CloudBridgeCapabilities.SUPPORTED_KINDS
        val capabilityProvider = AppCapabilityProviderAdapter(context, supportedKinds)
        val resultSink = DefaultCloudTaskResultSink()
        val taskExecutor = TaskOrchestratorExecutorAdapter(
            orchestrator = orchestrator,
            resultSink = resultSink,
        )

        client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = CloudBridgeDeviceId.get(context),
            appVersion = getAppVersion(context),
            filesDir = context.filesDir,
        )
        XLog.i(TAG, "CloudBridgeClient assembled")
        if (ForegroundService.isRunning()) {
            runCatching { client?.start() }
                .onFailure { XLog.w(TAG, "CloudBridge start after init failed", it) }
        }
    }

    private fun getAppVersion(context: Context): String {
        return try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
