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
import androidx.compose.ui.test.assertCountEquals
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
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.Scoring
import app.doorprints.ui.CriteriaScreen
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.HouseListScreen
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
 * The Criteria screen and what it changes elsewhere (docs/11 5.4, slice 2), in English on the real repository: the
 * weight, a must-have and its minimum, moving, archiving and bringing back, adding, the cap of 40, the rating share and
 * *Reset to defaults*; the house form's coverage line and missed must-have; the list's "Must-have missed" chip and
 * "best first" order. Every control is found by the label that names its criterion, as TalkBack reads it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class CriteriaScreenTest {
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

    private fun scoring(): Scoring = runBlocking { repo.scoring() }

    /** Waits until the repository says [condition]: the screen saves on Room's own threads. */
    private fun until(condition: (Scoring) -> Boolean) = compose.waitUntil(5_000) { condition(scoring()) }

    private fun show() {
        compose.setContent { ProvideAppServices { CriteriaScreen(onBack = {}) } }
        // Nothing but the bar until Room answers.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Your star rating").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Clicks the control [description] names, once the screen shows it (the repository answers after the write). */
    private fun click(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).performScrollTo().performClick()
    }

    @Test
    fun theWeightAMustHaveAndItsMinimumAreSavedAtOnce() {
        show()
        click("Water supply: High")
        until { it["water"]!!.weight == 3 }
        compose.onAllNodesWithText("Must-have")[0].performScrollTo().performClick()
        until { it["water"]!!.mustHave }
        click("Water supply: at least 4")
        until { it["water"]!!.minScore == 4 }
        // Only water has a record.
        assertEquals(listOf("water"), runBlocking { repo.localRows() }.criteria.map { it.key })
    }

    @Test
    fun movingArchivingAndBringingBack() {
        show()
        click("Move Water supply down")
        until { s -> s.criteria.take(2).map { it.key } == listOf("power", "water") }
        click("Archive Parking")
        until { it["parking"]!!.archived }
        click("Bring back Parking")
        until { !it["parking"]!!.archived }
        // Back at the end of the list.
        assertEquals("parking", scoring().criteria.last().key)
    }

    @Test
    fun addingACriterionAndTheCapOfForty() {
        show()
        compose.onNodeWithText("Name of the new criterion").performScrollTo().performTextInput("Lift")
        compose.onNodeWithText("Add criterion").performScrollTo().performClick()
        until { s -> s.criteria.any { it.label == "Lift" } }
        runBlocking { repeat(29) { repo.addCriterion("Custom $it") } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("At most 40 criteria").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add criterion").performScrollTo().assertIsNotEnabled()
        assertEquals(40, scoring().criteria.size)
    }

    @Test
    fun theRatingShareAndResetToDefaults() {
        show()
        compose.onNodeWithText("75%").performScrollTo().performClick()
        until { it.ratingShare == 0.75 }
        click("Water supply: Ignore")
        until { it["water"]!!.weight == 0 }
        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        compose.onNodeWithText("Reset").performClick()
        until { it == Scoring.DEFAULT }
        assertTrue(runBlocking { repo.localRows() }.criteria.isEmpty())
    }

    @Test
    fun aCustomCriterionIsDeletedFromTheScreenWhenNoHouseHasAScoreForIt() {
        val key = runBlocking { repo.addCriterion("Lift") }
        show()
        click("Delete Lift")
        until { it[key] == null }
        // A tombstone, so the key is never drawn again and a sync removes it elsewhere too.
        assertTrue(runBlocking { repo.localVersions() }.criteria.containsKey(key))
        assertTrue(runBlocking { repo.localRows() }.criteria.none { it.key == key })
    }

    @Test
    fun theHouseFormShowsTheCoverageAndAMissedMustHave() {
        runBlocking {
            repo.saveCriterion(scoring()["security"]!!.copy(mustHave = true, minScore = 4))
            repo.saveHouse(HouseEntity(id = "a", label = "Green View", lat = 12.97, lon = 77.59, checklist = mapOf("water" to 5, "security" to 2), createdAt = at, updatedAt = at))
        }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Scored 2 of 10 that matter").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Must-have missed: Safety and security").assertExists()
    }

    @Test
    fun aChecklistScoreIsSetClearedByTheSameOptionAgainAndByTheDash() {
        runBlocking { repo.saveHouse(HouseEntity(id = "a", label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at)) }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Water supply · not rated").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Scored", substring = true).assertCountEquals(0)
        fun tap(description: String) {
            compose.onNodeWithContentDescription(description, useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
        }
        // A score is set, and the coverage line counts it.
        tap("Water supply: 4 out of 5")
        compose.onNodeWithText("Water supply · 4 out of 5").assertExists()
        compose.onNodeWithText("Scored 1 of 10 that matter").assertExists()
        // The chosen option again clears it.
        tap("Water supply: 4 out of 5")
        compose.onNodeWithText("Water supply · not rated").assertExists()
        compose.onAllNodesWithText("Scored", substring = true).assertCountEquals(0)
        // Zero is a score; "–" clears it.
        tap("Water supply: 0 out of 5")
        compose.onNodeWithText("Water supply · 0 out of 5").assertExists()
        compose.onNodeWithText("Scored 1 of 10 that matter").assertExists()
        tap("Water supply: not rated")
        compose.onNodeWithText("Water supply · not rated").assertExists()
        compose.onAllNodesWithText("Scored", substring = true).assertCountEquals(0)
    }

    @Test
    fun theListMarksAMissedMustHaveAndRanksItLast() {
        runBlocking {
            repo.saveCriterion(scoring()["security"]!!.copy(mustHave = true, minScore = 4))
            repo.saveHouse(HouseEntity(id = "a", label = "Best but unsafe", lat = 12.97, lon = 77.59, checklist = mapOf("water" to 5, "security" to 2), createdAt = at, updatedAt = at))
            repo.saveHouse(HouseEntity(id = "b", label = "Plain", lat = 12.97, lon = 77.59, checklist = mapOf("water" to 2), createdAt = at + 1, updatedAt = at + 1))
        }
        compose.setContent { ProvideAppServices { HouseListScreen(onOpenHouse = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Must-have missed").fetchSemanticsNodes().isNotEmpty() }
        // Sorted "Best score": the house that misses a must-have goes after the other, though its score is higher.
        compose.onNodeWithText("Sort: Recent").performClick()
        compose.onNodeWithText("Best score").performClick()
        compose.waitForIdle()
        fun top(label: String) = compose.onNodeWithText(label, substring = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(top("Plain") < top("Best but unsafe"))
    }
}
