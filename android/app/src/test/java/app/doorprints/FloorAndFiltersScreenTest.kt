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
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.CostFilter
import app.doorprints.shared.model.HouseRoom
import app.doorprints.ui.CostFilterButton
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.RoomsSection
import app.doorprints.shared.model.LengthUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S4b-BL-84, 85 and 87 on screen, in English: a room moved up by its named button, the floor typed and saved, the
 * duplicate-flat warning in the form, and the cost filters' sheet.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class FloorAndFiltersScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val at = 1_760_000_000_000

    // The form's camera file goes through FileProvider, whose cache outlives a Robolectric application (as in MovingInScreenTest).
    @Before fun clearFileProviderCache() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private fun house(id: String, label: String, lat: Double, floor: Int?) = HouseEntity(
        id = id, label = label, lat = lat, lon = 77.59, locationSource = "GPS", bedrooms = 2, floor = floor,
        createdAt = at, updatedAt = at,
    )

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aRoomMovesUpByItsNamedButtonAndTheFirstCannotGoHigher() {
        var rooms = listOf(HouseRoom("r1", "HALL", "Hall", sort = 0), HouseRoom("r2", "KITCHEN", "Kitchen", sort = 1))
        compose.setContent { ProvideAppServices { RoomsSection(rooms, LengthUnit.FT) { rooms = it.orEmpty() } } }
        compose.onNodeWithContentDescription("Move Hall up").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Move Kitchen up").performClick()
        compose.waitForIdle()
        assertEquals(listOf("r2" to 0, "r1" to 1), rooms.map { it.id to it.sort })
    }

    @Test
    fun theFloorIsSavedAndASecondHouseOnItWarnsWithoutBlocking() {
        runBlocking {
            repo.saveHouse(house("a", "Lake View 2BHK", 12.97, floor = null))
            repo.saveHouse(house("b", "Same flat, other broker", 12.97009, floor = 3))
        }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        waitFor("0 is the ground floor")
        compose.onNodeWithText("Floor").performScrollTo().performTextInput("3")
        waitFor("Maybe the same flat as Same flat, other broker")
        compose.onAllNodesWithText("Save")[1].performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse("a") }?.floor == 3 }
    }

    @Test
    fun theFiltersSheetSetsARangeAndSaysHowManyAreOn() {
        var filter = CostFilter()
        compose.setContent { ProvideAppServices { CostFilterButton(filter, { filter = it }) } }
        compose.onNodeWithText("Filters").performClick()
        waitFor("Filter by cost")
        compose.onNodeWithText("Monthly cost up to (₹)").performTextInput("25000")
        compose.waitForIdle()
        assertEquals(25_000L, filter.monthly.max)
        assertEquals(1, filter.active)
    }
}
