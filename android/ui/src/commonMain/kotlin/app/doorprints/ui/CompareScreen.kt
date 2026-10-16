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

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.doorprints.data.HouseEntity
import app.doorprints.data.HouseVisitCount
import app.doorprints.shared.model.Broker
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.CompareCriteria
import app.doorprints.shared.model.CompareCriterion
import app.doorprints.shared.model.Distances
import app.doorprints.shared.model.Place
import app.doorprints.ui.res.*
import app.doorprints.shared.model.CostSummary
import app.doorprints.shared.model.HouseRooms
import app.doorprints.shared.model.HouseStatus
import app.doorprints.shared.model.HouseStatusRules
import app.doorprints.shared.model.Ranking
import app.doorprints.shared.export.ExportStrings
import app.doorprints.shared.model.KindValue
import app.doorprints.shared.model.KindValues
import app.doorprints.shared.model.Scoring
import app.doorprints.shared.model.ScopedCandidate
import app.doorprints.shared.model.SpaceScope
import app.doorprints.shared.model.LengthUnit
import app.doorprints.shared.model.RoomSizes
import kotlin.math.roundToLong
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The most houses the table compares. */
private const val MAX_COMPARED = 4

/** The pinned criterion column, and each house's column. */
private val LABEL_WIDTH = 112.dp
private val CELL_WIDTH = 140.dp

/**
 * One row of the table. [value] is what a cell shows, null when the house has none (drawn as "–"); [missing] is what
 * TalkBack says instead of "–" ("not scored" or "not set"); [spoken] overrides the spoken value when it differs from
 * the visible one (a checklist "3" is read "3 out of 5").
 */
private class CompareRow(
    val label: String,
    val missing: String,
    val value: (HouseEntity) -> String?,
    val spoken: (HouseEntity) -> String? = value,
    /** The candidates whose value is the best of the row (a kind's number or amount with a `better` hint, S4b-BL-292): marked, and said as best. */
    val best: Set<String> = emptySet(),
)

