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

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.AppSettings
import app.doorprints.data.HouseEntity
import app.doorprints.data.TrackPointEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.trace.RepeatLook
import app.doorprints.ui.PathTraceSection
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings > Hunt mode's path trace (docs/11 5.27.1, 5.27.4, 5.27.5; TC-U-149, TC-U-148 UI parts), in English on the real
 * repository: the look is a radio group that saves at once and shows with the trace off; the alert is off by default,
 * disabled while the trace is off, asks for the notification permission and stays off with a sentence when it is
 * refused; *Saved walks: n* with *Delete all saved walks* behind a confirmation; *Clear the path* clears only the
 * 30-day trace.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class PathTraceSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository

    private fun settings(): AppSettings = runBlocking { repo.settings.settings.first() }

    private fun show() {
        compose.setContent {
            ProvideAppServices {
                val s by repo.settings.settings.collectAsState(AppSettings())
                Column(Modifier.verticalScroll(rememberScrollState())) { PathTraceSection(s) }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Trace my path on the map").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private val alertRow = hasText("Warn me when I walk a path again") and hasClickAction()

    @Test fun theLookIsClearByDefaultAndAChoiceIsSavedAtOnceWithTheTraceOff() {
        assertEquals(RepeatLook.CLEAR, settings().repeatLook)
        assertFalse(settings().pathTrace)
        show()
        compose.onNodeWithText("Subtle").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings().repeatLook == RepeatLook.SUBTLE }
        compose.onNodeWithText("Off").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings().repeatLook == RepeatLook.OFF }
        compose.onNodeWithText("Clear").performScrollTo().performClick()
        compose.waitUntil(5_000) { settings().repeatLook == RepeatLook.CLEAR }
    }

    @Test fun theAlertIsOffByDefaultAndDisabledWhileTheTraceIsOff() {
        assertFalse(settings().repeatAlert)
        show()
        compose.onNode(alertRow).performScrollTo().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("Turn on Trace my path first.").assertExists()
    }

    @Test fun withTheTraceOnAndNotificationsAllowedTheAlertTurnsOnAndOff() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { repo.settings.savePathTrace(true) }
        show()
        compose.onNode(alertRow).performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { settings().repeatAlert }
        compose.onNode(alertRow).assertIsOn().performClick()
        compose.waitUntil(5_000) { !settings().repeatAlert }
    }

    @Test fun refusedNotificationsLeaveTheAlertOffAndSayWhy() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // The question was asked before (and refused): the action goes on, and finds the permission missing.
        runBlocking { repo.settings.savePathTrace(true); repo.settings.setNotificationsAsked() }
        show()
        compose.onNode(alertRow).performScrollTo().performClick()
        waitFor("Notifications are off for Doorprints, so there is no sound.")
        assertFalse(settings().repeatAlert)
        compose.onNode(alertRow).assertIsOff()
    }

    @Test fun theAlertAsksWhyBeforeTheSystemQuestionWhenNotAskedYet() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { repo.settings.savePathTrace(true) }
        show()
        compose.onNode(alertRow).performScrollTo().performClick()
        waitFor("The sound is a notification")
        assertFalse(settings().repeatAlert)
    }

    @Test fun savedWalksAreCountedAndDeleteAllNeedsAConfirmation() {
        runBlocking {
            repo.saveHouse(savedHouse())
            repeat(2) { i ->
                val id = 1_000_000L * (i + 1)
                (0..5).forEach { k -> repo.saveTrackPoint(TrackPointEntity(at = id + k * 20_000L, lat = 12.97 + k * 0.0003, lon = 77.6, accuracyM = 5f, walkId = id)) }
                repo.saveWalk("h", id)
            }
        }
        show()
        waitFor("Saved walks: 2")
        compose.onNodeWithText("Delete all saved walks").performScrollTo().performClick()
        compose.onNodeWithText("Delete all 2 saved walks? This cannot be undone.").assertExists()
        // Cancel keeps them.
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, runBlocking { repo.savedWalkCount.first() })
        compose.onNodeWithText("Delete all saved walks").performScrollTo().performClick()
        compose.onNode(hasText("Delete") and hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick()
        compose.waitUntil(5_000) { runBlocking { repo.savedWalkCount.first() } == 0 }
        waitFor("Saved walks: 0")
    }

    private fun savedHouse() = HouseEntity(id = "h", label = "Green View", lat = 12.97, lon = 77.6, createdAt = 1, updatedAt = 1)

    @Test fun clearThePathClearsOnlyTheThirtyDayTraceAndSaysSo() {
        runBlocking { repo.saveTrackPoint(TrackPointEntity(at = System.currentTimeMillis(), lat = 12.97, lon = 77.64, accuracyM = 8f)) }
        show()
        compose.onNodeWithText("Clears the walks kept for 30 days. Saved walks stay until you delete them.").performScrollTo().assertExists()
        compose.onNodeWithText("Clear the path").performScrollTo().performClick()
        waitFor("The path is cleared.")
    }

    @Test fun theTransferNoteIsShownOnAndroid() {
        show()
        compose.onNodeWithText("Android's phone-to-phone transfer", substring = true).performScrollTo().assertExists()
    }
}
