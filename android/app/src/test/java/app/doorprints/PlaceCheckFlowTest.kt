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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.getOrNull
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
import app.doorprints.shared.model.LocationSource
import app.doorprints.shared.trace.PlaceCheckStatus
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.MapScreen
import app.doorprints.ui.PlaceCheckController
import app.doorprints.ui.PlaceCheckState
import app.doorprints.ui.PlaceCheckSheetContent
import app.doorprints.ui.PlaceFailure
import app.doorprints.ui.PlaceFix
import app.doorprints.ui.PlaceKind
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * *Have I been here?* (docs/11 5.27.13; TC-U-153 and TC-U-154 UI parts), on the real repository: the three sources, the
 * gate (the first fix of 50 m or better, an imprecise one, none), the live walk left out for *Here* only, an area-only house,
 * the words of the answer, and that a check reads and writes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class PlaceCheckFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val repo = app.container.repository
    private val m = 0.000008993216
    private val t0 = System.currentTimeMillis() - 20 * 3_600_000L
    private val scope = CoroutineScope(Dispatchers.Default)

    @Before fun setUp() {
        runCatching {
            androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
        runBlocking {
            repo.saveHouse(HouseEntity(id = "h", label = "Green View", lat = 12.97 + 150 * m, lon = 77.6, createdAt = 1, updatedAt = 1))
            repo.saveHouse(HouseEntity(id = "area", label = "Area house", lat = 12.97, lon = 77.6, locationSource = LocationSource.APPROX, createdAt = 1, updatedAt = 1))
        }
    }

    @After fun clear() = scope.cancel()

    /** A walk north from (12.97, 77.6) of six fixes 60 m apart (300 m), id [id]. */
    private fun walk(id: Long) = runBlocking {
        (0..5).forEach { k ->
            repo.saveTrackPoint(TrackPointEntity(at = id + k * 60_000L, lat = 12.97 + k * 60 * m, lon = 77.6, accuracyM = 5f, walkId = id))
        }
    }

    private fun controller(fix: suspend () -> PlaceFix?, live: Long = 0) = PlaceCheckController(scope, repo, fix, { live })

    private fun await(c: PlaceCheckController, predicate: (PlaceCheckState?) -> Boolean = { it is PlaceCheckState.Answer || it is PlaceCheckState.Failed }): PlaceCheckState =
        runBlocking { withTimeout(5_000) { while (!predicate(c.state)) delay(10); c.state!! } }

    private fun answer(c: PlaceCheckController) = await(c) as PlaceCheckState.Answer

    // ---- the sources and the gate ----

    @Test fun aHouseOnTheLineIsWalkedAndAHouseOffItIsNone() {
        walk(t0 + 1_000_000)
        val c = controller({ null })
        c.start(PlaceKind.HOUSE, 12.97 + 150 * m, 77.6 + 10 * m)
        assertEquals(PlaceCheckStatus.WALKED, answer(c).result.status)
        c.start(PlaceKind.HOUSE, 12.97 + 150 * m, 77.6 + 300 * m)
        assertEquals(PlaceCheckStatus.NONE, runBlocking { withTimeout(5_000) { while ((c.state as? PlaceCheckState.Answer)?.result?.status != PlaceCheckStatus.NONE) delay(10); (c.state as PlaceCheckState.Answer).result.status } })
    }

    @Test fun aSavedWalkFarAwayStillMakesTheNoneAnswerSayTheSavedWalksWereLookedAt() {
        runBlocking {
            repo.saveHouse(HouseEntity(id = "far", label = "Far", lat = 12.5, lon = 77.0, createdAt = 1, updatedAt = 1))
            walk(t0 + 1_000_000)
            assertTrue(repo.saveWalk("far", t0 + 1_000_000) is SaveWalkResult.Saved)
        }
        val c = controller({ null })
        c.start(PlaceKind.SPOT, 12.0, 77.0)
        val a = answer(c)
        assertEquals(PlaceCheckStatus.NONE, a.result.status)
        assertTrue("the saved walk was compared although it has no row", a.anySaved)
        assertTrue("and, with no row, it is not kept", a.walks.isEmpty())
    }

    @Test fun eachRowPointsAtItsOwnWalkInTheKeptList() {
        // Two walks pass the place; a third, far away, has no row and is not kept: the rows' indexes are the kept walks'.
        walk(t0 + 1_000_000)
        runBlocking {
            (0..5).forEach { k ->
                repo.saveTrackPoint(TrackPointEntity(at = t0 + 9_000_000 + k * 60_000L, lat = 12.97 + k * 60 * m, lon = 77.6 + 6 * m, accuracyM = 5f, walkId = t0 + 9_000_000))
                repo.saveTrackPoint(TrackPointEntity(at = t0 + 5_000_000 + k * 60_000L, lat = 13.5 + k * 60 * m, lon = 77.6, accuracyM = 5f, walkId = t0 + 5_000_000))
            }
        }
        val c = controller({ null })
        c.start(PlaceKind.SPOT, 12.97 + 150 * m, 77.6)
        val a = answer(c)
        assertEquals(2, a.walks.size)
        assertEquals(listOf(0, 1), a.result.rows.map { it.walkIndex }.sorted())
        for (row in a.result.rows) {
            val points = a.walks[row.walkIndex].points
            assertTrue("row ${row.walkIndex} is the walk it was measured on", points.first().atMs <= row.atMs && row.atMs <= points.last().atMs)
        }
        assertEquals("newest first: the later walk is the first row", t0 + 9_000_000, a.walks[a.result.rows.first().walkIndex].points.first().walkId)
    }

    @Test fun noWalksAtAllIsTheEmptyAnswer() {
        val c = controller({ null })
        c.start(PlaceKind.SPOT, 12.97, 77.6)
        assertEquals(PlaceCheckStatus.EMPTY, answer(c).result.status)
    }

    @Test fun anAreaOnlyHouseSaysSoAndComparesNothing() {
        walk(t0 + 1_000_000)
        val c = controller({ null })
        c.start(PlaceKind.HOUSE, 12.97, 77.6, approximate = true)
        val st = c.state as PlaceCheckState.Failed
        assertEquals(PlaceFailure.APPROX_HOUSE, st.reason)
    }

    @Test fun hereUsesTheFixAndAFixLooserThanTheToleranceIsFuzzyButStillAnswers() {
        walk(t0 + 1_000_000)
        val c = controller({ PlaceFix(12.97 + 150 * m, 77.6, 30.0) })
        c.start(PlaceKind.HERE)
        val a = answer(c)
        assertEquals(PlaceCheckStatus.WALKED, a.result.status)
        assertTrue("a 30 m fix is accepted but loose", a.result.fuzzy)
        assertEquals(30.0, a.accuracyM!!, 0.0)
    }

    @Test fun aFixWorseThanFiftyMetresIsImpreciseAndExactlyFiftyPasses() {
        walk(t0 + 1_000_000)
        val c = controller({ PlaceFix(12.97 + 150 * m, 77.6, 50.1) })
        c.start(PlaceKind.HERE)
        assertEquals(PlaceCheckStatus.IMPRECISE, answer(c).result.status)
        val ok = controller({ PlaceFix(12.97 + 150 * m, 77.6, 50.0) })
        ok.start(PlaceKind.HERE)
        assertEquals(PlaceCheckStatus.WALKED, answer(ok).result.status)
    }

    @Test fun noFixIsTheTimeoutSentence() {
        val c = controller({ null })
        c.start(PlaceKind.HERE)
        assertEquals(PlaceFailure.TIMEOUT, (await(c) as PlaceCheckState.Failed).reason)
    }

    @Test fun whileLookingTheSheetSaysSoAndClosingStopsIt() {
        val never = CompletableDeferred<PlaceFix?>()
        val c = controller({ never.await() })
        c.start(PlaceKind.HERE)
        await(c) { it is PlaceCheckState.Locating }
        c.close()
        assertNull(c.state)
        never.complete(PlaceFix(12.97, 77.6, 5.0))
        runBlocking { delay(100) }
        assertNull("a late fix after closing shows nothing", c.state)
    }

    @Test fun theWalkBeingRecordedIsLeftOutForHereOnly() {
        val live = t0 + 1_000_000
        walk(live)
        val here = controller({ PlaceFix(12.97 + 150 * m, 77.6, 5.0) }, live = live)
        here.start(PlaceKind.HERE)
        assertEquals("only walk there is: the live one", PlaceCheckStatus.EMPTY, answer(here).result.status)
        val house = controller({ null }, live = live)
        house.start(PlaceKind.HOUSE, 12.97 + 150 * m, 77.6)
        assertEquals("a house counts the live walk", PlaceCheckStatus.WALKED, answer(house).result.status)
        val spot = controller({ null }, live = live)
        spot.start(PlaceKind.SPOT, 12.97 + 150 * m, 77.6)
        assertEquals(PlaceCheckStatus.WALKED, answer(spot).result.status)
    }

    @Test fun legacyRowsMergedIntoTheLiveWalkAreLeftOutForHereToo() {
        val live = t0 + 1_000_000
        // Rows from before the walk ids (id 0) ten minutes before the live walk merge into it: its first point has id 0.
        runBlocking {
            (0..2).forEach { k ->
                repo.saveTrackPoint(TrackPointEntity(at = live - 600_000 + k * 60_000L, lat = 12.97 + 150 * m, lon = 77.6, accuracyM = 5f, walkId = 0))
            }
        }
        walk(live)
        val here = controller({ PlaceFix(12.97 + 150 * m, 77.6, 5.0) }, live = live)
        here.start(PlaceKind.HERE)
        assertEquals("the merged walk is the live one's own points", PlaceCheckStatus.EMPTY, answer(here).result.status)
    }

    @Test fun aSavedWalkOfAnyAgeCountsAndIsTaggedSaved() {
        val old = 1_700_000_000_000L // far outside the 30 days
        walk(old)
        assertTrue(runBlocking { repo.saveWalk("h", old) } is SaveWalkResult.Saved)
        val c = controller({ null })
        c.start(PlaceKind.SPOT, 12.97 + 150 * m, 77.6)
        val a = answer(c)
        assertEquals(PlaceCheckStatus.WALKED, a.result.status)
        assertTrue(a.anySaved)
    }

    @Test fun aCheckReadsAndWritesNothing() {
        walk(t0 + 1_000_000)
        val before = runBlocking { repo.trackPoints.first().size to repo.savedWalkCount.first() }
        val c = controller({ PlaceFix(12.97, 77.6, 5.0) })
        c.start(PlaceKind.HERE); answer(c); c.close()
        c.start(PlaceKind.HOUSE, 12.97, 77.6); answer(c); c.close()
        assertEquals(before, runBlocking { repo.trackPoints.first().size to repo.savedWalkCount.first() })
    }

    // ---- the words, on the house page ----

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun housePage(id: String) =
        compose.setContent { ProvideAppServices { HouseEditScreen(id, null, null, null, onDone = {}) } }

    @Test fun theHousePageButtonAnswersInWordsWithTheDateAndTheNearMiss() {
        walk(t0 + 1_000_000)
        housePage("h")
        waitFor("Green View")
        compose.onNodeWithText("Did I walk past this house?").performScrollTo().performClick()
        waitFor("You walked within 1 m of this house on ")
        compose.onNodeWithText("Shown only here. Nothing is saved or sent.").assertExists()
        compose.onNodeWithText("Show on map").assertExists()
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Shown only here", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun anAreaOnlyHouseOnItsPageSaysItHasNoExactSpot() {
        walk(t0 + 1_000_000)
        housePage("area")
        waitFor("Area house")
        compose.onNodeWithText("Did I walk past this house?").performScrollTo().performClick()
        waitFor("This house has no exact spot yet, only an area.")
    }

    @Test fun withNoWalksTheHousePageSaysThereIsNothingToCompare() {
        housePage("h")
        waitFor("Green View")
        compose.onNodeWithText("Did I walk past this house?").performScrollTo().performClick()
        waitFor("There are no walks to compare yet.")
    }

    @Test fun aNearMissIsClosePhrasedAndNeverSaysWalked() {
        walk(t0 + 1_000_000)
        runBlocking { repo.saveHouse(repo.getHouse("h")!!.copy(lon = 77.6 + 41 * m)) }
        housePage("h")
        waitFor("Green View")
        compose.onNodeWithText("Did I walk past this house?").performScrollTo().performClick()
        waitFor("No walk of yours passed within 25 m of this house, but one came within 40 m on ")
        assertTrue(compose.onAllNodesWithText("You walked within", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("This covers only the walks Doorprints recorded.").assertExists()
    }

    @Test fun theAnswersLiveRegionIsOneMergedNodeWithTheText() {
        compose.setContent {
            PlaceCheckSheetContent(PlaceCheckState.Locating(PlaceKind.HERE), onShowOnMap = {}, onAgain = {}, onClose = {})
        }
        val region = compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion))
        val text = region.fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().joinToString { it.text }
        assertTrue("the live region itself carries the sentence (merged), so a screen reader announces it: '$text'", text.contains("Finding your location"))
    }

    @Test fun theMapHasTheButtonWhetherOrNotTheTraceIsOn() {
        compose.setContent {
            ProvideAppServices { CompositionLocalProvider(LocalInspectionMode provides true) { MapScreen(onOpenHouse = {}, onNewHouse = { _, _ -> }) } }
        }
        compose.onNodeWithContentDescription("Have I been here?").assertExists()
        compose.onNodeWithContentDescription("Have I been here?").performClick()
        compose.onNodeWithText("Where I am now").assertExists()
        compose.onNodeWithText("A spot on the map").performClick()
        compose.onNodeWithText("Check this spot").assertExists()
        compose.onNodeWithText("Move the map so the cross is on the spot, then press Check this spot.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }
}
