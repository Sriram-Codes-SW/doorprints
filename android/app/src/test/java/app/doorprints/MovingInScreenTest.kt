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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import app.doorprints.data.PhotoEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.HouseRoom
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.PhotoMeta
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.PhotoMetaDialog
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
 * Slice 5 on screen (docs/11 5.7, 5.24), in English on the real repository: choosing Taken asks *Mark the other houses
 * Not chosen?*; the Moving in card of a TAKEN house (*Start moving in*, ticking, *Add your own*, saved with the house);
 * *Close this hunt* with the number of houses, then the offer of *Save a copy*; and a photo's room, tags and caption.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class MovingInScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val at = 1_760_000_000_000

    @Before fun clearFileProviderCache() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private fun house(id: String, status: HouseStatus) =
        HouseEntity(id = id, label = "House $id", lat = 12.97, lon = 77.59, status = status, createdAt = at, updatedAt = at)

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun choosingTakenAsksAboutTheOtherHousesAndMarksThemWhenSaved() {
        runBlocking {
            repo.saveHouse(house("a", HouseStatus.SHORTLISTED))
            repo.saveHouse(house("b", HouseStatus.NEW))
            repo.saveHouse(house("c", HouseStatus.REJECTED))
        }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Taken").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Taken").performScrollTo().performClick()
        waitFor("Mark the other houses Not chosen?")
        compose.onNodeWithText("Mark them Not chosen").performClick()
        // The Moving in card is there as soon as the draft is Taken.
        compose.onNodeWithText("Moving in").performScrollTo().assertExists()
        compose.onAllNodesWithText("Save")[1].performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse("b") }?.status == HouseStatus.NOT_CHOSEN }
        assertEquals(HouseStatus.TAKEN, runBlocking { repo.getHouse("a") }!!.status)
        assertEquals(HouseStatus.REJECTED, runBlocking { repo.getHouse("c") }!!.status)
    }

    @Test
    fun theMovingInCardAddsTheDefaultsTicksAndAddsOneOfYourOwn() {
        runBlocking { repo.saveHouse(house("a", HouseStatus.TAKEN)) }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        waitFor("Start moving in")
        compose.onNodeWithText("Start moving in").performScrollTo().performClick()
        compose.onNodeWithText("0 of 6 done").performScrollTo().assertExists()
        compose.onNodeWithText("Keys received").performScrollTo().performClick()
        compose.onNodeWithText("1 of 6 done").performScrollTo().assertExists()
        compose.onNodeWithText("Add your own").performScrollTo().performTextInput("Paint the hall")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.onNodeWithText("1 of 7 done").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Remove “Police verification done”").performScrollTo().performClick()
        compose.onNodeWithText("1 of 6 done").performScrollTo().assertExists()
        compose.onAllNodesWithText("Save")[1].performClick()
        runCatching { compose.waitUntil(5_000) { runBlocking { repo.getHouse("a") }?.moveIn?.items?.size == 6 } }
            .onFailure { error("saved: " + runBlocking { repo.getHouse("a") }?.moveIn) }
        val items = runBlocking { repo.getHouse("a") }!!.moveIn!!.items!!
        assertEquals(listOf("mi_keys"), items.filter { it.isDone }.map { it.id })
        assertTrue(items.any { it.text == "Paint the hall" })
    }

    @Test
    fun closeThisHuntNamesTheNumberMarksThemAndOffersACopy() {
        runBlocking {
            repo.saveHouse(house("a", HouseStatus.TAKEN))
            repo.saveHouse(house("b", HouseStatus.NEW))
            repo.saveHouse(house("c", HouseStatus.SHORTLISTED))
            repo.saveHouse(house("d", HouseStatus.REJECTED))
        }
        var copies = 0
        compose.setContent {
            ProvideAppServices {
                HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}, onSaveCopy = { copies++ })
            }
        }
        waitFor("Close this hunt")
        compose.onNodeWithText("Close this hunt").performScrollTo().performClick()
        waitFor("2 houses will be marked Not chosen. Nothing is deleted.")
        compose.onAllNodesWithText("Close this hunt")[1].performClick()
        waitFor("Hunt closed: 2 houses marked Not chosen. Save a copy of everything?")
        assertEquals(listOf(HouseStatus.NOT_CHOSEN, HouseStatus.NOT_CHOSEN, HouseStatus.REJECTED), listOf("b", "c", "d").map { runBlocking { repo.getHouse(it) }!!.status })
        compose.onNodeWithText("Save a copy").performClick()
        compose.waitForIdle()
        assertEquals(1, copies)
    }

    @Test
    fun aHouseNotSavedAsTakenCannotCloseTheHuntYet() {
        runBlocking { repo.saveHouse(house("a", HouseStatus.NEW)) }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Taken").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Taken").performScrollTo().performClick()
        waitFor("Close this hunt")
        compose.onNodeWithText("Close this hunt").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save this house as Taken to close the hunt.").assertExists()
    }

    @Test
    fun thePhotoDetailsDialogPicksARoomTagsAndACaption() {
        val photo = PhotoEntity("p1", "a", "/x", createdAt = at)
        var saved: PhotoMeta? = null
        compose.setContent {
            ProvideAppServices {
                PhotoMetaDialog(photo, listOf(HouseRoom("r1", "KITCHEN", "Kitchen")), onDismiss = {}, onSave = { saved = it })
            }
        }
        compose.onNodeWithText("Kitchen").performClick()
        compose.onNodeWithText("Leak").performScrollTo().performClick()
        compose.onNodeWithText("Move-in").performScrollTo().performClick()
        compose.onNodeWithText("Your own tag").performScrollTo().performTextInput("damp corner")
        compose.onNodeWithText("Add tag").performScrollTo().performClick()
        compose.onNodeWithText("Caption").performScrollTo().performTextInput("Tap drips")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals(PhotoMeta("r1", listOf("LEAK", "MOVE_IN", "damp corner"), "Tap drips"), saved)
    }
}
