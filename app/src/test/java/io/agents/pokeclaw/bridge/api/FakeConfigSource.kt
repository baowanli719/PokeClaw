package io.agents.pokeclaw.bridge.api

/**
 * Fake [ConfigSource] for unit tests.
 * The config can be replaced at runtime to simulate reconfiguration or missing config.
 */
class FakeConfigSource(
    var config: BridgeConfig? = null,
) : ConfigSource {
    override fun load(): BridgeConfig? = config
}
