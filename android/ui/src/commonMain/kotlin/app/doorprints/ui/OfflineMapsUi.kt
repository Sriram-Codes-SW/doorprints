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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.doorprints.ui.res.Res
import app.doorprints.ui.res.common_cancel
import app.doorprints.ui.res.common_close
import app.doorprints.ui.res.common_delete
import app.doorprints.ui.res.common_save
import app.doorprints.ui.res.map_save_area
import app.doorprints.ui.res.offline_area_default_name
import app.doorprints.ui.res.offline_area_name
import app.doorprints.ui.res.offline_delete
import app.doorprints.ui.res.offline_delete_confirm
import app.doorprints.ui.res.offline_dialog_text
import app.doorprints.ui.res.offline_metered
import app.doorprints.ui.res.offline_size_mb
import app.doorprints.ui.res.offline_state_failed
import app.doorprints.ui.res.offline_state_saving
import app.doorprints.ui.res.offline_too_large
import app.doorprints.ui.res.settings_offline_empty
import app.doorprints.ui.res.settings_offline_hint
import app.doorprints.ui.res.settings_offline_maps
import org.jetbrains.compose.resources.stringResource

/** Material's "download" glyph, built from its path: the core icon set has none. */
internal val DownloadIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes("M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/**
 * *Save this area for offline* (docs/11 5.20): the map's visible [bounds], the size to download ([OfflineTiles]), a
 * name to give it (the area's locality from the geocoder when it answers in time, else "My area") and, on mobile
 * data, a word about Wi-Fi. An area over [OfflineTiles.MAX_TILES] is refused with the numbers: zoom in. [onSave]
 * starts the download; the caller says so in its snackbar.
 */
@Composable
fun SaveAreaDialog(
    bounds: GeoBounds,
    metered: Boolean?,
    suggestName: suspend () -> String?,
    onSave: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tiles = remember(bounds) { OfflineTiles.count(bounds) }
    val defaultName = stringResource(Res.string.offline_area_default_name)
    var name by rememberSaveable { mutableStateOf("") }
    var suggested by rememberSaveable { mutableStateOf(false) }
    // The geocoder's name once, unless the person typed first.
    LaunchedEffect(bounds) {
        if (suggested) return@LaunchedEffect
        val place = suggestName()
        suggested = true
        if (name.isBlank() && !place.isNullOrBlank()) name = place
    }
    val tooLarge = tiles > OfflineTiles.MAX_TILES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.map_save_area)) },
        text = { SaveAreaDialogContent(bounds, metered, name, onName = { name = it }) },
        confirmButton = {
            if (tooLarge) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_close)) }
            } else {
                TextButton(onClick = { onSave(name.trim().ifBlank { defaultName }) }) {
                    Text(stringResource(Res.string.common_save))
                }
            }
        },
        dismissButton = {
            if (!tooLarge) TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}

/** The dialog's body: the refusal, or the estimate, the mobile-data note and the name field. */
@Composable
fun SaveAreaDialogContent(bounds: GeoBounds, metered: Boolean?, name: String, onName: (String) -> Unit) {
    val tiles = remember(bounds) { OfflineTiles.count(bounds) }
    Column {
        if (tiles > OfflineTiles.MAX_TILES) {
            Text(stringResource(Res.string.offline_too_large, tiles.toInt(), OfflineTiles.MAX_TILES))
        } else {
            Text(stringResource(Res.string.offline_dialog_text, megabytesText(OfflineTiles.estimateBytes(tiles))))
            if (metered == true) {
                WarnNote(stringResource(Res.string.offline_metered), Modifier.padding(top = 8.dp))
            }
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(Res.string.offline_area_name)) },
                placeholder = { Text(stringResource(Res.string.offline_area_default_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
    }
}

/**
 * Settings > Offline maps (docs/11 5.20): what saved areas do and where they come from, then each area with its size
 * or progress and *Delete* (asked first: the map needs the network there again). Empty: one line, the Map is where
 * an area is saved.
 */
@Composable
fun OfflineMapsSection(offline: OfflineMapsServices) {
    val areas by offline.areas.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<OfflineArea?>(null) }
    SectionHeading(stringResource(Res.string.settings_offline_maps))
    Text(stringResource(Res.string.settings_offline_hint), style = MaterialTheme.typography.bodySmall)
    if (areas.isEmpty()) {
        Text(stringResource(Res.string.settings_offline_empty), style = MaterialTheme.typography.bodyMedium)
    }
    areas.forEach { area ->
        val status = when (area.state) {
            OfflineAreaState.READY -> stringResource(Res.string.offline_size_mb, megabytesText(area.bytes))
            OfflineAreaState.SAVING -> stringResource(Res.string.offline_state_saving, megabytesText(area.bytes))
            OfflineAreaState.FAILED -> stringResource(Res.string.offline_state_failed)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(area.name, style = MaterialTheme.typography.bodyLarge)
                // The progress line changes as the download runs; polite, so a screen reader hears it without
                // interruption (WCAG 4.1.3).
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            IconButton(onClick = { deleting = area }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(Res.string.offline_delete, area.name))
            }
        }
    }
    deleting?.let { area ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(Res.string.offline_delete_confirm, area.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    offline.delete(area.id)
                }) { Text(stringResource(Res.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(Res.string.common_cancel)) }
            },
        )
    }
}
