package io.agents.pokeclaw.bridge.api

/**
 * Fake [CapabilityProvider] for unit tests.
 * The snapshot can be replaced at runtime to simulate capability changes.
 */
class FakeCapabilityProvider(
    var snapshot: CapabilitySnapshot = CapabilitySnapshot(
        supportedKinds = emptyList(),
        accessibilityReady = true,
        installedTargetApps = emptyMap(),
        batteryLevel = null,
        charging = null,
    ),
) : CapabilityProvider {
    override fun currentSnapshot(): CapabilitySnapshot = snapshot
}
