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

/**
 * Reads the phone's active network for the sync worker, so it can wait on a captive portal and keep photos for Wi-Fi
 * when asked.
 */
object NetworkState {
    /**
     * The active network's [NetworkSnapshot]; with no network, or no way to read it, a disconnected, metered one (the
     * cautious answer).
     */
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
