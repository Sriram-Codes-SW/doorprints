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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled



import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.data.Repository
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.KindSetting
import app.doorprints.ui.DeviceBulkGuard
import app.doorprints.ui.KindsSettingsSection
import app.doorprints.ui.LocalRootSnackbar
import app.doorprints.ui.LocalSpace
import app.doorprints.ui.RecentlyDeletedSection
import app.doorprints.ui.SpaceUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The deletion safeguards on the phones (PR 3, S4b-BL-285; docs/02 T-D, TC-U-225), on the real app and Room: *Delete all in
 * this space* names the count and the kind, takes only that space's entries, asks for a tick over 10, focuses *Cancel*, has an
 * *Undo*, and runs once per 10 seconds; *Recently deleted* lists one space's entries with *Restore*. English; the other
 * languages are in `StringParityTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class, qualifiers = "w360dp-h2400dp")
class DeleteSpaceScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds = TwoKinds(app).also { it.enableSchool() }
    private val repo: Repository = kinds.repository
    private val at = 1_760_000_000_000

    @Before fun noPause() = DeviceBulkGuard.reset()

    @After fun close() {
        DeviceBulkGuard.reset()
        kinds.close()
    }

    private fun save(id: String, kind: String, label: String) = runBlocking {
        repo.saveHouse(HouseEntity(id = id, label = label, lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at, kind = kind))
    }

    private fun live() = runBlocking { repo.houses.first() }

    private fun show(space: String = "school") {
        val setting = KindSetting(listOf("house", "school"), experimental = "1", enabledText = "[\"house\",\"school\"]")
        compose.setContent {
            val snack = remember { SnackbarHostState() }
            CompositionLocalProvider(LocalInspectionMode provides true, LocalRootSnackbar provides snack, LocalSpace provides SpaceUi(setting, space)) {
                kinds.Provide {
                    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
                        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                            KindsSettingsSection()
                            RecentlyDeletedSection()
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun waitFor(what: String, done: () -> Boolean) = compose.waitUntil(10_000) { done() }.also { assertTrue(what, done()) }

    private fun deleteButton(kind: String): SemanticsNodeInteraction {
        // The setting and the counts arrive from DataStore and Room after the first frame.
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Delete all in this space: $kind").fetchSemanticsNodes().isNotEmpty() }
        return compose.onNodeWithContentDescription("Delete all in this space: $kind")
    }

    @Test fun theQuestionNamesTheCountAndTheKindAndDeletesOnlyThatSpaceWithAnUndo() {
        save("h1", "house", "Green View")
        save("h2", "house", "Blue Villa")
        for (i in 1..3) save("s$i", "school", "School $i")
        show()
        deleteButton("Schools").performClick()
        compose.onNodeWithText("Delete everything saved in Schools: 3?", substring = true).assertExists()
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onAllNodesWithText("I understand this cannot be undone").assertCountEquals(0)
        compose.onNodeWithText("Delete all").performClick()
        waitFor("only the schools are deleted") { live().map { it.id }.sorted() == listOf("h1", "h2") }
        compose.onNodeWithText("Deleted from Schools: 3").assertExists()
        compose.onNodeWithText("Undo").performClick()
        waitFor("the schools are back") { live().size == 5 }
        assertEquals(setOf("h1", "h2", "s1", "s2", "s3"), live().map { it.id }.toSet())
    }

    @Test fun overTenEntriesNeedTheTickBoxAndCancelDeletesNothing() {
        for (i in 1..11) save("s$i", "school", "School $i")
        show()
        deleteButton("Schools").performClick()
        compose.onNodeWithText("Delete everything saved in Schools: 11?", substring = true).assertExists()
        compose.onNodeWithText("Delete all").assertIsNotEnabled()
        compose.onNodeWithText("I understand this cannot be undone").performClick()
        compose.onNodeWithText("Delete all").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals(11, live().size)
    }

    @Test fun aSecondBulkActionWithinTenSecondsIsRefusedInAWord() {
        save("h1", "house", "Green View")
        save("s1", "school", "School 1")
        show()
        deleteButton("Schools").performClick()
        compose.onNodeWithText("Delete all").performClick()
        waitFor("the school is deleted") { live().map { it.id } == listOf("h1") }
        deleteButton("Houses").performClick()
        compose.onNodeWithText("A bulk delete just ran. Wait", substring = true).assertExists()
        compose.onAllNodesWithText("Delete everything saved in", substring = true).assertCountEquals(0)
        assertEquals(listOf("h1"), live().map { it.id })
    }

    @Test fun recentlyDeletedListsOneSpaceAndRestoreBringsBackOnlyThatRow() {
        save("h1", "house", "Green View")
        save("s1", "school", "Pine Hill School")
        save("s2", "school", "Oak School")
        runBlocking { repo.deleteHouses(listOf("h1", "s1")) }
        show("school")
        compose.onNodeWithText("Pine Hill School").assertExists()
        compose.onAllNodesWithText("Green View").assertCountEquals(0)
        compose.onNodeWithContentDescription("Restore Pine Hill School").performClick()
        waitFor("the school is live") { live().any { it.id == "s1" } }
        assertEquals(setOf("s1", "s2"), live().map { it.id }.toSet())
        compose.onNodeWithText("Restored Pine Hill School").assertExists()
    }

    @Test fun anEmptyBinSaysSo() {
        save("s1", "school", "Pine Hill School")
        show("school")
        compose.onNodeWithText("Nothing deleted in the last 30 days.").assertExists()
    }
}



