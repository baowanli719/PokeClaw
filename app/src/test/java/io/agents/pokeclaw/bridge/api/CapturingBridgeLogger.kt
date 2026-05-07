package io.agents.pokeclaw.bridge.api

/**
 * [BridgeLogger] implementation that captures all log calls for test assertions.
 */
class CapturingBridgeLogger : BridgeLogger {

    data class LogEntry(
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    )

    val entries = mutableListOf<LogEntry>()

    override fun d(tag: String, message: String) {
        entries += LogEntry("DEBUG", tag, message)
    }

    override fun i(tag: String, message: String) {
        entries += LogEntry("INFO", tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        entries += LogEntry("WARN", tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        entries += LogEntry("ERROR", tag, message, throwable)
    }
}
