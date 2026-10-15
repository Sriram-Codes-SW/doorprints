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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.doorprints.data.Repository
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.AppServices
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LocalDevKinds
import app.doorprints.ui.ProvidePlatformServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * *Fill in from listing text* on a school's form (kinds PR 7, S4b-BL-211; S4b-BL-273, docs/06 TC-U-239), English: with the AI on
 * (a fake switch; nothing reaches a provider) the form of a school offers the button as a house's does, and with it off neither
 * does. What the button's dialog sends and the sentence it ends in are held below the screen (`pasteKind` in
 * `KindListingMergeTest`, `SchoolPasteSummaryTest`): a Compose dialog keeps the Robolectric looper from resting, so a test cannot
 * open one and read it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w411dp-h2600dp-hdpi", application = ScreenshotTestApp::class)
class SchoolPasteFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun setUp() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    /** The real repository with the AI switched on. */
    private class AiOn(real: Repository) : Repository by real {
        override val aiEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun open(ai: Boolean) {
        val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
        val services = object : AppServices by app.container.services {
            override val repository: Repository = if (ai) AiOn(app.container.repository) else app.container.repository
        }
        compose.setContent {
            ProvidePlatformServices {
                CompositionLocalProvider(LocalAppServices provides services, LocalDevKinds provides true) {
                    HouseEditScreen(houseId = null, newLat = 26.1433, newLon = 91.7898, visitId = null, onDone = {})
                }
            }
        }
        compose.waitUntil(10_000) { shows("Save a house") }
        compose.waitForIdle()
    }

    @Test
    fun aSchoolsFormOffersTheFillWhenTheAiIsOnAsAHousesDoesAndNotOtherwise() {
        open(ai = true)
        assertTrue("a house", shows("Fill in from listing text"))
        compose.onNodeWithContentDescription("Save a school").performClick()
        compose.waitForIdle()
        assertTrue("a school", shows("School name"))
        assertTrue("a school's button", shows("Fill in from listing text"))
    }

    @Test
    fun withTheAiOffNoFormOffersIt() {
        open(ai = false)
        assertFalse("a house", shows("Fill in from listing text"))
        compose.onNodeWithContentDescription("Save a school").performClick()
        compose.waitForIdle()
        assertTrue("a school", shows("School name"))
        assertFalse("a school's button", shows("Fill in from listing text"))
    }
}
