package com.househunt.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunt.app.R
import com.househunt.app.data.Checklist
import com.househunt.app.data.HouseEntity
import com.househunt.app.data.HouseStatus

private data class CompareRow(val label: String, val value: @Composable (HouseEntity) -> String)

@Composable
fun CompareScreen(onOpenHouse: (String) -> Unit) {
    val repo = repository()
    val houses by repo.houses.collectAsStateWithLifecycle(emptyList())
    val counts by repo.visitCounts.collectAsStateWithLifecycle(emptyList())
    val visits = counts.associate { it.houseId to it.visits }
    var selected by remember { mutableStateOf(setOf<String>()) }
    val candidates = houses.filter { it.status != HouseStatus.REJECTED }
        .sortedWith(compareByDescending<HouseEntity> { it.status == HouseStatus.SHORTLISTED }.thenByDescending { it.score ?: -1.0 })
    LaunchedEffect(candidates.isNotEmpty()) {
        if (selected.isEmpty()) selected = candidates.take(3).map { it.id }.toSet()
    }
    val chosen = candidates.filter { it.id in selected }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(R.string.compare_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.compare_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        candidates.forEach { h ->
            val checked = h.id in selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = checked, role = Role.Checkbox,
                    enabled = checked || selected.size < 4,
                    onValueChange = { selected = if (it) selected + h.id else selected - h.id },
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Text(h.label.ifBlank { stringResource(R.string.house_unnamed) },
                    Modifier.padding(start = 8.dp).weight(1f))
                Text(stringResource(h.status.labelRes), color = h.status.color(), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (chosen.size < 2) {
            Text(stringResource(R.string.compare_pick_more), Modifier.padding(top = 16.dp))
            return@Column
        }
        Spacer(Modifier.height(16.dp))
        val dash = "–"
        val rows = buildList {
            add(CompareRow(stringResource(R.string.compare_overall)) { it.score.scoreText() })
            add(CompareRow(stringResource(R.string.compare_price)) { it.priceText() ?: dash })
            add(CompareRow(stringResource(R.string.compare_bhk)) { h -> h.bedrooms?.let { stringResource(R.string.common_bhk, it) } ?: dash })
            add(CompareRow(stringResource(R.string.compare_rating)) { h -> h.rating?.let { stringResource(R.string.common_stars, it) } ?: dash })
            add(CompareRow(stringResource(R.string.compare_visits)) { (visits[it.id] ?: 0).toString() })
            add(CompareRow(stringResource(R.string.compare_street)) { it.street ?: dash })
            Checklist.items.forEach { (key, label) ->
                add(CompareRow(stringResource(label)) { h -> h.checklist[key]?.toString() ?: dash })
            }
            add(CompareRow(stringResource(R.string.compare_contact)) { it.contactName ?: it.contactPhone ?: dash })
        }
        val best = chosen.maxByOrNull { it.score ?: -1.0 }
        val bestSuffix = stringResource(R.string.compare_best)
        // Row by row, so each row's cells share one height even when a label wraps at large font sizes.
        val tableWidth = 140.dp * (chosen.size + 1)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Cell("", header = true)
                chosen.forEach { h ->
                    val name = h.label.ifBlank { stringResource(R.string.house_unnamed) }
                    Cell(
                        if (h == best && h.score != null) "$name $bestSuffix" else name, header = true,
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.compare_open_house)) { onOpenHouse(h.id) },
                    )
                }
            }
            HorizontalDivider(Modifier.width(tableWidth))
            rows.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Cell(row.label, header = true)
                    chosen.forEach { Cell(row.value(it)) }
                }
                HorizontalDivider(Modifier.width(tableWidth))
            }
        }
        Text(stringResource(R.string.compare_footnote),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Cell(text: String, header: Boolean = false, modifier: Modifier = Modifier) {
    // Min height instead of a fixed one, so text can grow with the font scale (A11Y-A03).
    Box(modifier.width(140.dp).fillMaxHeight().heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart) {
        Text(
            text, maxLines = 4, overflow = TextOverflow.Ellipsis,
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
