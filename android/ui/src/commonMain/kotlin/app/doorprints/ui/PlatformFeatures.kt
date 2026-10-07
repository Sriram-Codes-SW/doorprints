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
    /** The PDF among the copies (Android draws it on `android.graphics.pdf`; the common writer has none, S4b-BL-81). */
    val pdfCopies: Boolean = true,
    /** The weekly backup to a folder, in Settings. */
    val weeklyBackup: Boolean = true,
    /**
     * Choosing the app's language inside the app. Off: Settings says the language follows the phone's settings for
     * the app and opens them ([PlatformServices.openAppSettings]).
     */
    val inAppLanguage: Boolean = true,
    /**
     * *Wake me in my hunting areas* in Settings > My areas (docs/11 "Design of slice 4b"). Android also hides it where
     * Google Play services are missing ([AreaWakeupServices.available]), the iPhone where Core Location cannot monitor
     * regions (since S4b-BL-96).
     */
    val areaWakeup: Boolean = true,
    /**
     * Android's own phone-to-phone transfer copies the whole database, saved walks included (docs/11 5.27.7), so
     * Settings > Hunt mode says so; the iPhone keeps its data out of iCloud and computer backups, so it has no such note.
     */
    val deviceTransferNote: Boolean = true,
    /**
     * Settings > Google Drive (docs/15): connect, backups, sync, deletion, devices. On since the iPhone's Drive wiring
     * (S4b-BL-117: `ASWebAuthenticationSession` and the Secure Enclave key); where the build has no Google client id the
     * card itself says Drive is not available, so the section stays in both apps and a switch off here is for a platform that
     * has no wiring at all.
     */
    val googleDrive: Boolean = true,
    /**
     * The Android emulator's name for its computer, `10.0.2.2`, may be an AI service's address over plain `http`
     * (S4b-BL-150, the one place `BaseUrlValidator`'s Android flag is true; the repository's `emulatorHostAllowed` is the
     * same switch). The iPhone has no such address.
     */
    val emulatorHost: Boolean = true,
) {
    companion object {
        /**
         * The iPhone app: since CMP-8c the map (with India's boundary rules, adding a house on it), since S4b-BL-69 Hunt
         * mode (`IosHunt`, the adapter around the common `HuntEngine`), since S4b-BL-96 the area wake-up
         * (`IosAreaWakeup`), since S4b-BL-81 *Save a copy* (every copy but the PDF) and *Import a backup* (`IosCopies`),
         * and since CMP-8b the list, the house form without new photos, Compare, the Assistant and Settings; since
         * S4b-BL-117 Google Drive (`IosDriveServices`), which says it is not available when the build has no Google client.
         */
        val Ios = PlatformFeatures(
            addPhotos = false,
            pdfCopies = false,
            weeklyBackup = false,
            inAppLanguage = false,
            deviceTransferNote = false,
            emulatorHost = false,
        )
    }
}

/** The [PlatformFeatures] of this composition; everything on unless a root provides otherwise (the iOS shell). */
val LocalPlatformFeatures = staticCompositionLocalOf { PlatformFeatures() }
