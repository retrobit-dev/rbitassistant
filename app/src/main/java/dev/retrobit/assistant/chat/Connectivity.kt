package dev.retrobit.assistant.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** "Online" = jaringan default punya INTERNET + VALIDATED (bukan sekadar Wi-Fi tersambung). */
class Connectivity(context: Context, private val onChange: (Boolean) -> Unit) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var online: Boolean = check(cm.getNetworkCapabilities(cm.activeNetwork))
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(check(caps))
        override fun onLost(network: Network) = update(false)
    }

    init {
        cm.registerDefaultNetworkCallback(callback)
    }

    private fun check(caps: NetworkCapabilities?): Boolean =
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun update(v: Boolean) {
        if (v != online) {
            online = v
            onChange(v)
        }
    }

    fun close() {
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }
}
