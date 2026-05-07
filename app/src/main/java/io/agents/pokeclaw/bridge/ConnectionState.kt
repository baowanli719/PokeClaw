package io.agents.pokeclaw.bridge

/**
 * Connection state machine for the Cloud Bridge WebSocket client.
 *
 * Legal transitions:
 * - Disconnected → Connecting
 * - Connecting → Connected | Disconnected | Stopped
 * - Connected → Authenticated | Disconnected | Stopped
 * - Authenticated → Disconnected | Stopped
 * - Disconnected → Connecting | Stopped
 * - Stopped → (terminal, no further transitions)
 */
sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object Authenticated : ConnectionState()
    data class Stopped(val reason: StopReason) : ConnectionState()
}

enum class StopReason {
    /** User explicitly called stop(). */
    USER_STOPPED,
    /** Server returned close code 4403 (authentication failed). */
    AUTH_FAILED,
    /** Server returned close code 4401 (replaced by another connection). */
    REPLACED,
}
