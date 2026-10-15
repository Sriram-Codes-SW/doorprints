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

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import kotlinx.coroutines.flow.first
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.doorprints.BuildConfig
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.HouseListScreen
import app.doorprints.ui.LocalDevKinds
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hidden schools kind on the phone's form (kinds PR 5, S4b-BL-209; docs/06 TC-U-211), English. With the developer
 * flag on, a saved school is drawn from its kind file (its name, board, medium, grades, commute, bus and fees; no price,
 * broker, rooms or questions, but a checklist of its own since kinds PR 27) and its typed values are saved as the school's extras; a new candidate offers
 * *Save a house* and *Save a school*. With the flag off (every build but a debug build made with
 * `-Pdoorprints.devKinds=true`) nothing changes: no picker, and a school row is drawn as a house, its kind and extras
 * kept through a save. The pixels are `ScreensScreenshotTest.schoolEdit`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h2600dp-hdpi", application = ScreenshotTestApp::class)
class SchoolFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private fun open(houseId: String?, flag: Boolean, ready: String) {
        compose.setContent {
            ProvideAppServices {
                CompositionLocalProvider(LocalDevKinds provides flag) {
                    HouseEditScreen(houseId = houseId, newLat = 26.1433, newLon = 91.7898, visitId = null, onDone = {})
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(ready, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** A radio row's words are its content description (RadioRow's spoken label). */
    private fun described(text: String) = compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theDefaultBuildHasTheFlagOff() {
        assertFalse(BuildConfig.DEV_KINDS)
    }

    @Test
    fun withTheFlagOnASchoolIsDrawnFromItsKindAndItsValuesAreSavedAsItsExtras() {
        runBlocking { repo.saveHouse(SchoolSample.entity()) }
        open(SchoolSample.ID, flag = true, ready = "Pine Hill School")
        for (t in listOf("School name", "Board", "Medium of instruction", "Grades offered", "Commute (minutes)", "School bus",
            "Fees", "Fees per year (₹)", "Admission fee (₹)", "Website",
            // The school's own checklist, from kinds PR 27 (docs/03 §18.21): the eight items, by the school's words.
            "Checklist (0 = bad, 5 = great)", "Safe drop-off and pick-up", "Classrooms and playground", "Drinking water and toilets",
            "Teachers are approachable", "Class size", "Homework and daily schedule", "Sports and activities", "Safety and CCTV")) assertTrue(t, shows(t))
        for (t in listOf("Price ₹", "Rent ₹/month", "Broker", "Rooms", "Questions to ask", "Water supply", "Power backup", "BHK")) {
            assertFalse(t, shows(t))
        }
        compose.onNodeWithText("CBSE").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("State board").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Grades offered").performScrollTo().performTextReplacement("Class 1 to 12")
        compose.waitForIdle()
        compose.onAllNodesWithText("Save")[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse(SchoolSample.ID) }?.extrasOther?.get("board") == JsonPrimitive("STATE") }
        val saved = runBlocking { repo.getHouse(SchoolSample.ID) }!!
        assertEquals("school", saved.kind)
        assertEquals(JsonPrimitive("Class 1 to 12"), saved.extrasOther!!["grades"])
        assertEquals(SchoolSample.entity().extrasOther!!["fees"], saved.extrasOther!!["fees"])
        assertNull(saved.price)
    }

    /** S4b-BL-261 item 2: a number outside the kind file's range (a school's commute is 0 to 300 minutes) is not written back by the form. */
    @Test
    fun aNumberOutsideTheKindFilesRangeIsNotSavedByTheForm() {
        val outside = SchoolSample.entity().let { it.copy(extrasOther = JsonObject(it.extrasOther!! + ("commuteMinutes" to JsonPrimitive(500)))) }
        runBlocking { repo.saveHouse(outside) }
        open(SchoolSample.ID, flag = true, ready = "Pine Hill School")
        compose.onNodeWithText("Grades offered").performScrollTo().performTextReplacement("Class 1 to 8")
        compose.waitForIdle()
        compose.onAllNodesWithText("Save")[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse(SchoolSample.ID) }?.extrasOther?.get("grades") == JsonPrimitive("Class 1 to 8") }
        val saved = runBlocking { repo.getHouse(SchoolSample.ID) }!!
        assertNull("the 500 is not the person's value", saved.extrasOther!!["commuteMinutes"])
        assertEquals(SchoolSample.entity().extrasOther!!["board"], saved.extrasOther!!["board"])
    }

    @Test
    fun withTheFlagOnANewCandidateOffersTheKindsAndASchoolSavesAsOne() {
        open(null, flag = true, ready = "Save a house")
        assertTrue(described("Save a school"))
        compose.onNodeWithContentDescription("Save a school").performClick()
        compose.waitForIdle()
        assertTrue(shows("School name"))
        assertFalse(shows("Rent ₹/month"))
        compose.onNodeWithText("Commute (minutes)").performScrollTo().performTextInput("25")
        compose.waitForIdle()
        compose.onAllNodesWithText("Save")[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { repo.houses.first() }.any { it.kind == "school" } }
        val saved = runBlocking { repo.houses.first() }.single { it.kind == "school" }
        assertEquals(JsonPrimitive(25), saved.extrasOther!!["commuteMinutes"])
        assertNull(saved.priceType)
    }

    @Test
    fun withTheFlagOffANewCandidateIsAHouseWithNoPicker() {
        open(null, flag = false, ready = "Rent ₹/month")
        assertFalse(described("Save a school"))
        assertFalse(shows("What are you saving? (developer preview)"))
        assertTrue(shows("BHK"))
    }

    @Test
    fun withTheFlagOffASchoolRowIsDrawnAsAHouseAndKeepsItsKindAndExtras() {
        runBlocking { repo.saveHouse(SchoolSample.entity()) }
        open(SchoolSample.ID, flag = false, ready = "Pine Hill School")
        for (t in listOf("Name (e.g. “Blue gate, 2nd floor”)", "BHK", "Broker", "Cost")) assertTrue(t, shows(t))
        for (t in listOf("Board", "School name", "Fees per year (₹)")) assertFalse(t, shows(t))
        compose.onNodeWithText("Notes").performScrollTo().performTextReplacement("Seen.")
        compose.waitForIdle()
        compose.onAllNodesWithText("Save")[0].performScrollTo().performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse(SchoolSample.ID) }?.notes == "Seen." }
        val saved = runBlocking { repo.getHouse(SchoolSample.ID) }!!
        assertEquals("school", saved.kind)
        assertEquals(SchoolSample.entity().extrasOther, saved.extrasOther)
    }

    /** Kinds PR 6 (S4b-BL-210): the list's search reads a school's extras only while the flag draws it as a school. */
    @Test
    fun theListSearchFindsASchoolByItsBoardOnlyWithTheFlagOn() {
        runBlocking { repo.saveHouse(SchoolSample.entity()) }
        var flag by mutableStateOf(false)
        compose.setContent {
            ProvideAppServices { CompositionLocalProvider(LocalDevKinds provides flag) { HouseListScreen(onOpenHouse = {}) } }
        }
        compose.waitUntil(15_000) { shows("Pine Hill School") }
        compose.onNodeWithText("Search name, street, notes").performTextInput("cbse")
        compose.waitUntil(15_000) { !shows("Pine Hill School") }
        flag = true
        compose.waitUntil(15_000) { shows("Pine Hill School") }
    }
}
