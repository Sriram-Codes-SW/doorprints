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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.doorprints.shared.model.DevKinds
import app.doorprints.shared.model.KindSetting
import app.doorprints.shared.model.UserKindFault
import app.doorprints.shared.model.UserKindFile
import app.doorprints.shared.model.UserKindFiles
import app.doorprints.shared.model.UserKindRead
import app.doorprints.shared.model.UserKinds
import app.doorprints.ui.res.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** What the last import or removal of a kind file did, said once under the import (a polite live region). */
private sealed interface KindNotice {
    data class Added(val many: String) : KindNotice
    data class Replaced(val many: String) : KindNotice
    data object Full : KindNotice
}

/**
 * Settings > What you track: *Kinds you add (experimental)* (docs/03 ADR-39), the twin of the website's part of
 * `kinds-settings.ts`. The switch is off by default; while it is on, the kinds a person added are offered in the rows below,
 * and *Import a kind file* reads one file (at most 64 KB; the platform's picker, none where there is none), checks it with the
 * same reader as the website and the server ([UserKindFiles]) and shows either the first fault in plain words and where it is,
 * or a preview of what the kind has. Nothing is stored until the person chooses *Add this kind* (or *Replace the kind*, for a
 * kind of the same id); a new kind is not turned on, the person does that in its row. Off, nothing is deleted.
 */
@Composable
internal fun UserKindsSection(current: KindSetting, change: ((KindSetting) -> KindSetting) -> Unit) {
    val services = LocalAppServices.current
    val repo = services.repository
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<UserKindFile?>(null) }
    var refusal by remember { mutableStateOf<UserKindFault?>(null) }
    var failed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<KindNotice?>(null) }
    fun clear() {
        preview = null
        refusal = null
        failed = false
        notice = null
    }
    SwitchRow(
        text = stringResource(Res.string.userkinds_switch), hint = stringResource(Res.string.userkinds_switch_hint),
        checked = current.customOn, horizontalPadding = 0.dp,
    ) { on ->
        if (!on) clear()
        change { it.withCustom(on) }
    }
    if (!current.customOn) return
    val picker = services.settingsScreen.rememberKindFilePicker { pick ->
        clear()
        when (pick) {
            KindFilePick.TooBig -> refusal = UserKindFault("tooBig", "$")
            KindFilePick.Failed -> failed = true
            is KindFilePick.Text -> scope.launch {
                val reserved = DevKinds.builtIn(true).map { it.id }
                when (val read = withContext(Dispatchers.Default) { UserKindFiles.read(pick.text, reserved) }) {
                    is UserKindRead.Refused -> refusal = read.fault
                    is UserKindRead.Ok -> preview = read.file
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.userkinds_heading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(Res.string.userkinds_import_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (picker != null) {
            OutlinedButton(onClick = { picker() }, modifier = Modifier.heightIn(min = 48.dp)) {
                ButtonLabel(stringResource(Res.string.userkinds_import))
            }
        }
        LiveMessage(assertive = true) {
            val fault = refusal
            if (fault != null) KindRefusal(fault)
            else if (failed) Text(stringResource(Res.string.userkinds_read_failed), color = MaterialTheme.colorScheme.error)
        }
        preview?.let { file ->
            UserKindPreviewCard(
                file, replaces = UserKinds.file(file.id)?.many,
                onAdd = {
                    preview = null
                    scope.launch {
                        notice = when (repo.settings.addUserKind(file)) {
                            UserKinds.Added.ADDED -> KindNotice.Added(file.many)
                            UserKinds.Added.REPLACED -> KindNotice.Replaced(file.many)
                            UserKinds.Added.FULL -> KindNotice.Full
                        }
                    }
                },
                onCancel = { preview = null },
            )
        }
        LiveMessage {
            when (val n = notice) {
                is KindNotice.Added -> Text(stringResource(Res.string.userkinds_added, n.many))
                is KindNotice.Replaced -> Text(stringResource(Res.string.userkinds_replaced, n.many))
                KindNotice.Full -> Text(stringResource(Res.string.userkinds_full, UserKindFiles.MAX_KINDS), color = MaterialTheme.colorScheme.error)
                null -> Unit
            }
        }
    }
}

/** The plain words of a refusal's [code] (`user-kind-vectors.json` `codes`); an unknown code reads as *not a kind file*. */
internal fun refusalReason(code: String): StringResource = when (code) {
    "tooBig" -> Res.string.userkinds_err_tooBig
    "duplicateKey" -> Res.string.userkinds_err_duplicateKey
    "tooDeep" -> Res.string.userkinds_err_tooDeep
    "tooMany" -> Res.string.userkinds_err_tooMany
    "tooLong" -> Res.string.userkinds_err_tooLong
    "prototypeKey" -> Res.string.userkinds_err_prototypeKey
    "mapKey" -> Res.string.userkinds_err_mapKey
    "unknownKey" -> Res.string.userkinds_err_unknownKey
    "notAllowed" -> Res.string.userkinds_err_notAllowed
    "required" -> Res.string.userkinds_err_required
    "type" -> Res.string.userkinds_err_type
    "format" -> Res.string.userkinds_err_format
    "id" -> Res.string.userkinds_err_id
    "reserved" -> Res.string.userkinds_err_reserved
    "textLength" -> Res.string.userkinds_err_textLength
    "textChars" -> Res.string.userkinds_err_textChars
    "range" -> Res.string.userkinds_err_range
    "limit" -> Res.string.userkinds_err_limit
    "repeated" -> Res.string.userkinds_err_repeated
    "value" -> Res.string.userkinds_err_value
    "ref" -> Res.string.userkinds_err_ref
    "capabilities" -> Res.string.userkinds_err_capabilities
    else -> Res.string.userkinds_err_notJson
}

/** *This file was not added*, the reason in plain words, and where in the file (not for the file as a whole). */
@Composable
internal fun KindRefusal(fault: UserKindFault) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.userkinds_refused, stringResource(refusalReason(fault.code))), color = MaterialTheme.colorScheme.error)
        if (fault.path != "$") {
            Text(stringResource(Res.string.userkinds_where, fault.path), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The preview of a checked kind file before it is added: its plural, its own fields and checklist (the person's words, as text),
 * and, when the phone has a kind of this id, that adding it replaces that kind's fields and words and keeps its entries. Focus
 * moves to its title when it appears, so TalkBack reads it first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UserKindPreviewCard(file: UserKindFile, replaces: String?, onAdd: () -> Unit, onCancel: () -> Unit) {
    val title = remember { FocusRequester() }
    LaunchedEffect(file.id) { runCatching { title.requestFocus() } }
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(Res.string.userkinds_preview_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.focusRequester(title).focusable().semantics { heading() },
            )
            Text(stringResource(Res.string.userkinds_preview_name, file.many))
            val fields = file.extras.map { it.label }
            Text(if (fields.isEmpty()) stringResource(Res.string.userkinds_preview_no_fields) else stringResource(Res.string.userkinds_preview_fields, fields.joinToString(", ")))
            val checks = file.checklist.map { it.second }
            if (checks.isNotEmpty()) Text(stringResource(Res.string.userkinds_preview_checklist, checks.joinToString(", ")))
            if (replaces != null) {
                Text(stringResource(Res.string.userkinds_preview_replaces, replaces), color = MaterialTheme.colorScheme.tertiary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(if (replaces != null) Res.string.userkinds_replace else Res.string.userkinds_add))
                }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { ButtonLabel(stringResource(Res.string.common_cancel)) }
            }
        }
    }
}

/** A kind's row in What you track, for a kind the person added: the *Added by you* mark and *Remove kind*. */
@Composable
internal fun UserKindRowActions(name: String, onRemove: () -> Unit) {
    Text(stringResource(Res.string.userkinds_yours), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val aria = stringResource(Res.string.userkinds_remove_aria, name)
    OutlinedButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = aria }) {
        ButtonLabel(stringResource(Res.string.userkinds_remove))
    }
}

