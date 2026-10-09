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

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.HouseScore
import app.doorprints.shared.model.ScoreResult
import app.doorprints.shared.model.Scoring
import app.doorprints.ui.res.*
import org.jetbrains.compose.resources.stringResource

/*
 * The house form's checklist and score (S4b-BL-168, phone slice 7): the heading, one row per criterion that is not
 * archived (a criterion set to Ignore says it is not counted), the score summary under them, and the rule that "-" or
 * the chosen score again clears a score ([withScore]). It owns what the checklist shows and how a tap changes it; the
 * form owns the draft and passes the checklist and the rating in. It emits its children straight into the caller's
 * scope (no wrapper), so the form's `spacedBy(12.dp)` column spaces them as it did when they were inline.
 */

/**
 * The checklist heading, a row for each criterion of [scoring] that is not archived, in their order, and the score
 * summary. An archived criterion is hidden, and its score on the house stays as it is. A tap on a row calls [onChange]
 * with the whole new checklist. [rating] is the house's star rating: the score counts it by the rating share, so the
 * summary needs it beside the [checklist] (the same `HouseScore.evaluate` that `HouseEntity.scoreResult` calls).
 */
@Composable
internal fun ChecklistSection(checklist: Map<String, Int>, rating: Int?, scoring: Scoring, onChange: (Map<String, Int>) -> Unit) {
    SectionHeading(stringResource(Res.string.house_checklist))
    // The criteria that are not archived, in their order (slice 2); one set to Ignore says it is not counted.
    // An archived criterion is hidden, and its score on the house stays as it is.
    scoring.criteria.filter { !it.archived }.forEach { c ->
        val key = c.key
        val name = c.displayName()
        val label = if (c.weight == 0) stringResource(Res.string.house_check_ignored, name) else name
        // "–" clears; tapping the chosen score again is kept as a shortcut for the same.
        ChecklistRow(label, checklist[key]) { n -> onChange(checklist.withScore(key, n)) }
    }
    ScoreSummary(HouseScore.evaluate(checklist, rating, scoring), scoring)
}

/**
 * The score under the checklist (slice 2): "Overall score: 4.3 out of 5", "Scored 7 of 10 that matter" once something
 * counted is scored, and the must-haves missed or not checked yet by name. Polite live region, so a new score is heard.
 */
@Composable
private fun ScoreSummary(result: ScoreResult, scoring: Scoring) {
    val names = scoring.criteria.associate { it.key to it.displayName() }
    fun list(keys: List<String>) = keys.joinToString(", ") { names[it] ?: it }
    LiveMessage {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(Res.string.house_overall, result.overall.scoreText()), fontWeight = FontWeight.SemiBold)
            if (result.scored > 0) {
                Text(stringResource(Res.string.house_coverage, result.scored, result.active), style = MaterialTheme.typography.bodySmall)
            }
            if (result.failedMustHave.isNotEmpty()) {
                WarnNote(stringResource(Res.string.house_must_have_missed, list(result.failedMustHave)))
            }
            if (result.uncheckedMustHave.isNotEmpty()) {
                Text(
                    stringResource(Res.string.house_must_have_unchecked, list(result.uncheckedMustHave)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** The checklist options in order: 0–5, then "–" (not scored) last; see [ChecklistRow]. */
private val CHECK_OPTIONS: List<Int?> = listOf(0, 1, 2, 3, 4, 5, null)

/**
 * One checklist item: its label and a segmented radio of 0–5 and "–" (not scored), the web's `.options .option`
 * (docs/05 §5; Design review, round 21). Each option is a fixed 48 dp square with an 8 dp corner: the chosen one is
 * filled `primary` with an `onPrimary` label and a 2 dp `primary` edge, the others `surface` with a 1 dp `outline`
 * edge. No ✓ (that is for toggle chips), and nothing changes size, so no option moves when another is chosen. The
 * chosen state is a change of lightness, not only of hue (`primary` on `surface` 6.02:1 light, over 3:1 dark;
 * WCAG 1.4.1); the label is 6.02:1 light, about 10:1 dark; the unselected edge 3.63:1 (1.4.11).
 *
 * The row wraps, never scrolls. **"–" is last** (whole-app audit): 7 × 48 + 6 × 4 = 360 dp needs a 392 dp phone, but
 * most Indian budget phones are 360–391 dp (328–359 dp inside the gutters). With "–" first, "5" went alone to a second
 * line on every one of the ten rows, and the 0–5 scale read as split; now 0–5 stay on one line (6 × 48 + 5 × 4 =
 * 308 dp) and only "–" wraps. "–" makes "not scored" an explicit choice (UX-005: every rating can be cleared); tapping
 * the chosen score again still clears it, as a shortcut. README section 8, device check 19 (h).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChecklistRow(label: String, value: Int?, onPick: (Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val state = value?.let { stringResource(Res.string.house_check_value, it) } ?: stringResource(Res.string.house_not_rated)
    val shape = RoundedCornerShape(8.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label · $state", style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CHECK_OPTIONS.forEach { n ->
                val sel = value == n
                val desc = if (n == null) stringResource(Res.string.house_check_option_none, label)
                else stringResource(Res.string.house_check_option, label, n)
                Box(
                    Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(shape)
                        .background(if (sel) scheme.primary else scheme.surface, shape)
                        .border(if (sel) 2.dp else 1.dp, if (sel) scheme.primary else scheme.outline, shape)
                        .selectable(selected = sel, role = Role.RadioButton, onClick = { onPick(n) })
                        .semantics { contentDescription = desc },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        n?.toString() ?: "–",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (sel) scheme.onPrimary else scheme.onSurface,
                    )
                }
            }
        }
    }
}