/** Saves the chosen house ids as a list (a Set is not Bundle-safe by itself). */
private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * The houses Compare offers (S4b-BL-99 a): those in the running ([HouseStatusRules.inTheRunning]: not Rejected, not Not
 * chosen, the same rule as the website's Compare and every Plan), shortlisted first, then by the ranking.
 */
internal fun compareCandidates(houses: List<HouseEntity>, scoring: Scoring): List<HouseEntity> =
    houses.filter { HouseStatusRules.inTheRunning(it.status) }
        .let { list -> Ranking.sort(list) { it.ranked(scoring) } }
        .sortedByDescending { it.status == HouseStatus.SHORTLISTED }

/**
 * Compare up to four houses side by side (UX review, whole-app audit).
 *
 * **Kept.** The selection is saved state, so opening a house from the table (the designed way to look at one) and
 * coming back, a rotation or the language switch keep the comparison the user built. The top-3 default applies once,
 * when there is no saved choice yet; ids of houses that were deleted, rejected or not chosen since are dropped.
 *
 * **Table first.** The table is at the top and the picker below it, in a "Choose houses (3 of 4)" section that can be
 * collapsed. With four houses chosen, "You can compare up to 4…" says why the others are disabled.
 *
 * **Readable with TalkBack.** Each table row is one item that says its criterion and every house's value: "Parking:
 * Green Villa, 3 out of 5; Blue Gate, not scored". The house names in the header are headings and buttons that open
 * the house; the best house reads "Green Villa (best)" through a format string, so each language orders it.
 *
 * **Pinned labels.** The criterion column stays put while the house columns scroll sideways together (one shared
 * [ScrollState]); the row dividers run the full width.
 *
 * **Empty.** Fewer than two houses to compare (Rejected and Not chosen ones are left out, [compareCandidates]) is a
 * designed empty state with *Add a house on the map* ([onOpenMap]).
 *
 * **Data.** [loaded] is the repository's live houses, null until the database answers, so the empty state does not
 * flash on the way in; [counts] its visits per house. Common code since CMP-4 P4c: `:app`'s `CompareScreen(onOpenHouse,
 * onOpenMap)` (`ui/CompareTab.kt`) collects both from the repository with the activity's lifecycle and passes them in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompareScreen(
    loaded: List<HouseEntity>?,
    counts: List<HouseVisitCount>,
    onOpenHouse: (String) -> Unit,
    onOpenMap: () -> Unit = {},
    /** The brokers by id (slice 1b): the Contact row names a linked house's broker and agency. */
    brokers: Map<String, Broker> = emptyMap(),
    /** This phone's length setting (slice 1c): the unit of the Rooms row's total area. */
    lengthUnit: LengthUnit = LengthUnit.FT,
    /** The effective scoring (slice 2): the Overall score and Must-haves rows, the checklist rows and the order. */
    scoring: Scoring = Scoring.DEFAULT,
    /** My places (slice 4a): one row each, the straight-line km from each house. */
    places: List<Place> = emptyList(),
    /**
     * Every saved candidate, only while two or more kinds are on (kinds PR C, S4b-BL-289): a pick that names a live
     * candidate of another space is refused (`SpaceScope.comparePick`), with one sentence; null with one kind on.
     */
    everyone: List<HouseEntity>? = null,
) {
    val space = LocalSpace.current
    // The house under the person's own scoring, another kind under its own checklist (kinds PR 27, docs/03 §18.21).
    val kindScoring = remember(space.built.id, scoring) { CompareCriteria.scoringFor(space.built, scoring) }
    val visits = counts.associate { it.houseId to it.visits }
    var selected by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var defaulted by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(true) }
    var refusedOther by rememberSaveable { mutableStateOf(false) }
    val candidates = compareCandidates(loaded.orEmpty(), kindScoring)
    val candidateIds = candidates.map { it.id }.toSet()
    LaunchedEffect(loaded != null, candidateIds, everyone) {
        if (loaded == null) return@LaunchedEffect
        // Picks that name a live candidate of another space are left out and said so (vectors `compare`); with one kind on there are none.
        val refused = everyone?.let { all ->
            SpaceScope.comparePick(selected.toList(), all.map { ScopedCandidate(it.id, it.kind, it.status.name) }, space.view::holds).refused
        }.orEmpty()
        if (refused.isNotEmpty()) refusedOther = true
        if (!defaulted) {
            // Once only: after that an empty choice is the user's own.
            if (selected.isEmpty()) selected = candidates.take(3).map { it.id }.toSet()
            defaulted = true
        } else {
            val kept = selected intersect candidateIds
            // Nothing left of the picks but another space's: start from the best of this one, as the first visit does.
            if (kept != selected) selected = if (kept.isEmpty() && refused.isNotEmpty()) candidates.take(3).map { it.id }.toSet() else kept
        }
    }
    // In the ranking's order (slice 2), shortlisted first: the best house leads the table.
    val chosen = candidates.filter { it.id in selected }
    val unnamed = stringResource(space.words.unnamed)
    fun nameOf(h: HouseEntity) = h.label.ifBlank { unnamed }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(Res.string.compare_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.tourTarget(TourTargets.COMPARE_TITLE).semantics { heading() })
        // Always composed, so the sentence is announced when it arrives (a pick of another space was left out).
        if (everyone != null) {
            LiveMessage {
                if (refusedOther) Text(stringResource(Res.string.compare_other_list), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
        when {
            // Room has not answered yet: the heading only.
            loaded == null -> Unit
            candidates.size < 2 -> HeroEmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(space.words.compareEmpty),
                body = stringResource(space.words.compareEmptyBody),
                horizontalPadding = 0.dp,
                // Houses are added on the map; without one (iOS for now; PlatformFeatures.map) there is no button.
                action = if (LocalPlatformFeatures.current.map) {
                    {
                        Button(onClick = onOpenMap, modifier = Modifier.heightIn(min = 48.dp)) {
                            ButtonLabel(stringResource(space.words.addOnMap))
                        }
                    }
                } else {
                    null
                },
            )
            else -> ComparePicker(
                candidates = candidates,
                chosen = chosen,
                selected = selected,
                onSelected = { selected = it; refusedOther = false },
                pickerOpen = pickerOpen,
                onPickerOpen = { pickerOpen = it },
                visits = visits,
                brokers = brokers,
                lengthUnit = lengthUnit,
                scoring = kindScoring,
                places = places,
                nameOf = ::nameOf,
                onOpenHouse = onOpenHouse,
            )
        }
    }
}

