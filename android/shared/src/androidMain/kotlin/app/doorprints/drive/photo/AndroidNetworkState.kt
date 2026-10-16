/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

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
