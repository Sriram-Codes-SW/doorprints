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

import app.doorprints.shared.model.KindCapabilities
import app.doorprints.shared.model.Candidates
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.doorprints.ui.res.*
import app.doorprints.shared.api.HouseDraftDto
import app.doorprints.shared.model.BuiltInKind
import app.doorprints.shared.model.KindFields
import app.doorprints.shared.model.KindSettingRules
import app.doorprints.shared.ai.ListingCut
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The field names a listing fill reports ("Filled in: price and contact name."). */
internal val ListingField.nameRes: StringResource
    get() = when (this) {
        ListingField.NAME -> Res.string.paste_field_name
        ListingField.ADDRESS -> Res.string.paste_field_address
        ListingField.STREET -> Res.string.paste_field_street
        ListingField.LOCALITY -> Res.string.paste_field_locality
        ListingField.PRICE -> Res.string.paste_field_price
        ListingField.BHK -> Res.string.paste_field_bhk
        ListingField.CONTACT -> Res.string.paste_field_contact
        ListingField.PHONE -> Res.string.paste_field_phone
        ListingField.LISTING -> Res.string.paste_field_listing
        ListingField.NOTES -> Res.string.paste_field_notes
        ListingField.AREA -> Res.string.paste_field_area
    }

/** The kind the paste dialog asks the extract for: a kind that fills (a school), not the house, whose call is the house's own. */
internal fun pasteKind(kind: BuiltInKind): String? = kind.id.takeIf { !KindCapabilities.of(it).baseline && kind.extracts }

/**
 * "Filled in: price and contact name. Check them, then save." plus what was kept and what the AI flagged. A kind's typed
 * extras the fill wrote or kept ([ListingMerge.extrasFilled], [ListingMerge.extrasKept], S4b-BL-273) are named after the
 * fields, by the kind's own labels ([fields]); a house's fill has none, so its line is as it was.
 */
suspend fun pasteResultText(merge: ListingMerge, warnings: List<String>, fields: KindFields = BuiltInKind.HOUSE.fields): String {
    suspend fun extras(ids: List<String>) = fields.others().filter { it.id in ids }.map { getString(kindStringResource(fields.label(it))) }
    suspend fun names(f: List<ListingField>, ids: List<String>) = joinedListText(f.map { getString(it.nameRes) } + extras(ids))
    val parts = mutableListOf<String>()
    parts += if (merge.filled.isEmpty() && merge.extrasFilled.isEmpty()) {
        getString(Res.string.house_paste_nothing)
    } else {
        getString(Res.string.house_paste_filled, names(merge.filled, merge.extrasFilled))
    }
    if (merge.kept.isNotEmpty() || merge.extrasKept.isNotEmpty()) parts += getString(Res.string.house_paste_kept, names(merge.kept, merge.extrasKept))
    if (warnings.isNotEmpty()) parts += getString(Res.string.house_paste_check, warnings.joinToString("; "))
    return parts.joinToString(" ")
}

/**
 * "Fill in from listing text". While the AI call runs, *Cancel* (and Back) stay available and cancel it (round 21).
 * A tap outside closes it only while nothing has been pasted, so a stray touch never throws a long ad away, and the
 * text area scrolls, so *Fill in* stays reachable with the keyboard up at 200 % (whole-app audit). The busy line and
 * the error are in a live region that is there before either appears.
 */
@Composable
fun PasteListingDialog(onDismiss: () -> Unit, onDraft: (HouseDraftDto, List<String>) -> Unit, kind: String? = null) {
    val repo = LocalAppServices.current.repository
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val errorText = aiErrorText()
    val cancel = {
        job?.cancel()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = cancel,
        properties = DialogProperties(dismissOnClickOutside = text.isBlank() && !busy),
        title = { Text(stringResource(Res.string.house_paste_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.house_paste_hint), style = MaterialTheme.typography.bodySmall)
                PasteListingText(text, { text = it })
                VoiceMicButton(text, { text = it }, Candidates.kindOf(kind))
                AiDisclosure(style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, pages = PageWords.of(Candidates.kindOf(kind)))
                LiveMessage(assertive = error != null && !busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (busy) {
                            ProgressBar()
                            Text(stringResource(Res.string.house_paste_busy))
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                job = scope.launch {
                    busy = true
                    error = null
                    // Extract reads the first MAX_INPUT_CHARS characters; PasteListingText says how many are left out (S4b-BL-182).
                    val result = runCatching { ListingCut.of(text).text.let { if (kind != null) repo.extractListing(it, kind) else repo.extractListing(it) } }
                    // Cancelled: the dialog is closing, so neither a draft nor an error.
                    ensureActive()
                    result.onSuccess { onDraft(it, it.warnings + priceWarning(text, it)) }.onFailure { error = errorText(it) }
                    busy = false
                }
            }) { Text(stringResource(Res.string.house_paste_go)) }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
