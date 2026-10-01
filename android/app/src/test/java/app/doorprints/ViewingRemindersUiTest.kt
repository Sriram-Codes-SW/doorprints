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
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.ViewingKind
import app.doorprints.ui.HuntRemindersSection
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.ViewingFormScreen
import app.doorprints.ui.ViewingRemindersSection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The reminders' screens (docs/11 5.8, slice 3b-2), in English: Settings > Viewings' *Remind me about viewings* and,
 * while exact alarms are not allowed, the 5.16 note and *Allow on-time reminders*; and the notification permission,
 * asked once when a viewing with a reminder is first saved, never before, the viewing saved whatever the answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class ViewingRemindersUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository
    private val note = "Reminders may arrive up to about 10 minutes early (or later if the phone is in battery saver)."
    private val rationale = "Doorprints reminds you with a notification before the viewing."

    @Before fun setUp() {
        val now = System.currentTimeMillis()
        runBlocking {
            repo.saveHouse(HouseEntity(id = "h1", label = "Green View", lat = 12.97, lon = 77.59, createdAt = now, updatedAt = now))
        }
    }

    @After fun tearDown() {
        ShadowAlarmManager.reset()
    }

    private fun shown(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(text: String) = compose.waitUntil(5_000) { shown(text) }

    // ---- Settings > Viewings ----

    @Test
    fun theSwitchTurnsRemindersOffAndOn() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        compose.setContent { ProvideAppServices { Column { ViewingRemindersSection() } } }
        waitFor("Remind me about viewings")
        assertTrue(runBlocking { repo.settings.viewingsRemind().first() })
        compose.onNodeWithText("Remind me about viewings").performClick()
        compose.waitUntil(5_000) { !runBlocking { repo.settings.viewingsRemind().first() } }
        compose.onNodeWithText("Remind me about viewings").performClick()
        compose.waitUntil(5_000) { runBlocking { repo.settings.viewingsRemind().first() } }
    }

    @Test
    fun theExactAlarmNoteAndButtonShowWhileNotAllowedAndOpenTheSystemPage() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.setContent { ProvideAppServices { Column { ViewingRemindersSection() } } }
        waitFor(note)
        compose.onNodeWithText("Allow on-time reminders").performClick()
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, started.action)
        assertEquals("package:" + app.packageName, started.dataString)
        // Reminders off: the note is about nothing, so it goes.
        compose.onNodeWithText("Remind me about viewings").performClick()
        compose.waitUntil(5_000) { !shown(note) }
        assertFalse(shown("Allow on-time reminders"))
    }

    @Test
    fun theExactAlarmNoteAndButtonHideWhenAllowed() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        compose.setContent { ProvideAppServices { Column { ViewingRemindersSection() } } }
        waitFor("Remind me about viewings")
        assertFalse(shown(note))
        assertFalse(shown("Allow on-time reminders"))
    }

    // ---- Settings > Hunt mode (slice 3c) ----

    @Test
    fun theHuntReminderSwitchAndLeadTimeAreKept() {
        compose.setContent { ProvideAppServices { Column { HuntRemindersSection() } } }
        waitFor("Offer Hunt mode before viewings")
        // The seven lead times, 15 chosen by default.
        for (m in listOf(5, 10, 15, 20, 30, 45, 60)) waitFor("$m min before")
        compose.onNodeWithText("15 min before").assertIsSelected()
        compose.onNodeWithText("45 min before").performClick()
        compose.waitUntil(5_000) { runBlocking { repo.settings.huntReminderMin().first() } == 45 }
        compose.onNodeWithText("Offer Hunt mode before viewings").performClick()
        compose.waitUntil(5_000) { !runBlocking { repo.settings.huntRemind().first() } }
        // Off: the lead time goes with it.
        compose.waitUntil(5_000) { !shown("45 min before") }
        compose.onNodeWithText("Offer Hunt mode before viewings").performClick()
        compose.waitUntil(5_000) { runBlocking { repo.settings.huntRemind().first() } }
    }

    @Test
    fun aViewingWithOnlyAHuntReminderAsksForNotificationsWhenSaved() {
        var done = 0
        compose.setContent { ProvideAppServices { ViewingFormScreen(null, "h1", ViewingKind.FIRST, onDone = { done++ }, nowMs = later) } }
        waitFor("House: Green View")
        pick("Reminder", "Off")
        compose.onNodeWithText("Offer Hunt mode before this viewing").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor(rationale)
        assertTrue(runBlocking { repo.viewings() }.single().huntReminder)
    }

    // ---- the permission, asked when a reminder is first saved ----

    // The form plans for the next whole hour after this: three hours ahead, so its 1-hour reminder is still to come.
    private val later = System.currentTimeMillis() + 3 * 3_600_000L

    private fun pick(menu: String, option: String) {
        compose.onNodeWithContentDescription(menu, useUnmergedTree = true).performScrollTo().performClick()
        compose.onNode(hasText(option) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    @Test
    fun theFirstSaveWithAReminderAsksOnceAndTheViewingIsSavedWhateverTheAnswer() {
        var done = 0
        compose.setContent { ProvideAppServices { ViewingFormScreen(null, "h1", ViewingKind.FIRST, onDone = { done++ }, nowMs = later) } }
        waitFor("House: Green View")
        // Not before the save (and never at start-up: nothing asks until here).
        assertFalse(shown(rationale))
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor(rationale)
        // Already saved, before any answer.
        assertEquals(1, runBlocking { repo.viewings() }.size)
        assertEquals(0, done)
        compose.onNodeWithText("Not now").performClick()
        compose.waitUntil(5_000) { done == 1 }
        compose.waitUntil(5_000) { runBlocking { repo.settings.viewingsNotificationsAsked.first() } }
        // Its own flag (S4b-BL-93f): Export and Import still ask their question.
        assertFalse(runBlocking { repo.settings.notificationsAsked.first() })
        // Asked once: the next save goes straight on.
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { done == 2 }
        assertFalse(shown(rationale))
    }

    @Test
    fun aViewingWithoutAReminderOrWithThePermissionGrantedDoesNotAsk() {
        var done = 0
        compose.setContent { ProvideAppServices { ViewingFormScreen(null, "h1", ViewingKind.FIRST, onDone = { done++ }, nowMs = later) } }
        waitFor("House: Green View")
        pick("Reminder", "Off")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { done == 1 }
        assertFalse(shown(rationale))
        assertFalse(runBlocking { repo.settings.viewingsNotificationsAsked.first() })
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        pick("Reminder", "1 hour before")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { done == 2 }
        assertFalse(shown(rationale))
    }
}
