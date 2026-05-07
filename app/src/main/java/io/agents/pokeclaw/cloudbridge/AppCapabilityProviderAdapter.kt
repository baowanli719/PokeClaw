// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.bridge.api.CapabilityProvider
import io.agents.pokeclaw.bridge.api.CapabilitySnapshot

/**
 * Adapts [AppCapabilityCoordinator] + [BatteryManager] into the bridge's
 * [CapabilityProvider] interface.
 *
 * @param context Application context for battery and package queries.
 * @param staticSupportedKinds The kind list to advertise in hello.
 */
class AppCapabilityProviderAdapter(
    private val context: Context,
    private val staticSupportedKinds: List<String>,
) : CapabilityProvider {

    companion object {
        private const val THS_PACKAGE = "com.hexin.plat.android"
    }

    override fun currentSnapshot(): CapabilitySnapshot {
        val appSnapshot = AppCapabilityCoordinator.snapshot(context)
        val accessibilityReady =
            appSnapshot.accessibilityState == ServiceBindingState.READY

        val installedTargetApps = mutableMapOf<String, Boolean>()
        val thsInstalled = isAppInstalled(context, THS_PACKAGE)
        for (kind in staticSupportedKinds) {
            installedTargetApps[kind] = thsInstalled
        }

        return CapabilitySnapshot(
            supportedKinds = staticSupportedKinds,
            accessibilityReady = accessibilityReady,
            installedTargetApps = installedTargetApps,
            batteryLevel = readBatteryLevel(context),
            charging = readCharging(context),
        )
    }

    private fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readBatteryLevel(context: Context): Double? {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return level.toDouble() / scale.toDouble()
    }

    private fun readCharging(context: Context): Boolean? {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }
}
