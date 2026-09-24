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

    /** Opens the phone's dialler with [number] filled in (Android: `ACTION_DIAL` with a `tel:` URI). Nothing is dialled. */
    fun dial(number: String)

    /** Opens the web link [url] in the browser; false when no app can open it. */
    fun openUrl(url: String): Boolean
}

/**
 * The [PlatformServices] of this composition. Every root provides it (Android: `ProvidePlatformServices` in
 * MainActivity and in the screenshot tests); reading it without a provider fails loudly rather than guessing.
 */
val LocalPlatformServices = staticCompositionLocalOf<PlatformServices> {
    error("No PlatformServices: wrap the content in ProvidePlatformServices")
}
