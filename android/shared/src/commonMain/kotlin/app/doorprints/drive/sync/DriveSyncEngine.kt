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

package app.doorprints.drive.sync

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
import app.doorprints.drive.UploadTarget
import app.doorprints.drive.downloadVerified
import app.doorprints.drive.ensureFolder
import app.doorprints.drive.listAll
import app.doorprints.drive.markComplete
import app.doorprints.drive.sha256HexOf
import app.doorprints.drive.photo.PhotoRef
import app.doorprints.drive.photo.PhotoRefs
import app.doorprints.drive.uploadBytes
import app.doorprints.shared.sync.DriveMerge
import app.doorprints.shared.sync.SyncFile
import app.doorprints.shared.sync.SyncFileException
import app.doorprints.shared.sync.SyncFileProblem
import app.doorprints.shared.sync.SyncFiles
import app.doorprints.shared.sync.SyncKind
import app.doorprints.shared.sync.SyncRow
import app.doorprints.shared.sync.SyncStamp
import app.doorprints.shared.sync.SyncTime
import kotlinx.coroutines.CancellationException

/**
 * Sync through Google Drive (S4b-BL-118; docs/15 §1.3, §5.1, §7 phase 5, §9.9), in common code over a signed-in
 * [DriveClient] and an opened [FolderSession]. Web twin: `drive-sync-engine.ts`.
 *
 * **One pass** ([run]), in this order:
 * 1. Keys are opened again through the device's pin ([FolderSession.refresh]); a device with no pin does nothing.
 * 2. One listing of `Sync/` (files of `kind=sync`, found by app properties, never by name). A file counts only if it
 *    is `state=complete`, its `device` is a device of the **authenticated** key list (or a revoked one), it downloads
 *    to the SHA-256 Drive reports, it is a `dpx/1` file of inner `sync/1` whose authenticated header passes
 *    [RevokedEpochRule] and whose writer key id is the device whose slot it is in, it decrypts, and its content parses
 *    ([SyncFiles.parse] with that device as the expected one). A file that fails any check is **skipped and reported**
 *    ([SkippedFile]); nothing is ever deleted because of what is read, and the pass goes on.
 * 3. Per device the highest authenticated `seq` wins; a `seq` lower than one already accepted is a rollback (a Drive
 *    revision put back) and changes nothing. The file is planned by [DriveMerge.plan] against this device's rows:
 *    last write wins, tombstones kept, rows stamped over 24 h ahead held, mass house deletions deferred for the
 *    person ([SyncPassResult.NeedsConfirmation]).
 * 4. This device's own file is the whole of its state: every local row (tombstones included) plus the rows just
 *    taken. It is written only when its rows changed or the last one is gone: encrypted as `dpx/1` with the current
 *    epoch's key, uploaded as `state=partial` under a `partial-` name, checked against Drive's checksum, marked
 *    `state=complete`, then **read back, decrypted and compared** before the pass reports success. Only then may the
 *    caller mark its rows clean. An interrupted pass leaves a partial file nobody reads and a `seq` that is never reused.
 *
 * A pass is idempotent: running it again with nothing new changes nothing and writes nothing. A device's checksum
 * cursor moves past a file only when nothing of it is left to apply, hold or confirm, so rows the caller did not apply
 * (a crash) come again.
 *
 * **Never** here: a revoke, a re-key, a wipe or a re-creation because of what `keys.json` or Drive said; deleting a
 * file because of its content (this device's own superseded files go to the bin after its new file is confirmed).
 */
