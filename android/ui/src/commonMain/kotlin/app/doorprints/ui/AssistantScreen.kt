package app.doorprints.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewmodel.compose.viewModel
import app.doorprints.data.Repository
import app.doorprints.ui.res.*
import app.doorprints.shared.api.ApiException
import app.doorprints.shared.api.AskResponseDto
import app.doorprints.shared.api.PlanRequest
import app.doorprints.shared.api.PlanResponseDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Plan visits could not start: there is no location fix. [permitted] says whether precise location was allowed at the
 * time (UX review, whole-app audit, round 4): without it this is the user's choice, shown as the location note, and
 * planning starts again by itself once precise location is granted, even in system settings; with it, no fix came,
 * which is a real failure.
 */
class NoLocationException(val permitted: Boolean) : Exception("No location")

/**
 * The Assistant's state and requests (UX review, whole-app audit). Scoped to the Assistant's back-stack entry, so an
 * answer or a plan survives opening a cited house or a plan stop and coming back, a tab switch (the tabs save their
 * state) and a rotation, and a request keeps running while the user looks elsewhere. The tab and both questions are
 * in the [SavedStateHandle]; so are the answer and the plan, as JSON, so even process death does not spend the AI
 * quota twice. [cancel] stops a running request.
 *
 * **Injected (CMP-5).** A common view model: it is given the [repo] and the [location] source (both application-wide,
 * from [AppServices]) and its [SavedStateHandle], and holds no platform object, so it outlives a rotation without
 * holding the old activity.
 *
 * **The questions are Compose state (UX review, whole-app audit, round 2).** [askQuestion] and [planQuestion] are
 * `mutableStateOf` kept in the [SavedStateHandle] through `saveable`, not a `StateFlow` collected by the screen: a text
 * field whose value arrives asynchronously can drop characters or move the cursor with fast typing or with the
 * composing text that Hindi, Tamil and Telugu keyboards use ("Effective state management for TextField").
 */
