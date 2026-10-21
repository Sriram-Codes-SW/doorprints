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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.UserKindFiles
import app.doorprints.shared.model.UserKindRead
import app.doorprints.shared.model.UserKinds
import app.doorprints.ui.AppServices
import app.doorprints.ui.KindFilePick
import app.doorprints.ui.KindsSettingsSection
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LocalDevKinds
import app.doorprints.ui.LocalRootSnackbar
import app.doorprints.ui.LocalSpace
import app.doorprints.ui.ProvidePlatformServices
import app.doorprints.ui.SettingsServices
import app.doorprints.ui.SpaceUi
import app.doorprints.ui.SpaceWords
import app.doorprints.ui.stringResource as uiString
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Settings > What you track > *Kinds you add* on the phones (docs/03 ADR-39, docs/06 TC-U-297), on the real app and Room, with
 * the platform's picker replaced by the file a test hands it: off by default and nothing to import; on, *Import a kind file*
 * shows a preview of a checked file (the person's words as plain text: a link, a formula, markup-like words stay words) and
 * stores it only on *Add this kind*; a hostile file is refused with the first fault in plain words and where it is, and a file
 * over 64 KB before it is read; *Remove kind* asks first (focus on *Cancel*, *Save a copy first*) and deletes no entry. The
 * space of an added kind reads its own plural and *new* and the neutral words elsewhere. English; the other languages are
 * `UserKindStringsTest`'s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class, qualifiers = "w360dp-h2400dp")
class UserKindsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds = TwoKinds(app)
    private val repo = kinds.repository
    private val at = 1_760_000_000_000

    /** What the next *Import a kind file* reads. */
    private var next: KindFilePick = KindFilePick.Failed
    private var picks = 0

    private val settings: SettingsServices = object : SettingsServices by kinds.services.settingsScreen {
        @Composable
        override fun rememberKindFilePicker(onPicked: (KindFilePick) -> Unit): () -> Unit = {
            picks++
            onPicked(next)
        }
    }
    private val services: AppServices = object : AppServices by kinds.services {
        override val settingsScreen: SettingsServices get() = settings
    }

    private val vectors = run {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/schemas/kinds/user-kind-vectors.json").isFile) dir = dir.parentFile
        Json.parseToJsonElement(File(checkNotNull(dir), "docs/schemas/kinds/user-kind-vectors.json").readText()).jsonObject
    }

    private fun sample(id: String): JsonObject =
        vectors.getValue("valid").jsonArray.map { it.jsonObject.getValue("file").jsonObject }.first { it.getValue("id").jsonPrimitive.content == id }

    @After fun close() {
        kinds.close()
        UserKinds.load(null)
    }

    private fun show() {
        compose.setContent {
            val snack = remember { SnackbarHostState() }
            ProvidePlatformServices {
                CompositionLocalProvider(
                    LocalInspectionMode provides true, LocalAppServices provides services, LocalDevKinds provides true, LocalRootSnackbar provides snack,
                    LocalSpace provides SpaceUi(KindSetting(listOf("house", "school")), "house"),
                ) {
                    Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
                        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) { KindsSettingsSection() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun setting() = runBlocking { repo.settings.kindSetting().first() }

    private fun turnOn() {
        waitFor("Kinds you add (experimental)")
        compose.onAllNodesWithText("Import a kind file").assertCountEquals(0)
        compose.onNodeWithText("Kinds you add (experimental)").performClick()
        waitFor("Import a kind file")
    }

    @Test fun aCheckedFileIsPreviewedAsPlainTextAndStoredOnlyOnAdd() {
        show()
        turnOn()
        next = KindFilePick.Text(sample("u_gym_two").toString())
        compose.onNodeWithText("Import a kind file").performClick()
        waitFor("Check this kind before you add it")
        // The person's words are text: a link-like name and a formula are shown as written (a Compose Text, never markup or a link).
        compose.onNodeWithText("Name: javascript:alert(1)").assertExists()
        compose.onNodeWithText("Check this kind before you add it").assertIsFocused()
        assertEquals(listOf("house", "school"), setting().available)
        compose.onNodeWithText("Add this kind").performClick()
        waitFor("javascript:alert(1) added. Turn it on below to use it.")
        assertEquals(listOf("house", "school", "u_gym_two"), setting().available)
        // Its row: the plural as written, *Added by you*, *Remove kind*; it is not turned on.
        waitFor("Added by you")
        compose.onNodeWithText("Show javascript:alert(1)").assertExists()
        assertEquals(listOf("house"), setting().enabled)
    }

    @Test fun aHostileFileIsRefusedInPlainWordsWithWhereAndNothingIsStored() {
        show()
        turnOn()
        next = KindFilePick.Text(JsonObject(sample("u_cafe") + ("style" to JsonPrimitive("https://tiles.example/style.json"))).toString())
        compose.onNodeWithText("Import a kind file").performClick()
        waitFor("This file was not added: it tries to set up the map. A kind file cannot change the map or its boundaries.")
        compose.onNodeWithText("Where in the file: \$.style").assertExists()
        next = KindFilePick.Text(JsonObject(sample("u_cafe") + ("__proto__" to JsonObject(emptyMap()))).toString())
        compose.onNodeWithText("Import a kind file").performClick()
        waitFor("it uses a name that is not allowed", substring = true)
        next = KindFilePick.TooBig
        compose.onNodeWithText("Import a kind file").performClick()
        waitFor("This file was not added: the file is larger than 64 KB.")
        compose.onAllNodesWithText("Where in the file", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Check this kind before you add it").assertCountEquals(0)
        assertEquals(listOf("house", "school"), setting().available)
        assertEquals(3, picks)
    }

    @Test fun cancelStoresNothingAndASecondFileOfTheSameIdSaysItReplaces() {
        runBlocking { repo.settings.addUserKind((UserKindFiles.read(sample("u_gym").toString()) as UserKindRead.Ok).file) }
        show()
        turnOn()
        next = KindFilePick.Text(JsonObject(sample("u_cafe") + ("id" to JsonPrimitive("u_gym"))).toString())
        compose.onNodeWithText("Import a kind file").performClick()
        waitFor("You already have this kind (Gyms). Adding it replaces its fields and words; its entries are kept.")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertEquals("Gyms", runBlocking { setting() }.let { UserKinds.file("u_gym")?.many })
        compose.onNodeWithText("Import a kind file").performClick()
        waitFor("Replace the kind")
        compose.onNodeWithText("Replace the kind").performClick()
        waitFor("Cafes updated. Its entries were kept.")
    }

    @Test fun removingAKindAsksFirstAndKeepsItsEntries() {
        runBlocking {
            repo.settings.addUserKind((UserKindFiles.read(sample("u_gym").toString()) as UserKindRead.Ok).file)
            repo.settings.editKindSetting { it.withCustom(true).withKindEnabled("u_gym", true) }
            repo.saveHouse(HouseEntity(id = "g1", label = "Iron Temple", lat = 12.97, lon = 77.59, createdAt = at, updatedAt = at, kind = "u_gym"))
        }
        show()
        waitFor("Added by you")
        compose.onNodeWithContentDescription("Remove the kind Gyms").performClick()
        waitFor("Remove the kind Gyms? Its 1 saved entries are kept", substring = true)
        compose.onNodeWithText("Cancel").assertIsFocused()
        compose.onNodeWithText("Save a copy first").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue("u_gym" in setting().available)
        compose.onNodeWithContentDescription("Remove the kind Gyms").performClick()
        compose.onNode(hasText("Remove kind") and hasClickAction() and !hasContentDescription("Remove the kind Gyms")).performClick()
        waitFor("Gyms removed. Its entries were kept.")
        assertEquals(listOf("house", "school"), setting().available)
        assertEquals("u_gym", runBlocking { repo.getHouse("g1") }?.kind)
    }

    @Test fun theSpaceOfAnAddedKindReadsItsOwnPluralAndNewAndTheNeutralWordsElsewhere() {
        runBlocking { repo.settings.addUserKind((UserKindFiles.read(sample("u_gym").toString()) as UserKindRead.Ok).file) }
        setting()
        val words = SpaceWords.of("u_gym")
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Column {
                    androidx.compose.material3.Text(uiString(words.many))
                    androidx.compose.material3.Text(uiString(words.addOnMap))
                    androidx.compose.material3.Text(org.jetbrains.compose.resources.stringResource(words.saveHere))
                    androidx.compose.material3.Text(org.jetbrains.compose.resources.stringResource(words.noMatch))
                }
            }
        }
        for (text in listOf("Gyms", "New gym", "Save entry here", "Nothing matches your search or filter.")) {
            compose.onAllNodes(hasText(text)).assertCountEquals(1)
        }
    }
}
