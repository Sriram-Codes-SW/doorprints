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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.doorprints.data.HouseEntity
import app.doorprints.screenshots.ScreenshotTestApp
import app.doorprints.shared.model.DefaultQuestions
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.Question
import app.doorprints.ui.HouseEditScreen
import app.doorprints.ui.ProvideAppServices
import app.doorprints.ui.QuestionsScreen
import app.doorprints.ui.QuestionsSection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Questions screen and the house form's *Questions to ask* section (docs/11 5.5, slice 3a), in English on the real
 * repository: editing a question, its category, *Applies to* and *Ask by default*, moving, archiving and bringing back,
 * deleting, adding, the cap of 100 and *Reset to defaults*; on a house, adding the usual questions (with the cost's
 * pre-fill), adding one from the bank or of its own, answering, skipping, removing, the cap of 60 and "Already added".
 * Every control is found by the label that names its question, as TalkBack reads it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = ScreenshotTestApp::class)
class QuestionsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val repo = ApplicationProvider.getApplicationContext<DoorprintsApp>().container.repository
    private val at = 1_760_000_000_000

    private fun bank(): List<Question> = runBlocking { repo.questions() }

    @Before fun clearFileProviderCache() {
        // The house form's camera file goes through androidx FileProvider, which caches its path roots per authority in
        // a static map, but Robolectric gives each test a new data directory (as in RootNavigationTest).
        runCatching {
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .let { (it.get(null) as MutableMap<*, *>).clear() }
        }
    }

    /** Waits until the repository says [condition]: the screen saves on Room's own threads. */
    private fun until(condition: (List<Question>) -> Boolean) = compose.waitUntil(5_000) { condition(bank()) }

    private fun show() {
        compose.setContent { ProvideAppServices { QuestionsScreen(onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Reset to defaults").fetchSemanticsNodes().isNotEmpty() }
    }

    /** Clicks the control [description] names, once the screen shows it (the repository answers after the write). */
    private fun click(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription(description, useUnmergedTree = true).performScrollTo().performClick()
    }

    private fun addTwo(): Pair<String, String> = runBlocking {
        repo.addQuestion("Is there a lift?") to repo.addQuestion("Is the terrace open?")
    }

    // ---- Settings > Questions ----

    @Test
    fun editingTheTextCategoryScopeAndDefaultSavesTheOneRecord() {
        val (lift, _) = addTwo()
        show()
        compose.onNodeWithText("Is there a lift?").performScrollTo().performTextReplacement("Is there a lift and a generator?")
        until { q -> q.first { it.id == lift }.text == "Is there a lift and a generator?" }
        compose.onAllNodesWithContentDescription("Applies to: Buy", useUnmergedTree = true)[0].performScrollTo().performClick()
        until { q -> q.first { it.id == lift }.appliesTo == "SALE" }
        compose.onAllNodesWithText("Ask by default")[0].performScrollTo().performClick()
        until { q -> q.first { it.id == lift }.defaultOn }
        compose.onAllNodesWithContentDescription("Category", useUnmergedTree = true)[0].performScrollTo().performClick()
        compose.onNodeWithText("Building").performClick()
        until { q -> q.first { it.id == lift }.category == "BUILDING" }
        // The other question was not touched.
        assertEquals(listOf("OTHER", "BOTH", "false"), bank().last().let { listOf(it.category, it.appliesTo, it.defaultOn.toString()) })
    }

    @Test
    fun movingArchivingBringingBackAndDeleting() {
        val (lift, terrace) = addTwo()
        show()
        click("Move Is there a lift? down")
        until { q -> q.map { it.id } == listOf(terrace, lift) }
        click("Archive Is there a lift?")
        until { q -> q.first { it.id == lift }.archived }
        click("Bring back Is there a lift?")
        until { q -> !q.first { it.id == lift }.archived }
        click("Delete Is the terrace open?")
        until { q -> q.none { it.id == terrace } }
        assertTrue(runBlocking { repo.localVersions() }.questions.containsKey(terrace))
    }

    @Test
    fun addingAQuestionAndTheCapOfAHundred() {
        show()
        compose.onNodeWithText("No questions. Add one below, or reset to the defaults.").assertExists()
        compose.onNodeWithText("New question").performScrollTo().performTextInput("Is there a water meter?")
        compose.onNodeWithText("Add question").performScrollTo().performClick()
        until { q -> q.any { it.text == "Is there a water meter?" && Question.isCustomId(it.id) } }
        runBlocking { repeat(99) { repo.addQuestion("Custom $it") } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("At most 100 questions").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add question").performScrollTo().assertIsNotEnabled()
        assertEquals(100, bank().size)
    }

    @Test
    fun resetToDefaultsBringsBackTheStandardQuestionsAndKeepsYourOwn() {
        runBlocking {
            repo.seedQuestions("en")
            repo.deleteQuestion("qd_pets")
        }
        val mine = runBlocking { repo.addQuestion("Is there a water meter?") }
        show()
        compose.onNodeWithText("Reset to defaults").performScrollTo().performClick()
        compose.onNodeWithText("Brings back the standard questions in your language. Your own questions stay.").assertExists()
        compose.onNodeWithText("Reset").performClick()
        until { q -> q.any { it.id == "qd_pets" } }
        assertEquals(DefaultQuestions.ALL.map { it.id } + mine, bank().map { it.id })
    }

    // ---- the house form's Questions to ask ----

    private val cost = HouseCost(deposit = 64_000, depositMonths = 2, maintenance = 2_500, maintenanceIncluded = false)

    /** The section alone around a list of answers the test owns, on the seeded English bank. */
    private fun section(start: List<HouseAnswer>? = null, priceType: String = "RENT"): () -> List<HouseAnswer>? {
        val bank = DefaultQuestions.bank("en")
        var answers by mutableStateOf(start)
        compose.setContent {
            ProvideAppServices {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    QuestionsSection(answers, bank, priceType, cost) { answers = it }
                }
            }
        }
        return { answers }
    }

    @Test
    fun addingTheUsualQuestionsPreFillsTheCostAndThenSaysAlreadyAdded() {
        val answers = section()
        compose.onNodeWithText("No questions yet. Add the usual questions to bring them to the viewing.").assertExists()
        compose.onNodeWithText("Add the usual questions").performScrollTo().performClick()
        compose.waitForIdle()
        val added = answers()!!
        assertEquals(8, added.size)
        assertEquals("₹64,000, 2 months", added.first { it.questionId == "qd_deposit" }.answer)
        assertEquals("₹2,500 a month (not included)", added.first { it.questionId == "qd_maintenance" }.answer)
        compose.onNodeWithText("2 of 8 answered").assertExists()
        compose.onNodeWithText("Add the usual questions").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Already added").assertExists()
    }

    @Test
    fun answeringSkippingAndRemovingAQuestion() {
        val water = DefaultQuestions.byId("qd_water")!!.textIn("en")
        val answers = section(listOf(HouseAnswer("a1", "qd_water", water), HouseAnswer("a2", text = "Is the terrace open?", sort = 1)))
        compose.onNodeWithText("0 of 2 answered").assertExists()
        compose.onAllNodesWithText("Answer")[0].performScrollTo().performTextInput("Borewell")
        compose.waitForIdle()
        assertEquals(HouseAnswer("a1", "qd_water", water, "Borewell", "ANSWERED", 0), answers()!!.first())
        compose.onNodeWithText("1 of 2 answered").assertExists()
        // Clearing it opens it again.
        compose.onNodeWithText("Borewell").performTextReplacement("")
        compose.waitForIdle()
        assertEquals("OPEN", answers()!!.first().status)
        assertNull(answers()!!.first().answer)
        click("Skip: Is the terrace open?")
        compose.waitForIdle()
        assertEquals("SKIPPED", answers()!!.last().status)
        click("Remove: Is the terrace open?")
        compose.waitForIdle()
        assertEquals(listOf("a1"), answers()!!.map { it.id })
        click("Remove: $water")
        compose.waitForIdle()
        assertNull(answers())
    }

    @Test
    fun addingOneQuestionFromTheBankOrOfItsOwn() {
        val answers = section()
        compose.onNodeWithText("Add a question").performScrollTo().performClick()
        // The picker offers the bank by category, the ones not asked yet, pets included (it is not asked by default).
        compose.onNodeWithText("Rules").assertExists()
        compose.onNodeWithText(DefaultQuestions.byId("qd_pets")!!.textIn("en")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("qd_pets"), answers()!!.map { it.questionId })
        compose.onNodeWithText("Add a question").performScrollTo().performClick()
        compose.onNodeWithText("Or ask something else").performScrollTo().performTextInput("Is the terrace open?")
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
        assertEquals(HouseAnswer(answers()!!.last().id, null, "Is the terrace open?", null, "OPEN", 1), answers()!!.last())
    }

    @Test
    fun atSixtyQuestionsBothAddsAreOff() {
        section((0 until HouseAnswers.MAX).map { HouseAnswer("a$it", text = "Question $it", sort = it) })
        compose.onNodeWithText("At most 60 questions").performScrollTo().assertExists()
        compose.onNodeWithText("Add the usual questions").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Add a question").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun theHouseFormShowsTheQuestionsAfterTheRoomsAndSavesThem() {
        runBlocking {
            repo.seedQuestions("en")
            repo.saveHouse(HouseEntity(id = "a", label = "Green View", lat = 12.97, lon = 77.59, priceType = "SALE", createdAt = at, updatedAt = at))
        }
        compose.setContent { ProvideAppServices { HouseEditScreen(houseId = "a", newLat = null, newLon = null, visitId = null, onDone = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Questions to ask").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) {
            runCatching { compose.onNodeWithText("Add the usual questions").performScrollTo().assertIsEnabled() }.isSuccess
        }
        compose.onNodeWithText("Add the usual questions").performClick()
        compose.onNodeWithText("0 of 9 answered").performScrollTo().assertExists()
        // The bar's Save (the form has a second one at its foot).
        compose.onAllNodesWithText("Save")[1].performClick()
        compose.waitUntil(5_000) { runBlocking { repo.getHouse("a") }?.answers?.size == 9 }
        assertEquals("qd_khata", runBlocking { repo.getHouse("a") }!!.answers!!.last().questionId)
    }
}
