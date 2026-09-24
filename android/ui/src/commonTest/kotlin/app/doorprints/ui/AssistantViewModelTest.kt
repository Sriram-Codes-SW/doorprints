package app.doorprints.ui

import androidx.lifecycle.SavedStateHandle
import app.doorprints.data.HouseEntity
import app.doorprints.data.HouseVisitCount
import app.doorprints.data.PhotoEntity
import app.doorprints.data.Repository
import app.doorprints.data.SettingsStore
import app.doorprints.data.VisitEntity
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.CitationDto
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.api.PlanRequest
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
    override suspend fun refreshAiStatus() = true

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
