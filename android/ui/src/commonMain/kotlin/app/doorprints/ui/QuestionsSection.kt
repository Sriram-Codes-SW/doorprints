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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.AnswerStatus
import app.doorprints.shared.model.AnswerWords
import app.doorprints.shared.model.HouseAnswer
import app.doorprints.shared.model.HouseAnswers
import app.doorprints.shared.model.HouseCost
import app.doorprints.shared.model.Question
import app.doorprints.shared.model.QuestionCategory
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/**
 * The house form's **Questions to ask** section (docs/11 5.5, slice 3a), after Rooms: "3 of 8 answered", one card per
 * question, open ones first (the question, an *Answer* box: typing makes it Answered, clearing it Open again; *Skip*;
 * *Remove*, which only takes it off this house), then *Add the usual questions* ([HouseAnswers.addUsual]: the house's
 * cost pre-fills the deposit, maintenance, brokerage and lock-in answers; "Already added" when it would add nothing) and
 * *Add a question* (a picker over the bank's questions not on the house yet, by category, and "Or ask something else").
 * At [HouseAnswers.MAX] both adds are off with "At most 60 questions". [onChange] gets the new list, null for none; the
 * repository's save coerces it.
 */
@Composable
fun QuestionsSection(
    answers: List<HouseAnswer>?,
    bank: List<Question>,
    priceType: String?,
    cost: HouseCost?,
    /** On the heading: the house form scrolls it into view for a reminder's *Questions* (S4b-BL-93b). */
    headingModifier: Modifier = Modifier,
    onChange: (List<HouseAnswer>?) -> Unit,
) {
    val list = answers.orEmpty()
    val words = answerWords()
    var picking by rememberSaveable { mutableStateOf(false) }
    SectionHeading(stringResource(Res.string.house_questions), headingModifier)
    if (list.isEmpty()) {
        Text(stringResource(Res.string.house_questions_empty), style = MaterialTheme.typography.bodySmall)
    } else {
        val answered = list.count { it.answerStatus == AnswerStatus.ANSWERED }
        LiveMessage {
            Text(
                stringResource(Res.string.house_questions_summary, answered, list.size),
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            )
        }
    }
    // Open first, in the order the cards had when the list last gained or lost one: a card does not jump away while
    // its answer is typed (which makes it Answered).
    val ids = list.map { it.id }
    val order = remember(ids.toSet()) { HouseAnswers.ordered(list).map { it.id } }
    val byId = list.associateBy { it.id }
    (order.mapNotNull { byId[it] } + list.filter { it.id !in order }).forEach { a ->
        key(a.id) {
            AnswerCard(
                a,
                onChange = { updated -> onChange(list.map { if (it.id == a.id) updated else it }) },
                onRemove = { onChange(list.filter { it.id != a.id }.takeIf { it.isNotEmpty() }) },
            )
        }
    }
    val full = list.size >= HouseAnswers.MAX
    val usual = HouseAnswers.usual(priceType, bank, list)
    OutlinedButton(
        onClick = { onChange(HouseAnswers.addUsual(priceType, cost, bank, list, words)) },
        enabled = !full && usual.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
        ButtonLabel(stringResource(Res.string.house_questions_add_usual))
    }
    // Always composed, so the reason the button is off is announced when it turns off.
    LiveMessage {
        // Only once something is on the house: with an empty bank there is nothing "already added" to point at.
        if (!full && usual.isEmpty() && list.isNotEmpty()) {
            Text(stringResource(Res.string.house_questions_already_added), style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(
        onClick = { picking = true },
        enabled = !full,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
        ButtonLabel(stringResource(Res.string.house_questions_add))
    }
    LiveMessage {
        if (full) Text(stringResource(Res.string.house_questions_max, HouseAnswers.MAX), style = MaterialTheme.typography.bodySmall)
    }
    if (picking && !full) {
        QuestionPicker(
            bank = bank, existing = list,
            onPick = { q ->
                picking = false
                onChange(list + HouseAnswers.ask(q.text, q.id, list))
            },
            onAsk = { text ->
                picking = false
                onChange(list + HouseAnswers.ask(text, null, list))
            },
            onDismiss = { picking = false },
        )
    }
}

/** The cost pre-fill's words in the app's language (`answer_*`), the templates [HouseAnswers.prefill] fills in. */
@Composable
private fun answerWords(): AnswerWords = AnswerWords(
    month = stringResource(Res.string.answer_month),
    months = stringResource(Res.string.answer_months),
    amountAndMonths = stringResource(Res.string.answer_amount_months),
    maintenance = stringResource(Res.string.answer_maintenance),
    maintenanceIncluded = stringResource(Res.string.answer_maintenance_included),
    maintenanceNotIncluded = stringResource(Res.string.answer_maintenance_not_included),
    lockInAndNotice = stringResource(Res.string.answer_lockin_notice),
    lockIn = stringResource(Res.string.answer_lockin),
    notice = stringResource(Res.string.answer_notice),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerCard(answer: HouseAnswer, onChange: (HouseAnswer) -> Unit, onRemove: () -> Unit) {
    val status = answer.answerStatus
    val skipped = status == AnswerStatus.SKIPPED
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                answer.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(status.labelResource), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                answer.answer ?: "",
                { v ->
                    val typed = v.take(HouseAnswers.MAX_ANSWER)
                    // Typing answers it, clearing it opens it again; a skipped one stays skipped until *Skip* is off.
                    val next = when {
                        skipped -> AnswerStatus.SKIPPED
                        typed.isBlank() -> AnswerStatus.OPEN
                        else -> AnswerStatus.ANSWERED
                    }
                    onChange(answer.copy(answer = typed.ifEmpty { null }, status = next.name))
                },
                label = { Text(stringResource(Res.string.house_question_answer)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val skipDesc = stringResource(Res.string.house_question_skip_desc, answer.text)
                FilterChip(
                    selected = skipped,
                    onClick = {
                        val next = when {
                            !skipped -> AnswerStatus.SKIPPED
                            answer.answer.isNullOrBlank() -> AnswerStatus.OPEN
                            else -> AnswerStatus.ANSWERED
                        }
                        onChange(answer.copy(status = next.name))
                    },
                    label = { Text(stringResource(Res.string.house_question_skip)) },
                    leadingIcon = if (skipped) ChipCheck else null,
                    border = brandFilterChipBorder(skipped),
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        role = Role.Checkbox
                        contentDescription = skipDesc
                    },
                )
                val removeDesc = stringResource(Res.string.house_question_remove_desc, answer.text)
                TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = removeDesc }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = ButtonDefaults.IconSpacing))
                    ButtonLabel(stringResource(Res.string.house_question_remove))
                }
            }
        }
    }
}

