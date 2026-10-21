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

package app.doorprints

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import app.doorprints.shared.model.UserKindFiles
import app.doorprints.ui.KindFilePick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * *Import a kind file* on Android (docs/03 ADR-39): the system's document picker for one file (JSON, or any file a file manager
 * does not type), read through the content resolver on the IO dispatcher, never more than [UserKindFiles.MAX_BYTES] and one
 * byte (a larger file is [KindFilePick.TooBig] without reading the rest). No permission is asked or kept: the picker's grant
 * lasts for the one read. The text is checked by `:shared`'s reader, not here.
 */
internal object KindFilePicker {
    /** The types the picker offers: JSON as it is typed, and the types a file manager gives a `.json` file it does not know. */
    private val TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

    @Composable
    fun remember(onPicked: (KindFilePick) -> Unit): () -> Unit {
        val resolver = LocalContext.current.contentResolver
        val latest by rememberUpdatedState(onPicked)
        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) scope.launch { latest(withContext(Dispatchers.IO) { read(resolver, uri) }) }
        }
        return { launcher.launch(TYPES) }
    }

    /** At most [UserKindFiles.MAX_BYTES] of [uri] as UTF-8 text, or why not. */
    fun read(resolver: ContentResolver, uri: Uri): KindFilePick = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            // A plain loop: `readNBytes` is API 33, and this reads one byte past the limit at most.
            val buffer = ByteArray(UserKindFiles.MAX_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val n = input.read(buffer, size, buffer.size - size)
                if (n < 0) break
                size += n
            }
            if (size > UserKindFiles.MAX_BYTES) KindFilePick.TooBig else KindFilePick.Text(buffer.decodeToString(0, size))
        } ?: KindFilePick.Failed
    }.getOrDefault(KindFilePick.Failed)
}
