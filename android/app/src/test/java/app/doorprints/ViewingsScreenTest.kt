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

import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.Viewing
import app.doorprints.shared.model.ViewingKind
import app.doorprints.ui.CalendarEvent
import app.doorprints.ui.Formats
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.ViewingFormScreen
import app.doorprints.ui.ViewingsHistory
import app.doorprints.ui.calendarInsertIntent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Viewings screen, the viewing form and the house card (docs/11 5.8, slice 3b-1), in English on the real repository:
 * planning, editing, cancelling and deleting a viewing, the form's validation, the history's groups, filters and search,
 * the *Missed?* buttons, *Mark viewing done* and *Book a second viewing?*, and *Add to calendar*'s intent. Controls are
 * found by the words TalkBack reads.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class ViewingsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val hour = 3_600_000L
    private val now = System.currentTimeMillis()

    @Before fun setUp() {
        // Slice 3b-2 asks for notifications when a reminder is first saved (ViewingRemindersUiTest); granted here, so
        // these tests' saves go straight on.
        shadowOf(ApplicationProvider.getApplicationContext<DoorprintsApp>()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        // The house form's camera file goes through androidx FileProvider, whose path cache outlives a test's data dir.
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        runBlocking {
            repo.saveHouse(
                HouseEntity(
                    id = "h1", label = "Green View", address = "12, MG Road", street = "MG Road", locality = "Adyar",
                    lat = 12.97, lon = 77.59, createdAt = now, updatedAt = now, checklist = mapOf("water" to 2),
                    answers = listOf(HouseAnswer("a1", text = "Is there a lift?")),
                ),
            )
            repo.saveHouse(HouseEntity(id = "h2", label = "Blue Gate", lat = 12.9, lon = 77.6, createdAt = now, updatedAt = now))
        }
    }

    private fun viewings() = runBlocking { repo.viewings() }
    private fun until(condition: (List<Viewing>) -> Boolean) = compose.waitUntil(5_000) { condition(viewings()) }
    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun pick(menu: String, option: String) {
        compose.onNodeWithContentDescription(menu, useUnmergedTree = true).performScrollTo().performClick()
        // The menu's own item, not a heading or a row with the same words.
        compose.onNode(hasText(option) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
    }

    private fun seed(vararg list: Viewing) = runBlocking { list.forEach { repo.saveViewing(it) } }

    // ---- the form ----

    @Test
    fun planningAViewingFromAHouseSavesItWithEveryField() {
        var done = false
        compose.setContent { ProvideAppServices { ViewingFormScreen(null, "h1", ViewingKind.FIRST, onDone = { done = true }) } }
        waitFor("House: Green View")
        pick("Kind", "Second viewing")
        pick("Duration", "45 min")
        pick("Reminder", "1 day before")
        compose.onNodeWithText("With whom").performScrollTo().performTextInput("Ravi")
        compose.onNodeWithText("Notes").performScrollTo().performTextInput("Bring a tape")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        until { it.size == 1 }
        val v = viewings().single()
        assertTrue(v.id, Viewing.isAppId(v.id))
        assertEquals(listOf("h1", "SECOND", "PLANNED", "45", "1440", "Ravi", "Bring a tape"),
            listOf(v.houseId, v.kind, v.status, v.durationMin.toString(), v.remindMin.toString(), v.withWhom, v.notes))
        // The Hunt reminder switch (slice 3c) is off unless turned on, and then not written; no status field.
        assertFalse(v.huntReminder)
        assertFalse(kotlinx.serialization.json.Json.encodeToString(Viewing.serializer(), v).contains("huntReminder"))
        compose.waitUntil(5_000) { done }
    }

    @Test
    fun theHuntReminderSwitchWritesHuntReminderOnlyWhenOn() {
        // Slice 3c: *Offer Hunt mode before this viewing*, off by default; on, the record keeps `huntReminder: true`.
        compose.setContent { ProvideAppServices { ViewingFormScreen(null, "h1", ViewingKind.FIRST, onDone = {}) } }
        waitFor("Offer Hunt mode before this viewing")
        compose.onNodeWithText("A notification 15 min before it starts asks whether to start Hunt mode. The time is in Settings > Hunt mode.")
            .assertExists()
        compose.onNodeWithText("Offer Hunt mode before this viewing").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        until { it.size == 1 }
        val v = viewings().single()
        assertTrue(v.huntReminder)
        assertTrue(kotlinx.serialization.json.Json.encodeToString(Viewing.serializer(), v).contains("\"huntReminder\":true"))
    }

    @Test
    fun editingAViewingKeepsItsHuntReminderAndTurningItOffRemovesIt() {
        seed(Viewing("v_00000001", "h1", now + 24 * hour, huntReminder = true))
        compose.setContent { ProvideAppServices { ViewingFormScreen("v_00000001", null, onDone = {}) } }
        waitFor("Offer Hunt mode before this viewing")
        compose.onNodeWithText("Offer Hunt mode before this viewing").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        until { !it.single().huntReminder }
    }

    @Test
    fun theFormNeedsAHouse() {
        compose.setContent { ProvideAppServices { ViewingFormScreen(null, null, ViewingKind.FIRST, onDone = {}) } }
        waitFor("House: Choose a house")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        waitFor("Choose a house.")
        assertTrue(viewings().isEmpty())
        pick("House", "Blue Gate")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        until { it.singleOrNull()?.houseId == "h2" }
    }

    @Test
    fun editingAndCancellingAViewing() {
        seed(Viewing("v_00000001", "h1", now + 24 * hour, notes = "Old"))
        compose.setContent { ProvideAppServices { ViewingFormScreen("v_00000001", null, onDone = {}) } }
        waitFor("Old")
        compose.onNodeWithText("Old").performScrollTo().performTextReplacement("New words")
        compose.onNodeWithText("Cancel viewing").performScrollTo().performClick()
        until { it.single().status == "CANCELLED" }
        assertEquals("New words", viewings().single().notes)
    }

    @Test
    fun deletingAViewingAsksFirst() {
        seed(Viewing("v_00000001", "h1", now + 24 * hour))
        var done = false
        compose.setContent { ProvideAppServices { ViewingFormScreen("v_00000001", null, onDone = { done = true }) } }
        waitFor("Delete viewing")
        compose.onNodeWithText("Delete viewing").performScrollTo().performClick()
        compose.onNodeWithText("Delete this viewing? This cannot be undone.").assertExists()
        compose.onNodeWithText("Delete").performClick()
        until { it.isEmpty() }
        compose.waitUntil(5_000) { done }
    }

    @Test
    fun addToCalendarOpensTheCalendarsNewEventWithoutWithWhom() {
        val start = now + 24 * hour
        seed(Viewing("v_00000001", "h1", start, durationMin = 45, withWhom = "Ravi Kumar", notes = "Bring a tape"))
        compose.setContent { ProvideAppServices { ViewingFormScreen("v_00000001", null, onDone = {}) } }
        waitFor("Add to calendar")
        compose.onNodeWithText("Add to calendar").performScrollTo().performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(CalendarContract.Events.CONTENT_URI, intent.data)
        assertEquals("Viewing: Green View", intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(start, intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0))
        assertEquals(start + 45 * 60_000L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
        assertEquals("12, MG Road", intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals("Bring a tape", intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertFalse(intent.extras!!.keySet().any { intent.extras!!.get(it).toString().contains("Ravi") })
    }

    @Test
    fun theCalendarIntentLeavesOutAnEmptyLocationAndDescription() {
        val intent = calendarInsertIntent(CalendarEvent("Viewing: A", 1, 2))
        assertNull(intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertNull(intent.getStringExtra(CalendarContract.Events.DESCRIPTION))
        assertEquals(2L, intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME, 0))
    }

    // ---- the history ----

    private var planned: Pair<String?, ViewingKind>? = null
    private var opened: String? = null

    private fun history(houseId: String? = null) {
        compose.setContent {
            ProvideAppServices {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ViewingsHistory(houseId, onOpenViewing = { opened = it }, onPlan = { h, k -> planned = h to k }, nowMs = now)
                }
            }
        }
    }

    @Test
    fun anEmptyHistorySaysHowToPlanOne() {
        history()
        waitFor("No viewings yet. Plan one from a house.")
        compose.onNodeWithText("Plan a viewing").performClick()
        assertEquals(null to ViewingKind.FIRST, planned)
    }

    @Test
    fun theHistoryGroupsFiltersAndSearches() {
        seed(
            Viewing("v_up", "h1", now + 2 * hour, withWhom = "Ravi Kumar"),
            Viewing("v_done", "h2", now - 48 * hour, kind = "SECOND", status = "DONE", notes = "Terrace leaks"),
            Viewing("v_gone", "gone", now + 5 * hour),
        )
        history()
        waitFor("Upcoming")
        compose.onNodeWithText("Done").assertExists()
        compose.onNodeWithText("A house that is gone").assertExists()
        // The search: with whom, then the notes, then the house's locality.
        compose.onNodeWithText("Search viewings").performTextInput("ravi")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Blue Gate").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Green View").assertExists()
        compose.onNodeWithText("ravi").performTextReplacement("terrace")
        waitFor("Blue Gate")
        compose.onNodeWithText("terrace").performTextReplacement("adyar")
        waitFor("Green View")
        compose.onNodeWithText("adyar").performTextReplacement("nothing like it")
        waitFor("No viewings match.")
        compose.onNodeWithText("Clear filters").performScrollTo().performClick()
        waitFor("Blue Gate")
        // The kind and status filters.
        pick("Kind", "Second viewing")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Green View").fetchSemanticsNodes().isEmpty() }
        pick("Kind", "Any")
        pick("Status", "Done")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A house that is gone").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Blue Gate").assertExists()
        // A tap opens the form.
        compose.onNodeWithContentDescription("Open the viewing: Blue Gate, ${Formats.dateTime(now - 48 * hour, "en")}").performScrollTo().performClick()
        assertEquals("v_done", opened)
    }

    @Test
    fun oneHousesHistoryShowsOnlyItsViewings() {
        seed(Viewing("v_1", "h1", now + hour), Viewing("v_2", "h2", now + hour))
        history("h1")
        waitFor("Viewings of Green View")
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Open the viewing: Green View, ${Formats.dateTime(now + hour, "en")}").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(compose.onAllNodesWithText("Blue Gate").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Plan a viewing").performClick()
        assertEquals("h1" to ViewingKind.FIRST, planned)
    }

    @Test
    fun aMissedViewingOffersItHappenedAndCancelThenTheSecondViewing() {
        val past = now - 10 * hour
        seed(Viewing("v_miss", "h1", past), Viewing("v_miss2", "h2", past - hour))
        history()
        waitFor("Missed?")
        val whenText = Formats.dateTime(past - hour, "en")
        compose.onNodeWithContentDescription("Cancel the viewing: Blue Gate, $whenText", useUnmergedTree = true).performScrollTo().performClick()
        until { v -> v.first { it.id == "v_miss2" }.status == "CANCELLED" }
        compose.onNodeWithContentDescription("It happened: Green View, ${Formats.dateTime(past, "en")}", useUnmergedTree = true)
            .performScrollTo().performClick()
        until { v -> v.first { it.id == "v_miss" }.status == "DONE" }
        // Book a second viewing? with the re-check list: the open question and the water scored 2.
        waitFor("Book a second viewing?")
        compose.onNodeWithText("Questions still open: 1").assertExists()
        compose.onNodeWithText("Scored 2 or less: Water supply").assertExists()
        compose.onNodeWithText("Book a second viewing").performClick()
        assertEquals("h1" to ViewingKind.SECOND, planned)
    }

    // ---- the house card ----

    @Test
    fun theHouseCardShowsTheNextViewingAndMarksItDoneWithTheVisit() {
        runBlocking {
            repo.saveVisit(VisitEntity("visit-1", "h1", 12.97, 77.59, arrivedAt = now - hour, updatedAt = now))
        }
        seed(Viewing("v_next", "h1", now + 30 * 60_000L))
        var plan: Pair<String, ViewingKind>? = null
        var all: String? = null
        compose.setContent {
            ProvideAppServices {
                HouseEditScreen(
                    houseId = "h1", newLat = null, newLon = null, visitId = null, onDone = {},
                    onPlanViewing = { h, k -> plan = h to k }, onOpenViewings = { all = it },
                )
            }
        }
        waitFor("Mark viewing done")
        compose.onNodeWithText("Next: ${Formats.dateTime(now + 30 * 60_000L, "en")}, First viewing").performScrollTo().assertExists()
        compose.onNodeWithText("All viewings of this house").performScrollTo().performClick()
        assertEquals("h1", all)
        compose.onNodeWithText("Mark viewing done").performScrollTo().performClick()
        until { v -> v.single().let { it.status == "DONE" && it.visitId == "visit-1" } }
        waitFor("Book a second viewing?")
        compose.onNodeWithText("Not now").performClick()
        waitFor("No viewing planned")
        compose.onAllNodesWithText("Plan a viewing")[0].performScrollTo().performClick()
        assertEquals("h1" to ViewingKind.FIRST, plan)
    }
}