/**
 * *Add a question*: the bank's questions that are not archived and not on this house yet, grouped by category (a row
 * of at least 48 dp each), and "Or ask something else" for a question of this house only.
 */
@Composable
private fun QuestionPicker(
    bank: List<Question>,
    existing: List<HouseAnswer>,
    onPick: (Question) -> Unit,
    onAsk: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val asked = existing.mapNotNullTo(HashSet()) { it.questionId }
    val left = bank.filter { !it.archived && it.id !in asked }.sortedWith(Question.ORDER)
    var other by rememberSaveable { mutableStateOf("") }
    fun ask() {
        if (other.isNotBlank()) onAsk(other.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.house_questions_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (left.isEmpty()) Text(stringResource(Res.string.house_questions_pick_none), style = MaterialTheme.typography.bodySmall)
                for (category in QuestionCategory.entries) {
                    val group = left.filter { it.questionCategory == category }
                    if (group.isEmpty()) continue
                    Text(
                        stringResource(category.labelResource), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    group.forEach { q ->
                        Text(
                            q.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .clickable(role = Role.Button) { onPick(q) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
                OutlinedTextField(
                    other, { other = it.take(HouseAnswers.MAX_TEXT) },
                    label = { Text(stringResource(Res.string.house_questions_other)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { ask() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { ask() }, enabled = other.isNotBlank()) {
                Text(stringResource(Res.string.house_questions_other_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
