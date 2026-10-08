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

package app.doorprints.drive.photo

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.Dpx
import app.doorprints.crypto.DpxException
import app.doorprints.crypto.DpxHeader
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.OpenedKeys
import app.doorprints.crypto.RevokedEpochRule
import app.doorprints.crypto.sha256Of
import app.doorprints.drive.DriveClient
import app.doorprints.drive.DriveException
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout
import app.doorprints.drive.DriveQuery
import app.doorprints.drive.NewFile
import app.doorprints.drive.UploadSession
import app.doorprints.drive.UploadTarget
import app.doorprints.drive.downloadVerified
import app.doorprints.drive.ensureFolder
import app.doorprints.drive.listAll
import app.doorprints.drive.markComplete
import app.doorprints.drive.sync.FolderSession
import app.doorprints.drive.uploadResumable
import app.doorprints.shared.records.RecordRules
import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * Photos over Google Drive (S4b-BL-128; docs/15 §5.1, §9, §11), in common code over a signed-in [DriveClient] and an
 * opened [FolderSession]. Web twin: `drive-photos.ts`, the same names.
 *
 * **One photo, one `photo/1` file** (`dpx/1`, a fresh content key per file) in the `Photos` folder (role `photos`), named
 * `p-<random>.dpx`, `appProperties kind=photo`, `photoId`, `state`, `device`, `createdAt`: no house, no address, no date
 * of the photo. Written as `partial-p-<random>.dpx` (`state=partial`), checked against Drive's `sha256Checksum` of the
 * **ciphertext**, then renamed and marked `state=complete`. The photo's **plaintext** SHA-256 goes into the photo's row
 * of the sync file (with the Drive file id), and is what every download is held to (`CHECKSUM_REQUIRED`).
 *
 * **Upload** ([upload]): no pin, nothing (KeysException NOT_PINNED); a photo over the cap is skipped for good; the same
 * plaintext already in Drive (the remembered file, or one found by `photoId` that decrypts to this SHA-256) is not sent
 * again; otherwise it is encrypted, sent (multipart up to 5 MB, a resumable session above, kept in memory so a dropped
 * network resumes from the byte Drive has), checked and completed. Only this device's own superseded files of the same
 * photo go to the bin, after the new file is complete.
 *
 * **Download** ([download]): the row's file must still be a complete `kind=photo` file in the verified `Photos` folder,
 * within the size cap, with the bytes Drive's checksum names; it must authenticate under a folder key whose writer passes
 * [RevokedEpochRule] and decrypt to the row's SHA-256. Anything else is **skipped and reported** ([skipped]), never thrown
 * and never deleted; a network failure is thrown (the caller retries).
 */
