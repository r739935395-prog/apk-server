package com.rafat.whatshub.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.rafat.whatshub.repository.HubRepository
import com.rafat.whatshub.security.SanitizedLogger

class ConnectivityMonitor(
    context: Context,
    private val repository: HubRepository
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            SanitizedLogger.info("Net", "Validated network available; notifying isolated sessions")
            repository.onNetworkChanged(true)
        }

        override fun onLost(network: Network) {
            val stillHasActive = hasActiveInternet()
            SanitizedLogger.info("Net", "Network lost (stillHasActive=$stillHasActive)")
            repository.onNetworkChanged(stillHasActive)
        }
    }

    private fun hasActiveInternet(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Synchronized
    fun start() {
        if (registered) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, callback)
            registered = true
        } catch (t: Throwable) {
            SanitizedLogger.warn("Net", "Failed to register NetworkCallback: ${t.javaClass.simpleName}")
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Throwable) {
        }
        registered = false
    }
}
