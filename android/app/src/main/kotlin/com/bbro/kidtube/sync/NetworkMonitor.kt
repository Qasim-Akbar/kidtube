package com.bbro.kidtube.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Tells the scheduler when the network comes back. A seam so tests do not need a ConnectivityManager. */
interface NetworkMonitor {
    /** Starts watching; [onRegained] is called each time connectivity (re)appears. A second call replaces the first. */
    fun register(onRegained: () -> Unit)

    fun unregister()
}

/**
 * `registerDefaultNetworkCallback` calls `onAvailable` once right away if a network is already up. That first call is
 * registration noise, not "regained", so it is swallowed; every later `onAvailable` counts.
 */
class RegainGate {
    private var initialSeen = false

    /** True when this `onAvailable` means the network came (back). */
    @Synchronized
    fun onAvailable(): Boolean {
        if (!initialSeen) {
            initialSeen = true
            return false
        }
        return true
    }
}

@Singleton
class AndroidNetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) : NetworkMonitor {
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Synchronized
    override fun register(onRegained: () -> Unit) {
        unregister()
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val gate = RegainGate()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (gate.onAvailable()) onRegained()
            }
        }
        callback = cb
        try {
            manager.registerDefaultNetworkCallback(cb)
        } catch (_: SecurityException) {
            callback = null // the periodic work still covers us
        }
    }

    @Synchronized
    override fun unregister() {
        val cb = callback ?: return
        callback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }
}
