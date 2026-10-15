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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.shared.model.HouseStatus
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.KindSetting
import app.doorprints.ui.CompareScreen
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.HouseListScreen
import app.doorprints.ui.LocalAppServices
import app.doorprints.ui.LocalSpace
import app.doorprints.ui.SpaceUi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A school's pages on the phones say nothing of a house (kinds PR 16, phone half; S4b-BL-256, S4b-BL-259, S4b-BL-266,
 * S4b-BL-277; docs/03 §18.9; docs/06 TC-U-230), on the real app and Room, in English (the other languages' words are
 * `SchoolPageWordsTest`'s, against the website's): the form's title, the walk check, the delete question, the not-found card
 * and a new candidate's name; the list row without a price or bedrooms; Compare's *Contact name*. A house's pages
 * are the screenshots' (`ScreensScreenshotTest`), which this change does not move.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class, qualifiers = "w360dp-h2400dp")
class SchoolWordsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<DoorprintsApp>()
    private val kinds = TwoKinds(app).also { it.enableSchool() }
    private val setting = KindSetting(listOf("house", "school"), experimental = "1", enabledText = "[\"house\",\"school\"]")

    // The form's camera file goes through FileProvider, whose cache outlives a Robolectric application (as in MovingInScreenTest).
    @Before fun clearFileProviderCache() {
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    @After fun close() = kinds.close()

    /** The sample school, with the values of a house a stray sync could have left on it. */
    private fun save(id: String = SchoolSample.ID) = runBlocking {
        kinds.repository.saveHouse(
            SchoolSample.entity().copy(
                id = id, label = if (id == SchoolSample.ID) "Pine Hill School" else "Oak Lane School", price = 48_000, priceType = "RENT",
                bedrooms = 3, status = HouseStatus.SHORTLISTED, rating = 4, contactName = "Mrs Rao",
            ),
        )
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalSpace provides SpaceUi(setting, "school")) { kinds.Provide { content() } }
        }
        compose.waitForIdle()
    }

    private fun text(s: String) = compose.onAllNodesWithText(s, substring = true)

    @Test fun theFormSaysSchoolInItsTitleAndItsWalkCheck() {
        save()
        show { HouseEditScreen(houseId = SchoolSample.ID, newLat = null, newLon = null, visitId = null, onDone = {}) }
        // The title is there before the form's body is: wait for the walk check itself.
        compose.waitUntil(10_000) { text("Did I walk past this school?").fetchSemanticsNodes().isNotEmpty() }
        text("School details").assertCountEquals(1)
        text("House details").assertCountEquals(0)
        text("Did I walk past this house?").assertCountEquals(0)
    }

    @Test fun theDeleteQuestionAsksAboutTheSchool() {
        save()
        show { HouseEditScreen(houseId = SchoolSample.ID, newLat = null, newLon = null, visitId = null, onDone = {}) }
        compose.waitUntil(10_000) { text("School details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Delete school").performClick()
        compose.onNodeWithText("Delete this school?").assertIsDisplayed()
        text("Delete this house?").assertCountEquals(0)
    }

    @Test fun aSchoolThatIsNotThereIsNotFoundAsASchool() {
        show { HouseEditScreen(houseId = "gone", newLat = null, newLon = null, visitId = null, onDone = {}) }
        compose.waitUntil(10_000) { text("School not found").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("This school is no longer on this phone.").assertExists()
        text("House not found").assertCountEquals(0)
    }

    @Test fun aNewSchoolStartsAsANewSchool() {
        show { HouseEditScreen(houseId = null, newLat = 26.1433, newLon = 91.7898, visitId = null, onDone = {}) }
        compose.waitUntil(10_000) { text("New school").fetchSemanticsNodes().isNotEmpty() }
        text("New house").assertCountEquals(0)
    }

    // The school has a checklist of its own since kinds PR 27 (docs/03 §18.21), so its row shows a score as a house's does (here
    // the rating, as nothing is checked yet): "Score 4.0".
    @Test fun theListRowShowsTheNameTheStatusTheScoreAndTheStreetButNoPriceOrBedrooms() {
        save()
        show { HouseListScreen(onOpenHouse = {}) }
        compose.waitUntil(10_000) { text("Pine Hill School").fetchSemanticsNodes().isNotEmpty() }
        text("BHK").assertCountEquals(0)
        text("48,000").assertCountEquals(0)
        text("Score 4.0").assertCountEquals(1)
    }

    @Test fun compareNamesTheContactRowByTheSchoolsLabel() {
        save(); save("55555555-5555-4555-8555-000000000210")
        show {
            val repo = LocalAppServices.current.repository
            val houses by repo.houses.collectAsState(initial = null)
            val counts by repo.visitCounts.collectAsState(initial = emptyList())
            CompareScreen(houses, counts, onOpenHouse = {})
        }
        // Each row is one item for TalkBack, saying its heading and every school's value: "Contact name: Oak Lane School, ...".
        fun rows(heading: String) = compose.onAllNodesWithContentDescription("$heading:", substring = true)
        compose.waitUntil(10_000) { rows("Board").fetchSemanticsNodes().isNotEmpty() }
        rows("Contact name").assertCountEquals(1)
        rows("Contact").assertCountEquals(0) // the house's heading for the row, *Contact*, is not there
    }
}
