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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.shared.model.SpaceCard
import app.doorprints.shared.model.KindTally
import app.doorprints.shared.model.spaceCardsOf
import app.doorprints.ui.res.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The chooser (kinds PR C, S4b-BL-274; docs/03 §18.12): the first screen while two or more kinds are on, *What are you
 * looking for?* One card per kind that is on, the default first and marked *Default*, each with *Saved: N* and *Last
 * change: <date>* (or *Nothing saved yet*), which together are its accessible name (the label in the name). A kind that is
 * turned off but still has entries is listed below, muted (*Hidden, N entries*), and still opens: it is never hidden
 * silently, and nothing is deleted. One hint (*Tap a card to switch*) shows until it is dismissed. The heading is a
 * heading for TalkBack; there is no bottom bar here. [onOpen] opens a kind's space, [onOpenSettings] goes to
 * *What you track*. The twin of the website's `pages/chooser`.
 */
@Composable
fun ChooserScreen(onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val repo = LocalAppServices.current.repository
    val space = LocalSpace.current
    val setting = space.setting
    val scope = rememberCoroutineScope()
    // One count and newest change per kind, read from the store through the `kind` index (S4b-BL-303), so it follows a sync or an
    // import while the chooser is open without loading every candidate.
    val tallies: List<KindTally>? by remember(repo) { repo.kindTallies }.collectAsStateWithLifecycle(initialValue = null)
    val hint by remember(repo) { repo.settings.chooserHintShown() }.collectAsStateWithLifecycle(initialValue = false)
    val cards = remember(tallies, setting) {
        spaceCardsOf(tallies.orEmpty(), setting.enabled, setting.default, setting.available)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(16.dp).widthIn(max = ContentMaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(Res.string.chooser_heading), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        if (hint) {
            // Once, when a second kind is first shown: how the chooser works. No second tour.
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.chooser_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { scope.launch { repo.settings.dismissChooserHint() } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { ButtonLabel(stringResource(Res.string.chooser_hint_dismiss)) }
                }
            }
        }
        cards.shown.forEach { card -> SpaceCardRow(card, hidden = false, onClick = { onOpen(card.kind) }) }
        if (cards.hidden.isNotEmpty()) {
            Text(
                stringResource(Res.string.chooser_hidden_heading), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp).semantics { heading() },
            )
            cards.hidden.forEach { card -> SpaceCardRow(card, hidden = true, onClick = { onOpen(card.kind) }) }
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.chooser_manage)) }
    }
}

/**
 * One card: the kind's plural noun, *Default* when it is, then what is saved and when it last changed. The whole card is
 * one button whose name is its visible words; a hidden kind is muted and says how many entries it keeps.
 */
@Composable
private fun SpaceCardRow(card: SpaceCard, hidden: Boolean, onClick: () -> Unit) {
    val words = SpaceWords.of(card.kind)
    val muted = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    OutlinedCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(words.many), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.weight(1f, fill = false))
                if (card.isDefault && !hidden) {
                    Text(stringResource(Res.string.chooser_default), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            val meta = MaterialTheme.typography.bodyMedium
            val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                hidden -> Text(pluralStringResource(Res.plurals.chooser_hidden, card.count, card.count), style = meta, color = metaColor)
                card.count > 0 -> {
                    Text(stringResource(Res.string.chooser_count, card.count), style = meta, color = metaColor)
                    val language = uiLanguage()
                    card.updated?.let { Text(stringResource(Res.string.chooser_updated, Formats.date(it, language)), style = meta, color = metaColor) }
                }
                else -> Text(stringResource(Res.string.chooser_none), style = meta, color = metaColor)
            }
        }
    }
}