@OptIn(SavedStateHandleSaveableApi::class)
class AssistantViewModel(
    private val repo: Repository,
    private val location: LocationSource,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }

    val tab: StateFlow<Int> = saved.getStateFlow(KEY_TAB, 0)

    /** The Ask tab's question, read and written synchronously by its text field ([editAsk]). */
    var askQuestion by saved.saveable { mutableStateOf("") }
        private set

    /** The Plan visits tab's question ([editPlan]). */
    var planQuestion by saved.saveable { mutableStateOf("") }
        private set

    private val _answer = MutableStateFlow(saved.get<String>(KEY_ANSWER)?.let { decode(AskResponseDto.serializer(), it) })
    val answer: StateFlow<AskResponseDto?> = _answer.asStateFlow()
    private val _plan = MutableStateFlow(saved.get<String>(KEY_ROUTE)?.let { decode(PlanResponseDto.serializer(), it) })
    val plan: StateFlow<PlanResponseDto?> = _plan.asStateFlow()

    /**
     * The last failure of each pane, mapped to text by the screen ([aiErrorText], [NoLocationException]). Kept while
     * the pane's next request runs (round 10), so its card stays in place, drawn as being updated, instead of making
     * way for the shorter progress row and back; cleared when that request succeeds or is cancelled, replaced when it
     * fails (a new instance, so the new card is announced even with the same text).
     */
    private val _askError = MutableStateFlow<Throwable?>(null)
    val askError: StateFlow<Throwable?> = _askError.asStateFlow()
    private val _planError = MutableStateFlow<Throwable?>(null)
    val planError: StateFlow<Throwable?> = _planError.asStateFlow()

    private val _askBusy = MutableStateFlow(false)
    val askBusy: StateFlow<Boolean> = _askBusy.asStateFlow()
    private val _planBusy = MutableStateFlow(false)
    val planBusy: StateFlow<Boolean> = _planBusy.asStateFlow()

    private var askJob: Job? = null
    private var planJob: Job? = null

    fun selectTab(index: Int) {
        saved[KEY_TAB] = index
    }

    fun editAsk(text: String) {
        askQuestion = text.take(MAX_QUESTION)
    }

    fun editPlan(text: String) {
        planQuestion = text.take(MAX_QUESTION)
    }

    fun ask() {
        val question = askQuestion.trim()
        if (question.isEmpty() || _askBusy.value) return
        _askBusy.value = true
        askJob = viewModelScope.launch {
            try {
                val result = repo.ask(question)
                _answer.value = result
                saved[KEY_ANSWER] = json.encodeToString(AskResponseDto.serializer(), result)
                _askError.value = null
            } catch (e: CancellationException) {
                _askError.value = null
                throw e
            } catch (e: Exception) {
                _askError.value = e
            } finally {
                _askBusy.value = false
            }
        }
    }

    fun plan() {
        val question = planQuestion.trim()
        if (question.isEmpty() || _planBusy.value) return
        _planBusy.value = true
        planJob = viewModelScope.launch {
            try {
                val here = withTimeoutOrNull(LOCATION_TIMEOUT_MS) { location.current() }
                    ?: throw NoLocationException(permitted = location.hasPrecisePermission())
                val result = repo.planVisits(PlanRequest(question, here.first, here.second))
                _plan.value = result
                saved[KEY_ROUTE] = json.encodeToString(PlanResponseDto.serializer(), result)
                _planError.value = null
            } catch (e: CancellationException) {
                _planError.value = null
                throw e
            } catch (e: Exception) {
                _planError.value = e
            } finally {
                _planBusy.value = false
            }
        }
    }

    /**
     * The location prompt was answered without precise location, or a tap came when Android will not ask again (round
     * 5): the pane shows the location note, as if planning had tried and found none (round 4: the first tap asks at
     * once, so there was no attempt to fail), without the "Planning…" state in between.
     */
    fun noLocation() {
        if (!_planBusy.value) _planError.value = NoLocationException(permitted = false)
    }

    /** Drops the Plan visits pane's last failure (a permission note that no longer applies). */
    fun clearPlanError() {
        _planError.value = null
    }

    /** Stops the running request of the tab on screen; the answer or route shown before stays, an earlier error goes. */
    fun cancel() {
        if (tab.value == 0) askJob?.cancel() else planJob?.cancel()
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, text: String): T? =
        runCatching { json.decodeFromString(serializer, text) }.getOrNull()

    private companion object {
        const val KEY_TAB = "tab"
        const val MAX_QUESTION = 1000
        const val KEY_ANSWER = "answer"
        const val KEY_ROUTE = "plan"
        const val LOCATION_TIMEOUT_MS = 15_000L
    }
}

