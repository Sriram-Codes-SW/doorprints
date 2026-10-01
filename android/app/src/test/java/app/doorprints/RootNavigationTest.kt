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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The common root's navigation (ADR-23 CMP-5: the graph moved to `:ui`, on JetBrains navigation-compose) behaves as
 * before for the notification links MainActivity hands it (docs/06 TC-I-35 checks the same on an emulator): a link
 * present at a cold start opens its screen over the Map, the same house link twice does not stack two forms, Settings
 * opens as its tab, Export is single-top, each link is reported handled, and Back returns to the Map. Every screen is
 * the real, common one: the house form, Export and Import since CMP-6 (English: "Save a house" for a new house, "House
 * details" for a stored one, "Save a copy"), and since CMP-7 the Map's chrome, recognised by its Hunt card ("Hunt
 * mode"), around an empty map view (inspection mode). Settings > Viewings (S4b-BL-103) opens the whole history, a row
 * opens its viewing and *Plan a viewing* a new one, and Back walks back through each.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class RootNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val links = MutableStateFlow<DeepLink?>(null)

    /** The Map's Hunt card title (map_hunt_mode, English). */
    private val MAP = "Hunt mode"

    /** Settings > Viewings (English): the row's hint, the history's intro, its empty state and a form field. */
    private val VIEWINGS_HINT = "Plan a viewing, and see the ones coming up and the ones you have done."
    private val VIEWINGS_INTRO = "When you plan to see a house, and how it went."
    private val NO_VIEWINGS = "No viewings yet. Plan one from a house."
    private val WITH_WHOM = "With whom"
    private var handled = 0

    @Before fun clearFileProviderCache() {
        // The house form's camera file goes through androidx FileProvider, which caches its path roots per authority in
        // a static map, but Robolectric gives each test a new data directory (as in ScreensScreenshotTest).
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private fun start(link: DeepLink?) {
        links.value = link
        compose.setContent {
            // Inspection mode: MapLibre's native library cannot load on the JVM, so the Map's view (PlatformMap) is an
            // empty box and its chrome, which is common code, is drawn around it ("Hunt mode", "Loading the map…").
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ProvideAppServices {
                    DoorprintsRoot(links, onDeepLinkHandled = { handled++; links.value = null })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onNodeWithText(text).assertExists()

    @Test
    fun aNewHouseLinkAtColdStartOpensTheFormOverTheMap() {
        start(DeepLink.NewHouse(12.9716, 77.5946, null))
        shows("Save a house")
        // The link's coordinates reached the form (its Latitude and Longitude fields, six decimals).
        shows("12.971600")
        shows("77.594600")
        assertEquals(1, handled)
        assertNull(links.value)
        back()
        shows(MAP)
    }

    @Test
    fun theSameHouseLinkTwiceStacksOneForm() {
        val id = "0b8f6a52-3c1e-4d7a-9f4e-2a6c5d1b7e90"
        // Stored first, so the form's title is "House details" whenever Room answers (not "House not found").
        runBlocking {
            val at = 1_760_000_000_000
            ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
                .saveHouse(HouseEntity(id = id, label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
        }
        start(DeepLink.OpenHouse(id))
        shows("House details")
        links.value = DeepLink.OpenHouse(id)
        compose.waitForIdle()
        assertEquals(2, handled)
        back()
        shows(MAP)
    }

    @Test
    fun aRemindersQuestionsActionOpensTheHouseAtItsQuestions() {
        // S4b-BL-93b: the house form opens scrolled to "Questions to ask"; a plain house link opens it at the top.
        val id = "0b8f6a52-3c1e-4d7a-9f4e-2a6c5d1b7e91"
        runBlocking {
            val at = 1_760_000_000_000
            ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
                .saveHouse(HouseEntity(id = id, label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
        }
        start(DeepLink.OpenHouse(id))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("House details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Questions to ask").assertIsNotDisplayed()
        back()
        links.value = DeepLink.OpenHouse(id, questions = true)
        compose.waitForIdle()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("Questions to ask").assertIsDisplayed() }.isSuccess }
        assertEquals(2, handled)
    }

    @Test
    fun theFormsDoneClosesIt() {
        start(null)
        shows(MAP)
        // No visit id: with one, the root first looks the visit up in Room, and the test's coroutine interceptor does
        // not bring the effect back to the main thread afterwards as the app's main dispatcher does.
        links.value = DeepLink.NewHouse(13.0, 77.5, null)
        compose.waitForIdle()
        shows("Save a house")
        // The form's back arrow: nothing typed, so it closes without asking.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        shows(MAP)
    }

    @Test
    fun settingsOpensAsItsTabAndBackReturnsToTheMap() {
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        // The Settings screen's heading (the bar's item says "Settings" too).
        compose.onNodeWithText("Server (optional)").assertExists()
        back()
        shows(MAP)
    }

    @Test
    fun aHuntReminderOpensItsViewingOnce() {
        // Slice 3c: the body of a Hunt mode reminder opens the viewing; tapped twice, one form.
        runBlocking {
            ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
                .saveViewing(app.doorprints.shared.model.Viewing("v_00000001", "h1", 1_790_569_800_000, huntReminder = true))
        }
        start(DeepLink.OpenViewing("v_00000001"))
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Offer Hunt mode before this viewing").fetchSemanticsNodes().isNotEmpty() }
        links.value = DeepLink.OpenViewing("v_00000001")
        compose.waitForIdle()
        assertEquals(2, handled)
    }

    @Test
    fun startHuntModeFromAReminderOpensTheMap() {
        // *Start Hunt mode* without location (slice 3c): the Map tab, where the Hunt switch's own location question is
        // asked (5.18), as the shared-listing link opens it.
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        compose.onNodeWithText("Server (optional)").assertExists()
        links.value = DeepLink.StartHunt
        compose.waitForIdle()
        assertEquals(2, handled)
        shows(MAP)
    }

    @Test
    fun aDeepLinkToTheMapClosesWhatWasOpenOverIt() {
        // S4b-BL-94a: a form open over the Map is closed, not brought back by the tab's restored stack.
        start(DeepLink.NewHouse(12.9716, 77.5946, null))
        shows("Save a house")
        links.value = DeepLink.StartHunt
        compose.waitForIdle()
        assertEquals(2, handled)
        shows(MAP)
        compose.onNodeWithText("Save a house").assertDoesNotExist()
        // Back from the Map leaves the app; no form waits under it.
        compose.onAllNodesWithText("12.971600").assertCountEquals(0)
    }

    @Test
    fun anIphoneReminderTapOffersHuntModeOnTheMap() {
        // S4b-BL-94c: the tap opened only the app, so the Map asks with *Start Hunt mode*; nothing is started yet.
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        links.value = DeepLink.OfferHunt
        compose.waitForIdle()
        assertEquals(2, handled)
        shows(MAP)
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Start Hunt mode?").fetchSemanticsNodes().isNotEmpty() }
        shows("Start Hunt mode")
    }

    private fun waitFor(text: String) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    /** Opens Settings > Viewings by its row (found by its hint, the row's own words; the row is one button). */
    private fun openSettingsViewings() {
        compose.onNodeWithText(VIEWINGS_HINT).performScrollTo().performClick()
        compose.waitForIdle()
        waitFor(VIEWINGS_INTRO)
    }

    @Test
    fun settingsViewingsOpensTheHistoryAStoredViewingAndBackWalksBack() {
        // S4b-BL-103 (S4b-BL-92f): Settings > Viewings, a row of the history, then Back to the history, Settings, the Map.
        runBlocking {
            val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
            val at = 1_760_000_000_000
            repo.saveHouse(HouseEntity(id = "h-nav", label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
            repo.saveViewing(app.doorprints.shared.model.Viewing("v_0000000a", "h-nav", System.currentTimeMillis() + 86_400_000))
        }
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        openSettingsViewings()
        // The whole history ("When you plan…", not one house's), with the stored viewing's row.
        val row = hasContentDescription("Open the viewing: Green View", substring = true)
        compose.waitUntil(5_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(row).performScrollTo().performClick()
        compose.waitForIdle()
        // The stored viewing's form (titled "Viewing", not "Plan a viewing"), in place of the history.
        waitFor(WITH_WHOM)
        compose.onNodeWithText("Viewing").assertExists()
        compose.onNodeWithText("Plan a viewing").assertDoesNotExist()
        compose.onNodeWithText(VIEWINGS_INTRO).assertDoesNotExist()
        back()
        waitFor(VIEWINGS_INTRO)
        back()
        compose.onNodeWithText("Server (optional)").assertExists()
        compose.onNodeWithText(VIEWINGS_INTRO).assertDoesNotExist()
        back()
        shows(MAP)
    }

    @Test
    fun settingsViewingsPlansANewViewingAndTheFormsBackReturnsToTheHistory() {
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        openSettingsViewings()
        compose.onNodeWithText(NO_VIEWINGS).assertExists()
        compose.onNodeWithText("Plan a viewing").performClick()
        compose.waitForIdle()
        // The form for a new viewing: titled with the button's words, in place of the history.
        waitFor(WITH_WHOM)
        compose.onNodeWithText("Plan a viewing").assertExists()
        compose.onNodeWithText(VIEWINGS_INTRO).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        waitFor(VIEWINGS_INTRO)
        compose.onNodeWithText(NO_VIEWINGS).assertExists()
    }

    @Test
    fun exportIsSingleTop() {
        start(DeepLink.OpenScreen(Routes.EXPORT))
        shows("Save a copy")
        links.value = DeepLink.OpenScreen(Routes.EXPORT)
        compose.waitForIdle()
        assertEquals(2, handled)
        back()
        shows(MAP)
    }
}
