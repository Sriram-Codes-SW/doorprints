package app.doorprints.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the common UI asks of the platform it runs on (ADR-23 P3, the "platform seams"): one small interface, provided
 * to the composition by [LocalPlatformServices], with an Android implementation in androidMain
 * (`AndroidPlatformServices`, provided by `ProvidePlatformServices`) and, from phase 8, an iOS one.
 *
 * It holds only what the code in `:ui` needs today. Planned members, added by the phase that moves their first user:
 *  - announce a message for accessibility, share text and open a URL (CMP-5 and CMP-6: the Assistant, the house form's
 *    listing link, Export's *Share this file*);
 *  - pickers (photo, camera, file; CMP-6), the location permission's state and request (CMP-5), a worker's progress
 *    (CMP-6's export and import behind an interface).
 */
interface PlatformServices {
    /**
     * True while a screen reader that explores by touch is on (Android: TalkBack's touch exploration; iOS: VoiceOver).
     * Read at the moment it matters (a focus move, a tip's wording), not remembered: it can change while the app runs.
     */
    fun isScreenReaderOn(): Boolean
}

/**
 * The [PlatformServices] of this composition. Every root provides it (Android: `ProvidePlatformServices` in
 * MainActivity and in the screenshot tests); reading it without a provider fails loudly rather than guessing.
 */
val LocalPlatformServices = staticCompositionLocalOf<PlatformServices> {
    error("No PlatformServices: wrap the content in ProvidePlatformServices")
}
