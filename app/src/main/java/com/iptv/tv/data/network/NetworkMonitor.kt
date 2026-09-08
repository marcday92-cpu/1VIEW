package com.iptv.tv.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkStatus(
    /** A validated default network with internet capability exists. */
    val online: Boolean,
    /** The default network is a VPN tunnel (Surfshark or any other VPN app). */
    val vpn: Boolean,
    /**
     * Increments whenever the default network changes identity (Wi‑Fi → VPN, VPN → Wi‑Fi,
     * reconnects). Players use it to know that in-flight requests belong to a dead route.
     */
    val generation: Int,
)

/**
 * One process-wide view of connectivity, read from [ConnectivityManager] without extra
 * permissions. Used by the player to pause retries while offline and by Settings → VPN.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _status = MutableStateFlow(snapshot(generation = 0))
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    val isOnline: Boolean get() = _status.value.online

    init {
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var lastNetwork: Network? = null

            override fun onAvailable(network: Network) {
                val changed = lastNetwork != null && lastNetwork != network
                lastNetwork = network
                publish(bump = changed)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                publish(bump = false)
            }

            override fun onLost(network: Network) {
                if (lastNetwork == network) lastNetwork = null
                publish(bump = true)
            }
        }
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }
    }

    private fun publish(bump: Boolean) {
        val previous = _status.value
        val next = snapshot(if (bump) previous.generation + 1 else previous.generation)
        if (next != previous) _status.value = next
    }

    private fun snapshot(generation: Int): NetworkStatus {
        val caps = runCatching {
            connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        }.getOrNull()
        val online = caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        return NetworkStatus(online = online, vpn = vpn, generation = generation)
    }
}
