package io.agents.pokeclaw.bridge.internal

import io.agents.pokeclaw.bridge.api.BridgeLogger
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "BridgeDispatcher"

/**
 * Single-threaded scheduled executor that wraps all submitted work in a top-level
 * try/catch so that exceptions never kill the dispatcher thread (Requirement 9.5).
 */
class BridgeDispatcher(private val logger: BridgeLogger) {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "bridge-dispatcher").apply { isDaemon = true }
    }

    /** Post a runnable to the dispatcher thread. Exceptions are caught and logged. */
    fun execute(runnable: Runnable) {
        executor.execute {
            try {
                runnable.run()
            } catch (t: Throwable) {
                logger.e(TAG, "Uncaught exception in dispatcher runnable", t)
            }
        }
    }

    /**
     * Schedule a runnable to run after [delayMs] milliseconds.
     * Exceptions inside the runnable are caught and logged.
     */
    fun schedule(delayMs: Long, runnable: Runnable): ScheduledFuture<*> {
        return executor.schedule(
            {
                try {
                    runnable.run()
                } catch (t: Throwable) {
                    logger.e(TAG, "Uncaught exception in scheduled runnable", t)
                }
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Submit a callable and return a Future for the result. */
    fun <T> submit(callable: Callable<T>): Future<T> {
        return executor.submit(callable)
    }

    /** Shut down the executor. Pending tasks are not interrupted. */
    fun shutdown() {
        executor.shutdown()
    }

    /** Returns true if the current thread is the dispatcher thread. */
    fun isDispatcherThread(): Boolean =
        Thread.currentThread().name == "bridge-dispatcher"
}
