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

package app.doorprints.ui

import androidx.lifecycle.SavedStateHandle
import app.doorprints.data.AiOff
import app.doorprints.data.ConnectLink
import app.doorprints.data.HouseEntity
import app.doorprints.data.HouseVisitCount
import app.doorprints.data.PhotoEntity
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.data.VisitEntity
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.CitationDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PairPolledDto
import app.doorprints.shared.api.PairStartedDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.model.Broker
import app.doorprints.shared.records.RecordType
import app.doorprints.shared.api.PlanResponseDto
import app.doorprints.shared.api.StatsDto
import app.doorprints.shared.export.ImportActions
import app.doorprints.shared.sync.SyncOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Assistant's view model as common code with its dependencies injected (CMP-5): it asks the [Repository] it is
 * given, plans from the [LocationSource] it is given, and keeps the tab, the answer and the plan in its
 * [SavedStateHandle], so a new instance on the same handle (process death) shows them again without a second AI call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class Where(var fix: Pair<Double, Double>?, var precise: Boolean) : LocationSource {
        override suspend fun current() = fix
        override fun hasPrecisePermission() = precise
    }

    @Test
    fun anAnswerIsKeptInSavedStateAndNotAskedForTwice() {
        val repo = FakeRepository()
        val saved = SavedStateHandle()
        val vm = AssistantViewModel(repo, Where(null, false), saved)
        vm.editAsk("Which house has the best water?")
        vm.selectTab(0)
        vm.ask()
        assertEquals(listOf("Which house has the best water?"), repo.questions)
        assertEquals("Green View", vm.answer.value?.answer)
        assertFalse(vm.askBusy.value)
        assertNull(vm.askError.value)

        // Process death: a new view model on the saved state shows the answer and asks nothing. (The questions are
        // saved through the saved-state registry when the activity saves its state, which a plain handle does not
        // do, so they are not checked here.)
        val again = AssistantViewModel(repo, Where(null, false), saved)
        assertEquals("Green View", again.answer.value?.answer)
        assertEquals(1, repo.questions.size)
    }

    @Test
    fun aQuestionIsCappedAndABlankOneAsksNothing() {
        val repo = FakeRepository()
        val vm = AssistantViewModel(repo, Where(null, false), SavedStateHandle())
        vm.editAsk("x".repeat(1_200))
        assertEquals(1_000, vm.askQuestion.length)
        vm.editAsk("   ")
        vm.ask()
        assertTrue(repo.questions.isEmpty())
    }

    @Test
    fun planningWithoutPreciseLocationIsTheUsersChoiceNotAFailure() {
        val repo = FakeRepository()
        val vm = AssistantViewModel(repo, Where(fix = null, precise = false), SavedStateHandle())
        vm.editPlan("Three houses near the lake")
        vm.plan()
        val error = assertIs<NoLocationException>(vm.planError.value)
        assertFalse(error.permitted)
        assertTrue(repo.plans.isEmpty())
    }

    @Test
    fun noFixWithPreciseLocationIsARealFailure() {
        val vm = AssistantViewModel(FakeRepository(), Where(fix = null, precise = true), SavedStateHandle())
        vm.editPlan("Three houses near the lake")
        vm.plan()
        assertTrue(assertIs<NoLocationException>(vm.planError.value).permitted)
    }

    @Test
    fun aPlanStartsFromTheFixAndIsKept() {
        val repo = FakeRepository()
        val saved = SavedStateHandle()
        val vm = AssistantViewModel(repo, Where(fix = 12.97 to 77.59, precise = true), saved)
        vm.selectTab(1)
        vm.editPlan("Three houses near the lake")
        vm.plan()
        assertEquals(listOf(PlanRequest("Three houses near the lake", 12.97, 77.59)), repo.plans)
        assertEquals(1_250L, vm.plan.value?.totalMeters)
        assertNull(vm.planError.value)
        val again = AssistantViewModel(repo, Where(null, false), saved)
        assertEquals(1, again.tab.value)
        assertEquals(1_250L, again.plan.value?.totalMeters)
    }

    @Test
    fun cancelStopsTheRunningRequestAndDropsNoAnswer() {
        val repo = FakeRepository(hold = true)
        val vm = AssistantViewModel(repo, Where(null, false), SavedStateHandle())
        vm.editAsk("Which house is cheapest?")
        vm.ask()
        assertTrue(vm.askBusy.value)
        vm.cancel()
        assertFalse(vm.askBusy.value)
        assertNull(vm.askError.value)
        assertNull(vm.answer.value)
    }
}

