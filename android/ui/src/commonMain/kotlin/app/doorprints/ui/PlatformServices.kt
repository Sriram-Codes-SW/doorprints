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

package app.doorprints.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the common UI asks of the platform it runs on (ADR-23 P3, the "platform seams"): one small interface, provided
 * to the composition by [LocalPlatformServices], with an Android implementation in androidMain
 * (`AndroidPlatformServices`, provided by `ProvidePlatformServices`) and, from phase 8, an iOS one.
 *
 * It holds only what the code in `:ui` needs today: the screen reader (CMP-3), and since CMP-5 the location and
 * notification permissions' state for the moved screens (the prompts themselves are [rememberLocationPermissionRequest]
 * and [rememberNotificationPermissionRequest], which need the composition). What only the app itself can do (its
 * data, the copy-import undo, Settings' backup folder and language) is [AppServices]. Since CMP-6 also the house
 * form's two hand-offs to other apps: a phone number to the dialler ([dial]) and a listing link to the browser
 * ([openUrl]). The pickers, the workers and sharing a saved copy are the app's ([AppServices.houseForm],
 * [AppServices.exportScreen], [AppServices.importScreen]).
 *
 * Read on the main thread, at the moment the answer matters: a permission or the screen reader can change while the
 * app runs (in system settings, then back), so nothing here is cached.
 */
interface PlatformServices {
    /**
     * True while a screen reader that explores by touch is on (Android: TalkBack's touch exploration; iOS: VoiceOver).
     * Read at the moment it matters (a focus move, a tip's wording), not remembered: it can change while the app runs.
     */
    fun isScreenReaderOn(): Boolean

    /** How much location the app may use now ([LocationAccess]; Android: the fine and coarse grants). */
    fun locationAccess(): LocationAccess

    /** True once any screen has shown the system's location prompt (Android: a flag in private preferences). */
    fun locationAsked(): Boolean

    /** Records that the location prompt is about to be shown; call right before launching the request. */
    fun markLocationAsked()

    /**
     * Whether the system will still show its location prompt ([canAskAgain]: never asked, or refused once). Not for
     * every recomposition: [LocationAsk] reads it once and again on resume.
     */
    fun canAskLocation(): Boolean

    /** Opens the app's page in the system settings, where a permission the system no longer asks for is turned on. */
    fun openAppSettings()

    /** True when the app may post notifications (Android: below API 33 always, from 33 with `POST_NOTIFICATIONS`). */
    fun canPostNotifications(): Boolean

    /**
     * True when a reminder can be set to the minute (Android 12+: *Alarms & reminders* allowed for the app; always on
     * older Android and on iOS). Read on resume: the person may have changed it in the system settings.
     */
    fun canScheduleExactAlarms(): Boolean = true

    /** Opens the system page where the app is allowed to set on-time reminders (Android 12+'s *Alarms & reminders*). */
    fun openExactAlarmSettings() {}

    /** Opens the phone's dialler with [number] filled in (Android: `ACTION_DIAL` with a `tel:` URI). Nothing is dialled. */
    fun dial(number: String)

    /** Opens the web link [url] in the browser; false when no app can open it. */
    fun openUrl(url: String): Boolean

    /** True where [addToCalendar] can hand an event to the phone's calendar app (Android; the iPhone shares a `.ics`). */
    val canAddToCalendar: Boolean get() = false

    /**
     * Opens the phone's calendar app on a new event filled from [event] (Android: `ACTION_INSERT` on
     * `CalendarContract.Events`, no permission: the person saves it there); false when no app can take it.
     */
    fun addToCalendar(event: CalendarEvent): Boolean = false

    /**
     * True where [shareCalendarFile] hands a viewing's `.ics` to the share sheet, from which the person adds it to a
     * calendar (the iPhone, S4b-BL-92a; docs/11 5.8). Android has [addToCalendar] instead.
     */
    val canShareCalendarFile: Boolean get() = false

    /** Shares [ics] as a file named [fileName] through the share sheet; false when it could not be written or shown. */
    fun shareCalendarFile(fileName: String, ics: String): Boolean = false

    /**
     * This phone's name on the server's owner page when it connects by code or QR code (docs/03 §12.1), such as
     * "Pixel 9 (Android app)", so the owner recognises it before approving.
     */
    fun deviceName(): String = "Doorprints app"

    /** True when the phone has a screen lock (PIN, pattern, password, passcode) the app lock can ask for (docs/11 5.19). */
    fun hasScreenLock(): Boolean = false

    /**
     * True while the screen is only being rebuilt, not left (Android: the activity recreated for a rotation or a
     * language switch), so the app lock does not count it as going to the background.
     */
    fun isRecreating(): Boolean = false

    /** Sends the app to the background, as Back does on the phone's own lock screen (Android: the task to the back). */
    fun leaveApp() {}

    /**
     * True where the system takes the app switcher's picture as the app stops being active (iOS): the app lock then
     * covers the app. False on Android, where the window itself is kept out of the recent-apps preview.
     */
    val coverWhenInactive: Boolean get() = false
}

/**
 * The [PlatformServices] of this composition. Every root provides it (Android: `ProvidePlatformServices` in
 * MainActivity and in the screenshot tests); reading it without a provider fails loudly rather than guessing.
 */
val LocalPlatformServices = staticCompositionLocalOf<PlatformServices> {
    error("No PlatformServices: wrap the content in ProvidePlatformServices")
}

/** A viewing as the phone's calendar gets it ([PlatformServices.addToCalendar]): never `withWhom` (contact data). */
data class CalendarEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val location: String? = null,
    val description: String? = null,
)
