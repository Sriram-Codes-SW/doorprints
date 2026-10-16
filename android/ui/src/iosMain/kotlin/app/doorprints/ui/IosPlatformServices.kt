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

import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * [PlatformServices] on iOS (ADR-23 CMP-8b): VoiceOver, Core Location's authorization ([iosLocationAccess]),
 * UIKit's `openURL` for the settings, the dialler and the browser, and the share sheet for a viewing's calendar file. Holds nothing, so one instance serves the process
 * ([MainViewController]). Called on the main thread, as UIKit needs.
 */
class IosPlatformServices : PlatformServices {
    override fun isScreenReaderOn(): Boolean = UIAccessibilityIsVoiceOverRunning()

    override fun locationAccess(): LocationAccess = iosLocationAccess()

    /** A flag in the app's own `NSUserDefaults`, as Android keeps one in private preferences. */
    override fun locationAsked(): Boolean = NSUserDefaults.standardUserDefaults.boolForKey(KEY_LOCATION_ASKED)

    override fun markLocationAsked() = NSUserDefaults.standardUserDefaults.setBool(true, forKey = KEY_LOCATION_ASKED)

    /** iOS shows its prompt only once, before the first answer; afterwards only the app's settings can change it. */
    override fun canAskLocation(): Boolean = iosCanAskLocation()

    /** The app's page in the Settings app (location, and the language iOS keeps per app). */
    override fun openAppSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let(::open)
    }

    /** Whether Hunt mode's alerts may be posted ([IosNotifications]; asked in context when Hunt mode is turned on). */
    override fun canPostNotifications(): Boolean = IosNotifications.canPost()

    /** iOS shows "Call …?" before it dials; digits and `+` only, so the number cannot carry another URL part. */
    override fun dial(number: String) {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return
        NSURL.URLWithString("tel:$digits")?.let(::open)
    }

    /** "iPhone (Doorprints app)" or "iPad (Doorprints app)": the model, as iOS names it without extra permission. */
    override fun deviceName(): String = "${platform.UIKit.UIDevice.currentDevice.model} (Doorprints app)"

    /**
     * Web links only (http, https): the house's listing link is typed or pasted by the user. False when the text is not
     * such a URL; otherwise true once the link is handed to iOS. `openURL` answers later, in its completion handler, so
     * a refusal then cannot be reported here. No `canOpenURL` check first: the scheme is already checked, and http and
     * https always have a browser (Safari, or the one the user chose).
     */
    override fun openUrl(url: String): Boolean {
        val link = NSURL.URLWithString(url.trim()) ?: return false
        val scheme = link.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        open(link)
        return true
    }

    override fun hasScreenLock(): Boolean = iosHasScreenLock()

    /** A viewing's `.ics` through the share sheet (S4b-BL-92a), where the person adds it to Calendar. */
    override val canShareCalendarFile: Boolean get() = true

    /** Written into `tmp/copies/calendar/` (files older than a day go first), then shared. */
    override fun shareCalendarFile(fileName: String, ics: String): Boolean {
        val folder = IosCopyFolders.calendar
        IosCopyFolders.sweep(folder, DAY_MS)
        val path = writeTextFile(folder, fileName.substringAfterLast('/'), ics) ?: return false
        return IosSheets.share(NSURL.fileURLWithPath(path))
    }

    /** iOS takes the app switcher's picture as the app resigns active; the app lock covers the app first. */
    override val coverWhenInactive: Boolean get() = true

    private fun open(url: NSURL) {
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }

    private companion object {
        const val KEY_LOCATION_ASKED = "locationAsked"
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
