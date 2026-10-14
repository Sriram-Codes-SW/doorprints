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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spaces on the phones (kinds PR C, S4b-BL-274; docs/03 §18.12, TC-U-217), on the real app and Room. A build that ships the
 * house alone is the app it always was (no chooser, no chip, the same tabs and Settings rows); a build that ships the school
 * and has the switch off is that app too; with the school on, the first screen is the chooser, a card opens a space (its
 * words, its list, no Hunt mode outside the house), the chip comes back, the house-only Settings rows follow the kind, and
 * a link, an alert or a shared listing resolves its space. English; the other languages are in `StringParityTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class, qualifiers = "w360dp-h1400dp")
class SpacesRootTest {
    // The effects run on a StandardTestDispatcher, so on the main (test) thread as in the app, and are run from [pump]: with the
    // rule's unconfined default a continuation after Room's or DataStore's answer stays on their thread, where
    // `NavController.navigate` fails (S4b-BL-46; as RootNavigationVisitLinkTest).
    private val effects = StandardTestDispatcher()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = effects)

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds = TwoKinds(app)
    private val links = MutableStateFlow<DeepLink?>(null)

    private val houseId = "0b8f6a52-3c1e-4d7a-9f4e-2a6c5d1b7e90"
    private val schoolId = "6c2f5b1e-8a3d-4c7e-9b05-1d4e7a9c3f21"

    @Before fun saveOneOfEach() {
        val at = 1_760_000_000_000
        runBlocking {
            app.container.repository.saveHouse(HouseEntity(id = houseId, label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
            app.container.repository.saveHouse(HouseEntity(id = schoolId, label = "Pine Hill School", lat = 26.14, lon = 91.78, createdAt = at, updatedAt = at + 5, kind = "school"))
        }
    }

    @Before fun clearFileProviderCache() {
        // As in RootNavigationVisitLinkTest: androidx FileProvider caches its path roots, Robolectric gives each test a new data dir.
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    @After fun close() = kinds.close()

    private fun startHouseOnly() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { ProvideAppServices { DoorprintsRoot(links, onDeepLinkHandled = { links.value = null }) } }
        }
        settle()
    }

    private fun startTwoKinds() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { kinds.Provide { DoorprintsRoot(links, onDeepLinkHandled = { links.value = null }) } }
        }
        settle()
    }

    private fun tab(label: String) = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(label))
    private fun hasNo(text: String) = compose.onAllNodesWithText(text, substring = true).assertCountEquals(0)
    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true)[0].assertExists()
    private fun card(name: String) = compose.onNode(hasText(name, substring = true) and hasClickAction())

    private fun pump() = compose.runOnIdle { effects.scheduler.runCurrent() }

    /** Compose idle and the effects run, a few times over (an effect that navigates is followed by the screen it opens). */
    private fun settle() {
        repeat(3) {
            compose.waitForIdle()
            pump()
        }
        compose.waitForIdle()
    }

    /** Runs the effects until [done]; a timeout says what was on screen. */
    private fun waitUntil(what: String, done: () -> Boolean) {
        try {
            compose.waitUntil(10_000) {
                pump()
                done()
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("'$what' did not happen in 10 s. On screen: " + compose.onRoot().printToString(maxDepth = 8), e)
        }
    }

    private fun waitFor(text: String) = waitUntil(text) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    /** The Map of a space is up: its description is the words of the space (the Save button is below this small window's fold). */
    private fun waitForMap(of: String) =
        waitUntil("Map of your $of") { compose.onAllNodes(hasContentDescription("Map of your $of", substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aBuildThatShipsTheHouseAloneIsTheAppItAlwaysWas() {
        startHouseOnly()
        waitFor("Hunt mode")
        for (label in listOf("Map", "Houses", "Compare", "Settings")) tab(label).assertExists()
        hasNo("What are you looking for?")
        hasNo("change what you are looking for")
        tab("Settings").performClick()
        settle()
        for (row in listOf("Brokers", "Criteria", "Questions", "Viewings")) shows(row)
        hasNo("What you track")
        hasNo("Experimental: more than houses")
    }

    @Test
    fun aBuildThatShipsTheSchoolWithTheSwitchOffOpensOnTheMapAndOffersTheSwitch() {
        startTwoKinds()
        waitFor("Hunt mode")
        hasNo("What are you looking for?")
        hasNo("change what you are looking for")
        tab("Settings").performClick()
        settle()
        compose.onNodeWithText("Experimental: more than houses").assertExists()
        // Only the switch: one kind is on, so there is no list of kinds to choose from.
        hasNo("Make Houses the default")
    }

    @Test
    fun withTheSchoolOnTheFirstScreenIsTheChooserWithCountsAndTheDefaultFirst() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("Saved: 1")
        shows("What are you looking for?")
        card("Houses").assertExists()
        card("Schools").assertExists()
        compose.onNode(hasText("Houses", substring = true) and hasText("Default", substring = true) and hasText("Saved: 1", substring = true)).assertExists()
        compose.onAllNodesWithText("Tap a card to switch", substring = true).assertCountEquals(1)
        hasNo("Hunt mode") // no map, no bar on the chooser
    }

    @Test
    fun theHintGoesWhenItIsDismissedAndStaysGone() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("Tap a card to switch")
        compose.onNodeWithText("Got it").performClick()
        waitUntil("hint gone") { compose.onAllNodesWithText("Tap a card to switch", substring = true).fetchSemanticsNodes().isEmpty() }
        assertEquals(false, runBlocking { kinds.store.chooserHintShown().first() })
    }

    @Test
    fun aCardOpensItsSpaceWithItsWordsItsListAndNoHuntMode() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        hasNo("Hunt mode")
        compose.onNodeWithContentDescription("Schools: change what you are looking for").assertExists()
        tab("Schools").performClick()
        waitFor("Search schools")
        shows("Pine Hill School")
        hasNo("Green View")
        compose.onNode(hasContentDescription("Schools shown: 1 of 1")).assertExists()
        // The sort and the cost filter are a house's: a school has no checklist and no money.
        hasNo("Sort:")
        hasNo("Filters")
    }

    @Test
    fun theChipComesBackToTheChooserAndTheHouseSpaceHasItsHuntModeAndItsHouses() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        compose.onNodeWithContentDescription("Schools: change what you are looking for").performClick()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode")
        compose.onNodeWithContentDescription("Houses: change what you are looking for").assertExists()
        tab("Houses").performClick()
        waitFor("Green View")
        hasNo("Pine Hill School")
        compose.onNode(hasContentDescription("Houses shown: 1 of 1")).assertExists()
    }

    @Test
    fun theHouseOnlySettingsRowsFollowTheKind() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        tab("Settings").performClick()
        settle()
        compose.onNodeWithText("What you track").assertExists()
        for (row in listOf("Brokers", "Criteria", "Questions", "Viewings")) hasNo(row)
        // Back in the house space the four rows are there, in the order they always were.
        compose.onNodeWithContentDescription("Schools: change what you are looking for").assertExists()
    }

    @Test
    fun whatYouTrackListsTheKindsWithShowDefaultAndTheSavedCountAndKeepsOneOn() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode")
        tab("Settings").performClick()
        compose.onNodeWithText("What you track").assertExists()
        compose.onNodeWithText("Show Houses").assertExists()
        compose.onNodeWithText("Show Schools").assertExists()
        compose.onAllNodesWithText("Saved: 1").assertCountEquals(2)
        // Turning the school off leaves one kind on: its box is disabled, and nothing is deleted.
        compose.onNodeWithText("Show Schools").performSemanticsAction(SemanticsActions.OnClick)
        waitUntil("School turned off") { runBlocking { kinds.store.kindSetting().first() }.enabled == listOf("house") }
        compose.onNodeWithText("Show Houses").assertIsNotEnabled()
        assertEquals(2, runBlocking { app.container.repository.houseSnapshot().size })
    }

    @Test
    fun aLinkToAnotherKindsCandidateOpensItsSpace() {
        // In the house space, a link to a school opens the form, and Back lands in the school's space.
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode")
        compose.onNodeWithContentDescription("Houses: change what you are looking for").assertExists()
        links.value = DeepLink.OpenHouse(schoolId)
        waitFor("School name")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
        pump()
        waitUntil("chip of the school space") { compose.onAllNodes(hasContentDescription("Schools: change what you are looking for")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anAlertMakesACandidateOfTheDefaultKind() {
        kinds.enableSchool(schoolDefault = true)
        startTwoKinds()
        waitFor("What are you looking for?")
        links.value = DeepLink.NewHouse(26.1433, 91.7898, null)
        waitFor("Save a school")
        shows("26.143300")
    }

    @Test
    fun aSharedListingAsksWhereToSaveItWhenTwoKindsAreOnAndGoesToTheChosenSpace() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        links.value = DeepLink.NewHouseFromListing("2BHK near the bus stop, call 98450 12345")
        waitFor("Save it in")
        shows("Add a shared listing")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton) and hasText("Schools")).performClick()
        compose.onNodeWithText("OK").performClick()
        waitForMap("schools")
        compose.onNodeWithContentDescription("Schools: change what you are looking for").assertExists()
    }

    @Test
    fun aSharedListingWithOneKindOnGoesStraightToTheMap() {
        startHouseOnly()
        waitFor("Hunt mode")
        links.value = DeepLink.NewHouseFromListing("2BHK near the bus stop, call 98450 12345")
        waitFor("Save house here")
        hasNo("Save it in")
    }

    @Test
    fun theAssistantIsOnlyForTheHouseUnlessItIsThePersonsOwn() {
        kinds.enableSchool()
        runBlocking { app.container.repository.setAiFeatures(true) }
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        // The server's Ask and Plan take no kind (S4b-BL-290): in the school space there is no Assistant tab without the person's own AI.
        hasNo("Assistant")
    }
}