class DriveSyncEngine(
    private val drive: DriveClient,
    private val p: CryptoProvider,
    private val session: FolderSession,
    private val state: SyncStateStore,
    private val local: LocalRows,
    private val clock: () -> Long,
    /** True while a deletion is pending or the folder was deleted: no pass then (docs/15 §3.3, §3.4). */
    private val paused: suspend () -> Boolean = { false },
    /** Where the photos' Drive files are remembered (S4b-BL-128): written into the photo rows, learned from the others' files. */
    private val photos: PhotoRefs? = null,
) {
    private val dpx = Dpx(p)
    private val staged = LinkedHashMap<RowId, SyncRow>()
    private var ownCache: Pair<String, Verified>? = null

    private data class RowId(val kind: SyncKind, val key: String)

    private class Verified(val file: SyncFile, val checksum: String, val plainSha: ByteArray)

    private class Skip(val reason: SkipReason, val detail: String? = null) : Exception(reason.name)

    private class NeedFreshKeys : Exception()

    private class Ctx(var keys: OpenedKeys, var refreshed: Boolean = false)

    /** Adds a row to the next written file that the local rows no longer hold (a deleted photo's tombstone). */
    fun stage(row: SyncRow) {
        val id = RowId(row.kind, row.key)
        val had = staged[id]
        if (had == null || DriveMerge.takesIncoming(had.stamp, row.stamp)) staged[id] = row
    }

    /**
     * One pass (see the class). [confirmShrink] is the person's yes to the shrink guard; [force] ignores the backoff
     * (the person's *Sync now*).
     */
    suspend fun run(confirmShrink: Boolean = false, force: Boolean = false): SyncPassResult {
        if (session.guard.watermark() == null) throw KeysException(KeysException.Kind.NOT_PINNED, "no pin for this folder: nothing is opened")
        if (paused()) return SyncPassResult.Paused
        val st = state.load()
        val now = clock()
        if (!force && now < st.notBefore) return SyncPassResult.Waiting(st.notBefore)
        try {
            return pass(st, now, confirmShrink)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DriveException) {
            val cur = state.load()
            val failures = cur.failures + 1
            val wait = e.retryAfterMs ?: drive.retry.backoffMs(failures.coerceAtMost(30))
            state.save(cur.copy(failures = failures, notBefore = clock() + wait))
            throw e
        }
    }

    /**
     * Whether Drive has lost what this device wrote (S4b-BL-70's `isBehind`): this device remembers a confirmed file,
     * and Drive has no complete file of this device with an authenticated `seq` at least that high. Cheap: one
     * `files.get` of the last file; Drive's checksum is a **hint** that spares the download, never the answer: when it
     * differs (or the file is gone) the own files are listed and read, and only an authenticated `seq` decides.
     * Unknown (an error) is false: a real failure shows in the pass that follows.
     */
    suspend fun isBehind(): Boolean {
        if (session.guard.watermark() == null) return false
        val st = state.load()
        if (st.confirmedSeq == 0L || st.lastFileId == null) return false
        return try {
            val hint = try {
                drive.getFile(st.lastFileId)
            } catch (e: DriveException) {
                if (e.kind != DriveException.Kind.NOT_FOUND) throw e
                null
            }
            if (hint != null && isOwnIntact(hint, st)) return false
            val keys = session.refresh()
            val ctx = Ctx(keys)
            val syncId = st.syncFolderId ?: return true
            val own = ownCandidates(drive.listAll(DriveQuery(parentId = syncId, appProperties = mapOf(DriveLayout.KIND to KIND_SYNC))))
            var best = 0L
            for (f in own) {
                try {
                    best = maxOf(best, verify(ctx, f, session.deviceId).file.seq)
                } catch (_: Skip) {
                }
            }
            best < st.confirmedSeq
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    // ---- The pass ---------------------------------------------------------------------------------------------------

    private suspend fun pass(initial: DriveSyncState, now: Long, confirmShrink: Boolean): SyncPassResult {
        var st = initial
        val me = session.deviceId
        val ctx = Ctx(session.refresh())
        val skipped = ArrayList<SkippedFile>()

        val current = HashMap<RowId, SyncRow>()
        for (r in local.all()) putIfNewer(current, r)
        for (r in staged.values) putIfNewer(current, r)
        val liveHouses = current.values.count { it.kind == SyncKind.HOUSES && !it.stamp.deleted }

        val syncId = drive.ensureFolder(DriveLayout.SYNC, session.rootId, create = true, knownId = st.syncFolderId)!!.id
        st = st.copy(syncFolderId = syncId)

        val files = LinkedHashMap<String, DriveFile>()
        for (f in drive.listAll(DriveQuery(parentId = syncId, appProperties = mapOf(DriveLayout.KIND to KIND_SYNC)))) files[f.id] = f
        // A listing may lag behind a write: this device's last file is asked for by id.
        st.lastFileId?.let { id ->
            if (id !in files) {
                try {
                    drive.getFile(id).takeIf { !it.trashed && syncId in it.parents && it.appProperties[DriveLayout.KIND] == KIND_SYNC }?.let { files[id] = it }
                } catch (e: DriveException) {
                    if (e.kind != DriveException.Kind.NOT_FOUND) throw e
                }
            }
        }

        // This device's own previous file: the sequence number it reached, and the photo tombstones it carries.
        var ownSeen = 0L
        var ownIntact = false
        val last = st.lastFileId?.let { files[it] }
        if (last != null && isOwnIntact(last, st)) {
            ownIntact = true
            val cached = ownCache?.takeIf { it.first == st.lastChecksum }?.second
            val v = cached ?: try {
                verify(ctx, last, me).also { ownCache = it.checksum to it }
            } catch (_: Skip) {
                null
            }
            if (v != null) {
                ownSeen = v.file.seq
                carryTombstones(current, v.file)
            }
        } else {
            for (f in ownCandidates(files.values)) {
                try {
                    val v = verify(ctx, f, me)
                    if (v.file.seq > ownSeen) {
                        ownSeen = v.file.seq
                        carryTombstones(current, v.file)
                    }
                } catch (s: Skip) {
                    skipped += SkippedFile(me, f.id, s.reason, s.detail)
                }
            }
        }

        // The other devices' files.
        val body = ctx.keys.body
        val known = HashSet<String>()
        body.devices.forEach { known += SyncDeviceIds.of(it.kid) }
        body.revoked.filter { !it.isRecovery }.forEach { known += SyncDeviceIds.of(it.kid) }
        val groups = LinkedHashMap<String, MutableList<DriveFile>>()
        for (f in files.values) {
            if (f.appProperties[DriveLayout.STATE] != DriveLayout.STATE_COMPLETE || f.trashed) continue
            val dev = f.appProperties[DriveLayout.DEVICE]
            if (dev == me) continue
            if (dev == null || dev !in known) {
                skipped += SkippedFile(dev?.takeIf(SyncFiles::isDeviceId), f.id, SkipReason.UNLISTED_DEVICE)
                continue
            }
            groups.getOrPut(dev) { ArrayList() } += f
        }

        val stamps = { kind: SyncKind, key: String -> current[RowId(kind, key)]?.stamp }
        val take = HashMap<RowId, SyncRow>()
        val deferredKeys = HashSet<RowId>()
        val deferredFrom = ArrayList<String>()
        var held = 0
        var peersRead = 0
        val peers = HashMap(st.peers)
        val learned = HashMap<String, PhotoRef>()
        for ((dev, group) in groups) {
            val peer = peers[dev] ?: SyncPeer()
            val candidates = group
                .filter { f -> f.sha256Checksum == null || !f.sha256Checksum.equals(peer.mergedChecksum, ignoreCase = true) }
                .filter { f -> hintSeq(f).let { it == null || it >= peer.highestSeq } }
                .sortedWith(compareByDescending<DriveFile> { hintSeq(it) ?: Long.MAX_VALUE }.thenByDescending { it.createdTime }.thenBy { it.id })
            if (candidates.size > MAX_CANDIDATES) skipped += SkippedFile(dev, null, SkipReason.TOO_MANY_FILES)
            var best: Verified? = null
            var bestFile: DriveFile? = null
            for (f in candidates.take(MAX_CANDIDATES)) {
                try {
                    val v = verify(ctx, f, dev)
                    if (best == null || v.file.seq > best.file.seq) {
                        best = v
                        bestFile = f
                    }
                } catch (s: Skip) {
                    skipped += SkippedFile(dev, f.id, s.reason, s.detail)
                }
            }
            if (best == null) continue
            peersRead++
            // The Drive file of each live photo the authenticated file names, for downloads (and for our own next file).
            for (row in best.file.rows(SyncKind.PHOTOS)) if (!row.stamp.deleted) SyncRows.refOf(row)?.let { if (row.key !in learned) learned[row.key] = it }
            val plan = DriveMerge.plan(best.file, now, peer.highestSeq.takeIf { it > 0 }, liveHouses, confirmShrink, stamps)
            if (plan.stale) {
                skipped += SkippedFile(dev, bestFile!!.id, SkipReason.ROLLED_BACK)
                continue
            }
            for (r in plan.take) {
                val id = RowId(r.kind, r.key)
                val had = take[id]
                if (had == null || DriveMerge.takesIncoming(had.stamp, r.stamp)) take[id] = r
            }
            held += plan.held.size
            if (plan.deferred.isNotEmpty()) {
                plan.deferred.forEach { deferredKeys += RowId(it.kind, it.key) }
                deferredFrom += dev
            }
            val settled = plan.take.isEmpty() && plan.held.isEmpty() && plan.deferred.isEmpty()
            peers[dev] = SyncPeer(maxOf(peer.highestSeq, best.file.seq), if (settled) best.checksum else peer.mergedChecksum)
        }
        for (r in take.values) putIfNewer(current, r)
        if (photos != null) {
            photos.learn(learned)
            // Every photo row of our file carries the Drive file of its bytes, once there is one.
            val refs = photos.refs()
            if (refs.isNotEmpty()) {
                for ((id, row) in current.entries.toList()) {
                    if (id.kind != SyncKind.PHOTOS || SyncRows.refOf(row) != null) continue
                    current[id] = SyncRows.withRef(row, refs[id.key] ?: continue)
                }
            }
        }

        // This device's own file.
        var wrote = false
        var seq: Long? = null
        val rowsFile = SyncFile(me, 1, SyncTime.EARLIEST_MS, current.values.groupBy { it.kind })
        val rowsHash = Bytes.hex(p.sha256Of(Bytes.utf8(SyncFiles.encode(rowsFile))))
        if (rowsHash != st.lastRowsHash || !ownIntact) {
            st = write(ctx, st, rowsFile, rowsHash, ownSeen, syncId, files.values, now)
            wrote = true
            seq = st.confirmedSeq
        }

        val delivered = take.values.sortedWith(compareBy<SyncRow>({ it.kind.ordinal }, { it.key }))
        st = st.copy(
            peers = peers,
            generation = if (delivered.isEmpty()) st.generation else st.generation + 1,
            failures = 0,
            notBefore = 0,
        )
        state.save(st)
        staged.clear()
        val report = SyncReport(delivered, held, deferredKeys.size, skipped, wrote, seq, peersRead, st.generation)
        return if (deferredKeys.isNotEmpty()) {
            SyncPassResult.NeedsConfirmation(report, deferredKeys.size, liveHouses, deferredFrom.distinct())
        } else {
            SyncPassResult.Done(report)
        }
    }

    // ---- Writing -----------------------------------------------------------------------------------------------------

    private suspend fun write(
        ctx: Ctx,
        before: DriveSyncState,
        rowsFile: SyncFile,
        rowsHash: String,
        ownSeen: Long,
        syncId: String,
        listed: Collection<DriveFile>,
        now: Long,
    ): DriveSyncState {
        val me = session.deviceId
        val seq = maxOf(before.lastSeq, ownSeen) + 1
        if (seq > SyncFiles.MAX_SEQ) throw DriveException(DriveException.Kind.BAD_REQUEST, reason = "seqExhausted")
        // Reserved first: a pass that stops half way never reuses it.
        var st = before.copy(lastSeq = seq)
        state.save(st)
        val text = SyncFiles.encode(SyncFile(me, seq, maxOf(now, SyncTime.EARLIEST_MS), rowsFile.rows))
        val keys = ctx.keys
        val folderKey = keys.currentFolderKey()
        val (bytes, written) = try {
            dpx.encryptBytes(folderKey, keys.epoch, session.deviceKid, SYNC_INNER, Bytes.utf8(text))
        } finally {
            folderKey.fill(0)
        }
        val sha = Bytes.hex(written.ciphertextSha256)
        val partial = drive.uploadBytes(
            UploadTarget.New(
                NewFile(
                    name = "partial-sync-$seq.dpx",
                    mimeType = "application/octet-stream",
                    parents = listOf(syncId),
                    appProperties = mapOf(
                        DriveLayout.KIND to KIND_SYNC,
                        DriveLayout.DEVICE to me,
                        DriveLayout.STATE to DriveLayout.STATE_PARTIAL,
                        DriveLayout.CREATED_AT to now.toString(),
                        PROP_SEQ to seq.toString(),
                    ),
                ),
            ),
            bytes,
        )
        val done = drive.markComplete(partial.id, sha, "sync-$me-$seq.dpx", partial)
        // Read back: what Drive holds is the file written, by its content and not by its say-so.
        val back = drive.getFile(done.id)
        if (back.trashed || back.appProperties[DriveLayout.STATE] != DriveLayout.STATE_COMPLETE ||
            back.appProperties[DriveLayout.DEVICE] != me || !sha.equals(back.sha256Checksum, ignoreCase = true)
        ) {
            throw DriveException(DriveException.Kind.CORRUPT, reason = "readBack")
        }
        val got = drive.downloadVerified(done.id, sha)
        val v = try {
            open(ctx, got, back, me)
        } catch (s: Skip) {
            throw DriveException(DriveException.Kind.CORRUPT, reason = "readBack:${s.reason.name}")
        }
        if (v.file.seq != seq || !v.plainSha.contentEquals(written.plaintextSha256) || rowsHashOf(v.file) != rowsHash) {
            throw DriveException(DriveException.Kind.CORRUPT, reason = "readBackContent")
        }
        st = st.copy(confirmedSeq = seq, lastFileId = done.id, lastChecksum = sha, lastRowsHash = rowsHash)
        state.save(st)
        ownCache = sha to v
        // Only now the superseded files of this device (and its partial leftovers) go to the bin.
        for (f in listed) {
            if (f.id != done.id && f.appProperties[DriveLayout.DEVICE] == me) {
                try {
                    drive.trash(f.id)
                } catch (e: DriveException) {
                    // Tidying only; the next pass tries again.
                }
            }
        }
        return st
    }

    private fun rowsHashOf(file: SyncFile): String =
        Bytes.hex(p.sha256Of(Bytes.utf8(SyncFiles.encode(SyncFile(file.deviceId, 1, SyncTime.EARLIEST_MS, file.rows)))))

    // ---- Reading -----------------------------------------------------------------------------------------------------

    private fun hintSeq(f: DriveFile): Long? = f.appProperties[PROP_SEQ]?.toLongOrNull()

    private fun ownCandidates(files: Collection<DriveFile>): List<DriveFile> =
        files.filter { it.appProperties[DriveLayout.DEVICE] == session.deviceId && it.appProperties[DriveLayout.STATE] == DriveLayout.STATE_COMPLETE && !it.trashed }
            .sortedWith(compareByDescending<DriveFile> { hintSeq(it) ?: Long.MAX_VALUE }.thenByDescending { it.createdTime }.thenBy { it.id })
            .take(MAX_CANDIDATES)

    private fun isOwnIntact(f: DriveFile, st: DriveSyncState): Boolean =
        !f.trashed && f.appProperties[DriveLayout.KIND] == KIND_SYNC && f.appProperties[DriveLayout.DEVICE] == session.deviceId &&
            f.appProperties[DriveLayout.STATE] == DriveLayout.STATE_COMPLETE && st.lastChecksum != null &&
            st.lastChecksum.equals(f.sha256Checksum, ignoreCase = true)

    /** The deleted photo rows of this device's own file that no local row covers: tombstones are kept for ever. */
    private fun carryTombstones(current: HashMap<RowId, SyncRow>, file: SyncFile) {
        for (r in file.rows(SyncKind.PHOTOS)) if (r.stamp.deleted) putIfNewer(current, r)
    }

    private fun putIfNewer(map: HashMap<RowId, SyncRow>, row: SyncRow) {
        val id = RowId(row.kind, row.key)
        val had = map[id]
        if (had == null || DriveMerge.takesIncoming(had.stamp, row.stamp)) map[id] = row
    }

    /** Downloads, verifies and parses one file claimed by [claimed]. Throws [Skip] for a file to leave out. */
    private suspend fun verify(ctx: Ctx, f: DriveFile, claimed: String): Verified {
        val size = f.size
        if (size != null && size > MAX_FILE_BYTES) throw Skip(SkipReason.TOO_LARGE)
        val bytes = try {
            drive.downloadVerified(f.id)
        } catch (e: DriveException) {
            when (e.kind) {
                DriveException.Kind.CORRUPT -> throw Skip(SkipReason.DOWNLOAD_CORRUPT)
                DriveException.Kind.NOT_FOUND, DriveException.Kind.FORBIDDEN -> throw Skip(SkipReason.UNREADABLE, e.kind.name)
                else -> throw e
            }
        }
        return open(ctx, bytes, f, claimed)
    }

    private suspend fun open(ctx: Ctx, bytes: ByteArray, f: DriveFile, claimed: String): Verified {
        if (bytes.size > MAX_FILE_BYTES) throw Skip(SkipReason.TOO_LARGE)
        while (true) {
            try {
                return openWith(ctx.keys, bytes, f, claimed)
            } catch (_: NeedFreshKeys) {
                if (ctx.refreshed) throw Skip(SkipReason.NEWER_EPOCH)
                ctx.refreshed = true
                ctx.keys = session.refresh()
            }
        }
    }

    private fun openWith(keys: OpenedKeys, bytes: ByteArray, f: DriveFile, claimed: String): Verified {
        val check = { h: DpxHeader ->
            when (RevokedEpochRule.check(keys.body, h.epoch, h.kid, f.modifiedTime)) {
                RevokedEpochRule.Verdict.ACCEPT -> Unit
                RevokedEpochRule.Verdict.NEWER_EPOCH -> throw NeedFreshKeys()
                RevokedEpochRule.Verdict.SKIP_REVOKED_WRITER -> throw Skip(SkipReason.REVOKED_WRITER)
                RevokedEpochRule.Verdict.SKIP_OLD_EPOCH_AFTER_REVOKE -> throw Skip(SkipReason.OLD_EPOCH_AFTER_REVOKE)
                RevokedEpochRule.Verdict.SKIP_UNKNOWN_WRITER -> throw Skip(SkipReason.UNKNOWN_WRITER)
            }
            if (SyncDeviceIds.of(h.kid) != claimed) throw Skip(SkipReason.WRONG_DEVICE, "kid")
        }
        val plain: ByteArray
        val read = try {
            val r = dpx.decryptBytes(keys, SYNC_INNER, bytes, SyncFiles.MAX_BYTES.toLong(), headerCheck = check)
            plain = r.first
            r.second
        } catch (e: DpxException) {
            throw when (e.kind) {
                DpxException.Kind.NOT_DPX -> Skip(SkipReason.NOT_ENCRYPTED)
                DpxException.Kind.UNSUPPORTED_VERSION, DpxException.Kind.UNSUPPORTED_ALGORITHM -> Skip(SkipReason.UNSUPPORTED_VERSION, e.kind.name)
                DpxException.Kind.TOO_LARGE -> Skip(SkipReason.TOO_LARGE)
                else -> Skip(SkipReason.BAD_ENVELOPE, e.kind.name)
            }
        } catch (e: KeysException) {
            throw Skip(SkipReason.BAD_ENVELOPE, e.kind.name)
        }
        val text = try {
            plain.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw Skip(SkipReason.BAD_FILE, "utf8")
        }
        val file = try {
            SyncFiles.parse(text, claimed)
        } catch (e: SyncFileException) {
            throw when (e.problem) {
                SyncFileProblem.UNSUPPORTED_VERSION -> Skip(SkipReason.UNSUPPORTED_VERSION, e.problem.name)
                SyncFileProblem.WRONG_DEVICE -> Skip(SkipReason.WRONG_DEVICE, e.problem.name)
                SyncFileProblem.TOO_LARGE -> Skip(SkipReason.TOO_LARGE, e.problem.name)
                else -> Skip(SkipReason.BAD_FILE, e.problem.name)
            }
        }
        return Verified(file, sha256HexOf(bytes), read.plaintextSha256)
    }

    companion object {
        /** `appProperties seq`: the writer's counter as a hint to order candidates; never trusted (the file's own is). */
        const val PROP_SEQ = "seq"

        /** Candidates read per device in one pass. */
        const val MAX_CANDIDATES = 4

        /** The largest ciphertext read: the plaintext cap plus the `dpx/1` overhead. */
        const val MAX_FILE_BYTES = SyncFiles.MAX_BYTES + 1024 * 1024
    }
}
