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

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.doorprints.shared.trace.PlaceBand
import app.doorprints.shared.trace.PlaceCheckStatus
import app.doorprints.shared.trace.WalkSource
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_close
import app.doorprints.ui.res.trace_house_show
import app.doorprints.ui.res.trace_here_again
import app.doorprints.ui.res.trace_here_and_more
import app.doorprints.ui.res.trace_here_approx_house
import app.doorprints.ui.res.trace_here_button
import app.doorprints.ui.res.trace_here_button_house
import app.doorprints.ui.res.trace_here_close
import app.doorprints.ui.res.trace_here_denied
import app.doorprints.ui.res.trace_here_empty
import app.doorprints.ui.res.trace_here_fuzzy
import app.doorprints.ui.res.trace_here_imprecise
import app.doorprints.ui.res.trace_here_invalid
import app.doorprints.ui.res.trace_here_label_here
import app.doorprints.ui.res.trace_here_label_house
import app.doorprints.ui.res.trace_here_label_spot
import app.doorprints.ui.res.trace_here_locating
import app.doorprints.ui.res.trace_here_none
import app.doorprints.ui.res.trace_here_none_saved
import app.doorprints.ui.res.trace_here_only_recorded
import app.doorprints.ui.res.trace_here_place_here
import app.doorprints.ui.res.trace_here_place_house
import app.doorprints.ui.res.trace_here_place_spot
import app.doorprints.ui.res.trace_here_press_menu
import app.doorprints.ui.res.trace_here_privacy
import app.doorprints.ui.res.trace_here_row
import app.doorprints.ui.res.trace_here_row_saved
import app.doorprints.ui.res.trace_here_rows_more
import app.doorprints.ui.res.trace_here_timeout
import app.doorprints.ui.res.trace_here_walked
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The sheet's title for [kind]: *Have I been here?*, *Did I walk past this house?*, *Did I walk here?*. */
internal fun checkTitle(kind: PlaceKind): StringResource = when (kind) {
    PlaceKind.HERE -> Res.string.trace_here_button
    PlaceKind.HOUSE -> Res.string.trace_here_button_house
    PlaceKind.SPOT -> Res.string.trace_here_press_menu
}

private fun placeWord(kind: PlaceKind): StringResource = when (kind) {
    PlaceKind.HERE -> Res.string.trace_here_place_here
    PlaceKind.HOUSE -> Res.string.trace_here_place_house
    PlaceKind.SPOT -> Res.string.trace_here_place_spot
}

/**
 * *Have I been here?*'s answer as a bottom sheet (docs/11 5.27.13). Closing it discards the answer ([onClose]); nothing
 * of it is saved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceCheckSheet(
    state: PlaceCheckState,
    onShowOnMap: (CheckOverlay) -> Unit,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onClose) { PlaceCheckSheetContent(state, onShowOnMap, onAgain, onClose) }
}

/**
 * The body of [PlaceCheckSheet]: the title (it takes focus when the sheet opens), the answer in words in a polite live region
 * (the headline and the rows are the whole answer; the map's outline is decoration), the rows of the headline's band (at most
 * five), the notes (an accepted loose fix, only the recorded walks) and *Shown only here. Nothing is saved or sent.*, then
 * *Show on map*, *Check again* (for *Here*) and *Close*.
 */
