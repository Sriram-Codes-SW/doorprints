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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.data.SaveWalkResult
import app.doorprints.data.TrackPointEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.Formats
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.SavedWalksCard
import app.doorprints.ui.ShowOnMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The house page's *Saved walks* card (docs/11 5.27.6; TC-U-150 UI part), in English on the real repository. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class SavedWalksCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val m = 0.000008993216
    private val t0 = System.currentTimeMillis() - 20 * 3_600_000L

    @Before fun setUp() = runBlocking {
        // The house form's camera file goes through androidx FileProvider, whose path cache outlives a test's data dir.
        runCatching {
            androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        ShowOnMap.consume()
        repo.saveHouse(HouseEntity(id = "h", label = "Green View", lat = 12.97, lon = 77.6, createdAt = 1, updatedAt = 1))
    }

    @After fun clear() = ShowOnMap.consume()

    private fun savedWalk(startMs: Long): String = runBlocking {
        (0..5).forEach { k ->
            repo.saveTrackPoint(TrackPointEntity(at = startMs + k * 60_000L, lat = 12.97 + k * 60 * m, lon = 77.6, accuracyM = 5f, walkId = startMs))
        }
        (repo.saveWalk("h", startMs) as SaveWalkResult.Saved).id
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun anEmptyCardSaysHowToGetAWalkHereAndThatWalksStayOnThePhone() {
        compose.setContent { ProvideAppServices { SavedWalksCard("h") {} } }
        compose.onNodeWithText("Saved walks").assertExists()
        waitFor("No saved walks. When you finish a walk, you can link it to this house.")
        compose.onNodeWithText("Saved walks stay on this phone only. They are not in a backup, a copy, Google Drive or on a server.").assertExists()
    }

    @Test fun eachWalkIsARowWithItsDateDistanceAndMinutes() {
        savedWalk(t0 + 1_000_000)
        compose.setContent { ProvideAppServices { SavedWalksCard("h") {} } }
        waitFor("300 m, 5 min")
        compose.onNodeWithText(Formats.date(t0 + 1_000_000) + ", 300 m, 5 min").assertExists()
    }

    @Test fun showOnMapHandsTheWalkToTheMapAndOpensIt() {
        savedWalk(t0 + 1_000_000)
        var opened = 0
        compose.setContent { ProvideAppServices { SavedWalksCard("h") { opened++ } } }
        waitFor("300 m")
        compose.onNodeWithText("Show on map").performClick()
        compose.waitUntil(5_000) { opened == 1 }
        val focus = checkNotNull(ShowOnMap.pending.value)
        assertEquals(3, focus.seconds)
        assertEquals(6, focus.overlay.points.size)
        assertNull("a walk is outlined, with no ring at a place", focus.overlay.lat)
    }

    @Test fun deleteWalkAsksFirstAndThenRemovesIt() {
        savedWalk(t0 + 1_000_000)
        compose.setContent { ProvideAppServices { SavedWalksCard("h") {} } }
        waitFor("300 m")
        compose.onNodeWithText("Delete walk").performClick()
        compose.onNodeWithText("Delete this saved walk?").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, runBlocking { repo.savedWalksOf("h").first().size })
        compose.onNodeWithText("Delete walk").performClick()
        compose.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { runBlocking { repo.savedWalksOf("h").first().isEmpty() } }
    }

    @Test fun newestWalkFirst() {
        savedWalk(t0 + 1_000_000)
        savedWalk(t0 + 9_000_000)
        compose.setContent { ProvideAppServices { SavedWalksCard("h") {} } }
        waitFor("300 m")
        val rows = runBlocking { repo.savedWalksOf("h").first() }
        assertEquals(listOf(t0 + 9_000_000, t0 + 1_000_000), rows.map { it.startedAt })
    }

    @Test fun theHousePageShowsTheCardForAnExistingHouse() {
        compose.setContent { ProvideAppServices { HouseEditScreen("h", null, null, null, onDone = {}) } }
        waitFor("Green View")
        compose.onNodeWithText("Saved walks").performScrollTo().assertExists()
    }

    @Test fun aNewHouseHasNoSavedWalksCard() {
        compose.setContent { ProvideAppServices { HouseEditScreen(null, 12.97, 77.6, null, onDone = {}) } }
        waitFor("Location")
        assertTrue(compose.onAllNodesWithText("Saved walks").fetchSemanticsNodes().isEmpty())
    }

    @Test fun theDeleteConfirmationSaysItsSavedWalksGoToo() {
        compose.setContent { ProvideAppServices { HouseEditScreen("h", null, null, null, onDone = {}) } }
        waitFor("Green View")
        compose.onNodeWithContentDescription("Delete house").performClick()
        compose.onNodeWithText("Its saved walks are deleted too.").assertExists()
        compose.onNodeWithText("Its notes, contact details and photos are removed.", substring = true).assertExists()
    }
}
