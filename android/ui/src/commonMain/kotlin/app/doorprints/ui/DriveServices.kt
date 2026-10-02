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

import androidx.compose.runtime.Composable
import app.doorprints.drive.connect.DriveSettingsController

/**
 * What Settings > Google Drive needs from the platform around the common state holder (S4b-BL-117, docs/15 §7 phase 4).
 * Android: `AndroidDriveServices` in `:app` (the Custom-Tab-free browser sign-in, the Keystore-sealed token, the
 * services assembled over the app's data); iOS: not wired yet (the Drive crypto has no iPhone provider until
 * S4b-BL-131), so [NoDrive].
 */
interface DriveServices {
    /**
     * The state holder, or null when this build has no Google OAuth client id (the owner supplies it at build time,
     * docs/15 §2.4): then Settings shows no Google Drive row at all and nothing about Drive is reachable.
     */
    val controller: DriveSettingsController?

    /** Copies the recovery key for a password manager, marked sensitive so the system keeps it out of previews. */
    fun copyRecoveryKey(text: String)

    /** The decrypted backup the last *Import a backup from Google Drive* staged, as a file address for the Import screen, once. */
    fun takeImportFile(): String?

    /** Opens the phone's own screen-lock settings (*Open settings* on the no-lock message). */
    fun openLockSettings()

    /** While [active] the screen is kept out of screenshots and the recent-apps preview (the recovery key is on it). */
    @Composable
    fun SecureScreen(active: Boolean)
}

/** Drive is not available on this platform or build: no row in Settings. */
object NoDrive : DriveServices {
    override val controller: DriveSettingsController? get() = null
    override fun copyRecoveryKey(text: String) {}
    override fun takeImportFile(): String? = null
    override fun openLockSettings() {}

    @Composable
    override fun SecureScreen(active: Boolean) {}
}
