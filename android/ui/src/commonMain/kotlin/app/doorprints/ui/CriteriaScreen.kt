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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.HouseEntity
import app.doorprints.shared.model.Criterion
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.ui.res.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The rating share's choices on the Criteria screen (docs/11 5.4): 0 %, 25 %, 50 % (the default), 75 %, 100 %. */
private val RATING_SHARES: List<Double> = listOf(0.0, 0.25, 0.5, 0.75, 1.0)

/**
 * Criteria (docs/11 5.4, slice 2), Settings > Criteria: how much each checklist item counts, which are must-haves and
 * their lowest accepted score, their order, archiving, your own criteria and the star rating's share; *Reset to
 * defaults*. Every change is saved at once (`Repository.saveCriterion`), and only what differs from the defaults is
 * stored, so a built-in set back to Medium leaves no record.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CriteriaScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.criteria_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) { CriteriaEditor() }
        }
    }
}

/** The screen's content without the bar (the screenshot shows it alone). */
@Composable
fun CriteriaEditor() {
    val repo = LocalAppServices.current.repository
    // null until Room answers, so nothing flashes on the way in.
    val scoring: Scoring? by remember(repo) { repo.observeScoring() }.collectAsStateWithLifecycle(initialValue = null)
    val houses: List<HouseEntity> by repo.houses.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val saveFailed = stringResource(Res.string.criteria_save_failed)
    val maxText = stringResource(Res.string.criteria_max, Criterion.MAX_CRITERIA)

    // Every change is saved at once; a failure says so under the list, a full list says why.
    fun save(block: suspend () -> Unit) {
        scope.launch {
            error = try {
                block()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecordLimitException) {
                maxText
            } catch (e: Exception) {
                saveFailed
            }
        }
    }

    val s = scoring ?: return
    val shown = s.criteria.filter { !it.archived }
    val archived = s.criteria.filter { it.archived }
    // A custom criterion can be deleted only while no house has a score under its key; otherwise it is archived.
    val scoredKeys = houses.flatMapTo(HashSet()) { it.checklist.keys }

    // The new order: the shown ones as moved, then the archived ones, renumbered 0..n.
    fun renumbered(order: List<Criterion>): List<Criterion> =
        (order + archived).mapIndexed { i, c -> c.copy(sort = i) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.criteria_intro), style = MaterialTheme.typography.bodyMedium)

        RatingShareSection(s.ratingShare) { share -> save { repo.saveRatingShare(share) } }

        HorizontalDivider()
        SectionHeading(stringResource(Res.string.criteria_heading))
        if (shown.isEmpty()) Text(stringResource(Res.string.criteria_all_archived), style = MaterialTheme.typography.bodyMedium)
        shown.forEachIndexed { index, c ->
            CriterionCard(
                criterion = c,
                canMoveUp = index > 0,
                canMoveDown = index < shown.lastIndex,
                canDelete = !c.isBuiltIn && c.key !in scoredKeys,
                onChange = { changed -> save { repo.saveCriterion(changed) } },
                onMove = { delta ->
                    val order = shown.toMutableList()
                    val other = index + delta
                    order[index] = shown[other]
                    order[other] = c
                    save { repo.saveCriteria(renumbered(order)) }
                },
                onArchive = { save { repo.saveCriterion(c.copy(archived = true)) } },
                onDelete = { save { repo.deleteCriterion(c.key) } },
            )
        }

        AddCriterion(full = s.criteria.size >= Criterion.MAX_CRITERIA, maxText = maxText) { name ->
            save { repo.addCriterion(name) }
        }

        LiveMessage {
            error?.let { WarnNote(it) }
        }

        HorizontalDivider()
        SectionHeading(stringResource(Res.string.criteria_archived))
        if (archived.isEmpty()) {
            Text(stringResource(Res.string.criteria_archived_none), style = MaterialTheme.typography.bodySmall)
        }
        archived.forEach { c ->
            val name = c.displayName()
            val desc = stringResource(Res.string.criteria_unarchive, name)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    // Back at the end of the list, where it is seen.
                    onClick = { save { repo.saveCriterion(c.copy(archived = false, sort = (s.criteria.maxOf { it.sort }) + 1)) } },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                ) { ButtonLabel(stringResource(Res.string.criteria_unarchive_button)) }
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.criteria_reset))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(Res.string.criteria_reset_title)) },
            text = { Text(stringResource(Res.string.criteria_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    save { repo.resetScoring() }
                }) { Text(stringResource(Res.string.criteria_reset_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

/** The star rating's share: 0 % to 100 % in steps of 25, 50 % by default. */
@Composable
private fun RatingShareSection(share: Double, onChange: (Double) -> Unit) {
    SectionHeading(stringResource(Res.string.criteria_rating_share))
    Text(stringResource(Res.string.criteria_rating_share_hint), style = MaterialTheme.typography.bodySmall)
    // The nearest step, so a share synced from elsewhere (0.4) still shows a choice.
    val current = RATING_SHARES.minBy { kotlin.math.abs(it - share) }
    // Chips that wrap, as the weights above (Wave D): a row of five segments cut "100%" to "100" at 200 % text.
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RATING_SHARES.forEach { value ->
            val selected = current == value
            FilterChip(
                selected = selected,
                onClick = { if (share != value) onChange(value) },
                label = { Text("${(value * 100).toInt()}%") },
                leadingIcon = if (selected) ChipCheck else null,
                border = brandFilterChipBorder(selected),
                modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton },
            )
        }
    }
}

/**
 * One criterion: its name, the weight (Ignore, Low, Medium, High), *Must-have* with the lowest accepted score when on,
 * move up and down, *Archive*, and *Delete* for a custom one no house has a score for. Every control's label names the
 * criterion ("Parking: High", "Move Parking up"), so TalkBack never reads a bare "High".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CriterionCard(
    criterion: Criterion,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onChange: (Criterion) -> Unit,
    onMove: (Int) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val name = criterion.displayName()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.criteria_weight), style = MaterialTheme.typography.bodySmall)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CriterionWeights.forEachIndexed { weight, res ->
                    val selected = criterion.weight == weight
                    val label = stringResource(res)
                    val desc = stringResource(Res.string.criteria_weight_desc, name, label)
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) onChange(criterion.copy(weight = weight)) },
                        label = { Text(label) },
                        leadingIcon = if (selected) ChipCheck else null,
                        border = brandFilterChipBorder(selected),
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            role = Role.RadioButton
                            contentDescription = desc
                        },
                    )
                }
            }
            SwitchRow(
                text = stringResource(Res.string.criteria_must_have),
                hint = null,
                checked = criterion.mustHave,
                horizontalPadding = 0.dp,
            ) { on -> onChange(criterion.copy(mustHave = on)) }
            if (criterion.mustHave) {
                Text(stringResource(Res.string.criteria_min_score), style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Criterion.MIN_SCORES.forEach { n ->
                        val selected = criterion.minScore == n
                        val desc = stringResource(Res.string.criteria_min_score_desc, name, n)
                        FilterChip(
                            selected = selected,
                            onClick = { if (!selected) onChange(criterion.copy(minScore = n)) },
                            label = { Text(n.toString()) },
                            leadingIcon = if (selected) ChipCheck else null,
                            border = brandFilterChipBorder(selected),
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                role = Role.RadioButton
                                contentDescription = desc
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.criteria_move_up, name))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.criteria_move_down, name))
                }
                Spacer(Modifier.weight(1f))
                val archiveDesc = stringResource(Res.string.criteria_archive, name)
                TextButton(onClick = onArchive, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = archiveDesc }) {
                    ButtonLabel(stringResource(Res.string.criteria_archive_button))
                }
                if (canDelete) {
                    val deleteDesc = stringResource(Res.string.criteria_delete, name)
                    TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteDesc }) {
                        ButtonLabel(stringResource(Res.string.common_delete))
                    }
                }
            }
        }
    }
}

/** *Add criterion*: a name (≤ 60) and the button; at 40 criteria both are off and "At most 40 criteria" says why. */
@Composable
private fun AddCriterion(full: Boolean, maxText: String, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val missing = tried && name.isBlank()
    fun add() {
        tried = true
        if (name.isBlank() || full) return
        onAdd(name.trim())
        name = ""
        tried = false
    }
    OutlinedTextField(
        name, { name = it.take(Criterion.MAX_LABEL) },
        label = { Text(stringResource(Res.string.criteria_add_name)) },
        enabled = !full,
        isError = missing,
        supportingText = when {
            full -> ({ Text(maxText) })
            missing -> ({ Text(stringResource(Res.string.criteria_add_needed)) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { add() }, enabled = !full, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.criteria_add))
    }
}

/**
 * "Must-have missed" on a house card (slice 2): the warning colours with a border and the word, so it does not rest on
 * colour alone; the card's merged description reads it with the rest.
 */
@Composable
fun MustHaveMissedChip(modifier: Modifier = Modifier) {
    val colors = LocalDoorprintsColors.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = colors.warn,
        contentColor = colors.onWarn,
        border = BorderStroke(1.dp, colors.warnBorder),
        modifier = modifier,
    ) {
        Text(
            stringResource(Res.string.houses_must_have_missed),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
