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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.Area
import app.doorprints.shared.model.AreaNote
import app.doorprints.shared.model.Place
import app.doorprints.ui.AreaFormScreen
import app.doorprints.ui.AreasEditor
import app.doorprints.ui.CompareScreen
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.HouseListScreen
import app.doorprints.ui.PlaceFormScreen
import app.doorprints.ui.PlacesList
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * My areas, My places and the house page's Area notes and Distances (docs/11 slice 4a), in English on the real
 * repository: adding an area with its radius and the *Wake me here* switch, a place, the caps' messages, the notes list
 * with its filter and delete, adding a note for the street or an area from a house, the distances, Compare's row per
 * place and the list's search by a note's text. Controls are found by the words TalkBack reads.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class AreasScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val now = System.currentTimeMillis()
    private val adyar = Area("a_1f2e3d4c", "Adyar", 13.0067, 80.2574, 500)

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        runBlocking {
            repo.saveHouse(
                HouseEntity(
                    id = "h1", label = "Green View", street = "MG Road", lat = 13.006, lon = 80.2574, locationSource = "GPS",
                    createdAt = now, updatedAt = now,
                ),
            )
            repo.saveHouse(HouseEntity(id = "h2", label = "Blue Gate", lat = 12.9, lon = 77.6, createdAt = now + 1, updatedAt = now + 1))
        }
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun addingAnAreaSavesTheNamePointRadiusAndTheWakeSwitch() {
        var done = false
        compose.setContent { ProvideAppServices { AreaFormScreen(null, onDone = { done = true }) } }
        waitFor("Radius: 500 m")
        compose.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Adyar")
        compose.onNodeWithText("Latitude").performScrollTo().performTextInput("13.0067")
        compose.onNodeWithText("Longitude").performScrollTo().performTextInput("80.2574")
        compose.onNodeWithContentDescription("Radius").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1234f) }
        waitFor("Radius: 1200 m")
        compose.onNodeWithText("Used by Wake me in my hunting areas.").assertExists()
        compose.onNodeWithText("Wake me here").performScrollTo().performClick()
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { done }
        val area = runBlocking { repo.areas() }.single()
        assertEquals(listOf("Adyar", "13.0067", "80.2574", "1200", "false"), listOf(area.name, area.lat.toString(), area.lon.toString(), area.radiusM.toString(), area.enabled.toString()))
        assertTrue(area.id, Regex("a_[0-9a-f]{8}").matches(area.id))
    }

    @Test
    fun theListShowsTheRadiusAndAtTwentyAreasSaysSo() {
        runBlocking { for (i in 0 until Area.MAX_AREAS) repo.saveArea(adyar.copy(id = "a_" + i.toString(16).padStart(8, '0'), name = "Area $i", enabled = i != 0)) }
        compose.setContent { ProvideAppServices { AreasEditor(onOpenArea = {}) } }
        waitFor("At most 20 areas")
        compose.onNodeWithText("Add area").assertIsNotEnabled()
        compose.onNodeWithText("500 m · Wake-up off").assertExists()
    }

    @Test
    fun addingAPlaceSavesIt() {
        var done = false
        compose.setContent { ProvideAppServices { PlaceFormScreen(null, onDone = { done = true }) } }
        waitFor("Use my current location")
        compose.onNodeWithText("Name").performTextInput("Office")
        compose.onNodeWithText("Latitude").performScrollTo().performTextInput("13.0827")
        compose.onNodeWithText("Longitude").performScrollTo().performTextInput("80.2707")
        compose.onNodeWithText("Save").performScrollTo().performClick()
        compose.waitUntil(5_000) { done }
        assertEquals(listOf("Office"), runBlocking { repo.places() }.map { it.name })
    }

    @Test
    fun atTenPlacesTheListSaysSo() {
        runBlocking { for (i in 0 until Place.MAX_PLACES) repo.savePlace(Place("p_" + i.toString(16).padStart(8, '0'), "P$i", 1.0, 1.0)) }
        compose.setContent { ProvideAppServices { PlacesList(onOpenPlace = {}) } }
        waitFor("At most 10 places")
        compose.onNodeWithText("Add place").assertIsNotEnabled()
    }

    @Test
    fun theNotesListFiltersByAreaOrStreetAndDeletesAfterAsking() {
        runBlocking {
            repo.saveArea(adyar)
            repo.saveAreaNote(AreaNote("n_00000001", areaId = adyar.id, text = "Water tanker every morning"))
            repo.saveAreaNote(AreaNote("n_00000002", street = "MG Road", text = "Noisy after 9 pm"))
            repo.saveAreaNote(AreaNote("n_00000003", areaId = "a_gone0000", text = "From a deleted area"))
        }
        compose.setContent { ProvideAppServices { AreasEditor(onOpenArea = {}) } }
        waitFor("Noisy after 9 pm")
        compose.onNodeWithText("An area that is gone").assertExists()
        compose.onNodeWithContentDescription("Show notes for: All notes").performClick()
        compose.onNode(hasText("Street: MG Road") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Water tanker every morning").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Noisy after 9 pm").assertExists()
        compose.onNodeWithContentDescription("Note: Noisy after 9 pm").performClick()
        compose.onNode(hasText("Delete note") and hasAnyAncestor(isPopup())).performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { runBlocking { repo.areaNotes() }.none { it.id == "n_00000002" } }
    }

    @Test
    fun theHousePageShowsTheNotesThatReachItAddsOneForItsStreetAndShowsTheDistances() {
        runBlocking {
            repo.saveArea(adyar)
            repo.saveAreaNote(AreaNote("n_00000001", areaId = adyar.id, text = "Water tanker every morning"))
            repo.savePlace(Place("p_0a1b2c3d", "Office", 13.0827, 80.2707))
        }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "h1", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        waitFor("Water tanker every morning")
        compose.onNodeWithText("Area: Adyar").performScrollTo().assertExists()
        compose.onNodeWithText("Distances").performScrollTo().assertExists()
        compose.onNodeWithText("8.6 km · about 141 min on foot", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("Add a note for this street").performScrollTo().performClick()
        compose.onNodeWithText("Street: MG Road").assertExists()
        compose.onNode(hasText("Note") and hasAnyAncestor(isDialog())).performTextInput("Floods in the monsoon")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { runBlocking { repo.areaNotes() }.any { it.street == "MG Road" && it.text == "Floods in the monsoon" } }
        waitFor("Floods in the monsoon")
        // For an area: the area this house is in comes first and is marked.
        compose.onNodeWithText("Add a note for an area").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Area: Adyar (this house is in it)").assertExists()
    }

    @Test
    fun aHouseWithoutAStreetOffersNoStreetNoteAndWithoutAPointNoDistances() {
        runBlocking {
            repo.savePlace(Place("p_0a1b2c3d", "Office", 13.0827, 80.2707))
            repo.saveHouse(HouseEntity(id = "h3", label = "No point", lat = 0.0, lon = 0.0, createdAt = now, updatedAt = now))
        }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "h3", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        waitFor("Add a note for an area")
        assertTrue(compose.onAllNodesWithText("Add a note for this street").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Distances").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("No area notes reach this house yet.").performScrollTo().assertExists()
    }

    @Test
    fun theListSearchFindsAHouseByTheTextOfANoteThatReachesIt() {
        runBlocking { repo.saveAreaNote(AreaNote("n_00000002", street = "mg road", text = "Floods in the monsoon")) }
        compose.setContent { ProvideAppServices { HouseListScreen(onOpenHouse = {}) } }
        waitFor("Blue Gate")
        compose.onNodeWithText("Search name, street, notes").performTextInput("monsoon")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Blue Gate").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Green View", substring = true).assertExists()
    }

    @Test
    fun compareHasOneRowPerPlace() {
        runBlocking { repo.savePlace(Place("p_0a1b2c3d", "Office", 13.0827, 80.2707)) }
        val houses = runBlocking { repo.houseSnapshot() }
        compose.setContent {
            ProvideAppServices { CompareScreen(houses, emptyList(), onOpenHouse = {}, places = runBlocking { repo.places() }) }
        }
        // One TalkBack item per row: the place, then each house's km.
        val row = "Office: Blue Gate, 290.1 km; Green View, 8.6 km"
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(row)).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
