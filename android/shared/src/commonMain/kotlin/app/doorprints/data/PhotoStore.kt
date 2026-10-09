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

package app.doorprints.data

import app.doorprints.shared.export.BackupValidation
import app.doorprints.shared.model.PhotoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The photos on this phone: the folder their files live in and the photo rows' own local edits (S4b-BL-168, slice 1).
 * It is separate from [CommonRepository] because the sync pull and push, both import modes, the import's undo, the
 * exporters and the screens' thumbnails all reach a photo's file, and each needs the same rules (the file is found
 * from the row's id, the folder is created when missing, an imported id may not leave the folder); the loops over the
 * rows that call it stay with the sync and the import they belong to.
 *
 * [photoDir] is the folder the files live in (Android: `filesDir/photos`). A row's `path` is written as the file's
 * full path but never read to reach the file: the file is found from the row's id ([fileOf]), because an iOS app's
 * container folder moves when the app is updated (S4b-BL-52). Files are read and written with kotlinx-io's
 * [SystemFileSystem].
 */
internal class PhotoStore(
    private val db: AppDatabase,
    private val photoDir: String,
    private val now: () -> Long,
    private val syncSoon: () -> Unit,
    private val fs: FileSystem = SystemFileSystem,
) {
    fun observeForHouse(houseId: String) = db.photos().observeForHouse(houseId)

    /**
     * The photo folder, created when missing. Two calls on the IO pool can race to create it on a first run, and
     * kotlinx-io's native `createDirectories` then fails with "File exists" where `java.io.File.mkdirs` did not; a
     * folder that is there afterwards is all that counts.
     */
    fun dirPath(): Path = Path(photoDir).also { dir ->
        try {
            fs.createDirectories(dir)
        } catch (e: IOException) {
            if (fs.metadataOrNull(dir)?.isDirectory != true) throw e
        }
    }

    /**
     * The file photo [id]'s bytes live in, built from the photo folder and the id; creates nothing. Every read of a
     * photo row's file goes through this rather than the row's stored `path` (S4b-BL-52): on iOS the app's container
     * folder changes when the app is updated, so a stored full path goes stale, while the id does not. On Android the
     * folder does not move, so this is the same file the row names.
     */
    fun fileOf(id: String): Path = fileIn(photoDir, id)

    /** The path a photo row's bytes live in, for the exporter and the importer; the folder is created when missing. */
    fun pathFor(id: String): Path {
        dirPath()
        return fileOf(id)
    }

    /**
     * The photo path for an id that came out of a backup, or null when it would not land in the photo
     * directory.
     *
     * `BackupValidation.checkData` already refuses a backup whose ids are not `[A-Za-z0-9_-]{1,64}`, so this
     * never fires on a file that got this far; it is here because [pathFor] interpolates its argument straight
     * into a path, and a second, independent check costs one path resolution per photo. The id check alone keeps
     * the name inside the folder (no separator, no `..`); a file already at that name is resolved as well, so a link
     * to an existing file elsewhere is refused. A dangling link is not caught (`exists` follows links), as the old
     * `canonicalPath` check did not catch it either; the folder is the app's private one. A file that vanishes between
     * the two calls makes the photo count as skipped rather than stopping the import. Defence in depth for the same
     * bug class as the zip-slip guard on entry names.
     */
    fun importedPathFor(id: String): Path? {
        if (!BackupValidation.isValidId(id)) return null
        val path = pathFor(id)
        if (!fs.exists(path)) return path
        return runCatching {
            if (fs.resolve(path).parent == fs.resolve(dirPath())) path else null
        }.getOrNull()
    }

    fun write(path: Path, bytes: ByteArray) = fs.sink(path).buffered().use { it.write(bytes) }

    /** The size of the file at [path], or null when it is not there. */
    fun sizeOf(path: Path): Long? = fs.metadataOrNull(path)?.size

    /** The file at [path], streamed (an upload reads it without holding it in memory). */
    fun open(path: Path): Source = fs.source(path).buffered()

    /** Deletes a photo file if it is there; like `java.io.File.delete`, never throws (a failure leaves the file). */
    fun deleteFile(path: Path) {
        runCatching { fs.delete(path, mustExist = false) }
    }

    /**
     * Deletes the local file now. A photo the server already has is queued (deleted = 1) and the delete is sent on
     * the next sync, even if the phone is offline now (threat model F-15).
     */
    suspend fun delete(photo: PhotoEntity): Unit = withContext(Dispatchers.IO) {
        deleteFile(fileOf(photo.id))
        if (photo.uploaded) {
            db.photos().markDeleted(photo.id)
            syncSoon()
        } else {
            db.photos().delete(photo.id)
        }
    }

    /** Saves a photo's room, tags and caption (slice 5); true when something changed. */
    suspend fun saveMeta(photoId: String, meta: PhotoMeta): Boolean {
        val photo = db.photos().get(photoId)?.takeUnless { it.deleted } ?: return false
        val clean = PhotoMeta.coerced(meta.roomId, meta.tags, meta.caption, null)
        if (clean.sameValues(photo.meta)) return false
        // Past the stored time, whatever the clock says, so the edit wins against the one it replaces.
        val stamp = maxOf(now(), photo.metaUpdatedAt + 1)
        db.photos().upsert(photo.withMeta(clean.copy(metaUpdatedAt = stamp), dirty = true))
        syncSoon()
        return true
    }

    /**
     * After a copy import that did not finish: deletes each photo file it wrote ([written], file to photo id) whose
     * row is not in the database, and keeps the others. The transaction is all or nothing, so after a rollback no row
     * is there and every file goes, as before; but a cancellation of the caller can also land after the commit (the
     * rows are written, then the resumption throws), and deleting then would leave committed rows pointing at missing
     * files (code review of PR #23). A row that cannot be read counts as missing, the old rule. `NonCancellable`,
     * because the caller is usually being cancelled right now (Room 2.8's reads do not check that today, but nothing
     * promises it); only these reads and deletes are, never the transaction itself.
     */
    suspend fun discardUncommitted(written: Map<Path, String>) = withContext(NonCancellable) {
        for ((file, id) in written) {
            val committed = runCatching { db.photos().get(id) != null }.getOrDefault(false)
            if (!committed) deleteFile(file)
        }
    }

    companion object {
        /** Photo [id]'s file in the photo folder [photoDir]: `<photoDir>/<id>.jpg` ([fileOf]; `PhotoFileTest`). */
        fun fileIn(photoDir: String, id: String): Path = Path(photoDir, "$id.jpg")
    }
}
