package io.agents.pokeclaw.bridge

import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.CapabilitySnapshot
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.api.FakeCapabilityProvider
import io.agents.pokeclaw.bridge.api.FakeConfigSource
import io.agents.pokeclaw.bridge.api.FakeTaskExecutor
import io.agents.pokeclaw.bridge.internal.FakeClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Example unit tests for CloudBridgeClient.
 *
 * These tests exercise scenarios that do NOT require a real WebSocket connection:
 * - null config → stays Disconnected
 * - start() then stop() → Stopped(USER_STOPPED)
 * - reconfigure() with changed URL triggers close + reconnect
 * - observeState() returns a Flow that reflects state changes
 *
 * Validates: Requirements 7.1, 7.2, 7.3, 7.5, 10.2, 10.3
 */
class CloudBridgeClientTest {

    private lateinit var tempDir: File
    private lateinit var logger: CapturingBridgeLogger
    private lateinit var clock: FakeClock
    private lateinit var capabilityProvider: FakeCapabilityProvider
    private lateinit var taskExecutor: FakeTaskExecutor

    @BeforeEach
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "bridge_test_${System.nanoTime()}")
        tempDir.mkdirs()
        logger = CapturingBridgeLogger()
        clock = FakeClock(wallMs = 1_000_000L)
        capabilityProvider = FakeCapabilityProvider(
            snapshot = CapabilitySnapshot(
                supportedKinds = listOf("ths.sync_holdings"),
                accessibilityReady = true,
                installedTargetApps = mapOf("ths.sync_holdings" to true),
                batteryLevel = 0.9,
                charging = false,
            )
        )
        taskExecutor = FakeTaskExecutor()
    }

    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 1: ConfigSource returns null → stays Disconnected, no connection
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `config null keeps state Disconnected and does not connect`() {
        val configSource = FakeConfigSource(null)

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_001",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        client.start()

        assertThat(client.currentState())
            .isEqualTo(ConnectionState.Disconnected)

        // Verify a warning was logged about missing config
        val warnings = logger.entries.filter { it.level == "WARN" }
        assertThat(warnings).anyMatch {
            it.message.contains("Config missing") || it.message.contains("incomplete")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 2: start() then stop() → Stopped(USER_STOPPED)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `start then stop transitions to Stopped USER_STOPPED`() {
        // Use null config so start() doesn't try to connect
        val configSource = FakeConfigSource(null)

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_002",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        client.start()
        client.stop()

        assertThat(client.currentState())
            .isEqualTo(ConnectionState.Stopped(StopReason.USER_STOPPED))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 3: reconfigure() with changed URL triggers close + reconnect
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `reconfigure with changed URL logs reconnection`() {
        val configSource = FakeConfigSource(
            BridgeConfig(
                serverUrl = "wss://old.example.com/ws",
                deviceToken = "tok_abcd1234",
                advertisedCapabilities = listOf("ths.sync_holdings"),
            )
        )

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_003",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        client.start()

        // Change the config URL
        configSource.config = BridgeConfig(
            serverUrl = "wss://new.example.com/ws",
            deviceToken = "tok_abcd1234",
            advertisedCapabilities = listOf("ths.sync_holdings"),
        )

        client.reconfigure()

        // Verify that reconfiguration was logged
        val infoLogs = logger.entries.filter { it.level == "INFO" }
        assertThat(infoLogs).anyMatch {
            it.message.contains("Config changed") || it.message.contains("Reconfiguring")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 4: observeState() returns a Flow that reflects state
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `observeState returns flow with initial Disconnected state`() {
        val configSource = FakeConfigSource(null)

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_004",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        val flow = client.observeState()
        val initialState = runBlocking { flow.first() }

        assertThat(initialState).isEqualTo(ConnectionState.Disconnected)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 5: observeState() reflects state after stop
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `observeState reflects Stopped state after stop`() {
        val configSource = FakeConfigSource(null)

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_005",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        client.start()
        client.stop()

        val flow = client.observeState()
        val currentState = runBlocking { flow.first() }

        assertThat(currentState).isEqualTo(ConnectionState.Stopped(StopReason.USER_STOPPED))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 6: start() is idempotent
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `start is idempotent - second call is ignored`() {
        val configSource = FakeConfigSource(null)

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_006",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        client.start()
        client.start() // second call should be ignored

        // Should still be Disconnected (null config)
        assertThat(client.currentState()).isEqualTo(ConnectionState.Disconnected)

        // Verify "already started" was logged
        val debugLogs = logger.entries.filter { it.level == "DEBUG" }
        assertThat(debugLogs).anyMatch {
            it.message.contains("already started")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Test 7: reconfigure with null config disconnects
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `reconfigure with null config transitions to Disconnected`() {
        val configSource = FakeConfigSource(
            BridgeConfig(
                serverUrl = "wss://example.com/ws",
                deviceToken = "tok_test1234",
                advertisedCapabilities = listOf("test"),
            )
        )

        val client = CloudBridgeClient(
            configSource = configSource,
            capabilityProvider = capabilityProvider,
            taskExecutor = taskExecutor,
            logger = logger,
            deviceId = "dev_007",
            appVersion = "1.0.0",
            filesDir = tempDir,
            clock = clock,
        )

        client.start()

        // Now set config to null and reconfigure
        configSource.config = null
        client.reconfigure()

        assertThat(client.currentState()).isEqualTo(ConnectionState.Disconnected)
    }
}
