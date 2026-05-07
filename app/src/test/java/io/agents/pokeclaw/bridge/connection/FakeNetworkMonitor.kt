package io.agents.pokeclaw.bridge.connection

/**
 * JVM test double for [NetworkMonitor]. Tests drive events manually via [triggerAvailable] and
 * [triggerLost] without needing Android framework plumbing.
 */
class FakeNetworkMonitor : NetworkMonitor {

    private var listener: NetworkMonitor.Listener? = null

    /** True between [start] and [stop]. */
    val isStarted: Boolean get() = listener != null

    override fun start(listener: NetworkMonitor.Listener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    /** Simulate [NetworkMonitor.Listener.onAvailable] being invoked. No-op if not started. */
    fun triggerAvailable() {
        listener?.onAvailable()
    }

    /** Simulate [NetworkMonitor.Listener.onLost] being invoked. No-op if not started. */
    fun triggerLost() {
        listener?.onLost()
    }
}
