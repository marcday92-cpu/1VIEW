package com.iptv.tv.data.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class VpnStatus(
    /** The Surfshark app is installed on this stick. */
    val surfsharkInstalled: Boolean,
    /** Android reports the active network is a VPN tunnel (any VPN app, not only Surfshark). */
    val tunnelActive: Boolean,
)

/**
 * The VPN stays a separate app. Surfshark publishes no third-party control API or intents,
 * so 1VIEW only hands the user to the Surfshark app (or its store page) and reports what
 * Android itself knows about the active network. Nothing here needs extra permissions.
 */
@Singleton
class VpnAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    networkMonitor: NetworkMonitor,
) {
    val status: Flow<VpnStatus> = networkMonitor.status.map { net ->
        VpnStatus(surfsharkInstalled = isSurfsharkInstalled(), tunnelActive = net.vpn)
    }

    fun isSurfsharkInstalled(): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(SURFSHARK_PACKAGE) != null }
            .getOrDefault(false)

    /** Brings the Surfshark app to the front. Returns false when it is not installed. */
    fun openSurfshark(): Boolean {
        val launch = runCatching { context.packageManager.getLaunchIntentForPackage(SURFSHARK_PACKAGE) }
            .getOrNull() ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(launch); true }.getOrDefault(false)
    }

    /** Opens the Surfshark listing in the Amazon Appstore, falling back to any market app. */
    fun openStore(): Boolean {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("amzn://apps/android?p=$SURFSHARK_PACKAGE")),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SURFSHARK_PACKAGE")),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    companion object {
        const val SURFSHARK_PACKAGE = "com.surfshark.vpnclient.android"
    }
}