/** A [Repository] with only the AI calls; anything else is not used by the Assistant's view model. */
private class FakeRepository(private val hold: Boolean = false) : Repository {
    val questions = mutableListOf<String>()
    val plans = mutableListOf<PlanRequest>()

    override suspend fun ask(question: String): AskResponseDto {
        questions += question
        if (hold) CompletableDeferred<Unit>().await()
        return AskResponseDto(answer = "Green View", citations = listOf(CitationDto("a", "Green View")), grounded = true)
    }

    override suspend fun planVisits(request: PlanRequest): PlanResponseDto {
        plans += request
        return PlanResponseDto(summary = "Two stops", totalMeters = 1_250, totalWalkMinutes = 16)
    }

    override val aiEnabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val aiOff: StateFlow<AiOff?> = MutableStateFlow(null)
    override suspend fun refreshAiStatus() = true
    override suspend fun setAiFeatures(on: Boolean) = TODO()
    override suspend fun saveGeminiKey(key: String) = TODO()
    override suspend fun removeGeminiKey() = TODO()
    override suspend fun setAiProvider(choice: app.doorprints.data.AiProviderChoice) = TODO()
    override suspend fun testGeminiKey(key: String): Result<Unit> = TODO()
    override suspend fun startPairing(serverUrl: String, deviceName: String): PairStartedDto = TODO()
    override suspend fun pollPairing(serverUrl: String, pollToken: String): PairPolledDto = TODO()
    override suspend fun redeemInvite(link: ConnectLink, deviceName: String): String = TODO()