/** The table (when two or more are chosen), then the collapsible picker; see [CompareScreen]. */
@Composable
private fun ComparePicker(
    candidates: List<HouseEntity>,
    chosen: List<HouseEntity>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    pickerOpen: Boolean,
    onPickerOpen: (Boolean) -> Unit,
    visits: Map<String, Int>,
    brokers: Map<String, Broker>,
    lengthUnit: LengthUnit,
    scoring: Scoring,
    places: List<Place>,
    nameOf: (HouseEntity) -> String,
    onOpenHouse: (String) -> Unit,
) {
    Column {
        val words = LocalSpace.current.words
        Text(stringResource(words.compareHint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        if (chosen.size < 2) {
            Text(stringResource(words.comparePickMore), Modifier.padding(bottom = 16.dp))
        } else {
            CompareTable(chosen, visits, brokers, lengthUnit, scoring, places, nameOf, onOpenHouse)
            Text(stringResource(words.compareFootnote),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        }

        // The picker, below the table, collapsible.
        val pickerTitle = stringResource(words.compareChoose, chosen.size, MAX_COMPARED)
        val stateText = stringResource(if (pickerOpen) Res.string.common_expanded else Res.string.common_collapsed)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).tourTarget(TourTargets.COMPARE_PICKER)
                .clickable(role = Role.Button) { onPickerOpen(!pickerOpen) }
                .semantics { stateDescription = stateText },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pickerTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Icon(if (pickerOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null)
        }
        if (pickerOpen) {
            candidates.forEach { h ->
                val checked = h.id in selected
                val statusText = stringResource(LocalSpace.current.built.labels.statusText(h.status))
                val statusColor = h.status.color()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                        value = checked, role = Role.Checkbox,
                        enabled = checked || selected.size < MAX_COMPARED,
                        onValueChange = { onSelected(if (it) selected + h.id else selected - h.id) },
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(nameOf(h), Modifier.padding(start = 8.dp).weight(1f))
                    // The status with its glyph (UX-002); the glyph is not read out.
                    Text(
                        "${h.status.glyph} $statusText",
                        color = statusColor,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = statusText },
                    )
                }
            }
        }
        // Always composed, so reaching the limit is announced.
        LiveMessage {
            if (selected.size >= MAX_COMPARED) {
                Text(
                    stringResource(Res.string.compare_max),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** The comparison table itself; see [CompareScreen]. */
@Composable
private fun CompareTable(
    chosen: List<HouseEntity>,
    visits: Map<String, Int>,
    brokers: Map<String, Broker>,
    lengthUnit: LengthUnit,
    scoring: Scoring,
    places: List<Place>,
    nameOf: (HouseEntity) -> String,
    onOpenHouse: (String) -> Unit,
) {
    // Each chosen house's price as the list shows it (priceText reads price_per_month from Compose resources).
    val prices = chosen.associate { h -> h.id to key(h.id) { priceText(h.price, h.priceType) } }
    val notScored = stringResource(Res.string.compare_not_scored)
    val notSet = stringResource(Res.string.compare_not_set)
    val scoreRow = stringResource(Res.string.compare_overall)
    val bhkFormat = stringResource(Res.string.common_bhk)
    val starsFormat = stringResource(Res.string.common_stars)
    val checkFormat = stringResource(Res.string.house_check_value)
    val space = LocalSpace.current
    // The criteria that are not archived, in their order, under their names (slice 2): custom ones too; another kind's, the ones its Compare names.
    val checklistLabels = CompareCriteria.comparedRows(space.built, scoring).map { it.key to it.displayName(space.built) }
    val results = chosen.associate { it.id to it.scoreResult(scoring) }
    val nameOfKey = scoring.criteria.associate { it.key to it.displayName(space.built) }
    val metText = stringResource(Res.string.compare_must_have_met)
    val missedFormat = stringResource(Res.string.compare_must_have_missed)
    val uncheckedFormat = stringResource(Res.string.compare_must_have_unchecked)
    // "Missed: Water supply", else "Not checked yet: Security", else "All met"; not set when there is no must-have.
    fun mustHaves(h: HouseEntity): String? {
        val r = results[h.id] ?: return null
        fun names(keys: List<String>) = keys.joinToString(", ") { nameOfKey[it] ?: it }
        return when {
            r.failedMustHave.isNotEmpty() -> formatPositional(missedFormat, names(r.failedMustHave))
            r.uncheckedMustHave.isNotEmpty() -> formatPositional(uncheckedFormat, names(r.uncheckedMustHave))
            else -> metText
        }
    }
    val sqftFormat = stringResource(Res.string.common_sqft)
    // The house's own values (docs/11 5.21, slice 1a) line up under the price: what it costs, then the size.
    val summaries = chosen.associate { h -> h.id to CostSummary.of(h.price, h.priceType, h.areaSqft, h.cost) }
    // The rooms (slice 1c): "3 rooms · 235 sq ft", the total when a room has both sizes; not set without rooms.
    val roomTexts = chosen.associate { h ->
        h.id to key(h.id) {
            h.rooms?.takeIf { it.isNotEmpty() }?.let { rooms ->
                val (total, sized) = HouseRooms.totalAreaSqCm(rooms)
                val count = pluralStringResource(Res.plurals.compare_rooms_count, rooms.size, rooms.size)
                if (sized > 0) "$count · ${RoomSizes.areaText(total, lengthUnit)}" else count
            }
        }
    }
    // A kind shows a row only for a place it has (kinds PR C, S4b-BL-289: `labels.isVisible`); the house has every one, so its
    // rows are the ones it always had, in the same order. A kind's other extras are rows too, in its file's order, before the rating.
    val extraRows = kindExtraRows(space.built, chosen, notSet)
    // A row's heading: Compare's own word for the house, the kind's label for another kind (S4b-BL-256, the website's labelKey).
    @Composable
    fun head(id: String, house: StringResource) = stringResource(if (CompareCriteria.usesPersonScoring(space.built)) house else space.built.labels.fieldText(id))
    val rows = buildList {
        fun row(ref: String, r: CompareRow) {
            if (space.shows(ref)) add(r)
        }
        row("section.checklist", CompareRow(scoreRow, notScored, { h -> results[h.id]?.overall?.let { Formats.score(it) } }))
        // Only when a criterion is a must-have (slice 2): the row would say nothing otherwise.
        if (scoring.criteria.any { it.mustHave && !it.archived }) {
            row("section.checklist", CompareRow(stringResource(Res.string.compare_must_haves), notSet, ::mustHaves))
        }
        row("field.money", CompareRow(stringResource(Res.string.compare_price), notSet, { h -> prices[h.id] }))
        row("field.agreedPrice", CompareRow(stringResource(Res.string.compare_agreed_price), notSet, { h -> h.cost?.agreedPrice?.let { Formats.rupees(it) } }))
        row("field.money", CompareRow(stringResource(Res.string.compare_monthly_cost), notSet, { h -> summaries[h.id]?.monthlyCost?.let { Formats.rupees(it) } }))
        row("field.money", CompareRow(stringResource(Res.string.compare_move_in), notSet, { h -> summaries[h.id]?.moveIn?.let { Formats.rupees(it) } }))
        row("field.money", CompareRow(stringResource(Res.string.compare_per_sqft), notSet, { h -> summaries[h.id]?.perSqFt?.let { Formats.rupees(it.roundToLong()) } }))
        row("field.bedrooms", CompareRow(stringResource(Res.string.compare_bhk), notSet, { h -> h.bedrooms?.let { formatPositional(bhkFormat, it) } }))
        row("field.areaSqft", CompareRow(stringResource(Res.string.compare_area), notSet, { h -> h.areaSqft?.let { formatPositional(sqftFormat, it) } }))
        row("section.rooms", CompareRow(stringResource(Res.string.compare_rooms), notSet, { h -> roomTexts[h.id] }))
        row("field.availableFrom", CompareRow(stringResource(Res.string.compare_available_from), notSet, { h -> h.cost?.availableFrom }))
        addAll(extraRows)
        row("field.rating", CompareRow(head("rating", Res.string.compare_rating), notScored, { h -> h.rating?.let { formatPositional(starsFormat, it) } }))
        row("section.visits", CompareRow(stringResource(Res.string.compare_visits), notSet, { (visits[it.id] ?: 0).toString() }))
        row("field.street", CompareRow(head("street", Res.string.compare_street), notSet, { it.street?.takeIf { s -> s.isNotBlank() } }))
        // One row per place (slice 4a): the straight-line km, not set for a house without a point.
        val kmFormat = stringResource(Res.string.compare_km)
        places.forEach { place ->
            row("section.distances", CompareRow(place.name, notSet, { h ->
                Distances.toPlaces(h.point(), listOf(place)).firstOrNull()?.let { formatPositional(kmFormat, it.km) }
            }))
        }
        checklistLabels.forEach { (key, label) ->
            row(
                "section.checklist",
                CompareRow(
                    label, notScored,
                    value = { h -> h.checklist[key]?.toString() },
                    spoken = { h -> h.checklist[key]?.let { formatPositional(checkFormat, it) } },
                ),
            )
        }
        row("field.contactName", CompareRow(head("contactName", Res.string.compare_contact), notSet, {
            it.brokerId?.let(brokers::get)?.label
                ?: it.contactName?.takeIf { n -> n.isNotBlank() } ?: it.contactPhone?.takeIf { p -> p.isNotBlank() }
        }))
    }
    // The best by the ranking, when it has a score at all (a kind with no checklist has no score, so no best).
    val best = Ranking.sort(chosen) { it.ranked(scoring) }.firstOrNull()?.takeIf { results[it.id]?.overall != null && space.shows("section.checklist") }
    val bestFormat = stringResource(Res.string.compare_best_name)
    val openLabel = stringResource(space.pages.openDetails)
    val headerName: (HouseEntity) -> String = { h -> if (h == best) formatPositional(bestFormat, nameOf(h)) else nameOf(h) }
    // One ScrollState for every row: the house columns scroll together, the labels stay.
    val hScroll = rememberScrollState()

    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Cell("", header = true, width = LABEL_WIDTH)
            Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                chosen.forEach { h ->
                    // A heading and a button: it opens the house. Primary and underlined, so it looks like one.
                    Box(
                        Modifier.width(CELL_WIDTH).fillMaxHeight().heightIn(min = 48.dp)
                            .clickable(role = Role.Button, onClickLabel = openLabel) { onOpenHouse(h.id) }
                            .semantics { heading() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(headerName(h)) }
                            },
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        rows.forEach { row ->
            // One TalkBack item per row: "Parking: Green Villa, 3 out of 5; Blue Gate, not scored".
            val spokenRow = row.label + ": " + chosen.map { h ->
                val said = row.spoken(h) ?: row.missing
                "${nameOf(h)}, ${if (h.id in row.best) formatPositional(bestFormat, said) else said}"
            }.joinToString("; ")
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .semantics(mergeDescendants = true) { }
                    .clearAndSetSemantics { contentDescription = spokenRow },
            ) {
                Cell(row.label, header = true, width = LABEL_WIDTH)
                Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    chosen.forEach { h -> Cell(row.value(h)?.let { if (h.id in row.best) "✓ $it" else it } ?: "–", width = CELL_WIDTH, best = h.id in row.best) }
                }
            }
            HorizontalDivider()
        }
    }
}

/** One cell of the comparison table: [width] wide, bold when it is a [header] or the [best] of its row. */
@Composable
private fun Cell(text: String, width: Dp, header: Boolean = false, best: Boolean = false) {
    // Min height instead of a fixed one, so text can grow with the font scale (A11Y-A03).
    Box(
        Modifier.width(width).fillMaxHeight().heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text, maxLines = 4, overflow = TextOverflow.Ellipsis,
            fontWeight = if (header || best) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The rows of a kind's other typed extras (kinds PR C, S4b-BL-289; the website's `KindFields.others` rows): one per extra the
 * kind has, in its file's order, each in its type's form (money as rupees, a switch as yes or no, an option by its label, a
 * number's digits, a text as typed). A house has none, so its table is the table it always had.
 */
@Composable
private fun kindExtraRows(kind: BuiltInKind, chosen: List<HouseEntity>, notSet: String): List<CompareRow> {
    val fields = kind.fields
    val extras = fields.others()
    if (extras.isEmpty()) return emptyList()
    val words = ExportStrings.of(uiLanguage())
    val texts: Map<String, Map<String, String>> = chosen.associate { h ->
        h.id to key(h.id) {
            KindValues.of(fields, h.extrasOther).associate { v ->
                v.field.id to when (v) {
                    is KindValue.Money -> Formats.rupees(v.amount)
                    is KindValue.Count -> v.value.toString()
                    is KindValue.Bool -> words[if (v.value) "yes" else "no"]
                    is KindValue.Option -> stringResource(kindStringResource(v.label))
                    is KindValue.Text -> v.value
                }
            }
        }
    }
    // The best of a number or an amount, marked only for the extras the kind's Compare names (`capabilities.compare`, kinds PR 27) and
    // in the direction the kind file gives (S4b-BL-292): the school's lower commute and fees, not its admission fee.
    val criteria: List<CompareCriterion> = CompareCriteria.of(kind)
    return extras.map { e ->
        val better = criteria.firstOrNull { it.id == e.id && it.source == CompareCriterion.Source.EXTRA }?.better
        val best = CompareCriteria.bestOfExtra(chosen.map { CompareCriteria.extraFigure(fields, it.extrasOther, e.id) }, better).map { chosen[it].id }.toSet()
        CompareRow(stringResource(kindStringResource(fields.label(e))), notSet, { h -> texts[h.id]?.get(e.id) }, best = best)
    }
}