class DrivePhotos(
    private val drive: DriveClient,
    private val p: CryptoProvider,
    private val session: FolderSession,
    private val store: PhotoStateStore,
    private val clock: () -> Long,
    private val config: PhotoConfig = PhotoConfig(),
) : PhotoRefs {
    private val dpx = Dpx(p)
    private var pending: Pending? = null
    private var keysAt: Long? = null
    private val skips = ArrayList<SkippedPhoto>()

    /** What was skipped since the last [drainSkipped]. */
    val skipped: List<SkippedPhoto> get() = skips.toList()

    /** Returns what was skipped since the last call and forgets it. */
    fun drainSkipped(): List<SkippedPhoto> = skips.toList().also { skips.clear() }

    /** An encrypted photo on its way: kept so a retry resumes the same session with the same bytes. */
    private class Pending(
        val photoId: String,
        val plainSha: String,
        val cipher: ByteArray,
        val cipherSha: String,
        val token: String,
        var session: UploadSession? = null,
        var uploaded: DriveFile? = null,
    )

    private class Reject(val reason: PhotoSkipReason, val detail: String? = null) : Exception(reason.name)

    private class NeedFreshKeys : Exception()

    /** The photo-to-file references this device knows. */
    override suspend fun refs(): Map<String, PhotoRef> = store.load().refs

    /**
      * Adds references read from other devices' authenticated rows. A known reference is replaced only when it was
      * marked bad
     * and the new one points at another file.
     */
    override suspend fun learn(found: Map<String, PhotoRef>) {
        if (found.isEmpty()) return
        val st = store.load()
        var refs = st.refs
        var bad = st.bad
        for ((id, r) in found) {
            val have = refs[id]
            if (have == null || (have.driveFileId != r.driveFileId && bad[id]?.fileId == have.driveFileId)) {
                refs = refs + (id to r)
                bad = bad - id
            }
        }
        if (refs != st.refs) store.save(st.copy(refs = refs, bad = bad))
    }

    // ---- Upload ------------------------------------------------------------------------------------------------------

    /** Uploads one photo (see the class). [size] is the length the caller knows; [open] gives the bytes, once per try. */
    suspend fun upload(photoId: String, size: Long, open: () -> Source): PhotoUploadResult {
        requirePinned()
        if (!RecordRules.isValidId(photoId)) return skip(photoId, PhotoSkipReason.BAD_ID)
        if (size > config.maxPlaintextBytes) return skip(photoId, PhotoSkipReason.TOO_LARGE)
        val plain = readCapped(open) ?: return skip(photoId, PhotoSkipReason.TOO_LARGE)
        if (plain.isEmpty()) return skip(photoId, PhotoSkipReason.EMPTY)
        val sha = Bytes.hex(p.sha256Of(plain))
        var st = store.load()
        val folder = drive.ensureFolder(DriveLayout.PHOTOS, session.rootId, create = true, knownId = st.photosFolderId)!!
        if (folder.id != st.photosFolderId) {
            st = st.copy(photosFolderId = folder.id)
            store.save(st)
        }
        val me = session.deviceId

        // 1. Not again: the file this device knows is intact and holds these very bytes.
        val known = st.refs[photoId]
        if (known != null && known.sha256 == sha && intact(known.driveFileId, folder.id)) return PhotoUploadResult.Unchanged(known)

        // 2. Dedupe by photoId: a file already in Drive for this photo (another device, or an earlier try that stopped before
        //    its row was written) is adopted only if it decrypts, under a folder key, to exactly these bytes.
        val listed = drive.listAll(DriveQuery(parentId = folder.id, appProperties = mapOf(DriveLayout.KIND to KIND_PHOTO, PROP_PHOTO_ID to photoId)))
        // A file this very service sent and could not complete yet is finished below without reading it back.
        val resuming = pending?.takeIf { it.photoId == photoId && it.plainSha == sha }
        val candidates = if (resuming?.uploaded != null) emptyList() else listed.filter { !it.trashed && !it.isFolder }
            .sortedWith(compareBy<DriveFile> { it.createdTime }.thenBy { it.id })
            .take(config.maxCandidates)
        for (f in candidates) {
            val authentic = try {
                readVerified(f, sha)
                true
            } catch (_: Reject) {
                false
            }
            if (!authentic) continue
            val done = if (f.appProperties[DriveLayout.STATE] == DriveLayout.STATE_COMPLETE) {
                f
            } else {
                val checksum = f.sha256Checksum ?: continue
                drive.markComplete(f.id, checksum, "p-${token()}.dpx", f)
            }
            return PhotoUploadResult.Adopted(remember(photoId, done.id, sha))
        }

        // 3. Encrypt (once per photo: a retry reuses the bytes and the session) and send.
        val pend = resuming ?: encrypt(photoId, plain, sha, keys()).also { pending = it }
        val partial = pend.uploaded ?: sendPartial(pend, folder.id).also { pend.uploaded = it }
        val done = try {
            drive.markComplete(partial.id, pend.cipherSha, "p-${pend.token}.dpx", partial)
        } catch (e: DriveException) {
            if (e.kind == DriveException.Kind.CORRUPT) {
                // What Drive holds is not what was sent: this device's own partial goes to the bin; nothing is trusted.
                pending = null
                tryTrash(partial.id)
            }
            throw e
        }
        val ref = remember(photoId, done.id, sha)
        pending = null
        // Only now the superseded files of this device for this photo (and its leftovers) go to the bin.
        for (f in listed) if (f.id != done.id && !f.trashed && f.appProperties[DriveLayout.DEVICE] == me) tryTrash(f.id)
        return PhotoUploadResult.Uploaded(ref)
    }

    private fun encrypt(photoId: String, plain: ByteArray, sha: String, keys: OpenedKeys): Pending {
        val folderKey = keys.currentFolderKey()
        val (cipher, written) = try {
            dpx.encryptBytes(folderKey, keys.epoch, session.deviceKid, Dpx.PHOTO, plain)
        } finally {
            folderKey.fill(0)
        }
        check(Bytes.hex(written.plaintextSha256) == sha) { "plaintext hash" }
        return Pending(photoId, sha, cipher, Bytes.hex(written.ciphertextSha256), token())
    }

    private suspend fun sendPartial(pend: Pending, folderId: String): DriveFile {
        val target = UploadTarget.New(
            NewFile(
                name = "partial-p-${pend.token}.dpx",
                mimeType = "application/octet-stream",
                parents = listOf(folderId),
                appProperties = mapOf(
                    DriveLayout.KIND to KIND_PHOTO,
                    PROP_PHOTO_ID to pend.photoId,
                    DriveLayout.DEVICE to session.deviceId,
                    DriveLayout.STATE to DriveLayout.STATE_PARTIAL,
                    DriveLayout.CREATED_AT to clock().toString(),
                ),
            ),
        )
        val cipher = pend.cipher
        if (cipher.size <= DriveClient.MULTIPART_LIMIT) return drive.upload(target, cipher)
        return drive.uploadResumable(
            target, cipher.size.toLong(),
            { offset, length -> cipher.copyOfRange(offset.toInt(), offset.toInt() + length) },
            config.chunkSize,
            session = pend.session,
            onSession = { pend.session = it },
        )
    }

    private suspend fun remember(photoId: String, fileId: String, sha: String): PhotoRef {
        val ref = PhotoRef(fileId, sha)
        val st = store.load()
        store.save(st.copy(refs = st.refs + (photoId to ref), bad = st.bad - photoId))
        return ref
    }

    private suspend fun intact(fileId: String, folderId: String): Boolean {
        val f = try {
            drive.getFile(fileId)
        } catch (e: DriveException) {
            if (e.kind != DriveException.Kind.NOT_FOUND) throw e
            return false
        }
        return isPhotoFile(f, folderId) && f.sha256Checksum != null
    }

    private suspend fun tryTrash(fileId: String) {
        try {
            drive.trash(fileId)
        } catch (e: DriveException) {
            // Tidying only.
        }
    }

    // ---- Download ----------------------------------------------------------------------------------------------------

    /** The photo's bytes, or null when this photo is skipped (reported in [skipped]); a network failure is thrown. */
    suspend fun download(photoId: String): ByteArray? {
        requirePinned()
        val st = store.load()
        val ref = st.refs[photoId] ?: run {
            skip(photoId, PhotoSkipReason.NO_REFERENCE)
            return null
        }
        val mark = st.bad[photoId]
        if (mark != null && mark.fileId == ref.driveFileId && clock() >= mark.at && clock() - mark.at < config.badRetryMs) {
            skip(photoId, mark.reason, "remembered")
            return null
        }
        val folder = drive.ensureFolder(DriveLayout.PHOTOS, session.rootId, create = false, knownId = st.photosFolderId)
        if (folder == null) {
            skip(photoId, PhotoSkipReason.NO_FOLDER)
            return null
        }
        return try {
            val f = try {
                drive.getFile(ref.driveFileId)
            } catch (e: DriveException) {
                if (e.kind != DriveException.Kind.NOT_FOUND) throw e
                throw Reject(PhotoSkipReason.UNREADABLE, "NOT_FOUND")
            }
            if (!isPhotoFile(f, folder.id)) throw Reject(PhotoSkipReason.NOT_OURS)
            readVerified(f, ref.sha256)
        } catch (r: Reject) {
            val cur = store.load()
            store.save(cur.copy(bad = cur.bad + (photoId to PhotoBad(ref.driveFileId, r.reason, clock()))))
            skip(photoId, r.reason, r.detail)
            null
        }
    }

    // ---- Checks ------------------------------------------------------------------------------------------------------

    private fun isPhotoFile(f: DriveFile, folderId: String): Boolean =
        !f.trashed && !f.isFolder && folderId in f.parents && f.appProperties[DriveLayout.KIND] == KIND_PHOTO &&
            f.appProperties[DriveLayout.STATE] == DriveLayout.STATE_COMPLETE

    /** The plaintext of [f] when it is a `photo/1` file that authenticates and decrypts to [expectedSha]; [Reject] otherwise. */
    private suspend fun readVerified(f: DriveFile, expectedSha: String): ByteArray {
        val size = f.size
        if (size != null && size > config.maxFileBytes) throw Reject(PhotoSkipReason.TOO_LARGE)
        val bytes = try {
            drive.downloadVerified(f.id)
        } catch (e: DriveException) {
            when (e.kind) {
                DriveException.Kind.CORRUPT -> throw Reject(PhotoSkipReason.DOWNLOAD_CORRUPT)
                DriveException.Kind.NOT_FOUND, DriveException.Kind.FORBIDDEN -> throw Reject(PhotoSkipReason.UNREADABLE, e.kind.name)
                else -> throw e
            }
        }
        if (bytes.size > config.maxFileBytes) throw Reject(PhotoSkipReason.TOO_LARGE)
        var refreshed = false
        while (true) {
            try {
                return open(keys(), bytes, f, expectedSha)
            } catch (_: NeedFreshKeys) {
                if (refreshed) throw Reject(PhotoSkipReason.NEWER_EPOCH)
                refreshed = true
                session.refresh()
                keysAt = clock()
            }
        }
    }

    private fun open(keys: OpenedKeys, bytes: ByteArray, f: DriveFile, expectedSha: String): ByteArray {
        val check = { h: DpxHeader ->
            when (RevokedEpochRule.check(keys.body, h.epoch, h.kid, f.modifiedTime)) {
                RevokedEpochRule.Verdict.ACCEPT -> Unit
                RevokedEpochRule.Verdict.NEWER_EPOCH -> throw NeedFreshKeys()
                RevokedEpochRule.Verdict.SKIP_REVOKED_WRITER -> throw Reject(PhotoSkipReason.REVOKED_WRITER)
                RevokedEpochRule.Verdict.SKIP_OLD_EPOCH_AFTER_REVOKE -> throw Reject(PhotoSkipReason.OLD_EPOCH_AFTER_REVOKE)
                RevokedEpochRule.Verdict.SKIP_UNKNOWN_WRITER -> throw Reject(PhotoSkipReason.UNKNOWN_WRITER)
            }
        }
        return try {
            dpx.decryptBytes(keys, Dpx.PHOTO, bytes, config.maxPlaintextBytes.toLong(), Bytes.unhex(expectedSha), headerCheck = check).first
        } catch (e: DpxException) {
            throw when (e.kind) {
                DpxException.Kind.NOT_DPX -> Reject(PhotoSkipReason.NOT_ENCRYPTED)
                DpxException.Kind.UNSUPPORTED_VERSION, DpxException.Kind.UNSUPPORTED_ALGORITHM -> Reject(PhotoSkipReason.UNSUPPORTED_VERSION, e.kind.name)
                DpxException.Kind.TOO_LARGE -> Reject(PhotoSkipReason.TOO_LARGE)
                DpxException.Kind.CHECKSUM_MISMATCH -> Reject(PhotoSkipReason.CHECKSUM_MISMATCH)
                else -> Reject(PhotoSkipReason.BAD_ENVELOPE, e.kind.name)
            }
        } catch (e: KeysException) {
            throw Reject(PhotoSkipReason.BAD_ENVELOPE, e.kind.name)
        }
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    private fun requirePinned() {
        if (session.guard.watermark() == null) throw KeysException(KeysException.Kind.NOT_PINNED, "no pin for this folder: nothing is opened")
    }

    /** The opened key list, read again through the pin when it is older than [PhotoConfig.keysFreshMs] (a revoke is seen). */
    private suspend fun keys(): OpenedKeys {
        val now = clock()
        val at = keysAt
        if (at == null || now < at || now - at > config.keysFreshMs) {
            session.refresh()
            keysAt = now
        }
        return session.keys
    }

    private fun skip(photoId: String, reason: PhotoSkipReason, detail: String? = null): PhotoUploadResult.Skipped {
        skips += SkippedPhoto(photoId, reason, detail)
        return PhotoUploadResult.Skipped(reason)
    }

    private fun token(): String = Bytes.hex(p.randomBytes(12))

    /** The bytes of [open] when they fit the cap, else null. */
    private fun readCapped(open: () -> Source): ByteArray? {
        val src = open()
        try {
            if (src.request(config.maxPlaintextBytes.toLong() + 1)) return null
            return src.readByteArray()
        } finally {
            src.close()
        }
    }
}
