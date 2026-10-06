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

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.R
import app.doorprints.screenshots.ScreenshotTestApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The device-lock notice of Settings > Google Drive in all four languages (docs/15 §10.3): the documented English words,
 * a heading only where the Drive card is replaced, and *Open settings* in both states.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class DriveLockNoticeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun str(id: Int) = compose.activity.getString(id)

    @Test
    fun withoutALockDriveCannotBeSwitchedOnAndTheWordsSayWhatToDo() {
        compose.setContent { LockNoticeBlock(LockNotice.NEEDS_LOCK) }
        compose.onNodeWithText(
            "Google Drive backup needs a screen lock on this phone (a PIN, pattern, password, fingerprint or face). Set one in the phone's settings, then come back.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Back up to Google Drive").assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
    }

    @Test
    fun aRemovedLockPausesAndSaysTheHousesAreSafe() {
        compose.setContent { LockNoticeBlock(LockNotice.PAUSED) }
        compose.onNodeWithText(
            "Google Drive backup is paused because this phone no longer has a screen lock. Your houses are safe on this phone. Set a screen lock to continue.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        // The Drive card carries its own heading: the notice adds none above it.
        assertEquals(0, compose.onAllNodesWithText("Back up to Google Drive").fetchSemanticsNodes().size)
    }

    @Test
    fun aKeyTheKeyStoreLostIsToldApartFromARemovedLock() {
        compose.setContent { LockNoticeBlock(LockNotice.KEY_LOST) }
        compose.onNodeWithText(
            "Google Drive backup is paused because this phone's key store lost the key. Your houses are safe on this phone. Connect to Google Drive again to continue.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Open settings").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithText(str(R.string.drive_lock_paused)).fetchSemanticsNodes().size)
    }

    @Test
    fun eachNoticeHasItsOwnWordsForTheScreenAndTheNotification() {
        assertEquals(R.string.drive_lock_needs, LockNotice.NEEDS_LOCK.messageRes())
        assertEquals(R.string.drive_lock_paused, LockNotice.PAUSED.messageRes())
        assertEquals(R.string.drive_lock_key_lost, LockNotice.KEY_LOST.messageRes())
        assertNotEquals(str(R.string.drive_lock_paused), str(R.string.drive_lock_key_lost))
    }

    @Test
    @Config(qualifiers = "hi")
    fun hindi() = inLanguage()

    @Test
    @Config(qualifiers = "ta")
    fun tamil() = inLanguage()

    @Test
    @Config(qualifiers = "te")
    fun telugu() = inLanguage()

    private fun inLanguage() {
        compose.setContent { LockNoticeBlock(LockNotice.NEEDS_LOCK) }
        val needs = str(R.string.drive_lock_needs)
        val open = str(R.string.drive_lock_open_settings)
        assertNotEquals("shown in its own language, not English", "Open settings", open)
        assertTrue(needs.isNotBlank())
        compose.onNodeWithText(needs).assertIsDisplayed()
        compose.onNodeWithText(open).assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithText(str(R.string.drive_lock_heading)).fetchSemanticsNodes().size)
        val lost = str(R.string.drive_lock_key_lost)
        assertNotEquals("the key store words are translated", str(R.string.drive_lock_paused), lost)
        assertTrue(lost.isNotBlank())
    }

    @Test
    @Config(qualifiers = "ta")
    fun tamilKeyLost() = keyLostShows()

    @Test
    @Config(qualifiers = "te")
    fun teluguKeyLost() = keyLostShows()

    @Test
    @Config(qualifiers = "hi")
    fun hindiKeyLost() = keyLostShows()

    private fun keyLostShows() {
        compose.setContent { LockNoticeBlock(LockNotice.KEY_LOST) }
        compose.onNodeWithText(str(R.string.drive_lock_key_lost)).assertIsDisplayed()
    }
}
