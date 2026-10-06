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

package app.doorprints

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import app.doorprints.drive.auth.browser.DriveAuthorizers
import app.doorprints.drive.wiring.ActivityHooks
import app.doorprints.drive.wiring.DeferredActivityLauncher
import app.doorprints.drive.wiring.DriveCadenceGate
import app.doorprints.drive.wiring.of
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.doorprints.data.ConnectLink
import app.doorprints.i18n.AppLocale
import app.doorprints.ui.AppLockHost
import app.doorprints.shared.listing.ListingText
import app.doorprints.shared.records.RecordRules
import androidx.core.app.NotificationManagerCompat
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

class MainActivity : ComponentActivity() {

    private val deepLinks = MutableStateFlow<DeepLink?>(null)

    /**
     * Google Drive's two screens for a result (docs/15 §5.5, §10.2): Google's consent screen and, on Android 8 to 9, the
     * keyguard's confirm screen. Registered here, before the Activity starts, and answered to the process's one launcher
     * ([ActivityProvider.results]), which a new Activity after a rotation reaches as well.
     */
    private val driveConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        activities.results.deliver(it.resultCode, it.data)
    }
    private val driveConfirm = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        activities.results.deliver(it.resultCode, it.data)
    }
    private val driveStarter = object : DeferredActivityLauncher.Starter {
        override fun start(consent: PendingIntent) = driveConsent.launch(IntentSenderRequest.Builder(consent).build())
        override fun start(intent: Intent) = driveConfirm.launch(intent)
    }
    /** Where the system browser's Google sign-in comes back to (the intent filter for `app.doorprints:/oauth2redirect`). */
    private val browserRedirect get() = (applicationContext as DoorprintsApp).container.drive.browserRedirect
    private val activities get() = (applicationContext as DoorprintsApp).container.activities

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Re-create the channels with this (localised) context so their names follow the app language.
        Notifications.createChannels(this)
        // Only on a real start (UX review, whole-app audit, blocker). After a rotation, the in-app language switch
        // (AppLocale.set recreates the activity), a dark-mode change or process death, getIntent() still holds the
        // notification's extras while NavController has already restored its back stack, so handling them again
        // pushed the house (or one more new-house form) on top of it on every recreation.
        // The Drive sign-in's redirect is the pending request's answer, not a deep link: nothing else is done with it.
        if (savedInstanceState == null && !DriveAuthorizers.deliver(browserRedirect, intent)) handle(intent)
        setContent {
            // The common UI's seams (ADR-23 CMP-3, CMP-5): the platform's (screen reader, permissions) and the app's
            // (data, backup, language). The root and its graph are common code; the intent is read here and handed
            // over as a DeepLink. Every screen is common since CMP-7.
            // The app lock (docs/11 5.19) covers the root while it is locked; off by default.
            ProvideAppServices {
                AppLockHost {
                    DoorprintsRoot(
                        deepLinks = deepLinks,
                        onDeepLinkHandled = { deepLinks.value = null },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The app in front ends the Drive sync's back-off (DriveCadence).
        DriveCadenceGate.of(this).reset()
        activities.register(this, ActivityHooks(driveStarter) { deepLinks.value = it })
    }

    override fun onStop() {
        activities.unregister(this)
        super.onStop()
    }

    override fun onDestroy() {
        // Finished for good (not a rotation): a screen still waiting for its answer is closed as cancelled.
        if (isFinishing) activities.results.cancelPending()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The new intent becomes getIntent(), so its extras are the ones handle() removes, and a later recreation
        // does not see the launcher intent's (or an older notification's) extras instead.
        setIntent(intent)
        if (DriveAuthorizers.deliver(browserRedirect, intent)) return
        handle(intent)
    }

    /**
     * The launcher activity is exported, so any app can start it with extras (threat model F-25): accept only a
     * well-formed UUID and in-range coordinates; the screens then look the ids up in the local database.
     */
    private fun handle(intent: Intent?) {
        intent ?: return
        // Reopened from Recents (after process death the system replays the task's last intent, which may be an old
        // notification's): that notification was already acted on, so its extras must not run a second time.
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            parse(intent)?.let { deepLinks.value = it }
        }
        // Consumed: a later getIntent() (a recreation of this same activity) carries nothing to act on.
        DEEP_LINK_EXTRAS.forEach(intent::removeExtra)
        intent.data = null
    }

    private fun parse(intent: Intent): DeepLink? {
        // A connect link (doorprints://connect, the owner page's QR code): only a checked one; the app asks first.
        if (intent.action == Intent.ACTION_VIEW) {
            ConnectLink.parse(intent.dataString)?.let { return DeepLink.Connect(it) }
            // A backup or update file opened in Doorprints (docs/11 5.28): a document another app holds. The Import
            // screen validates it as any picked file; only a content or file reference is taken, never a link.
            return intent.data?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { DeepLink.ImportFile(it.toString()) }
        }
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            if (stream != null) {
                return stream.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { DeepLink.ImportFile(it.toString()) }
            }
            // A listing shared as text (docs/11 5.29, *Add a shared listing*): untrusted text, capped (SEC-043),
            // parsed on the device; the subject line, when the sender gives one, goes first as the listing's title.
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: return null
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() && it !in text }
            val whole = (if (subject != null) "$subject\n$text" else text).take(ListingText.MAX_CHARS)
            return DeepLink.NewHouseFromListing(whole)
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_SCREEN)?.let {
            // A fixed allow-list, never a route taken from the extra as-is.
            return if (it in Notifications.SCREENS) DeepLink.OpenScreen(it) else null
        }
        intent.getStringExtra(Notifications.EXTRA_OPEN_HOUSE)?.let {
            return if (isUuid(it)) DeepLink.OpenHouse(it, questions = intent.getBooleanExtra(Notifications.EXTRA_OPEN_QUESTIONS, false)) else null
        }
        // A Hunt mode reminder (docs/11 5.16, slice 3c): its body opens the viewing; *Start Hunt mode* without location
        // opens the Map, which asks first (5.18). Only a valid record id; the screens look it up locally.
        intent.getStringExtra(Notifications.EXTRA_OPEN_VIEWING)?.let {
            return if (RecordRules.isValidId(it)) DeepLink.OpenViewing(it) else null
        }
        intent.getStringExtra(Notifications.EXTRA_START_HUNT)?.let {
            if (!RecordRules.isValidId(it)) return null
            NotificationManagerCompat.from(this).cancel(Notifications.huntTag(it), Notifications.HUNT_REMINDER_ID)
            return DeepLink.StartHunt
        }
        // An area wake-up's *Start Hunt mode* without location (slice 4b): the same Map question.
        intent.getStringExtra(Notifications.EXTRA_START_HUNT_AREA)?.let {
            if (!RecordRules.isValidId(it)) return null
            NotificationManagerCompat.from(this).cancel(Notifications.areaTag(it), Notifications.AREA_WAKEUP_ID)
            return DeepLink.StartHunt
        }
        if (intent.hasExtra(Notifications.EXTRA_NEW_LAT)) {
            val lat = intent.getDoubleExtra(Notifications.EXTRA_NEW_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(Notifications.EXTRA_NEW_LON, Double.NaN)
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val visitId = intent.getStringExtra(Notifications.EXTRA_VISIT_ID)?.takeIf(::isUuid)
            return DeepLink.NewHouse(lat, lon, visitId)
        }
        return null
    }

    private fun isUuid(value: String): Boolean =
        value.length == 36 && runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        /** Every extra a notification can carry into this activity; all are removed once handled. */
        val DEEP_LINK_EXTRAS = listOf(
            Notifications.EXTRA_OPEN_SCREEN,
            Notifications.EXTRA_OPEN_HOUSE,
            Notifications.EXTRA_OPEN_QUESTIONS,
            Notifications.EXTRA_NEW_LAT,
            Notifications.EXTRA_NEW_LON,
            Notifications.EXTRA_VISIT_ID,
            Notifications.EXTRA_OPEN_VIEWING,
            Notifications.EXTRA_START_HUNT,
            Notifications.EXTRA_START_HUNT_AREA,
        )
    }
}
