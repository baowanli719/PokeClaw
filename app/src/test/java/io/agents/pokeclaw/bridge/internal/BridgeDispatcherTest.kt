package io.agents.pokeclaw.bridge.internal

import io.agents.pokeclaw.bridge.api.BridgeLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BridgeLogger that captures error calls for assertion.
 */
private class CapturingBridgeLogger : BridgeLogger {
    val errors: MutableList<Triple<String, String, Throwable?>> =
        Collections.synchronizedList(mutableListOf())

    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) {
        errors.add(Triple(tag, message, throwable))
    }
}

class BridgeDispatcherTest {

    private lateinit var dispatcher: BridgeDispatcher
    private lateinit var logger: CapturingBridgeLogger

    @BeforeEach
    fun setUp() {
        logger = CapturingBridgeLogger()
        dispatcher = BridgeDispatcher(logger)
    }

    @AfterEach
    fun tearDown() {
        dispatcher.shutdown()
    }

    // -------------------------------------------------------------------------
    // Test 1: All runnables execute serially on "bridge-dispatcher" thread
    // Validates: Requirements 7.6
    // -------------------------------------------------------------------------

    @Test
    fun `all runnables execute serially on the bridge-dispatcher thread`() {
        val taskCount = 10
        val latch = CountDownLatch(taskCount)
        val threadNames = Collections.synchronizedList(mutableListOf<String>())

        for (i in 0 until taskCount) {
            dispatcher.execute {
                threadNames.add(Thread.currentThread().name)
                latch.countDown()
            }
        }

        assertThat(latch.await(5, TimeUnit.SECONDS))
            .describedAs("All tasks should complete within 5 seconds")
            .isTrue()

        assertThat(threadNames)
            .describedAs("Every runnable must execute on bridge-dispatcher thread")
            .hasSize(taskCount)
            .allMatch { it == "bridge-dispatcher" }
    }

    // -------------------------------------------------------------------------
    // Test 2: Exception in runnable does not kill the dispatcher
    // Validates: Requirements 9.5
    // -------------------------------------------------------------------------

    @Test
    fun `runnable throwing exception does not kill dispatcher - subsequent tasks still execute`() {
        val afterExceptionLatch = CountDownLatch(1)
        val afterExceptionRan = AtomicBoolean(false)

        // First runnable: throws
        dispatcher.execute { throw RuntimeException("intentional test explosion") }

        // Second runnable: should still run
        dispatcher.execute {
            afterExceptionRan.set(true)
            afterExceptionLatch.countDown()
        }

        assertThat(afterExceptionLatch.await(5, TimeUnit.SECONDS))
            .describedAs("Post-exception task should complete within 5 seconds")
            .isTrue()

        assertThat(afterExceptionRan.get())
            .describedAs("Runnable submitted after exception must still execute")
            .isTrue()

        // Verify the exception was logged
        assertThat(logger.errors)
            .describedAs("Exception should be reported to BridgeLogger.e")
            .isNotEmpty()
    }

    // -------------------------------------------------------------------------
    // Test 3: schedule() executes runnable after the specified delay
    // Validates: Requirements 7.6
    // -------------------------------------------------------------------------

    @Test
    fun `schedule executes runnable after approximately the specified delay`() {
        val latch = CountDownLatch(1)
        val delayMs = 100L
        val startTime = System.nanoTime()
        var executionTime = 0L

        dispatcher.schedule(delayMs) {
            executionTime = System.nanoTime()
            latch.countDown()
        }

        assertThat(latch.await(2, TimeUnit.SECONDS))
            .describedAs("Scheduled task should complete within 2 seconds")
            .isTrue()

        val elapsedMs = (executionTime - startTime) / 1_000_000
        assertThat(elapsedMs)
            .describedAs("Scheduled runnable should execute after ~${delayMs}ms (actual: ${elapsedMs}ms)")
            .isGreaterThanOrEqualTo(delayMs - 20) // allow small timing variance
            .isLessThan(delayMs + 200) // generous upper bound for CI environments
    }
}
