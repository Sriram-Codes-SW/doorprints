package app.doorprints.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** What the active network looks like right now (OSI L1-L3, see docs/09-osi-layer-analysis.md). */
data class NetworkSnapshot(
    val connected: Boolean,
    /** Android confirmed internet access (NET_CAPABILITY_VALIDATED). False on a LAN-only network too. */
    val validated: Boolean,
    /** The network is behind a captive portal that still needs a sign-in. */
    val captivePortal: Boolean,
    /** Mobile data or a metered hotspot: photo transfers wait for Wi-Fi when the user asked for that. */
    val metered: Boolean,
)

object NetworkState {
    fun current(context: Context): NetworkSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkSnapshot(connected = false, validated = false, captivePortal = false, metered = true)
        val network = cm.activeNetwork
            ?: return NetworkSnapshot(connected = false, validated = false, captivePortal = false, metered = true)
        val caps = cm.getNetworkCapabilities(network)
            ?: return NetworkSnapshot(connected = false, validated = false, captivePortal = false, metered = true)
        return NetworkSnapshot(
            connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            captivePortal = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
            metered = cm.isActiveNetworkMetered,
        )
    }
}
