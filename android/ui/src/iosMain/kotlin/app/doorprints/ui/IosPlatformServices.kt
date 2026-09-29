package app.doorprints.ui

import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * [PlatformServices] on iOS (ADR-23 CMP-8b): VoiceOver, Core Location's authorization ([iosLocationAccess]) and
 * UIKit's `openURL` for the settings, the dialler and the browser. Holds nothing, so one instance serves the process
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

    /** No notifications on iOS yet (Hunt mode, their only sender, is hidden by [PlatformFeatures.Ios]): never asked. */
    override fun canPostNotifications(): Boolean = false

    /** iOS shows "Call …?" before it dials; digits and `+` only, so the number cannot carry another URL part. */
    override fun dial(number: String) {
        val digits = number.filter { it.isDigit() || it == '+' }
        if (digits.isEmpty()) return
        NSURL.URLWithString("tel:$digits")?.let(::open)
    }

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

    private fun open(url: NSURL) {
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }

    private companion object {
        const val KEY_LOCATION_ASKED = "locationAsked"
    }
}