    override val trackPoints: Flow<List<app.doorprints.data.TrackPointEntity>> get() = TODO()
    override suspend fun saveTrackPoint(point: app.doorprints.data.TrackPointEntity) = TODO()
    override suspend fun pruneTrack(before: Long) = TODO()
    override suspend fun clearTrack() = TODO()
    override val settings: SettingsStore get() = TODO()
    override val houses: Flow<List<HouseEntity>> get() = TODO()
    override val visitCounts: Flow<List<HouseVisitCount>> get() = TODO()
    override fun house(id: String): Flow<HouseEntity?> = TODO()
    override fun visitsFor(houseId: String): Flow<List<VisitEntity>> = TODO()
    override fun photosFor(houseId: String): Flow<List<PhotoEntity>> = TODO()
    override suspend fun houseSnapshot(): List<HouseEntity> = TODO()
    override suspend fun getHouse(id: String): HouseEntity? = TODO()
    override suspend fun saveHouse(house: HouseEntity) = TODO()
    override suspend fun deleteHouse(id: String) = TODO()
    override suspend fun saveVisit(visit: VisitEntity) = TODO()
    override suspend fun getVisit(id: String): VisitEntity? = TODO()
    override suspend fun deleteVisit(id: String) = TODO()
    override suspend fun markVisitedNow(house: HouseEntity) = TODO()
    override suspend fun streetInfo(street: String): Repository.StreetInfo = TODO()
    override suspend fun deletePhoto(photo: PhotoEntity) = TODO()
    override fun <T> observeRecords(type: RecordType<T>): Flow<List<Pair<String, T>>> = TODO()
    override suspend fun <T> saveRecord(type: RecordType<T>, id: String, value: T) = TODO()
    override suspend fun deleteRecord(type: RecordType<*>, id: String) = TODO()
    override fun observeBrokers(): Flow<List<Pair<String, Broker>>> = TODO()
    override suspend fun saveBroker(broker: Broker, id: String?): String = TODO()
    override suspend fun deleteBroker(id: String) = TODO()
    override fun brokerHouses(id: String): Flow<List<HouseEntity>> = TODO()
    override fun observeScoring(): Flow<app.doorprints.shared.model.Scoring> = TODO()
    override suspend fun scoring(): app.doorprints.shared.model.Scoring = TODO()
    override suspend fun saveCriterion(criterion: app.doorprints.shared.model.Criterion) = TODO()
    override suspend fun saveCriteria(criteria: List<app.doorprints.shared.model.Criterion>) = TODO()
    override suspend fun addCriterion(label: String, weight: Int): String = TODO()
    override suspend fun deleteCriterion(key: String): Boolean = TODO()
    override suspend fun saveRatingShare(share: Double) = TODO()
    override suspend fun resetScoring() = TODO()
    override fun observeQuestions(): Flow<List<app.doorprints.shared.model.Question>> = TODO()
    override suspend fun questions(): List<app.doorprints.shared.model.Question> = TODO()
    override suspend fun seedQuestions(language: String): Int = TODO()
    override suspend fun seedQuestionsOnce(language: String) = TODO()
    override suspend fun resetQuestions(language: String) = TODO()
    override suspend fun saveQuestion(question: app.doorprints.shared.model.Question) = TODO()
    override suspend fun saveQuestions(questions: List<app.doorprints.shared.model.Question>) = TODO()
    override suspend fun addQuestion(
        text: String,
        category: app.doorprints.shared.model.QuestionCategory,
        appliesTo: app.doorprints.shared.model.QuestionScope,
        defaultOn: Boolean,
    ): String = TODO()
    override suspend fun deleteQuestion(id: String) = TODO()
    override suspend fun saveAnswers(houseId: String, answers: List<app.doorprints.shared.model.HouseAnswer>?) = TODO()
    override fun observeViewings(): Flow<List<app.doorprints.shared.model.Viewing>> = TODO()
    override suspend fun viewings(): List<app.doorprints.shared.model.Viewing> = TODO()
    override suspend fun viewingsOf(houseId: String): List<app.doorprints.shared.model.Viewing> = TODO()
    override suspend fun nextViewing(houseId: String, nowMs: Long): app.doorprints.shared.model.Viewing? = TODO()
    override suspend fun getViewing(id: String): app.doorprints.shared.model.Viewing? = TODO()
    override suspend fun saveViewing(viewing: app.doorprints.shared.model.Viewing) = TODO()
    override suspend fun newViewingId(): String = TODO()
    override suspend fun deleteViewing(id: String) = TODO()
    override suspend fun markViewingDone(id: String, visitId: String?) = TODO()
    override fun observeAreas(): Flow<List<app.doorprints.shared.model.Area>> = TODO()
    override suspend fun areas(): List<app.doorprints.shared.model.Area> = TODO()
    override suspend fun saveArea(area: app.doorprints.shared.model.Area) = TODO()
    override suspend fun newAreaId(): String = TODO()
    override suspend fun deleteArea(id: String) = TODO()
    override fun observePlaces(): Flow<List<app.doorprints.shared.model.Place>> = TODO()
    override suspend fun places(): List<app.doorprints.shared.model.Place> = TODO()
    override suspend fun savePlace(place: app.doorprints.shared.model.Place) = TODO()
    override suspend fun newPlaceId(): String = TODO()
    override suspend fun deletePlace(id: String) = TODO()
    override fun observeAreaNotes(): Flow<List<app.doorprints.shared.model.AreaNote>> = TODO()
    override suspend fun areaNotes(): List<app.doorprints.shared.model.AreaNote> = TODO()
    override suspend fun saveAreaNote(note: app.doorprints.shared.model.AreaNote) = TODO()
    override suspend fun newAreaNoteId(): String = TODO()
    override suspend fun deleteAreaNote(id: String) = TODO()
    override suspend fun testConnection(): Result<StatsDto> = TODO()
    override suspend fun extractListing(text: String): HouseDraftDto = TODO()
    override suspend fun sync(photosAllowed: Boolean): SyncOutcome = TODO()
    override suspend fun localRows(): Repository.LocalRows = TODO()
    override fun localRowsFlow(): Flow<Repository.LocalRows> = TODO()
    override suspend fun localVersions(): Repository.LocalVersions = TODO()
    override suspend fun applyImport(
        actions: ImportActions,
        onProgress: (done: Int, total: Int) -> Unit,
        photoBytes: suspend (entry: String) -> ByteArray?,
    ): Repository.ImportResult = TODO()
    override suspend fun undoCopyImport(
        houses: Map<String, Long>,
        visits: Map<String, Long>,
        photos: Collection<String>,
    ): Repository.UndoResult = TODO()
}
