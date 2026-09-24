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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.data.ChecklistLabels
import app.doorprints.data.HouseEntity
import app.doorprints.data.glyph
import app.doorprints.data.labelRes
import app.doorprints.ui.res.*
import app.doorprints.shared.model.HouseStatus
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
)

/** Saves the chosen house ids as a list (a Set is not Bundle-safe by itself). */
private val SelectionSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })

/**
 * Compare up to four houses side by side (UX review, whole-app audit).
 *
 * **Kept.** The selection is saved state, so opening a house from the table (the designed way to look at one) and
 * coming back, a rotation or the language switch keep the comparison the user built. The top-3 default applies once,
 * when there is no saved choice yet; ids of houses that were deleted or rejected since are dropped.
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
 * **Empty.** Fewer than two houses to compare (rejected ones are left out) is a designed empty state with *Add a house
 * on the map* ([onOpenMap]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompareScreen(onOpenHouse: (String) -> Unit, onOpenMap: () -> Unit = {}) {
    val repo = repository()
    // null until Room answers, so the empty state does not flash on the way in.
    val loaded: List<HouseEntity>? by repo.houses.collectAsStateWithLifecycle(initialValue = null)
    val counts by repo.visitCounts.collectAsStateWithLifecycle(emptyList())
    val visits = counts.associate { it.houseId to it.visits }
    var selected by rememberSaveable(stateSaver = SelectionSaver) { mutableStateOf(emptySet<String>()) }
    var defaulted by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(true) }
    val candidates = loaded.orEmpty().filter { it.status != HouseStatus.REJECTED }
        .sortedWith(compareByDescending<HouseEntity> { it.status == HouseStatus.SHORTLISTED }.thenByDescending { it.score ?: -1.0 })
    val candidateIds = candidates.map { it.id }.toSet()
    LaunchedEffect(loaded != null, candidateIds) {
        if (loaded == null) return@LaunchedEffect
        if (!defaulted) {
            // Once only: after that an empty choice is the user's own.
            if (selected.isEmpty()) selected = candidates.take(3).map { it.id }.toSet()
            defaulted = true
        } else {
            val kept = selected intersect candidateIds
            if (kept != selected) selected = kept
        }
    }
    val chosen = candidates.filter { it.id in selected }
    val unnamed = stringResource(Res.string.house_unnamed)
    fun nameOf(h: HouseEntity) = h.label.ifBlank { unnamed }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(Res.string.compare_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        when {
            // Room has not answered yet: the heading only.
            loaded == null -> Unit
            candidates.size < 2 -> HeroEmptyState(
                icon = Icons.AutoMirrored.Filled.List,
                title = stringResource(Res.string.compare_empty),
                body = stringResource(Res.string.compare_empty_body),
                horizontalPadding = 0.dp,
                action = {
                    Button(onClick = onOpenMap, modifier = Modifier.heightIn(min = 48.dp)) {
                        ButtonLabel(stringResource(Res.string.common_add_on_map))
                    }
                },
            )
            else -> ComparePicker(
                candidates = candidates,
                chosen = chosen,
                selected = selected,
                onSelected = { selected = it },
                pickerOpen = pickerOpen,
                onPickerOpen = { pickerOpen = it },
                visits = visits,
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
    nameOf: (HouseEntity) -> String,
    onOpenHouse: (String) -> Unit,
) {
    Column {
        Text(stringResource(Res.string.compare_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))

        if (chosen.size < 2) {
            Text(stringResource(Res.string.compare_pick_more), Modifier.padding(bottom = 16.dp))
        } else {
            CompareTable(chosen, visits, nameOf, onOpenHouse)
            Text(stringResource(Res.string.compare_footnote),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        }

        // The picker, below the table, collapsible.
        val pickerTitle = stringResource(Res.string.compare_choose, chosen.size, MAX_COMPARED)
        val stateText = stringResource(if (pickerOpen) Res.string.common_expanded else Res.string.common_collapsed)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
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
                val statusText = stringResource(h.status.labelRes)
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
    nameOf: (HouseEntity) -> String,
    onOpenHouse: (String) -> Unit,
) {
    val context = LocalContext.current
    LocalConfiguration.current // read again after a language change, like priceText()
    val notScored = stringResource(Res.string.compare_not_scored)
    val notSet = stringResource(Res.string.compare_not_set)
    val scoreRow = stringResource(Res.string.compare_overall)
    val bhkFormat = stringResource(Res.string.common_bhk)
    val starsFormat = stringResource(Res.string.common_stars)
    val checkFormat = stringResource(Res.string.house_check_value)
    val checklistLabels = ChecklistLabels.items.map { (key, res) -> key to stringResource(res) }
    val rows = buildList {
        add(CompareRow(scoreRow, notScored, { h -> h.score?.let { Formats.score(context, it) } }))
        add(CompareRow(stringResource(Res.string.compare_price), notSet, { h -> Formats.price(context, h.price, h.priceType) }))
        add(CompareRow(stringResource(Res.string.compare_bhk), notSet, { h -> h.bedrooms?.let { String.format(bhkFormat, it) } }))
        add(CompareRow(stringResource(Res.string.compare_rating), notScored, { h -> h.rating?.let { String.format(starsFormat, it) } }))
        add(CompareRow(stringResource(Res.string.compare_visits), notSet, { (visits[it.id] ?: 0).toString() }))
        add(CompareRow(stringResource(Res.string.compare_street), notSet, { it.street?.takeIf { s -> s.isNotBlank() } }))
        checklistLabels.forEach { (key, label) ->
            add(
                CompareRow(
                    label, notScored,
                    value = { h -> h.checklist[key]?.toString() },
                    spoken = { h -> h.checklist[key]?.let { String.format(checkFormat, it) } },
                ),
            )
        }
        add(CompareRow(stringResource(Res.string.compare_contact), notSet, {
            it.contactName?.takeIf { n -> n.isNotBlank() } ?: it.contactPhone?.takeIf { p -> p.isNotBlank() }
        }))
    }
    val best = chosen.maxByOrNull { it.score ?: -1.0 }?.takeIf { it.score != null }
    val bestFormat = stringResource(Res.string.compare_best_name)
    val openLabel = stringResource(Res.string.compare_open_house)
    val headerName: (HouseEntity) -> String = { h -> if (h == best) String.format(bestFormat, nameOf(h)) else nameOf(h) }
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
                "${nameOf(h)}, ${row.spoken(h) ?: row.missing}"
            }.joinToString("; ")
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .semantics(mergeDescendants = true) { }
                    .clearAndSetSemantics { contentDescription = spokenRow },
            ) {
                Cell(row.label, header = true, width = LABEL_WIDTH)
                Row(Modifier.weight(1f).horizontalScroll(hScroll)) {
                    chosen.forEach { h -> Cell(row.value(h) ?: "–", width = CELL_WIDTH) }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Cell(text: String, width: Dp, header: Boolean = false) {
    // Min height instead of a fixed one, so text can grow with the font scale (A11Y-A03).
    Box(
        Modifier.width(width).fillMaxHeight().heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text, maxLines = 4, overflow = TextOverflow.Ellipsis,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
