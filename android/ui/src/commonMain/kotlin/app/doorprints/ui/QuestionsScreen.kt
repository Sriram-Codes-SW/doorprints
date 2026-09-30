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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.shared.model.QuestionScope
import app.doorprints.shared.records.RecordLimitException
import app.doorprints.ui.res.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How long a typed question rests before it is saved: one record write per pause, not one per key. */
private const val TEXT_SAVE_DELAY_MS = 600L

/**
 * Questions (docs/11 5.5, slice 3a), Settings > Questions: the bank of viewing questions grouped by category, each with
 * its text, category, *Applies to* (Rent, Buy, Both), *Ask by default*, move up and down within its group, *Archive*
 * and *Delete* (a seeded one too: it stays deleted until *Reset to defaults*); *Add question* (at most 100) and
 * *Reset to defaults*, which brings the standard questions back in the app's language and keeps the person's own.
 * Every change saves the one record it changed (`Repository.saveQuestion`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.questions_title)) },
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
            Column(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) { QuestionsEditor() }
        }
    }
}

/** The screen's content without the bar (the screenshot shows it alone). */
@Composable
fun QuestionsEditor() {
    val repo = LocalAppServices.current.repository
    // null until Room answers, so nothing flashes on the way in.
    val bank: List<Question>? by remember(repo) { repo.observeQuestions() }.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val saveFailed = stringResource(Res.string.questions_save_failed)
    val maxText = stringResource(Res.string.questions_max, Question.MAX_QUESTIONS)
    val language = uiLanguage()

    // Every change is saved at once; a failure says so under the list, a full bank says why.
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

    val all = bank ?: return
    val shown = all.filter { !it.archived }
    val archived = all.filter { it.archived }
    val groups = QuestionCategory.entries.associateWith { c -> shown.filter { it.questionCategory == c } }

    // The new order after a move inside a group: the groups in category order, then the archived, renumbered 0..n.
    fun renumbered(moved: QuestionCategory, order: List<Question>): List<Question> =
        (QuestionCategory.entries.flatMap { if (it == moved) order else groups.getValue(it) } + archived)
            .mapIndexed { i, q -> q.copy(sort = i) }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.questions_intro), style = MaterialTheme.typography.bodyMedium)
        if (shown.isEmpty()) Text(stringResource(Res.string.questions_empty), style = MaterialTheme.typography.bodyMedium)

        for ((category, list) in groups) {
            if (list.isEmpty()) continue
            SectionHeading(stringResource(category.labelResource))
            list.forEachIndexed { index, q ->
                key(q.id) {
                    QuestionCard(
                        question = q,
                        canMoveUp = index > 0,
                        canMoveDown = index < list.lastIndex,
                        onChange = { changed -> save { repo.saveQuestion(changed) } },
                        onMove = { delta ->
                            val order = list.toMutableList()
                            order[index] = list[index + delta]
                            order[index + delta] = q
                            save { repo.saveQuestions(renumbered(category, order)) }
                        },
                        onArchive = { save { repo.saveQuestion(q.copy(archived = true)) } },
                        onDelete = { save { repo.deleteQuestion(q.id) } },
                    )
                }
            }
        }

        HorizontalDivider()
        AddQuestion(full = all.size >= Question.MAX_QUESTIONS, maxText = maxText) { text ->
            save { repo.addQuestion(text) }
        }

        LiveMessage {
            error?.let { WarnNote(it) }
        }

        HorizontalDivider()
        SectionHeading(stringResource(Res.string.questions_archived))
        if (archived.isEmpty()) {
            Text(stringResource(Res.string.questions_archived_none), style = MaterialTheme.typography.bodySmall)
        }
        archived.forEach { q ->
            val desc = stringResource(Res.string.questions_unarchive, q.text)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(q.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    // Back at the end of the bank, where it is seen.
                    onClick = { save { repo.saveQuestion(q.copy(archived = false, sort = all.maxOf { it.sort } + 1)) } },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = desc },
                ) { ButtonLabel(stringResource(Res.string.questions_unarchive_button)) }
            }
        }

        HorizontalDivider()
        OutlinedButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            ButtonLabel(stringResource(Res.string.questions_reset))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(Res.string.questions_reset_title)) },
            text = { Text(stringResource(Res.string.questions_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    save { repo.resetQuestions(language) }
                }) { Text(stringResource(Res.string.questions_reset_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.common_cancel)) } },
        )
    }
}

