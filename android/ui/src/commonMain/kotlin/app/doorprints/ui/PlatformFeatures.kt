package app.doorprints.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Which of the app's features the platform it runs on has (ADR-23 CMP-8b, owner decision of 2026-09-29: on iOS the
 * features it does not have yet are hidden, not shown disabled). Android has them all, so it provides nothing and gets
 * the defaults; the iOS shell provides [PlatformFeatures.Ios] until each feature comes to iPhone.
 *
 * A hidden feature leaves no entry point on screen: no button, row or card that leads to it, and no note about it
 * except the Map's, which says the map is not on this phone yet.
 */
@Immutable
data class PlatformFeatures(
    /**
     * The map view. Off: the app starts on the Houses tab; the Map tab (still in the bar) says the map is not on this
     * phone yet and points to the Houses tab; no *Add a house on the map* or *Go to the map* anywhere, and the empty
     * house list says houses come from a connected server instead; the privacy note names no map tiles or geocoder.
     */
    val map: Boolean = true,
    /** Hunt mode (the Map's Hunt card) and its settings (the alert radius and the minimum stay). */
    val huntMode: Boolean = true,
    /** Taking and picking photos on the house form. Photos a house already has are still shown. */
    val addPhotos: Boolean = true,
    /** *Save a copy* and *Import a backup*: the Settings rows and the empty house list's import button. */
    val copiesAndImports: Boolean = true,
    /** The weekly backup to a folder, in Settings. */
    val weeklyBackup: Boolean = true,
    /**
     * Choosing the app's language inside the app. Off: Settings says the language follows the phone's settings for
     * the app and opens them ([PlatformServices.openAppSettings]).
     */
    val inAppLanguage: Boolean = true,
) {
    companion object {
        /** The iPhone app of CMP-8b: the list, the house form without new photos, Compare, the Assistant, Settings. */
        val Ios = PlatformFeatures(
            map = false,
            huntMode = false,
            addPhotos = false,
            copiesAndImports = false,
            weeklyBackup = false,
            inAppLanguage = false,
        )
    }
}

/** The [PlatformFeatures] of this composition; everything on unless a root provides otherwise (the iOS shell). */
val LocalPlatformFeatures = staticCompositionLocalOf { PlatformFeatures() }
