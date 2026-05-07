package io.agents.pokeclaw.bridge

import io.agents.pokeclaw.bridge.api.BridgeConfig
import io.agents.pokeclaw.bridge.api.BridgeLogger
import io.agents.pokeclaw.bridge.api.CapabilitySnapshot
import io.agents.pokeclaw.bridge.api.CapturingBridgeLogger
import io.agents.pokeclaw.bridge.api.ConfigSource
import io.agents.pokeclaw.bridge.api.FakeCapabilityProvider
import io.agents.pokeclaw.bridge.api.FakeConfigSource
import io.agents.pokeclaw.bridge.api.FakeTaskExecutor
import io.agents.pokeclaw.bridge.api.TaskExecutor
import io.agents.pokeclaw.bridge.api.TaskExecutorCallback
import io.agents.pokeclaw.bridge.api.TaskHandle
import io.agents.pokeclaw.bridge.internal.FakeClock
import com.google.gson.JsonObject
import net.jqwik.api.*
import org.assertj.core.api.Assertions.assertThat
import java.io.File

/**
 * Property 9: Public API never propagates internal exceptions
 *
 * For any combination of: malformed inbound frame, TaskExecutor callback that throws,
 * ConfigSource that returns malformed values, transient file IO error in the outbox,
 * BridgeLogger that throws; calling any of CloudBridgeClient.start(), stop(),
 * reconfigure(), currentState(), observeState() shall not throw or return an error;
 * failures shall be swallowed and reported via BridgeLogger.e(...).
 *
 * **Validates: Requirements 9.5**
 */
class CloudBridgeClientPropertyTest {

    // ═══════════════════════════════════════════════════════════════════════
    // Failure scenario model
    // ═══════════════════════════════════════════════════════════════════════

    enum class FailureType {
        EXECUTOR_EXECUTE_THROWS,
        EXECUTOR_CANCEL_THROWS,
        CONFIG_RETURNS_EMPTY_URL,
        CONFIG_RETURNS_NULL,
        CONFIG_THROWS,
        LOGGER_THROWS,
    }

    data class FailureScenario(
        val failures: List<FailureType>,
        val apiCalls: List<ApiCall>,
    )

    enum class ApiCall {
        START, STOP, RECONFIGURE, CURRENT_STATE, OBSERVE_STATE,
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Throwing fakes
    // ═══════════════════════════════════════════════════════════════════════

    private class ThrowingTaskExecutor : TaskExecutor {
        override fun execute(
            requestId: String,
            kind: String,
            params: JsonObject,
            deadlineTsMillis: Long?,
            callback: TaskExecutorCallback,
        ): TaskHandle {
            throw RuntimeException("execute exploded")
        }

        override fun cancel(requestId: String) {
            throw RuntimeException("cancel exploded")
        }
    }

    private class ThrowingConfigSource : ConfigSource {
        override fun load(): BridgeConfig? {
            throw RuntimeException("config load exploded")
        }
    }

