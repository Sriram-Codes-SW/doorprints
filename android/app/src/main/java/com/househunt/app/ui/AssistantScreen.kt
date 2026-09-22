package com.househunt.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunt.app.R
import com.househunt.shared.api.AskResponseDto
import com.househunt.shared.api.PlanRequest
import com.househunt.shared.api.PlanResponseDto
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Optional AI assistant (docs/ai/ai-design.md section 13): "Ask" (RAG answers with cited houses) and "Plan visits"
 * (ordered walking route from the current location). The tab is only shown when GET /api/ai/status says enabled;
 * if the server becomes unreachable the screen says so instead of failing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onOpenHouse: (String) -> Unit) {
    val repo = repository()
    val aiEnabled by repo.aiEnabled.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp).semantics { heading() })
        if (!aiEnabled) {
            Text(stringResource(R.string.ai_unavailable), modifier = Modifier.padding(16.dp))
        } else {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.ai_ask_tab)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.ai_plan_tab)) })
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.ai_disclosure), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tab == 0) AskPane(onOpenHouse) else PlanPane(onOpenHouse)
            }
        }
    }
}

/** Turns "[house:<id>]" markers into nothing; the cited houses are listed as buttons below the answer. */
private val citationMarker = Regex("""\s*\[house:[0-9a-fA-F-]{36}]""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskPane(onOpenHouse: (String) -> Unit) {
    val repo = repository()
    val scope = rememberCoroutineScope()
    var question by rememberSaveableText()
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<AskResponseDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorText = aiErrorText()

    OutlinedTextField(
        question, { question = it.take(1000) },
        label = { Text(stringResource(R.string.ai_ask_label)) },
        placeholder = { Text(stringResource(R.string.ai_ask_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = !busy && question.isNotBlank(), onClick = {
        scope.launch {
            busy = true; error = null
            runCatching { repo.ask(question.trim()) }
                .onSuccess { answer = it }
                .onFailure { error = errorText(it) }
            busy = false
        }
    }) { Text(stringResource(R.string.ai_ask_go)) }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.ai_asking), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    error?.let { ErrorText(it) }
    answer?.let { a ->
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(a.answer.replace(citationMarker, "").trim(), style = MaterialTheme.typography.bodyLarge)
            if (!a.grounded) {
                Text(stringResource(R.string.ai_not_grounded), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (a.citations.isNotEmpty()) {
            SectionHeading(stringResource(R.string.ai_sources))
            a.citations.forEach { c ->
                OutlinedCard(onClick = { onOpenHouse(c.houseId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(c.label ?: stringResource(R.string.house_unnamed), fontWeight = FontWeight.SemiBold)
                        c.snippet?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanPane(onOpenHouse: (String) -> Unit) {
    val repo = repository()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var question by rememberSaveableText()
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<PlanResponseDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val errorText = aiErrorText()
    val noLocation = stringResource(R.string.ai_no_location)

    OutlinedTextField(
        question, { question = it.take(1000) },
        label = { Text(stringResource(R.string.ai_plan_label)) },
        placeholder = { Text(stringResource(R.string.ai_plan_hint)) },
        minLines = 2, modifier = Modifier.fillMaxWidth(),
    )
    Button(enabled = !busy && question.isNotBlank(), onClick = {
        scope.launch {
            busy = true; error = null
            val here = currentLocation(context)
            if (here == null) {
                error = noLocation
            } else {
                runCatching { repo.planVisits(PlanRequest(question.trim(), here.first, here.second)) }
                    .onSuccess { plan = it }
                    .onFailure { error = errorText(it) }
            }
            busy = false
        }
    }) { Text(stringResource(R.string.ai_plan_go)) }
    if (busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.ai_planning), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    error?.let { ErrorText(it) }
    plan?.let { p ->
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (p.fallback) {
                Text(stringResource(R.string.ai_plan_fallback), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.ai_plan_total, String.format(Locale.ROOT, "%.1f", p.totalMeters / 1000.0), p.totalWalkMinutes),
                fontWeight = FontWeight.SemiBold)
        }
        p.stops.sortedBy { it.order }.forEach { stop ->
            OutlinedCard(onClick = { onOpenHouse(stop.houseId) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.ai_plan_stop, stop.order, stop.label ?: stringResource(R.string.house_unnamed)),
                        fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.ai_plan_leg, stop.legMeters.toInt(), stop.walkMinutes),
                        style = MaterialTheme.typography.bodySmall)
                    stop.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
}

@Composable
private fun rememberSaveableText(): MutableState<String> =
    androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