/**
 * *Remove kind?* (docs/03 ADR-39): it takes the kind's file away and deletes no entry (they move to the default space until the
 * kind is imported again; *Delete all in this space* is how to delete them). First focus on *Cancel*, *Save a copy first*, then
 * the danger button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoveUserKindDialog(name: String, count: Int, onCancel: () -> Unit, onSaveCopy: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    BasicAlertDialog(onDismissRequest = onCancel) {
        RemoveUserKindCard(name, count, onCancel, onSaveCopy, onConfirm, cancelFocus)
    }
}

/** The dialog's card, apart from its window, so a test can draw it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RemoveUserKindCard(
    name: String,
    count: Int,
    onCancel: () -> Unit,
    onSaveCopy: () -> Unit,
    onConfirm: () -> Unit,
    cancelFocus: FocusRequester = remember { FocusRequester() },
) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.userkinds_remove), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Text(stringResource(Res.string.userkinds_remove_confirm, name, count), style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).focusRequester(cancelFocus)) {
                    ButtonLabel(stringResource(Res.string.common_cancel))
                }
                TextButton(onClick = onSaveCopy, modifier = Modifier.heightIn(min = 48.dp)) {
                    ButtonLabel(stringResource(Res.string.drive_delete_save_copy_first))
                }
                DangerButton(stringResource(Res.string.userkinds_remove_do), onConfirm)
            }
        }
    }
}
