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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.data.VisitEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.ui.DeepLink
import app.doorprints.ui.DoorprintsRoot
import app.doorprints.ui.ProvideAppServices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The stay-alert link with a visit id (S4b-BL-38): "Save this house?" from a Hunt mode arrival alert carries the visit.
 * Root looks the visit up in Room first; a visit already saved as a house opens that house, not a second new-house form
 * that would save a duplicate, and the same unsaved visit tapped twice stacks one form. [RootNavigationTest] cannot
 * cover it: with the unconfined test dispatcher the continuation after Room's answer stays on Room's thread, where
 * `NavController.navigate` fails (S4b-BL-46). These tests run the effects on a [StandardTestDispatcher], so on the main
 * (test) thread as in the app, and run them from [pump], as ScreensScreenshotTest does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class RootNavigationVisitLinkTest {
    private val effects = StandardTestDispatcher()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = effects)

    private val links = MutableStateFlow<DeepLink?>(null)
    private var handled = 0
    private val at = 1_760_000_000_000
    private val repo get() = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository

    @Before fun clearFileProviderCache() {
        // As in RootNavigationTest: androidx FileProvider caches its path roots, Robolectric gives each test a new data dir.
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private fun start(link: DeepLink?) {
        links.value = link
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                ProvideAppServices {
                    DoorprintsRoot(links, onDeepLinkHandled = { handled++; links.value = null })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun pump() = compose.runOnIdle { effects.scheduler.runCurrent() }

    /** Runs the effects that resumed since (Room's answer) on the main thread, until [text] shows. */
    private fun waitFor(text: String) = compose.waitUntil(10_000) {
        pump()
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        pump()
    }

    @Test
    fun aVisitAlreadySavedAsAHouseOpensThatHouseNotANewForm() {
        runBlocking {
            repo.saveHouse(HouseEntity(id = "h-visit", label = "Green View", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at))
            repo.saveVisit(VisitEntity(id = "visit-saved", houseId = "h-visit", lat = 12.97, lon = 77.59, arrivedAt = at, updatedAt = at))
        }
        start(DeepLink.NewHouse(12.97, 77.59, "visit-saved"))
        waitFor("House details")
        assertEquals(0, count("Save a house"))
        assertEquals(1, handled)
        back()
        waitFor("Hunt mode")
    }

    @Test
    fun anUnsavedVisitOpensTheNewHouseFormOnceEvenWhenTappedTwice() {
        runBlocking { repo.saveVisit(VisitEntity(id = "visit-open", lat = 12.9716, lon = 77.5946, arrivedAt = at, updatedAt = at)) }
        start(DeepLink.NewHouse(12.9716, 77.5946, "visit-open"))
        waitFor("Save a house")
        links.value = DeepLink.NewHouse(12.9716, 77.5946, "visit-open")
        // The form is already showing, so wait for the second tap itself to be handled (Room's answer resumes it later).
        compose.waitUntil(10_000) {
            pump()
            handled == 2
        }
        assertEquals(1, count("Save a house"))
        back()
        // One form was stacked: a single Back reaches the Map.
        waitFor("Hunt mode")
        assertEquals(0, count("Save a house"))
        compose.onNodeWithText("Hunt mode").assertExists()
    }
}
