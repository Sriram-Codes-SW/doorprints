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
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.data.SaveWalkResult
import app.doorprints.data.TrackPointEntity
import app.doorprints.location.HuntState
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.MapScreen
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.WalkAnswer
import app.doorprints.ui.WalkEndAnswers
import app.doorprints.ui.WalkEndSheetContent
import app.doorprints.ui.WalkSummary
import app.doorprints.ui.walkToAskAbout
import app.doorprints.shared.trace.TracePoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Ending a walk (docs/11 5.27.6; TC-U-148, TC-U-150 UI parts), in English on the real repository: *Save this walk?* asked
 * once, when the Map opens or a walk ends, never for the walk being recorded; the three answers and their effects on the
 * two stores and the watermark; the house picker's preselection; *Finish walk* on the Hunt card reaches the service.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class WalkEndFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository
    private val m = 0.000008993216

    /** Walks are in the last day: the 30-day trace is read from the real clock. */
    private val t0 = System.currentTimeMillis() - 20 * 3_600_000L

    @Before fun setUp() = runBlocking {
        repo.saveHouse(HouseEntity(id = "near", label = "Green View", lat = 12.97 + 310 * m, lon = 77.6, createdAt = 1, updatedAt = 1))
        repo.saveHouse(HouseEntity(id = "other", label = "Lake Road", lat = 12.9, lon = 77.7, createdAt = 1, updatedAt = 1))
        HuntState.update { HuntState.State() }
    }

    @After fun clear() = HuntState.update { HuntState.State() }

    /** A walk of [points] fixes 60 m apart going north from [startMs]; its id is [startMs]. */
    private fun walk(startMs: Long, points: Int = 6) = runBlocking {
        (0 until points).forEach { k ->
            repo.saveTrackPoint(TrackPointEntity(at = startMs + k * 60_000L, lat = 12.97 + k * 60 * m, lon = 77.6, accuracyM = 5f, walkId = startMs))
        }
        startMs
    }

    private fun stored(walkId: Long) = runBlocking { repo.trackPoints.first().count { it.walkId == walkId } }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    // ---- the answers ----

    @Test fun keepOnlyMovesTheWatermarkSoTheWalkIsAskedOnce() = runBlocking {
        val id = walk(t0 + 1_000_000)
        assertEquals(id, walkToAskAbout(repo, liveWalkId = 0)!!.walkId)
        WalkEndAnswers.keep(repo, id)
        assertNull(walkToAskAbout(repo, 0))
        assertEquals("the points stay for the 30-day prune", 6, stored(id))
    }

    @Test fun deleteRemovesThePointsAndMovesTheWatermark() = runBlocking {
        val id = walk(t0 + 1_000_000)
        WalkEndAnswers.delete(repo, id)
        assertEquals(0, stored(id))
        assertNull(walkToAskAbout(repo, 0))
    }

    @Test fun saveMovesTheWalkIntoTheHouseAndMovesTheWatermark() = runBlocking {
        val id = walk(t0 + 1_000_000)
        assertEquals(WalkAnswer.Done, WalkEndAnswers.save(repo, id, "near"))
        assertEquals(0, stored(id))
        assertEquals(1, repo.savedWalksOf("near").first().size)
        assertNull(walkToAskAbout(repo, 0))
        assertEquals("the watermark is the walk's id", id, repo.settings.settings.first().walkAskedUpTo)
    }

    @Test fun aRefusalOfTheHouseLeavesTheWalkToAskAgain() = runBlocking {
        val id = walk(t0 + 1_000_000)
        repeat(20) { i -> val w = walk(t0 + 2_000_000L + i * 1_000_000L); assertTrue(repo.saveWalk("near", w) is SaveWalkResult.Saved) }
        val answer = WalkEndAnswers.save(repo, id, "near")
        assertEquals(WalkAnswer.Refused(SaveWalkResult.HouseFull), answer)
        assertEquals("changes nothing", 6, stored(id))
        assertNotNull("not yet handled: asked again", walkToAskAbout(repo, 0))
        // Another house takes it.
        assertEquals(WalkAnswer.Done, WalkEndAnswers.save(repo, id, "other"))
    }

    @Test fun aWalkTooLongToSaveStaysThirtyDaysAndIsNotAskedAgain() = runBlocking {
        val id = t0 + 1_000_000L
        (0..5_000).forEach { k -> repo.saveTrackPoint(TrackPointEntity(at = id + k * 20_000L, lat = 12.97 + k * 0.0002, lon = 77.6, accuracyM = 5f, walkId = id)) }
        assertEquals(WalkAnswer.Refused(SaveWalkResult.TooLong), WalkEndAnswers.save(repo, id, "near"))
        assertEquals(5_001, stored(id))
        assertNull(walkToAskAbout(repo, 0))
    }

    @Test fun theWalkBeingRecordedIsNeverTheOneAskedAbout() = runBlocking {
        val live = walk(t0 + 5_000_000)
        assertNull(walkToAskAbout(repo, liveWalkId = live))
        assertEquals(live, walkToAskAbout(repo, liveWalkId = 0)!!.walkId)
    }

    // ---- the sheet ----

    private val points = (0..5).map { k -> TracePoint(12.97 + k * 60 * m, 77.6, t0 + 1_000_000L + k * 60_000L, t0 + 1_000_000L) }

    @Test fun theSheetSaysWhatItIsAndKeepIsThePrimaryDefault() {
        var kept = false
        compose.setContent {
            ProvideAppServices { WalkEndSheetContent(WalkSummary(t0 + 1_000_000, points), emptyList(), 30, null, { kept = true }, {}, {}) }
        }
        compose.onNodeWithText("Save this walk?").assertExists()
        compose.onNodeWithText("Walk of 300 m in 5 min").assertExists()
        compose.onNodeWithText("It stays on this phone and is never in a backup or a copy.", substring = true).assertExists()
        compose.onNodeWithText("Keep for 30 days").performClick()
        assertTrue(kept)
    }

    @Test fun deleteAsksFirst() {
        var deleted = 0
        compose.setContent {
            ProvideAppServices { WalkEndSheetContent(WalkSummary(t0 + 1_000_000, points), emptyList(), 30, null, {}, { deleted++ }, {}) }
        }
        compose.onNodeWithText("Delete this walk").performClick()
        compose.onNodeWithText("Delete this walk? This cannot be undone.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
        compose.onNodeWithText("Delete this walk").performClick()
        compose.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(1, deleted)
    }

    @Test fun thePickerPreselectsTheNearestAndSavesTheChosenHouse() {
        val houses = runBlocking { repo.houses.first() }
        var saved: String? = null
        compose.setContent {
            ProvideAppServices { WalkEndSheetContent(WalkSummary(t0 + 1_000_000, points), houses, 30, null, {}, {}, { saved = it }) }
        }
        compose.onNodeWithText("Save with a house").performClick()
        compose.onNodeWithText("Which house was this walk to?").assertExists()
        compose.onNodeWithText("Nearest to where you stopped, 10 m away").assertExists()
        compose.onNodeWithText("Save walk").assertIsEnabled().performClick()
        assertEquals("near", saved)
    }

    @Test fun withNoHouseNearTheStopNothingIsPreselectedAndSaveWaitsForAChoice() {
        val houses = runBlocking { repo.houses.first() }.filter { it.id == "other" }
        compose.setContent {
            ProvideAppServices { WalkEndSheetContent(WalkSummary(t0 + 1_000_000, points), houses, 30, null, {}, {}, {}, initiallyPicking = true) }
        }
        compose.onNodeWithText("Save walk").assertIsNotEnabled()
        compose.onNodeWithText("Search your houses").performClick()
    }

    @Test fun withNoHousesThePickerSaysSoAndOffersKeep() {
        compose.setContent {
            ProvideAppServices { WalkEndSheetContent(WalkSummary(t0 + 1_000_000, points), emptyList(), 30, null, {}, {}, {}, initiallyPicking = true) }
        }
        compose.onNodeWithText("You have no saved houses yet. Add a house first, or keep the walk for 30 days.").assertExists()
        assertTrue(compose.onAllNodesWithText("Save walk").fetchSemanticsNodes().isEmpty())
    }

    // ---- the Map ----

    private fun showMap() = compose.setContent {
        ProvideAppServices { CompositionLocalProvider(LocalInspectionMode provides true) { MapScreen(onOpenHouse = {}, onNewHouse = { _, _ -> }) } }
    }

    @Test fun theMapOpensWithTheQuestionForAnEndedWalkAndAnswersItOnce() {
        walk(t0 + 1_000_000)
        showMap()
        waitFor("Save this walk?")
        compose.onNodeWithText("Keep for 30 days").performClick()
        waitFor("Walk kept for 30 days.")
        // Asked once: a new Map does not ask again.
        assertNull(runBlocking { walkToAskAbout(repo, 0) })
    }

    @Test fun theMapDoesNotAskAboutTheWalkBeingRecorded() {
        val live = walk(t0 + 1_000_000)
        HuntState.update { it.copy(active = true, walkId = live) }
        showMap()
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Save this walk?").fetchSemanticsNodes().isEmpty())
    }

    @Test fun savingFromTheMapsSheetLinksTheWalkToTheHouseAndSaysSo() {
        walk(t0 + 1_000_000)
        showMap()
        waitFor("Save this walk?")
        compose.onNodeWithText("Save with a house").performClick()
        compose.onNodeWithText("Save walk").performClick()
        waitFor("Walk saved with Green View.")
        assertEquals(1, runBlocking { repo.savedWalksOf("near").first().size })
    }

    @Test fun finishWalkOnTheHuntCardAsksTheServiceToEndTheWalk() {
        HuntState.update { it.copy(active = true, walkId = 0) }
        showMap()
        compose.onNodeWithText("Finish walk").performClick()
        val started: Intent = checkNotNull(shadowOf(app).nextStartedService)
        assertEquals("finish-walk", started.action)
        assertEquals("app.doorprints.location.HuntService", started.component!!.className)
    }

    @Test fun noFinishWalkButtonWhileHuntModeIsOff() {
        showMap()
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Finish walk").fetchSemanticsNodes().isEmpty())
    }
}
