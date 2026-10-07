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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.TourEnd
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The guided tour on the real screens (S4b-FR-39), through the real root: the one-time offer on the Map and what it
 * remembers, every step in order with its counter, Skip, Back and the system Back, *Take the tour* in Settings, a
 * highlighted control that still takes the touch, and a card that never covers it, at the normal and at twice the font
 * size. The pure rules are in `TourTest` (`:ui`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class, qualifiers = "w360dp-h740dp")
class TourOverlayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val links = MutableStateFlow<DeepLink?>(null)

    private val settings get() = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository.settings

    private fun storedEnd(): TourEnd? = runBlocking { settings.tourEnd().first() }

    private fun start(link: DeepLink? = null, fontScale: Float = 1f) {
        links.value = link
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                ProvideAppServices { DoorprintsRoot(links, onDeepLinkHandled = { links.value = null }) }
            }
        }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun has(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun hasSub(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(text: String) = compose.waitUntil(5_000) { has(text) }

    private fun tap(text: String) {
        compose.onNodeWithText(text).performClick()
        compose.waitForIdle()
    }

    /** Takes the tour from its offer and waits for the first card. */
    private fun takeTheTour() {
        waitFor("Take the tour")
        tap("Take the tour")
        waitFor("Welcome to Doorprints")
    }

    /** "Step n of N" of the card on screen: (n, N). */
    private fun counter(): Pair<Int, Int> {
        val node = compose.onNode(hasText("Step ", substring = true)).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }
        val m = Regex("""Step (\d+) of (\d+)""").matchEntire(text)!!
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun boundsOf(text: String): Rect {
        val b = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        return Rect(b.left, b.top, b.right, b.bottom)
    }

    /** The tour card's box: from its counter line to its buttons, whatever the step. */
    private fun cardBounds(): Rect {
        val top = compose.onNode(hasText("Step ", substring = true)).fetchSemanticsNode().boundsInRoot
        val bottom = compose.onNodeWithText("Skip tour").fetchSemanticsNode().boundsInRoot
        return Rect(top.left, top.top, top.right, bottom.bottom)
    }

    /** Presses Next until the card titled [title] is on screen. */
    private fun nextUntil(title: String) {
        var guard = 0
        while (!has(title) && guard++ < 40) {
            tap("Next")
        }
        waitFor(title)
    }

    @Test
    fun theFirstRunOffersTheTourAndNotNowIsRememberedSoItComesOnce() {
        assertNull(storedEnd())
        start()
        waitFor("New here? Take a quick tour")
        compose.onNodeWithText("Take the tour").assertIsDisplayed()
        tap("Not now")
        assertFalse(has("New here? Take a quick tour"))
        compose.waitUntil(5_000) { storedEnd() == TourEnd.SKIPPED }
        // Back on the Map after a visit elsewhere, it is not offered again.
        tap("Settings")
        tap("Map")
        assertFalse(has("New here? Take a quick tour"))
    }

    @Test
    fun theOfferIsOnTheHomeTabOnly() {
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        compose.onNodeWithText("Server (optional)").assertExists()
        assertFalse(has("New here? Take a quick tour"))
    }

    @Test
    fun theTourWalksEveryStepInOrderAndFinishRemembersDone() {
        start()
        takeTheTour()
        val (first, total) = counter()
        assertEquals(1, first)
        assertTrue("every feature has a step: $total", total >= 20)
        compose.onNodeWithText("Back").assertDoesNotExist()
        for (k in 2..total) {
            tap("Next")
            assertEquals("step $k", k to total, counter())
        }
        // The last card: its button says Finish, and the tour had no Hunt-less step on the way (an Android phone).
        compose.onNodeWithText("You are ready").assertExists()
        compose.onNodeWithText("Next").assertDoesNotExist()
        tap("Back")
        assertEquals(total - 1, counter().first)
        tap("Next")
        tap("Finish")
        assertFalse(has("You are ready"))
        compose.waitUntil(5_000) { storedEnd() == TourEnd.DONE }
        assertFalse(has("New here? Take a quick tour"))
    }

    @Test
    fun skipTourAndTheSystemBackBothEndItAsSkipped() {
        start()
        takeTheTour()
        tap("Next")
        tap("Skip tour")
        assertFalse(hasSub("Step "))
        compose.waitUntil(5_000) { storedEnd() == TourEnd.SKIPPED }

        // Take it again from Settings, and leave with the system Back.
        tap("Settings")
        compose.onNodeWithText("Take the tour").performScrollTo().performClick()
        waitFor("Welcome to Doorprints")
        back()
        assertFalse(has("Welcome to Doorprints"))
        compose.onNodeWithText("Hunt mode").assertExists()
    }

    @Test
    fun takeTheTourInSettingsStartsItEvenAfterItWasSeen() {
        runBlocking { settings.setTourEnd(TourEnd.DONE) }
        start(DeepLink.OpenScreen(Routes.SETTINGS))
        assertFalse(has("New here? Take a quick tour"))
        compose.onNodeWithText("Take the tour").performScrollTo().performClick()
        waitFor("Welcome to Doorprints")
        assertEquals(1, counter().first)
        // The first step is on the Map, where the app went by itself.
        compose.onNodeWithText("Hunt mode").assertExists()
        compose.onNodeWithText("Server (optional)").assertDoesNotExist()
    }

    @Test
    fun theHighlightedControlStillTakesTheTouch() {
        runBlocking { settings.setTourEnd(TourEnd.SKIPPED) }
        start()
        // Replay from Settings, then on to the Criteria step.
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Take the tour").performScrollTo().performClick()
        waitFor("Welcome to Doorprints")
        nextUntil("Say what matters to you")
        compose.waitForIdle()
        // The card is up, the screen is dimmed round the row, and a tap on the row opens Criteria all the same.
        compose.onNodeWithText("Server (optional)").assertExists()
        compose.onNodeWithText("Criteria").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Server (optional)").assertDoesNotExist()
    }

    private fun cardNeverCoversTheTarget(fontScale: Float) {
        runBlocking { settings.setTourEnd(TourEnd.SKIPPED) }
        start(fontScale = fontScale)
        compose.onNodeWithText("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Take the tour").performScrollTo().performClick()
        waitFor("Welcome to Doorprints")
        // (title of the step, the text of the control it points at)
        val steps = listOf(
            "Add a house on the map" to "Save house here",
            "Find a house again" to "Search name, street, notes",
            "Compare houses" to "Compare",
            "Say what matters to you" to "Criteria",
            "Help is one tap away" to "Help",
        )
        for ((title, target) in steps) {
            nextUntil(title)
            // Let the scroll to the target and the layout after it settle.
            compose.waitForIdle()
            val card = cardBounds()
            for (node in compose.onAllNodesWithText(target).fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.height > 0f }) {
                val control = Rect(node.left, node.top, node.right, node.bottom)
                assertFalse("$title: card $card covers $target $control (font x$fontScale)", card.overlaps(control))
            }
            compose.onNodeWithText("Skip tour").assertIsDisplayed()
            compose.onNodeWithText("Next").assertIsDisplayed()
        }
    }

    @Test
    fun theCardNeverCoversWhatItPointsAt() = cardNeverCoversTheTarget(1f)

    @Test
    fun theCardNeverCoversWhatItPointsAtAtTwiceTheFontSizeAndKeepsItsButtons() = cardNeverCoversTheTarget(2f)
}
