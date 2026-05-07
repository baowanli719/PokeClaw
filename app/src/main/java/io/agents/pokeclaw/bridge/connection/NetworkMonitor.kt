package io.agents.pokeclaw.bridge.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.agents.pokeclaw.bridge.internal.BridgeDispatcher

/**
 * Observes network connectivity changes and notifies a listener.
 *
 * The default implementation [AndroidNetworkMonitor] wraps
 * [ConnectivityManager.NetworkCallback]; tests should use a fake implementation.
 *
 * Validates: Requirements 4.4
 */
interface NetworkMonitor {
    interface Listener {
        fun onAvailable()
        fun onLost()
    }

    /** Start observing. Safe to call repeatedly; subsequent calls replace the previous listener. */
    fun start(listener: Listener)

    /** Stop observing. Safe to call before [start] or multiple times. */
    fun stop()
}

/**
 * Default [NetworkMonitor] backed by Android's [ConnectivityManager]. All listener callbacks are
 * posted onto the provided [BridgeDispatcher] so downstream state mutations stay single-threaded.
 */
class AndroidNetworkMonitor(
    private val context: Context,
    private val dispatcher: BridgeDispatcher,
) : NetworkMonitor {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(listener: NetworkMonitor.Listener) {
        // Idempotent: drop any previous registration before installing a new one.
        stop()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                dispatcher.execute { listener.onAvailable() }
            }

            override fun onLost(network: Network) {
                dispatcher.execute { listener.onLost() }
            }
        }
        callback = cb
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(request, cb)
    }

    override fun stop() {
        val cb = callback ?: return
        try {
            connectivityManager.unregisterNetworkCallback(cb)
        } catch (_: IllegalArgumentException) {
            // Callback was not registered (or was already unregistered); safe to ignore.
        }
        callback = null
    }
}
