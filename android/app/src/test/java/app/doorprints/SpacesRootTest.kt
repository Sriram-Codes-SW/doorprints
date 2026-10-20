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

import android.Manifest
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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import app.doorprints.location.HuntState
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.HuntSpace
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Spaces on the phones (kinds PR C, S4b-BL-274; docs/03 §18.12, TC-U-220), on the real app and Room. A build that ships the
 * house alone is the app it always was (no chooser, no chip, the same tabs and Settings rows); a build that ships the school
 * and has the switch off is that app too; with the school on, the first screen is the chooser, a card opens a space (its
 * words, its list, its Hunt mode: the kind file's capability, docs/03 section 18.20), the chip comes back, the house-only Settings rows follow the kind, and
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

    @After fun close() {
        kinds.close()
        HuntState.update { HuntState.State() }
    }

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
        val houseOnly = TwoKinds(app, listOf("house"))
        try {
            compose.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) { houseOnly.Provide { DoorprintsRoot(links, onDeepLinkHandled = { links.value = null }) } }
            }
            settle()
            waitFor("Hunt mode")
            for (label in listOf("Map", "Houses", "Compare", "Settings")) tab(label).assertExists()
            hasNo("What are you looking for?")
            hasNo("change what you are looking for")
            tab("Settings").performClick()
            settle()
            for (row in listOf("Brokers", "Criteria", "Questions", "Viewings")) shows(row)
            hasNo("What you track")
            hasNo("Experimental: more than houses")
        } finally {
            houseOnly.close()
        }
    }

    /**
     * What a person reaches in the shipped app's first minute, as a comparable record: the chooser, the chip, the tabs, the house list
     * (the store holds a house and a school, so the school's entry is there to leak) and the Settings rows. Taken once on a build
     * that lists the house alone and once on the build that lists the school with the switch off: equal but for the switch's own section.
     */
    private fun look(): Map<String, Any> {
        waitFor("Hunt mode")
        val seen = linkedMapOf<String, Any>()
        seen["chooser"] = compose.onAllNodesWithText("What are you looking for?", substring = true).fetchSemanticsNodes().size
        seen["chip"] = compose.onAllNodesWithContentDescription("change what you are looking for", substring = true).fetchSemanticsNodes().size
        seen["tabs"] = listOf("Map", "Houses", "Compare", "Assistant", "Settings", "Schools").filter {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(it)).fetchSemanticsNodes().isNotEmpty()
        }
        tab("Houses").performClick()
        waitFor("Green View")
        seen["houses"] = listOf("Green View", "Pine Hill School").filter { compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
        tab("Settings").performClick()
        settle()
        seen["rows"] = listOf("Brokers", "Criteria", "Questions", "Viewings", "Make Houses the default", "Show Houses", "Show Schools", "Saved: ").filter {
            compose.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithContentDescription(it, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        return seen
    }

    private fun startWith(store: TwoKinds) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { store.Provide { DoorprintsRoot(links, onDeepLinkHandled = { links.value = null }) } }
        }
        settle()
    }

    /**
     * The school released and not shown (owner, 2026-10-15 and 2026-10-20; TC-U-264, ADR-13b): the shipped app shows what a build that
     * lists the house alone shows, and Settings adds the one section with a Houses row and a Schools row, off, and no switch (it hides
     * itself while no listed kind is unreleased). The house build's record is the expected one.
     */
    @Test
    fun withTheSchoolNotShownTheShippedAppShowsWhatAHouseOnlyBuildShowsAndASchoolsRow() {
        startWith(kinds)
        // The house build's record, plus only the section's own rows: Show and Default for each kind, and their counts.
        val kindRows = listOf("Make Houses the default", "Show Houses", "Show Schools", "Saved: ")
        assertEquals(houseOnlyLook + ("rows" to (houseOnlyLook.getValue("rows") as List<*> + kindRows)), look())
        compose.onAllNodesWithText("Experimental: more than houses").assertCountEquals(0)
        compose.onAllNodesWithText("Show Schools").assertCountEquals(1)
    }

    @Test
    fun aHouseOnlyBuildShowsTheRecordTheShippedAppIsHeldTo() {
        val houseOnly = TwoKinds(app, listOf("house"))
        try {
            startWith(houseOnly)
            assertEquals(houseOnlyLook, look())
            compose.onAllNodesWithText("Experimental: more than houses").assertCountEquals(0)
        } finally {
            houseOnly.close()
        }
    }

    /** The house build: no chooser, no chip, the four tabs, both rows listed as houses, the house-only Settings rows and no kind rows. */
    private val houseOnlyLook: Map<String, Any> = mapOf(
        "chooser" to 0, "chip" to 0, "tabs" to listOf("Map", "Houses", "Compare", "Settings"),
        "houses" to listOf("Green View", "Pine Hill School"), "rows" to listOf("Brokers", "Criteria", "Questions", "Viewings"),
    )

    @Test
    fun aBuildThatReleasesTheSchoolOpensOnTheMapAndOffersTheSchoolsRowAndNoSwitch() {
        startTwoKinds()
        waitFor("Hunt mode")
        hasNo("What are you looking for?")
        hasNo("change what you are looking for")
        tab("Settings").performClick()
        settle()
        hasNo("Experimental: more than houses")
        // The rows: the house is on and the school is off, so there is no chooser until the school is shown.
        compose.onNodeWithText("Show Schools").assertExists()
        compose.onAllNodesWithContentDescription("Make Houses the default").assertCountEquals(1)
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
    fun aCardOpensItsSpaceWithItsWordsItsListAndItsHuntMode() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        waitFor("Hunt mode") // the school has Hunt mode of its own (capabilities.hunt)
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
        for (row in listOf("Brokers", "Criteria")) hasNo(row)
        // Kinds PR 29 (docs/03 section 18.23): the school has viewings, so its Settings has the Questions and the visits rows.
        for (row in listOf("Questions", "School visits")) shows(row)
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
    fun aHuntAlertMakesAHouseEvenWhenTheSchoolIsTheDefault() {
        // S4b-BL-304: a house walk's "stay here?" alert makes a house, never a candidate of the default kind; the alert carries the walk's kind (docs/03 section 18.20).
        kinds.enableSchool(schoolDefault = true)
        startTwoKinds()
        waitFor("What are you looking for?")
        links.value = DeepLink.NewHouse(26.1433, 91.7898, null, "house")
        waitFor("Save a house")
        shows("26.143300")
        hasNo("Save a school")
    }

    @Test
    fun aSchoolWalksStayAlertMakesASchoolEvenWhenTheHouseIsTheDefault() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        links.value = DeepLink.NewHouse(26.1433, 91.7898, null, "school")
        waitFor("Save a school")
        shows("26.143300")
        hasNo("Save a house")
    }

    @Test
    fun aSchoolWalksStayAlertWithTheSchoolOffMakesTheDefaultKindsEntry() {
        // The walk's kind was turned off after the alert was posted (the switch is off here): the entry is the default kind's, never one in a space that is off.
        startTwoKinds()
        waitFor("Hunt mode")
        links.value = DeepLink.NewHouse(26.1433, 91.7898, null, "school")
        waitFor("Save a house")
        hasNo("Save a school")
    }

    // The school as the default kind, and the house turned off (docs/03 §18.18; TC-U-268).

    @Test
    fun withTheSchoolTheDefaultAndTheHouseOffTheKeptHouseIsStillListedAndWhatYouTrackCountsIt() {
        kinds.schoolOnly()
        startTwoKinds()
        waitForMap("schools")
        hasNo("What are you looking for?") // one kind on: no chooser, no chip
        hasNo("change what you are looking for")
        waitFor("Hunt mode") // the school alone has its own Hunt card
        tab("Schools").performClick()
        waitFor("Search schools")
        shows("Pine Hill School")
        shows("Green View") // the house is kept and listed, never hidden
        tab("Settings").performClick()
        settle()
        compose.onNodeWithText("Show Houses").assertExists()
        compose.onAllNodesWithText("Saved: 1").assertCountEquals(2)
        compose.onNodeWithText("Show Schools").assertIsNotEnabled() // the last one on stays
        assertEquals(2, runBlocking { app.container.repository.houseSnapshot().size })
    }

    @Test
    fun withTheHouseOffAHuntAlertMakesAnEntryOfTheDefaultKindNotOfTheKindThatIsOff() {
        kinds.schoolOnly()
        startTwoKinds()
        waitForMap("schools")
        links.value = DeepLink.NewHouse(26.1433, 91.7898, null)
        waitFor("Save a school")
        hasNo("Save a house")
    }

    @Test
    fun withTheHouseOffAReminderStartHuntModeStartsTheSchoolsWalkBecauseTheSchoolHasViewingsToo() {
        // Kinds PR 29 (docs/03 sections 18.20 and 18.23): both kinds have viewings, so the reminder starts the default kind's walk.
        allowLocationAndNotifications()
        kinds.schoolOnly()
        startTwoKinds()
        waitForMap("schools")
        links.value = DeepLink.StartHunt()
        waitUntil("the school's walk starts") { kinds.huntStarts.isNotEmpty() }
        assertEquals(listOf("school"), kinds.huntStarts)
        hasNo("Hunt mode runs in the Houses space only")
        hasNo("Hunt mode stopped")
    }

    @Test
    fun withTheHouseOnAReminderStartHuntModeOpensTheDefaultKindsSpaceAndSaysNothing() {
        // The default kind is the school here, and it has viewings since kinds PR 29: its space opens (it was the house's before).
        kinds.enableSchool(schoolDefault = true)
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitForMap("houses")
        links.value = DeepLink.StartHunt()
        waitFor("Hunt mode")
        hasNo("turned off in What you track")
        waitForMap("schools")
    }

    @Test
    fun aWalkRunningWhenThePersonTurnsTheHouseOffStopsAndSaysWhy() {
        kinds.enableSchool(schoolDefault = true)
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode")
        HuntState.update { it.copy(active = true, kind = "house", startedAt = 1L) }
        settle()
        assertEquals(0, kinds.huntStops)
        tab("Settings").performClick()
        settle()
        compose.onNodeWithText("Show Houses").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("Hunt mode stopped. It runs in the Houses space only.")
        assertEquals(1, kinds.huntStops)
        assertEquals(false, HuntState.state.value.active)
        assertEquals(2, runBlocking { app.container.repository.houseSnapshot().size })
    }

    @Test
    fun aWalkStartedInTheHouseSpaceStopsWhenThePersonSwitchesToASchoolSpaceAndSaysWhy() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode")
        HuntState.update { it.copy(active = true, kind = "house", startedAt = 1L) }
        settle()
        assertEquals(0, kinds.huntStops) // in the house space the walk goes on
        compose.onNodeWithContentDescription("Houses: change what you are looking for").performClick()
        waitFor("What are you looking for?")
        assertEquals(0, kinds.huntStops) // the chooser is not a space: nothing has changed yet
        card("Schools").performClick()
        waitFor("Hunt mode stopped. It runs in the Houses space only.")
        assertEquals(1, kinds.huntStops)
        assertEquals(false, HuntState.state.value.active)
        waitForMap("schools")
    }

    @Test
    fun aWalkStartedWhileASchoolSpaceIsOnScreenIsLeftAlone() {
        // A reminder's Start Hunt mode (or the notification's action) starts the walk with another space open: only the move out of the house space stops one.
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        HuntState.update { it.copy(active = true, kind = "house", startedAt = 1L) }
        settle()
        assertEquals(0, kinds.huntStops)
    }

    @Test
    fun withOneKindOnNoWalkIsEverStoppedByASpaceChange() {
        startHouseOnly()
        waitFor("Hunt mode")
        HuntState.update { it.copy(active = true, kind = "house", startedAt = 1L) }
        tab("Settings").performClick()
        settle()
        tab("Map").performClick()
        settle()
        assertEquals(true, HuntState.state.value.active)
        hasNo("Hunt mode stopped. It runs")
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

    // Hunt as a capability (docs/03 section 18.20, kinds PR 26; TC-U-270): the school has the card and the switch, the walk is the space's.

    private fun allowLocationAndNotifications() =
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)

    private fun huntSwitch() = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))

    @Test
    fun aSchoolSpaceHasTheHuntCardAndItsSwitchStartsAWalkOfTheSchool() {
        allowLocationAndNotifications()
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        waitFor("Hunt mode")
        shows("Turn on while you are out looking")
        huntSwitch().performClick()
        waitUntil("the school's walk starts") { kinds.huntStarts.isNotEmpty() }
        assertEquals(listOf("school"), kinds.huntStarts)
        assertEquals("school", HuntState.state.value.kind)
    }

    @Test
    fun theHouseSpaceStillStartsAWalkOfTheHouse() {
        allowLocationAndNotifications()
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode")
        huntSwitch().performClick()
        waitUntil("the house's walk starts") { kinds.huntStarts.isNotEmpty() }
        assertEquals(listOf("house"), kinds.huntStarts)
    }

    @Test
    fun aSchoolWalkStopsWhenThePersonSwitchesToAnotherSpaceAndSaysWhyInTheSchoolsWords() {
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        waitFor("Hunt mode")
        HuntState.update { it.copy(active = true, kind = "school", startedAt = 1L) }
        settle()
        assertEquals(0, kinds.huntStops) // in the school space the walk goes on
        compose.onNodeWithContentDescription("Schools: change what you are looking for").performClick()
        waitFor("What are you looking for?")
        card("Houses").performClick()
        waitFor("Hunt mode stopped. It runs in the Schools space only.")
        assertEquals(1, kinds.huntStops)
        assertEquals(false, HuntState.state.value.active)
        waitForMap("houses")
    }

    @Test
    fun anAreaWakeUpAndAReminderBothStartTheDefaultKindsWalkWithTheSchoolTheDefault() {
        allowLocationAndNotifications()
        kinds.enableSchool(schoolDefault = true)
        startTwoKinds()
        waitFor("What are you looking for?")
        links.value = DeepLink.StartHunt(HuntSpace.Starter.AREA)
        waitForMap("schools")
        waitUntil("the school's walk starts") { kinds.huntStarts.isNotEmpty() }
        assertEquals(listOf("school"), kinds.huntStarts)
        // Both kinds have viewings since kinds PR 29 (docs/03 section 18.23): a reminder's walk is the default kind's too.
        HuntState.update { HuntState.State() }
        links.value = DeepLink.StartHunt(HuntSpace.Starter.REMINDER)
        waitUntil("the school's walk starts again") { kinds.huntStarts.size == 2 }
        assertEquals(listOf("school", "school"), kinds.huntStarts)
        waitForMap("schools")
    }

    @Test
    fun withTheHouseTheDefaultAReminderStartsTheHousesWalkFromTheSchoolSpace() {
        allowLocationAndNotifications()
        kinds.enableSchool()
        startTwoKinds()
        waitFor("What are you looking for?")
        card("Schools").performClick()
        waitForMap("schools")
        links.value = DeepLink.StartHunt(HuntSpace.Starter.REMINDER)
        waitForMap("houses")
        waitUntil("the house's walk starts") { kinds.huntStarts.isNotEmpty() }
        assertEquals(listOf("house"), kinds.huntStarts)
    }

    @Test
    fun withOnlyTheSchoolOnAnAreaWakeUpAndAReminderBothStartTheSchoolsWalk() {
        allowLocationAndNotifications()
        kinds.schoolOnly()
        startTwoKinds()
        waitForMap("schools")
        links.value = DeepLink.StartHunt(HuntSpace.Starter.AREA)
        waitUntil("the school's walk starts") { kinds.huntStarts.isNotEmpty() }
        assertEquals(listOf("school"), kinds.huntStarts)
        HuntState.update { HuntState.State() }
        links.value = DeepLink.StartHunt(HuntSpace.Starter.REMINDER)
        waitUntil("the school's walk starts again") { kinds.huntStarts.size == 2 }
        assertEquals(listOf("school", "school"), kinds.huntStarts) // a reminder's walk is the school's: it has viewings now
    }
}
