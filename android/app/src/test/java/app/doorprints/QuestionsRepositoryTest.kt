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

import android.app.Application
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.doorprints.data.AndroidRepository
import app.doorprints.data.AppDatabase
import app.doorprints.data.DatabaseFile
import app.doorprints.data.HouseEntity
import app.doorprints.data.RecordEntity
import app.doorprints.data.SecretStore
import app.doorprints.data.SettingsStore
import app.doorprints.data.create
import app.doorprints.data.toBundle
import app.doorprints.data.toDto
import app.doorprints.data.toEntity
import app.doorprints.shared.export.BackupData
import app.doorprints.shared.export.BackupFormat
import app.doorprints.shared.export.ExportOptions
import app.doorprints.shared.export.ImportMode
import app.doorprints.shared.export.ImportPlan
import app.doorprints.shared.model.DefaultQuestions
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.shared.model.QuestionScope
import app.doorprints.shared.model.QuestionType
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.shared.sync.SyncRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The question bank and a house's answers on the phone (docs/11 5.5, slice 3a) on the app's own database (Robolectric):
 * seeding once with the fixed ids in the app's language, a deleted default staying deleted, *Reset to defaults*,
 * saving, reordering, archiving, deleting and adding (the cap of 100, custom ids that never clash), answers that survive
 * a save, a sync's mapping and a backup, and a `/2` backup's questions merging by id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuestionsRepositoryTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var repo: AndroidRepository
    private val at = 1_760_000_000_000

    private object MemorySecrets : SecretStore {
        private val key = stringPreferencesKey("testKey")
        override fun get(settings: Preferences): String? = settings[key]
        override fun put(settings: MutablePreferences, apiKey: String) {
            settings[key] = apiKey
        }
        override fun clear(settings: MutablePreferences) {
            settings.remove(key)
        }
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        context.deleteDatabase(DatabaseFile.NAME)
        db = AppDatabase.create(context)
        settings = SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "questions-test.preferences_pb") },
            MemorySecrets,
        )
        repo = AndroidRepository(context, db, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        context.deleteDatabase(DatabaseFile.NAME)
        File(context.filesDir, "questions-test.preferences_pb").delete()
    }

    private suspend fun ids() = repo.questions().map { it.id }

    /** Another device's edit of a default, pulled: any real time is later than a seed's. */
    private val pulledEditAt = 1_760_000_000_000L

    @Test
    fun seedingWritesTheFourteenDefaultsOnceWithTheirFixedIdsInTheAppsLanguage(): Unit = runBlocking {
        assertEquals(emptyList<String>(), ids())
        repo.seedQuestionsOnce("ta")
        assertEquals(DefaultQuestions.ALL.map { it.id }, ids())
        val water = repo.questions().first { it.id == "qd_water" }
        assertEquals(DefaultQuestions.byId("qd_water")!!.text.getValue("ta"), water.text)
        // S4b-BL-90a: clean and stamped 2000-01-01, so a seed is never pushed and another device's edit or deletion wins.
        val seeded = db.records().get(QuestionType.name, "qd_water")!!
        assertFalse("a seed is not pushed", seeded.dirty)
        assertEquals(DefaultQuestions.SEEDED_AT, seeded.updatedAt)
        assertEquals("2000-01-01T00:00:00Z", java.time.Instant.ofEpochMilli(DefaultQuestions.SEEDED_AT).toString())
        assertFalse(SyncRules.keepLocal(seeded, seeded.copy(updatedAt = pulledEditAt, dirty = false)))
        // An edit here is the person's: dirty, pushed with its own time.
        repo.saveQuestion(water.copy(text = "Borewell?"))
        assertTrue(db.records().get(QuestionType.name, "qd_water")!!.dirty)
        // Once per install: a second start seeds nothing, even after every default was deleted.
        for (id in ids()) repo.deleteQuestion(id)
        repo.seedQuestionsOnce("ta")
        assertEquals(emptyList<String>(), ids())
        assertTrue(settings.questionsSeeded())
    }

    @Test
    fun seedingRespectsATombstoneAndAnEditAndTakesAnyOtherLanguageAsEnglish(): Unit = runBlocking {
        repo.seedQuestions("mr")
        assertEquals(DefaultQuestions.byId("qd_pets")!!.text.getValue("en"), repo.questions().first { it.id == "qd_pets" }.text)
        repo.deleteQuestion("qd_pets")
        val water = repo.questions().first { it.id == "qd_water" }
        repo.saveQuestion(water.copy(text = "Borewell or corporation water?"))
        assertEquals(0, repo.seedQuestions("hi"))
        assertFalse("a deleted default is not brought back", "qd_pets" in ids())
        assertEquals("Borewell or corporation water?", repo.questions().first { it.id == "qd_water" }.text)
    }

    @Test
    fun resetBringsBackEveryDefaultInTheCurrentLanguageAndKeepsTheCustomOnes(): Unit = runBlocking {
        repo.seedQuestions("en")
        repo.deleteQuestion("qd_pets")
        repo.saveQuestion(repo.questions().first { it.id == "qd_water" }.copy(text = "Edited", archived = true))
        val mine = repo.addQuestion("Is there a water meter?")
        repo.resetQuestions("hi")
        val bank = repo.questions()
        assertEquals(DefaultQuestions.ALL.map { it.id } + mine, bank.map { it.id })
        assertEquals(DefaultQuestions.byId("qd_water")!!.question("hi"), bank.first { it.id == "qd_water" })
        assertEquals(DefaultQuestions.byId("qd_pets")!!.question("hi"), bank.first { it.id == "qd_pets" })
        assertEquals("Is there a water meter?", bank.last().text)
    }

    @Test
    fun savingWritesOnlyWhatChangedAndReorderingStampsOnlyTheMovedOnes(): Unit = runBlocking {
        repo.seedQuestions("en")
        val deposit = repo.questions().first { it.id == "qd_deposit" }
        val stamp = db.records().get(QuestionType.name, "qd_deposit")!!.updatedAt
        repo.saveQuestion(deposit)
        assertEquals(stamp, db.records().get(QuestionType.name, "qd_deposit")!!.updatedAt)
        repo.saveQuestion(deposit.copy(category = QuestionCategory.OTHER.name, appliesTo = QuestionScope.BOTH.name, defaultOn = false))
        val changed = repo.questions().first { it.id == "qd_deposit" }
        assertEquals(listOf("OTHER", "BOTH", "false"), listOf(changed.category, changed.appliesTo, changed.defaultOn.toString()))
        // Maintenance and deposit swap: two records change, the other twelve keep their stamps.
        val stamps = db.records().listByType(QuestionType.name).associate { it.id to it.updatedAt }
        val order = repo.questions().toMutableList()
        order[0] = order[1].also { order[1] = order[0] }
        Thread.sleep(2)
        repo.saveQuestions(order.mapIndexed { i, q -> q.copy(sort = i) })
        assertEquals(listOf("qd_deposit", "qd_maintenance"), ids().take(2))
        val moved = db.records().listByType(QuestionType.name).filter { stamps[it.id] != it.updatedAt }.map { it.id }.toSet()
        assertEquals(setOf("qd_deposit", "qd_maintenance"), moved)
        // A blank text is refused, and archiving keeps the row in the bank.
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveQuestion(changed.copy(text = " ")) } }
        repo.saveQuestion(repo.questions().first { it.id == "qd_deposit" }.copy(archived = true))
        assertTrue(repo.questions().first { it.id == "qd_deposit" }.archived)
        assertEquals(
            """{"text":"${changed.text}","category":"OTHER","appliesTo":"BOTH","defaultOn":false,"sort":0,"archived":true}""",
            db.records().get(QuestionType.name, "qd_deposit")!!.payload,
        )
    }

    @Test
    fun aCustomQuestionGetsAFreshIdAtTheEndAndTheHundredAndFirstIsRefused(): Unit = runBlocking {
        repo.seedQuestions("en")
        val id = repo.addQuestion("  Is there a water meter? ", QuestionCategory.WATER_POWER, QuestionScope.RENT, defaultOn = true)
        assertTrue(id, Question.isCustomId(id))
        val meter = repo.questions().last()
        assertEquals(Question(id, "Is there a water meter?", "WATER_POWER", "RENT", true, 14), meter)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addQuestion(" ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.addQuestion("x".repeat(301)) } }
        // Up to 100 in all, the seeded ones and the archived ones included; an id a tombstone holds is never reused.
        repo.deleteQuestion(id)
        val more = (1..86).map { repo.addQuestion("Custom $it") }
        assertEquals(100, repo.questions().size)
        assertFalse(id in more)
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.addQuestion("One too many") } }
        assertThrows(RecordLimitException::class.java) {
            runBlocking { repo.saveQuestion(Question("q_ffffffff", "Another", sort = 200)) }
        }
        // Changing one that is there is not refused; a deleted default is not brought back past 100 by Reset.
        repo.saveQuestion(repo.questions().first().copy(text = "Changed"))
        repo.deleteQuestion("qd_pets")
        repo.addQuestion("Fills the place")
        repo.resetQuestions("en")
        assertEquals(100, repo.questions().size)
        assertFalse("qd_pets" in ids())
    }

    @Test
    fun aHousesAnswersSurviveASaveASyncsMappingAndABackup(): Unit = runBlocking {
        val answers = listOf(
            HouseAnswer("a1", "qd_water", "Where does the water come from?", "Borewell", "OPEN", 0),
            HouseAnswer("a2", text = "Is the terrace open?", sort = 1),
        )
        repo.saveHouse(HouseEntity(id = "h1", label = "A", lat = 1.0, lon = 1.0, createdAt = at, updatedAt = at))
        repo.saveAnswers("h1", answers)
        val saved = repo.getHouse("h1")!!
        // The save coerces: a typed answer reads ANSWERED.
        assertEquals(listOf("ANSWERED", "OPEN"), saved.answers!!.map { it.status })
        assertTrue(saved.dirty)
        assertEquals(saved.answers, saved.toDto().toEntity().answers)
        repo.saveAnswers("h1", emptyList())
        assertNull(repo.getHouse("h1")!!.answers)
        repo.saveAnswers("missing", answers)
        assertNull(repo.getHouse("missing"))

        repo.saveAnswers("h1", answers)
        val data = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at, includeContacts = false)))
        assertEquals(BackupFormat.ID_2, data.format)
        assertEquals(repo.getHouse("h1")!!.answers, data.houses.single().answers)
    }

    @Test
    fun aRowThatCannotBeTrustedIsSkippedFromTheBankTheBackupAndTheNextSortPosition(): Unit = runBlocking {
        repo.seedQuestions("en")
        db.records().upsert(RecordEntity(QuestionType.name, "q_0000bad1", """{"text":" ","sort":50}""", at))
        db.records().upsert(RecordEntity(QuestionType.name, "q_0000bad2", "not json", at))
        db.records().upsert(RecordEntity(QuestionType.name, "bad id", """{"text":"Pets?","sort":60}""", at))
        assertEquals(DefaultQuestions.ALL.map { it.id }, ids())
        assertEquals(DefaultQuestions.ALL.map { it.id }, repo.observeQuestions().first().map { it.id })
        val data = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(14, data.questionRows.size)
        // The next custom question goes after the last question that can be read (sort 14), not after a skipped row.
        val id = repo.addQuestion("Is there a lift?")
        assertEquals(14, repo.questions().first { it.id == id }.sort)
        // A row from a newer app reads with the nearest value it knows: an unknown category is OTHER, scope BOTH, sort 0.
        db.records().upsert(
            RecordEntity(QuestionType.name, "q_0000cafe", """{"text":"Odd?","category":"X","appliesTo":"Y","sort":-3}""", at),
        )
        assertEquals(Question("q_0000cafe", "Odd?", "OTHER", "BOTH", false, 0), repo.questions().first { it.id == "q_0000cafe" })
    }

    @Test
    fun resetAtTheCapRestoresALiveDefaultButNotADeletedOne(): Unit = runBlocking {
        repo.seedQuestions("en")
        repeat(86) { repo.addQuestion("Custom $it") }
        assertEquals(100, repo.questions().size)
        repo.saveQuestion(repo.questions().first { it.id == "qd_water" }.copy(text = "Edited"))
        repo.resetQuestions("hi")
        assertEquals(DefaultQuestions.byId("qd_water")!!.question("hi"), repo.questions().first { it.id == "qd_water" })
        assertEquals(100, repo.questions().size)
    }

    @Test
    fun savingATrimmedQuestionWritesOnlyWhenItDiffersAndATombstonesIdAtTheCapIsRefused(): Unit = runBlocking {
        repo.seedQuestions("en")
        val water = repo.questions().first { it.id == "qd_water" }
        val stamp = db.records().get(QuestionType.name, "qd_water")!!.updatedAt
        // Padding alone is not a change: it trims to what is stored, so nothing is written.
        repo.saveQuestion(water.copy(text = "  " + water.text + " "))
        assertEquals(stamp, db.records().get(QuestionType.name, "qd_water")!!.updatedAt)
        repo.saveQuestion(water.copy(text = "  Borewell?  "))
        assertEquals("Borewell?", repo.questions().first { it.id == "qd_water" }.text)
        repo.saveQuestion(water.copy(text = "x".repeat(300)))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.saveQuestion(water.copy(text = "x".repeat(301))) } }
        // At the cap a deleted question's id is not "there": saving it is refused, as a new question is.
        val gone = repo.addQuestion("Soon gone")
        repo.deleteQuestion(gone)
        repeat(86) { repo.addQuestion("Custom $it") }
        assertEquals(100, repo.questions().size)
        assertThrows(RecordLimitException::class.java) { runBlocking { repo.saveQuestion(Question(gone, "Back again")) } }
        // Archived questions count toward the 100 and can still be saved.
        repo.saveQuestion(repo.questions().last().copy(archived = true))
        assertEquals(100, repo.questions().size)
    }

    @Test
    fun aBackupCarriesTheBankAndAnImportMergesItByIdWithLastWriteWins(): Unit = runBlocking {
        val none = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID, none.format)
        repo.seedQuestions("en")
        val mine = repo.addQuestion("Is there a water meter?")
        val data = BackupData.of(repo.localRows().toBundle(ExportOptions(exportedAtMillis = at)))
        assertEquals(BackupFormat.ID_2, data.format)
        assertEquals(15, data.questionRows.size)
        repo.deleteQuestion("qd_pets")

        // A newer water in the file wins, an older meter does not, a new one is added; the deleted pets (newer here) stays.
        val water = data.questionRows.first { it.id == "qd_water" }
        val file = data.copy(
            questions = listOf(
                water.copy(text = "Water from where?", updatedAt = Long.MAX_VALUE / 2),
                data.questionRows.first { it.id == mine }.copy(text = "Old words", updatedAt = 1),
                water.copy(id = "q_0000abcd", text = "Lift?", updatedAt = 5),
                data.questionRows.first { it.id == "qd_pets" },
            ),
        )
        val local = repo.localVersions()
        val actions = ImportPlan.plan(
            file, local.houses, local.visits, local.photoIds, emptySet(), ImportMode.MERGE, newId = { "x" },
            localQuestions = local.questions,
        )
        val result = repo.applyImport(actions) { null }
        assertEquals(2, result.questions)
        val bank = repo.questions().associateBy { it.id }
        assertEquals("Water from where?", bank.getValue("qd_water").text)
        assertEquals("Is there a water meter?", bank.getValue(mine).text)
        assertEquals("Lift?", bank.getValue("q_0000abcd").text)
        assertFalse("qd_pets" in bank)
        assertTrue(db.records().get(QuestionType.name, "qd_water")!!.dirty)
        assertEquals(repo.questions(), repo.observeQuestions().first())
    }
}
