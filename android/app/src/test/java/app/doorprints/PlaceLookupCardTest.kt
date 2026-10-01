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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.PlaceLookupCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S4b-BL-83 on screen, in English: the Map's *Find “Indiranagar” on the map* for a shared listing hands the two
 * sentences to say to the lookup, is disabled while it runs, and shows what it found. The lookup itself is the
 * platform's (`ReverseGeocoder.find`); its pure parts are `PlaceLookupTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class PlaceLookupCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun findLooksUpOnTheTapAndSaysWhatItFound() {
        val asked = mutableListOf<Pair<String, String>>()
        compose.setContent {
            var finding by remember { mutableStateOf(false) }
            var note by remember { mutableStateOf<String?>(null) }
            PlaceLookupCard(place = "Indiranagar", finding = finding, note = note, onFind = { found, notFound ->
                asked += found to notFound
                finding = true
                note = found
            })
        }
        compose.onNodeWithText("Find “Indiranagar” on the map").performClick()
        assertEquals(
            listOf(
                "The map shows “Indiranagar”. Long-press the house’s spot to add it there." to
                    "No place called “Indiranagar” was found in India. Move the map to the house yourself.",
            ),
            asked,
        )
        compose.onNodeWithText("Finding “Indiranagar”…").assertIsNotEnabled()
        compose.onNodeWithText("The map shows “Indiranagar”. Long-press the house’s spot to add it there.").assertExists()
    }
}