/**
 * One question: its text (saved after a pause in typing; never blank), its category, *Applies to*, *Ask by default*,
 * move up and down, *Archive* and *Delete*. The move, archive and delete controls name the question for TalkBack.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    question: Question,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (Question) -> Unit,
    onMove: (Int) -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember(question.id) { mutableStateOf(question.text) }
    // Another device's edit (or Reset) replaces what is shown, unless it is what is being typed.
    LaunchedEffect(question.text) { if (text.trim() != question.text) text = question.text }
    LaunchedEffect(text) {
        val clean = text.trim()
        if (clean.isEmpty() || clean == question.text) return@LaunchedEffect
        delay(TEXT_SAVE_DELAY_MS)
        onChange(question.copy(text = clean))
    }
    val name = question.text
    val blank = text.isBlank()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it.take(Question.MAX_TEXT) },
                label = { Text(stringResource(Res.string.questions_text)) },
                isError = blank,
                supportingText = if (blank) ({ Text(stringResource(Res.string.questions_text_needed)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            ChoiceMenu(
                label = stringResource(Res.string.questions_category),
                options = QuestionCategory.entries,
                chosen = question.questionCategory,
                text = { stringResource(it.labelResource) },
            ) { onChange(question.copy(category = it.name)) }
            val appliesLabel = stringResource(Res.string.questions_applies)
            Text(appliesLabel, style = MaterialTheme.typography.bodySmall)
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuestionScope.entries.forEach { s ->
                    val selected = question.scope == s
                    val label = stringResource(s.labelResource)
                    val desc = stringResource(Res.string.questions_applies_desc, appliesLabel, label)
                    FilterChip(
                        selected = selected,
                        onClick = { if (!selected) onChange(question.copy(appliesTo = s.name)) },
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
                text = stringResource(Res.string.questions_default_on),
                hint = null,
                checked = question.defaultOn,
                horizontalPadding = 0.dp,
            ) { on -> onChange(question.copy(defaultOn = on)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.questions_move_up, name))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.questions_move_down, name))
                }
                Spacer(Modifier.weight(1f))
                val archiveDesc = stringResource(Res.string.questions_archive, name)
                TextButton(onClick = onArchive, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = archiveDesc }) {
                    ButtonLabel(stringResource(Res.string.questions_archive_button))
                }
                val deleteDesc = stringResource(Res.string.questions_delete, name)
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = deleteDesc }) {
                    ButtonLabel(stringResource(Res.string.common_delete))
                }
            }
        }
    }
}

/** *Add question*: a text (≤ 300) and the button; at 100 questions both are off and "At most 100 questions" says why. */
@Composable
private fun AddQuestion(full: Boolean, maxText: String, onAdd: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var tried by rememberSaveable { mutableStateOf(false) }
    val missing = tried && text.isBlank()
    fun add() {
        tried = true
        if (text.isBlank() || full) return
        onAdd(text.trim())
        text = ""
        tried = false
    }
    OutlinedTextField(
        text, { text = it.take(Question.MAX_TEXT) },
        label = { Text(stringResource(Res.string.questions_add_text)) },
        enabled = !full,
        isError = missing,
        supportingText = when {
            full -> ({ Text(maxText) })
            missing -> ({ Text(stringResource(Res.string.questions_add_needed)) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { add() }),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(onClick = { add() }, enabled = !full, modifier = Modifier.heightIn(min = 48.dp)) {
        ButtonLabel(stringResource(Res.string.questions_add))
    }
}