/**
 * Optional AI assistant (docs/ai/ai-design.md section 13): "Ask" (RAG answers with cited houses) and "Plan visits"
 * (ordered walking route from the current location). The tab is shown when GET /api/ai/status says enabled, and
 * stays while the user is on it (Root).
 *
 * **Kept (whole-app audit).** State and requests live in [AssistantViewModel]; see there.
 *
 * **Announced (A11Y-006).** One always-composed [LiveMessage] under each pane's button says "Thinking…" /
 * "Planning…", then the error (assertive) or "Answer ready" / "Plan ready: 3 stops". The result has a heading ("Answer",
 * "Your route"); with TalkBack on, focus moves to it when a new result arrives. Asking or planning again after an
 * error keeps the error card in place, its icon and border dimmed, the bar along its foot and "Thinking…" /
 * "Planning…" as its state ([RefreshableResultCard], round 10, the same rule as Settings' server result), so the
 * answer or route below does not jump.
 *
 * **Input.** Both questions capitalise sentences, and the keyboard's Send runs them. While a request runs its button
 * is *Cancel*: the same button ([StateButton], round 6), so TalkBack's focus stays on it. Without location, the first tap on *Plan visits* asks for it at once, as the Map and the form do
 * (round 4), and planning runs when it is granted; once Android will not ask again, the tap shows the note at once
 * and planning does not start ([locationStart], round 5); with TalkBack on, focus moves to the note (round 6), as on
 * the Map. Refused, the shared [LocationPermissionNote] (round 3) says
 * "Location is off for Doorprints. ‘Plan visits’ needs it to start from where you are." (round 5, the same pattern as
 * the Map and the form) with *Allow location*, or with approximate location only "Doorprints has
 * only your approximate location. ‘Plan visits’ needs precise location to start from where you are." with *Turn on
 * precise location*; *Open settings* once Android will not ask again. It is an amber note, not error red, and polite,
 * not assertive; "Your location is not available." (the red [ResultCard] with the warning sign, assertive) is kept
 * for a real failure, when precise location was allowed and no fix came ([NoLocationException.permitted]). Every
 * failure on both tabs is that card (round 9), as on Settings, the Map, the house form and Export/Import. Precise location granted later, even in system
 * settings, starts planning again by itself instead of turning the note into that red failure. The questions are
 * synchronous Compose state (see [AssistantViewModel]).
 *
 * **Layout (rounds 8 to 10).** The title, the tabs, the panes and the "not available" hero share one centred column,
 * at most [ContentMaxWidth] wide, so they share one left edge on a landscape phone or a tablet. Only the tabs are fixed
 * above the scrolling pane, 8 dp under the title; on a window under 480 dp tall ([mapControlsInRow]) the title is
 * left out, so the field and *Ask* keep room with the keyboard up at 200 % font. With the assistant off, the title
 * scrolls with the hero, as on Settings.
 *
 * **Not available.** When the assistant is off or unreachable: "The assistant is not available right now." with
 * *Try again* and *Go to the map*.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onOpenHouse: (String) -> Unit, onOpenMap: () -> Unit = {}) {
    val services = LocalAppServices.current
    val repo = services.repository
    val scope = rememberCoroutineScope()
    val vm: AssistantViewModel = viewModel {
        AssistantViewModel(services.repository, services.location, createSavedStateHandle())
    }
    val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    var retrying by remember { mutableStateOf(false) }
    // A short window (a landscape phone, split-screen): under 480 dp tall, the Map's threshold for its one-row
    // controls (MapRules.mapControlsInRow).
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compactHeight = mapControlsInRow(maxHeight.value)
        Column(Modifier.fillMaxSize()) {
            // The title and the tabs are in the same centred column, at most 640 dp wide, as the panes and the hero below
            // (round 9): round 8 centred only the panes, so on an 800 dp landscape phone the title started at 16 dp and
            // the field at about 96 dp, and each tab stretched 400 dp, its indicator no longer over the panel it controls.
            // As Settings and the web's ask page (h1 inside .page.narrow). The title's 16 dp matches the panes' and the
            // hero's 16 dp padding, so they share one left edge.
            // Round 10: only the tabs must stay fixed (they control the pane below). On a short window the title is left
            // out, so a landscape phone at 200 % font in ta or te (a 48 sp title over two-line tabs, about 170 dp) with
            // the keyboard up still has room for the field and Ask; the tabs and the navigation bar still say where the
            // user is. With the assistant off there are no tabs, and the title scrolls with the hero, as on Settings.
            // 8 dp between the title and the tabs, the screen's 8/12 dp rhythm.
            if (aiEnabled) {
                Column(
                    Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth)
                        .fillMaxWidth(),
                ) {
                    if (!compactHeight) {
                        AssistantTitle(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp))
                    }
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { vm.selectTab(0) },
                            text = { Text(stringResource(Res.string.ai_ask_tab)) })
                        Tab(selected = tab == 1, onClick = { vm.selectTab(1) },
                            text = { Text(stringResource(Res.string.ai_plan_tab)) })
                    }
                }
            }
            if (!aiEnabled) {
                // At most 640 dp wide and centred, as the panes below (round 8, ContentMaxWidth); the title first, in the
                // scroll (round 10).
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth).fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    AssistantTitle(Modifier.padding(top = 16.dp, bottom = 8.dp))
                    HeroEmptyState(
                        icon = Icons.Default.Search,
                        title = stringResource(Res.string.ai_unavailable_now),
                        body = stringResource(Res.string.ai_unavailable),
                        horizontalPadding = 0.dp,
                        action = {
                            Column(
                                Modifier.width(IntrinsicSize.Max),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = {
                                        if (!retrying) {
                                            retrying = true
                                            scope.launch {
                                                try {
                                                    repo.refreshAiStatus()
                                                } finally {
                                                    retrying = false
                                                }
                                            }
                                        }
                                    },
                                    enabled = !retrying,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { ButtonLabel(stringResource(Res.string.common_try_again)) }
                                OutlinedButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                                    ButtonLabel(stringResource(Res.string.ai_go_to_map))
                                }
                            }
                        },
                    )
                    LiveMessage {
                        if (retrying) ProgressBar()
                    }
                }
            } else {
                Column(
                    // Above the keyboard (edge-to-edge no longer resizes the window), so Ask stays reachable. The scroll
                    // is full width; the pane is at most 640 dp wide and centred (round 8, ContentMaxWidth), as the house
                    // form and the web's Ask page (--content-narrow), so landscape and tablets do not stretch the fields,
                    // the full-width buttons or the answer's lines.
                    Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                        .wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = ContentMaxWidth).fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(Res.string.ai_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (tab == 0) AskPane(vm, onOpenHouse) else PlanPane(vm, onOpenHouse)
                }
            }
        }
    }
}

/** The Assistant's title, a heading for TalkBack's heading navigation. */
@Composable
private fun AssistantTitle(modifier: Modifier) {
    Text(stringResource(Res.string.ai_title), style = MaterialTheme.typography.headlineSmall,
        modifier = modifier.semanticsHeading())
}

