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

package app.doorprints.drive.store

import app.doorprints.crypto.KeysWatermark
import app.doorprints.crypto.KeysWatermarkStore
import app.doorprints.drive.backup.ControlWatermark
import app.doorprints.drive.backup.ControlWatermarkStore
import app.doorprints.drive.backup.FolderTrustStores
import kotlinx.serialization.Serializable

/**
 * The two watermarks of each Drive folder, as small files in [dir] (docs/15 §9.9: the pin of `keys.json`, the mark of
 * `doorprints.json`); one file per folder and watermark. Web twin: the `keys-watermark` store of `drive-db.ts`.
 * Pass a folder the OS does not copy to another phone (`Context.noBackupFilesDir`): a pin is per device.
 *
 * A watermark file that is damaged reads as **no pin**: `KeysGuard` then opens nothing from Drive (`NOT_PINNED`) until
 * the person's own proof (QR enrolment or the recovery key) pins again, so a lost pin fails closed.
 */
class FileFolderTrustStores(private val dir: String) : FolderTrustStores {
    /** The `keys.json` pin of the folder [rootId], in a file of its own. */
    override fun keys(rootId: String): KeysWatermarkStore = FileKeysWatermarkStore(stateFileAt("$dir/" + folderFileName("keys-watermark", rootId)))
    /** The `doorprints.json` mark of the folder [rootId], in a file of its own. */
    override fun control(rootId: String): ControlWatermarkStore = FileControlWatermarkStore(stateFileAt("$dir/" + folderFileName("control-watermark", rootId)))
}

/** One folder's [KeysWatermark] in [file]; [compareAndSet] is atomic across every instance on the same file in this process. */
class FileKeysWatermarkStore(file: StateFile) : KeysWatermarkStore {
    private val state = file
    private val lock = file.lock

    override fun load(): KeysWatermark? = readState(state, KeysDto.serializer(), { it.v }) {
        KeysWatermark(it.epoch, it.revision, it.keyId.hexToByteArray(), it.bodyHash.hexToByteArray())
    }

    /**
      * Writes [next] only if the stored pin still equals [expected]; the check and the write happen under the file's
      * lock.
     */
    override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark): Boolean = lock.withLock {
        if (load() != expected) {
            false
        } else {
            writeState(state, KeysDto.serializer(), KeysDto(STATE_VERSION, next.epoch, next.revision, next.keyId.toHexString(), next.bodyHash.toHexString()))
            true
        }
    }

    @Serializable
    private class KeysDto(val v: Int, val epoch: Int, val revision: Long, val keyId: String, val bodyHash: String)
}

/** One folder's [ControlWatermark] in [file]; same rules as [FileKeysWatermarkStore]. */
class FileControlWatermarkStore(file: StateFile) : ControlWatermarkStore {
    private val state = file
    private val lock = file.lock

    override fun load(): ControlWatermark? = readState(state, ControlDto.serializer(), { it.v }) {
        ControlWatermark(it.revision, it.bodyHash.hexToByteArray(), it.backupsDeletedAt)
    }

    /**
      * Writes [next] only if the stored mark still equals [expected]; the check and the write happen under the file's
      * lock.
     */
    override fun compareAndSet(expected: ControlWatermark?, next: ControlWatermark): Boolean = lock.withLock {
        if (load() != expected) {
            false
        } else {
            writeState(state, ControlDto.serializer(), ControlDto(STATE_VERSION, next.revision, next.bodyHash.toHexString(), next.backupsDeletedAt))
            true
        }
    }

    @Serializable
    private class ControlDto(val v: Int, val revision: Long, val bodyHash: String, val backupsDeletedAt: Long? = null)
}