@Composable
fun PlaceCheckSheetContent(
    state: PlaceCheckState,
    onShowOnMap: (CheckOverlay) -> Unit,
    onAgain: () -> Unit,
    onClose: () -> Unit,
) {
    val focus = FocusRequester()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val place = stringResource(placeWord(state.kind))
    val tolerance = distanceText(CHECK_TOLERANCE_M)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(checkTitle(state.kind)),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.focusRequester(focus).focusable().semantics { heading() },
        )
        // The whole answer in one polite live region: read as text on arrival.
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is PlaceCheckState.Locating -> Text(stringResource(Res.string.trace_here_locating), style = MaterialTheme.typography.bodyLarge)
                is PlaceCheckState.Failed -> Text(
                    stringResource(
                        when (state.reason) {
                            PlaceFailure.TIMEOUT -> Res.string.trace_here_timeout
                            PlaceFailure.APPROX_HOUSE -> Res.string.trace_here_approx_house
                            PlaceFailure.DENIED -> Res.string.trace_here_denied
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                is PlaceCheckState.Answer -> AnswerText(state, place, tolerance)
            }
        }
        if (state is PlaceCheckState.Answer || state is PlaceCheckState.Failed) {
            Text(stringResource(Res.string.trace_here_privacy), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            if (state is PlaceCheckState.Answer) {
                val overlay = CheckOverlay.of(state.lat, state.lon, placeLabel(state.kind), state.result, state.walks)
                if (overlay.stretches.isNotEmpty()) {
                    Button(onClick = { onShowOnMap(overlay) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonLabel(stringResource(Res.string.trace_house_show))
                    }
                }
            }
            if (state.kind == PlaceKind.HERE && state !is PlaceCheckState.Locating) {
                OutlinedButton(onClick = onAgain, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.trace_here_again)) }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(if (state is PlaceCheckState.Locating) Res.string.common_cancel else Res.string.common_close))
            }
        }
    }
}

@Composable
private fun placeLabel(kind: PlaceKind): String = stringResource(
    when (kind) {
        PlaceKind.HERE -> Res.string.trace_here_label_here
        PlaceKind.HOUSE -> Res.string.trace_here_label_house
        PlaceKind.SPOT -> Res.string.trace_here_label_spot
    },
)

@Composable
private fun AnswerText(state: PlaceCheckState.Answer, place: String, tolerance: String) {
    val r = state.result
    val body = MaterialTheme.typography.bodyLarge
    when (r.status) {
        PlaceCheckStatus.WALKED, PlaceCheckStatus.CLOSE -> {
            val band = if (r.status == PlaceCheckStatus.WALKED) PlaceBand.WALKED else PlaceBand.CLOSE
            val facts = headlineFacts(r.rows, band)!!
            val dates = joinedDates(facts)
            val distance = distanceText(facts.distanceM)
            Text(
                if (band == PlaceBand.WALKED) stringResource(Res.string.trace_here_walked, distance, place, dates)
                else stringResource(Res.string.trace_here_close, tolerance, place, distance, dates),
                style = body,
            )
            val (rows, more) = listedRows(r.rows, band)
            rows.forEach { row ->
                val date = dateWithWeekday(row.atMs)
                val away = distanceText(kotlin.math.ceil(row.distanceM).toInt().coerceAtLeast(1))
                Text(
                    stringResource(if (row.source == WalkSource.SAVED) Res.string.trace_here_row_saved else Res.string.trace_here_row, date, away),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (more > 0) Text(stringResource(Res.string.trace_here_rows_more, more), style = MaterialTheme.typography.bodyMedium)
            Notes(r.fuzzy, state.accuracyM, r.status == PlaceCheckStatus.CLOSE)
        }
        PlaceCheckStatus.NONE -> {
            Text(
                stringResource(if (state.anySaved) Res.string.trace_here_none_saved else Res.string.trace_here_none, tolerance, place),
                style = body,
            )
            Notes(r.fuzzy, state.accuracyM, onlyRecorded = true)
        }
        PlaceCheckStatus.EMPTY -> Text(stringResource(Res.string.trace_here_empty), style = body)
        PlaceCheckStatus.IMPRECISE -> Text(stringResource(Res.string.trace_here_imprecise), style = body)
        PlaceCheckStatus.INVALID_PLACE -> Text(stringResource(Res.string.trace_here_invalid), style = body)
    }
}

@Composable
private fun Notes(fuzzy: Boolean, accuracyM: Double?, onlyRecorded: Boolean) {
    if (fuzzy && accuracyM != null) {
        // An accepted but loose fix: said in whole metres, rounded up; never added to the tolerance.
        Text(stringResource(Res.string.trace_here_fuzzy, kotlin.math.ceil(accuracyM).toInt()), style = MaterialTheme.typography.bodySmall)
    }
    if (onlyRecorded) Text(stringResource(Res.string.trace_here_only_recorded), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun joinedDates(facts: HeadlineFacts): String {
    val dates = joinedList(facts.days.map { dateWithWeekday(it) })
    return if (facts.moreDays > 0) stringResource(Res.string.trace_here_and_more, dates, facts.moreDays) else dates
}