/** Turns "[house:<id>]" markers into nothing; the cited houses are listed as buttons below the answer. */
private val citationMarker = Regex("""\s*\[house:[0-9a-fA-F-]{36}]""")

/**
 * The result heading's focus request: with TalkBack on, a new result moves focus to its heading, one frame after it is
 * drawn (the pattern used on the other screens). The result already there when the pane opened is not new.
 */
@Composable
private fun rememberResultFocus(resultKey: Any?): FocusRequester {
    val platform = LocalPlatformServices.current
    val focus = remember { FocusRequester() }
    val first = remember { resultKey }
    LaunchedEffect(resultKey) {
        if (resultKey == null || resultKey === first || !platform.isScreenReaderOn()) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    return focus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskPane(vm: AssistantViewModel, onOpenHouse: (String) -> Unit) {
    val question = vm.askQuestion
    val busy by vm.askBusy.collectAsStateWithLifecycle()
    val answer by vm.answer.collectAsStateWithLifecycle()
    val error by vm.askError.collectAsStateWithLifecycle()
    val errorText = aiErrorText()
    val headingFocus = rememberResultFocus(answer)

    OutlinedTextField(
        question, vm::editAsk,
        label = { Text(stringResource(Res.string.ai_ask_label)) },
        placeholder = { Text(stringResource(Res.string.ai_ask_hint)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { vm.ask() }),
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    // One button position and one call site: Ask, or Cancel while the request runs (round 6), so TalkBack keeps focus
    // on it and the next double-tap cancels.
    StateButton(
        text = stringResource(if (busy) Res.string.common_cancel else Res.string.ai_ask_go),
        onClick = { if (busy) vm.cancel() else vm.ask() },
        style = if (busy) BarButtonStyle.OUTLINED else BarButtonStyle.FILLED,
        // Full width (round 7): the button keeps its size and place when it becomes Cancel, so nothing moves under the
        // finger, and it matches the ActionBar's and HeroEmptyState's primary buttons.
        modifier = Modifier.fillMaxWidth(),
        enabled = busy || question.isNotBlank(),
    )
    val readyText = stringResource(Res.string.ai_answer_ready)
    val askingText = stringResource(Res.string.ai_asking)
    LiveMessage(assertive = error != null && !busy) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val e = error
            // The app's one error style (round 9): the red card with the warning sign, as Settings, the Map, the
            // house form and Export/Import, not red text alone. Asking again after an error keeps that card in place,
            // drawn as being updated with the bar along its foot and "Thinking…" as its state (round 10, Settings'
            // rule, RefreshableResultCard), so the answer below does not jump up by its height and back. Keyed on the
            // failure, so a new failure is a new node and is read even when its text is the same.
            when {
                e != null -> key(e) {
                    RefreshableResultCard(ResultTone.ERROR, errorText(e), busyText = if (busy) askingText else null)
                }
                busy -> {
                    ProgressBar()
                    Text(askingText)
                }
                answer != null -> Text(
                    readyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    answer?.let { a ->
        SectionHeading(
            stringResource(Res.string.ai_answer_heading),
            // Focusable, so the request below can land on it (a heading is not a focus target otherwise).
            Modifier.focusRequester(headingFocus).focusable(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.answer.replace(citationMarker, "").trim(), style = MaterialTheme.typography.bodyLarge)
            if (!a.grounded) {
                Text(stringResource(Res.string.ai_not_grounded), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (a.citations.isNotEmpty()) {
            SectionHeading(stringResource(Res.string.ai_sources))
            a.citations.forEach { c ->
                OutlinedCard(onClick = { onOpenHouse(c.houseId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.label ?: stringResource(Res.string.house_unnamed), fontWeight = FontWeight.SemiBold)
                        c.snippet?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanPane(vm: AssistantViewModel, onOpenHouse: (String) -> Unit) {
    val platform = LocalPlatformServices.current
    val question = vm.planQuestion
    val busy by vm.planBusy.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    val error by vm.planError.collectAsStateWithLifecycle()
    val errorText = aiErrorText()
    val noLocation = stringResource(Res.string.ai_no_location)
    val headingFocus = rememberResultFocus(plan)
    // The location note under *Plan visits*; planning starts again once precise location is granted. The "asked" flag
    // is shared with the Map and the house form (LocationPermission.kt): once Android will not ask again, the button
    // is *Open settings*, and it follows a second refusal at once.
    val locationAsk = rememberLocationAsk()
    // With TalkBack on, a tap on *Plan visits* that starts nothing (Android will not ask again) moves focus to the note,
    // as the Map does (round 6): republishing the same text said nothing new, and focus stayed on the button.
    val noteFocus = remember { FocusRequester() }
    var focusNote by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusNote) {
        if (focusNote == 0 || !platform.isScreenReaderOn()) return@LaunchedEffect
        // Two frames later: the note arrives through the view model's flow, then is composed and laid out.
        repeat(2) { withFrameNanos { } }
        runCatching { noteFocus.requestFocus() }
    }
    val noteModifier = Modifier.focusRequester(noteFocus)
        .then(if (platform.isScreenReaderOn()) Modifier.focusable() else Modifier)
    val askLocation = rememberLocationPermissionRequest {
        locationAsk.refresh()
        if (platform.locationAccess() == LocationAccess.PRECISE) vm.plan() else vm.noLocation()
    }
    // *Plan visits* (button or the keyboard's Send): without precise location, the first tap asks at once while
    // Android will still ask (round 4); once it will not, the tap shows the note straight away (round 5), so
    // "Planning…" never flashes before the same note. Planning only starts with precise location.
    fun planOrAsk() {
        if (busy || vm.planQuestion.isBlank()) return
        val precise = platform.locationAccess() == LocationAccess.PRECISE
        when (locationStart(precise = precise, canAsk = locationAsk.canAsk)) {
            LocationStart.RUN -> vm.plan()
            LocationStart.ASK -> {
                locationAsk.markAsked()
                askLocation()
            }
            LocationStart.SHOW_NOTE -> {
                vm.noLocation()
                focusNote++
            }
        }
    }
    // Precise location granted while the note shows (in system settings, then back here): plan again, rather than
    // show the red "Your location is not available." for a failure that never happened (round 4). Only a failure
    // from missing permission is retried; a real no-fix failure waits for the user.
    LaunchedEffect(locationAsk.granted, error, busy) {
        val e = error
        if (locationAsk.granted && !busy && e is NoLocationException && !e.permitted) {
            // A question cleared since has nothing to plan: just drop the stale note.
            if (vm.planQuestion.isBlank()) vm.clearPlanError() else vm.plan()
        }
    }

    OutlinedTextField(
        question, vm::editPlan,
        label = { Text(stringResource(Res.string.ai_plan_label)) },
        placeholder = { Text(stringResource(Res.string.ai_plan_hint)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { planOrAsk() }),
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    // One call site, as on the Ask tab: Plan visits, or Cancel while planning runs.
    StateButton(
        text = stringResource(if (busy) Res.string.common_cancel else Res.string.ai_plan_go),
        onClick = { if (busy) vm.cancel() else planOrAsk() },
        style = if (busy) BarButtonStyle.OUTLINED else BarButtonStyle.FILLED,
        // Full width, as Ask (round 7): Plan visits and Cancel are the same size and place.
        modifier = Modifier.fillMaxWidth(),
        enabled = busy || question.isNotBlank(),
    )
    val stops = plan?.stops?.size ?: 0
    val readyText = pluralStringResource(Res.plurals.ai_plan_ready, stops, stops)
    val planningText = stringResource(Res.string.ai_planning)
    // The calm permission note is polite, as on the form; only a real failure interrupts (round 4).
    val permissionNote = error is NoLocationException && !locationAsk.granted
    val retrying = (error as? NoLocationException)?.permitted == false && locationAsk.granted
    LiveMessage(assertive = error != null && !busy && !permissionNote && !retrying) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val e = error
            // A real failure is the red card with the warning sign, as on the Ask tab (round 9); planning again keeps
            // it in place, drawn as being updated, until the new result (round 10, as Ask). Not for the permission
            // note, which is the user's choice rather than a failure.
            val cardText = when {
                e == null || permissionNote -> null
                e is NoLocationException -> if (e.permitted) noLocation else null
                else -> errorText(e)
            }
            when {
                cardText != null -> key(e) {
                    RefreshableResultCard(ResultTone.ERROR, cardText, busyText = if (busy) planningText else null)
                }
                busy -> {
                    ProgressBar()
                    Text(planningText)
                }
                // No precise location is the user's choice, not a failure: the shared amber note with its next step
                // (round 3), naming Plan visits (round 4). With precise location and still no fix, it is a real
                // failure, the red error card.
                permissionNote -> LocationPermissionNote(
                    ask = locationAsk,
                    deniedText = stringResource(Res.string.ai_plan_location_off),
                    approximateText = approximateLocationText(Res.string.ai_plan_needs_precise),
                    launchRequest = askLocation,
                    modifier = noteModifier,
                )
                // Granted since: planning starts again at once (the effect above); nothing to say meanwhile.
                retrying -> Unit
                plan != null -> Text(
                    readyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    plan?.let { p ->
        SectionHeading(
            stringResource(Res.string.ai_route_heading),
            // Focusable, so the request below can land on it (a heading is not a focus target otherwise).
            Modifier.focusRequester(headingFocus).focusable(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (p.fallback) {
                Text(stringResource(Res.string.ai_plan_fallback), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(Res.string.ai_plan_total, Formats.oneDecimal(p.totalMeters / 1000.0), p.totalWalkMinutes),
                fontWeight = FontWeight.SemiBold)
        }
        p.stops.sortedBy { it.order }.forEach { stop ->
            OutlinedCard(onClick = { onOpenHouse(stop.houseId) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(Res.string.ai_plan_stop, stop.order, stop.label ?: stringResource(Res.string.house_unnamed)),
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(Res.string.ai_plan_leg, stop.legMeters.toInt(), stop.walkMinutes),
                        style = MaterialTheme.typography.bodySmall)
                    stop.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** A heading for TalkBack's heading navigation. */
private fun Modifier.semanticsHeading(): Modifier = this.then(
    Modifier.semantics { heading() },
)

/**
 * Returns a function that turns an AI call failure into a translated message (read in composition): the Assistant's
 * and the house form's *Paste a listing*. [formatPositional] fills the counts as `String.format` did (CMP-5).
 */
@Composable
fun aiErrorText(): (Throwable) -> String {
    val rate = stringResource(Res.string.ai_rate_limited)
    val down = stringResource(Res.string.ai_provider_down)
    val offline = stringResource(Res.string.ai_offline)
    val generic = stringResource(Res.string.ai_error)
    return { e ->
        when {
            e is ApiException && e.kind == ApiException.Kind.RATE_LIMITED ->
                formatPositional(rate, e.retryAfterSeconds ?: 60L)
            e is ApiException && e.kind == ApiException.Kind.AI_UNAVAILABLE -> down
            e is ApiException -> formatPositional(generic, e.code)
            else -> offline
        }
    }
}