    private class ThrowingBridgeLogger : BridgeLogger {
        override fun d(tag: String, message: String) {
            throw RuntimeException("logger.d exploded")
        }
        override fun i(tag: String, message: String) {
            throw RuntimeException("logger.i exploded")
        }
        override fun w(tag: String, message: String, throwable: Throwable?) {
            throw RuntimeException("logger.w exploded")
        }
        override fun e(tag: String, message: String, throwable: Throwable?) {
            throw RuntimeException("logger.e exploded")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Property 9
    // ═══════════════════════════════════════════════════════════════════════

    @Property(tries = 50)
    fun `public API never throws regardless of internal failures`(
        @ForAll("failureScenarios") scenario: FailureScenario,
    ) {
        val tempDir = createTempDir("bridge_prop9_")
        try {
            val configSource: ConfigSource = when {
                FailureType.CONFIG_THROWS in scenario.failures -> ThrowingConfigSource()
                FailureType.CONFIG_RETURNS_NULL in scenario.failures -> FakeConfigSource(null)
                FailureType.CONFIG_RETURNS_EMPTY_URL in scenario.failures -> FakeConfigSource(
                    BridgeConfig(serverUrl = "", deviceToken = "tok_1234", advertisedCapabilities = listOf("test"))
                )
                else -> FakeConfigSource(
                    BridgeConfig(
                        serverUrl = "wss://example.com/ws",
                        deviceToken = "tok_valid_1234",
                        advertisedCapabilities = listOf("test"),
                    )
                )
            }

            val taskExecutor: TaskExecutor = when {
                FailureType.EXECUTOR_EXECUTE_THROWS in scenario.failures ||
                FailureType.EXECUTOR_CANCEL_THROWS in scenario.failures -> ThrowingTaskExecutor()
                else -> FakeTaskExecutor()
            }

            val logger: BridgeLogger = when {
                FailureType.LOGGER_THROWS in scenario.failures -> ThrowingBridgeLogger()
                else -> CapturingBridgeLogger()
            }

            val client = CloudBridgeClient(
                configSource = configSource,
                capabilityProvider = FakeCapabilityProvider(
                    snapshot = CapabilitySnapshot(
                        supportedKinds = listOf("test"),
                        accessibilityReady = true,
                        installedTargetApps = emptyMap(),
                        batteryLevel = 0.5,
                        charging = false,
                    )
                ),
                taskExecutor = taskExecutor,
                logger = logger,
                deviceId = "device_test",
                appVersion = "1.0.0",
                filesDir = tempDir,
                clock = FakeClock(wallMs = 1_000_000L),
            )

            // Execute all API calls — none should throw
            for (call in scenario.apiCalls) {
                when (call) {
                    ApiCall.START -> client.start()
                    ApiCall.STOP -> client.stop()
                    ApiCall.RECONFIGURE -> client.reconfigure()
                    ApiCall.CURRENT_STATE -> client.currentState()
                    ApiCall.OBSERVE_STATE -> client.observeState()
                }
            }

            // If we reach here, no exception was propagated — property holds
            assertThat(true)
                .describedAs("Public API should never throw regardless of internal failures")
                .isTrue()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Property 10: Token is never logged in cleartext
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Property 10: Token is never logged in cleartext
     *
     * For any BridgeConfig(deviceToken = t) with t.length >= 8, the concatenation of
     * every string passed to BridgeLogger.{d,i,w,e} during Bridge execution shall not
     * contain t as a substring. The bridge may log a masked form ***{t.takeLast(4)}.
     *
     * **Validates: Requirements 10.4**
     */
    @Property(tries = 50)
    fun `device token never appears in log output`(
        @ForAll("tokens") token: String,
    ) {
        val tempDir = createTempDir("bridge_prop10_")
        try {
            val logger = CapturingBridgeLogger()
            val config = BridgeConfig(
                serverUrl = "wss://example.com/ws",
                deviceToken = token,
                advertisedCapabilities = listOf("test"),
            )

            val client = CloudBridgeClient(
                configSource = FakeConfigSource(config),
                capabilityProvider = FakeCapabilityProvider(
                    snapshot = CapabilitySnapshot(
                        supportedKinds = listOf("test"),
                        accessibilityReady = true,
                        installedTargetApps = emptyMap(),
                        batteryLevel = 0.8,
                        charging = true,
                    )
                ),
                taskExecutor = FakeTaskExecutor(),
                logger = logger,
                deviceId = "device_prop10",
                appVersion = "2.0.0",
                filesDir = tempDir,
                clock = FakeClock(wallMs = 1_000_000L),
            )

            // Call start() which triggers config loading and logging
            client.start()
            // Call reconfigure() which also logs the token masked
            client.reconfigure()
            // Call stop()
            client.stop()

            // Assert: the full token never appears in any log entry
            val allMessages = logger.entries.map { it.message }
            for (message in allMessages) {
                assertThat(message)
                    .describedAs(
                        "Log message should not contain the full token '%s'",
                        token,
                    )
                    .doesNotContain(token)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Generators
    // ═══════════════════════════════════════════════════════════════════════

    @Provide
    fun failureScenarios(): Arbitrary<FailureScenario> {
        val failureTypes = Arbitraries.of(FailureType::class.java)
            .list().ofMinSize(1).ofMaxSize(3)
        val apiCalls = Arbitraries.of(ApiCall::class.java)
            .list().ofMinSize(1).ofMaxSize(5)
        return Combinators.combine(failureTypes, apiCalls).`as` { failures, calls ->
            FailureScenario(failures, calls)
        }
    }

    @Provide
    fun tokens(): Arbitrary<String> {
        // Generate tokens of length >= 8 with alphanumeric + special chars
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .withCharRange('A', 'Z')
            .withCharRange('0', '9')
            .withChars('_', '-', '.', '!')
            .ofMinLength(8)
            .ofMaxLength(64)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun createTempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$prefix${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
