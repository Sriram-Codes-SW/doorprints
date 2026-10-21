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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.doorprints.shared.model.UserKindFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

/**
 * *Import a kind file* on the iPhone (docs/03 ADR-39): the Files app's picker ([IosSheets.pickKindFile]) gives a copy of one
 * JSON file in the app's temporary folder; its size is read first (over [UserKindFiles.MAX_BYTES] it is refused unread), then
 * its bytes as UTF-8 text, and the copy is removed either way. The text is checked by `:shared`'s reader, not here.
 */
internal object IosKindFile {
    @Composable
    fun rememberPicker(onPicked: (KindFilePick) -> Unit): () -> Unit {
        val latest by rememberUpdatedState(onPicked)
        return remember {
            {
                IosSheets.pickKindFile { url -> if (url != null) latest(read(url)) }
                Unit
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    fun read(url: NSURL): KindFilePick {
        val path = url.path ?: return KindFilePick.Failed
        val files = NSFileManager.defaultManager
        return try {
            val attributes = files.attributesOfItemAtPath(path, error = null)
            val size = (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
            when {
                size == null -> KindFilePick.Failed
                size > UserKindFiles.MAX_BYTES -> KindFilePick.TooBig
                else -> {
                    val data = NSData.dataWithContentsOfFile(path)
                    when {
                        data == null -> KindFilePick.Failed
                        data.length.toLong() > UserKindFiles.MAX_BYTES -> KindFilePick.TooBig
                        else -> KindFilePick.Text(data.bytesOf().decodeToString())
                    }
                }
            }
        } finally {
            files.removeItemAtPath(path, error = null)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun NSData.bytesOf(): ByteArray {
        val size = length.toInt()
        val out = ByteArray(size)
        if (size > 0) out.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
        return out
    }
}
