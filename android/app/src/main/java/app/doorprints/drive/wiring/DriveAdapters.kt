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

package app.doorprints.drive.wiring

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.ConsentResolver
import app.doorprints.drive.auth.PendingConsent
import app.doorprints.drive.auth.PlayPendingConsent
import app.doorprints.drive.photo.NetworkConditions
import app.doorprints.drive.photo.NetworkState
import app.doorprints.drive.photo.PhotoNetworkPolicy

/** What an Activity answered to a screen the app started for a result. */
class ActivityOutcome(val resultCode: Int, val data: Intent?)

/** Starts Google's consent screen or the keyguard's confirm screen on the foreground Activity and waits for its answer. */
interface ActivityLauncher {
    suspend fun launch(consent: PendingIntent): ActivityOutcome
    suspend fun launch(intent: Intent): ActivityOutcome
}

/**
 * The Activity side of Google's consent (A1 notes): the token provider asks, this starts the screen on the foreground
 * Activity ([launcher], null in the background) and has [interpret] (`PlayGoogleAuthorizer.fromActivityResult`) read the
 * answer, so a closed screen stays "cancelled" and a screen with the Drive permission unticked stays "granted without it"
 * for the provider's own checks. Nobody to show it to is "unavailable", never a refusal.
 */
class ConsentBridge(
    private val launcher: () -> ActivityLauncher?,
    private val interpret: (resultCode: Int, data: Intent?) -> AuthorizerResult,
) : ConsentResolver {
    /** The resolver to give the token provider: null while no Activity can show the screen. */
    fun resolverOrNull(): ConsentResolver? = if (launcher() != null) this else null

    override suspend fun resolve(consent: PendingConsent): AuthorizerResult {
        val play = consent as? PlayPendingConsent ?: return AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
        val activity = launcher() ?: return AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
        val out = activity.launch(play.intent)
        return interpret(out.resultCode, out.data)
    }
}

/** The network as `ConnectivityManager` shows it, for the photo policy (Wi-Fi only by default, Data Saver and roaming respected). */
class ConnectivityNetworkState(private val context: Context) : NetworkState {
    override fun current(): NetworkConditions {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkConditions.OFFLINE
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return NetworkConditions.OFFLINE) ?: return NetworkConditions.OFFLINE
        return conditions(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            restrictBackground = cm.restrictBackgroundStatus,
        )
    }

    companion object {
        /** Online needs a validated internet connection (a captive portal is not online). Data Saver counts when it is on for this app. */
        fun conditions(internet: Boolean, validated: Boolean, notMetered: Boolean, notRoaming: Boolean, restrictBackground: Int): NetworkConditions =
            PhotoNetworkPolicy.fromAndroid(
                hasInternet = internet && validated,
                notMetered = notMetered,
                notRoaming = notRoaming,
                dataSaverEnabled = restrictBackground == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
            )
    }
}
