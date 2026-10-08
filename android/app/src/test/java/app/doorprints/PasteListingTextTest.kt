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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.ai.OnDeviceAi
import app.doorprints.ui.PasteListingText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S4b-BL-182 on screen, in English: Extract reads the first `OnDeviceAi.MAX_INPUT_CHARS` characters of the pasted
 * listing, and the box says how many at the end are left out (nothing at the limit), in a polite live region that is
 * there before the line. The cut itself is `ListingCut` (`ParityVectorsTest`, `ListingLimitsTest`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class PasteListingTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val cap = OnDeviceAi.MAX_INPUT_CHARS
    private val polite = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)

    private fun show(text: String) = compose.setContent { PasteListingText(text, {}) }

    @Test
    fun saysNothingAtTheLimit() {
        show("x".repeat(cap))
        compose.onNode(polite).assertExists()
        compose.onNodeWithText("characters are read", substring = true).assertDoesNotExist()
    }

    @Test
    fun countsTheSpacesRoundTheTextAsNotSent() {
        show("  " + "x".repeat(cap) + "   ")
        compose.onNodeWithText("characters are read", substring = true).assertDoesNotExist()
    }

    @Test
    fun saysHowManyCharactersAtTheEndAreLeftOut() {
        show("x".repeat(cap + 1))
        compose.onNodeWithText("Only the first 8,000 characters are read. Characters left out at the end: 1.").assertExists()
    }

    @Test
    fun groupsALargeCount() {
        show("x".repeat(cap + 12_345))
        compose.onNodeWithText("Only the first 8,000 characters are read. Characters left out at the end: 12,345.").assertExists()
    }

    @Test
    fun theLineIsInsideThePoliteLiveRegion() {
        show("x".repeat(cap + 5))
        compose.onNode(hasText("Characters left out at the end: 5.", substring = true) and hasAnyAncestor(polite)).assertExists()
    }
}
