// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.cloudbridge

import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.ConfigSource
import io.agents.pokeclaw.utils.KVUtils

/**
 * Reads cloud bridge configuration from MMKV via [KVUtils].
 *
 * Returns null if either serverUrl or deviceToken is empty,
 * which causes the bridge to stay in Disconnected state.
 */
class KVUtilsConfigSource : ConfigSource {

    companion object {
        const val KEY_CLOUD_BRIDGE_URL = "cloud_bridge_url"
        const val KEY_CLOUD_BRIDGE_DEVICE_TOKEN = "cloud_bridge_device_token"
        const val DEFAULT_CLOUD_BRIDGE_URL = "ws://120.79.22.68:8080/ws/device"
        const val DEFAULT_CLOUD_BRIDGE_DEVICE_TOKEN = "-DWkTqlZhV7DS6spiyvjgvZCtB4dg6zf1RA1Hkr_AwM"

        fun getConfiguredUrl(): String =
            KVUtils.getString(KEY_CLOUD_BRIDGE_URL, DEFAULT_CLOUD_BRIDGE_URL)
                .ifBlank { DEFAULT_CLOUD_BRIDGE_URL }

        fun getConfiguredDeviceToken(): String =
            KVUtils.getString(KEY_CLOUD_BRIDGE_DEVICE_TOKEN, DEFAULT_CLOUD_BRIDGE_DEVICE_TOKEN)
                .ifBlank { DEFAULT_CLOUD_BRIDGE_DEVICE_TOKEN }
    }

    override fun load(): BridgeConfig? {
        val url = getConfiguredUrl()
        val token = getConfiguredDeviceToken()

        if (url.isBlank() || token.isBlank()) return null

        return BridgeConfig(
            serverUrl = url,
            deviceToken = token,
            advertisedCapabilities = CloudBridgeCapabilities.SUPPORTED_KINDS,
        )
    }
}
