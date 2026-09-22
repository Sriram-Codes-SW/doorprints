package com.househunt.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.househunt.app.R
import com.househunt.app.data.HouseEntity
import com.househunt.app.data.HouseStatus

private enum class Sort(@StringRes val label: Int) {
    RECENT(R.string.sort_recent), SCORE(R.string.sort_score), PRICE(R.string.sort_price)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HouseListScreen(onOpenHouse: (String) -> Unit) {
    val repo = repository()
    val houses by repo.houses.collectAsStateWithLifecycle(emptyList())
    val counts by repo.visitCounts.collectAsStateWithLifecycle(emptyList())
    val visitsByHouse = counts.associateBy { it.houseId }
    var filter by remember { mutableStateOf<HouseStatus?>(null) }
    var sort by remember { mutableStateOf(Sort.RECENT) }
    var query by remember { mutableStateOf("") }

    val shown = houses
        .filter { filter == null || it.status == filter }
        .filter {
            query.isBlank() || listOfNotNull(it.label, it.street, it.address, it.locality, it.notes)
                .any { f -> f.contains(query, ignoreCase = true) }
        }
        .let { list ->
            when (sort) {
                Sort.RECENT -> list
                Sort.SCORE -> list.sortedByDescending { it.score ?: -1.0 }
                Sort.PRICE -> list.sortedBy { it.price ?: Long.MAX_VALUE }
            }
        }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.houses_title), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() })
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text(stringResource(R.string.houses_search)) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
            FilterChip(
                selected = filter == null, onClick = { filter = null },
                label = { Text(stringResource(R.string.status_filter, stringResource(R.string.status_ALL), houses.size)) },
            )
            HouseStatus.entries.forEach { s ->
                FilterChip(
                    selected = filter == s, onClick = { filter = s },
                    label = { Text(stringResource(R.string.status_filter, stringResource(s.labelRes), houses.count { it.status == s })) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
            Text(stringResource(R.string.houses_sort), style = MaterialTheme.typography.bodySmall)
            Sort.entries.forEach { s ->
                val selected = sort == s
                Box(
                    Modifier.heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { sort = s })
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(s.label),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(if (houses.isEmpty()) R.string.houses_empty else R.string.houses_no_match),
                modifier = Modifier.padding(top = 24.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shown, key = { it.id }) { h ->
                HouseCard(h, visitsByHouse[h.id]?.visits ?: 0) { onOpenHouse(h.id) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HouseCard(h: HouseEntity, visits: Int, onClick: () -> Unit) {
    // clickable merges the card's texts into one TalkBack item: "name, status, place, price, score, visits".
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.label.ifBlank { stringResource(R.string.house_unnamed) }, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(stringResource(h.status.labelRes), color = h.status.color(), style = MaterialTheme.typography.labelLarge)
            }
            val place = listOfNotNull(h.street, h.locality).joinToString(", ")
            if (place.isNotBlank()) Text(place, style = MaterialTheme.typography.bodySmall)
            // FlowRow wraps at large font scales instead of clipping (A11Y-A03).
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                h.priceText()?.let { Text(it, fontWeight = FontWeight.Medium) }
                h.bedrooms?.let { Text(stringResource(R.string.common_bhk, it)) }
                Text(stringResource(R.string.common_score_value, h.score.scoreText()))
                Text(stringResource(R.string.common_visits_count, visits))
            }
        }
    }
}
