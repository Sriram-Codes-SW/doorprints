package app.doorprints.drive.photo

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * [NetworkState] on Android (S4b-BL-128, docs/15 §11): `NET_CAPABILITY_NOT_METERED` says Wi-Fi (or any unmetered
 * network), `NET_CAPABILITY_NOT_ROAMING` roaming, `getRestrictBackgroundStatus()` Data Saver. No network, or no
 * capabilities, is offline. Read at the moment of the call; the mapping is [PhotoNetworkPolicy.fromAndroid].
 */
class AndroidNetworkState(private val context: Context) : NetworkState {
    override fun current(): NetworkConditions {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkConditions.OFFLINE
        val network = cm.activeNetwork ?: return NetworkConditions.OFFLINE
        val caps = cm.getNetworkCapabilities(network) ?: return NetworkConditions.OFFLINE
        return PhotoNetworkPolicy.fromAndroid(
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            dataSaverEnabled = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        )
    }
}
