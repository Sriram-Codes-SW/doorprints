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

package app.doorprints.crypto

/** The highest `keys.json` epoch and revision a device has accepted for one Drive folder. */
data class KeysWatermark(val epoch: Int, val revision: Long)

/** Where a device keeps its [KeysWatermark] (sealed on the device; injected, so the rule has no I/O). */
interface KeysWatermarkStore {
    fun load(): KeysWatermark?
    fun save(watermark: KeysWatermark)
}

/**
 * The rollback rule (docs/15 §9.3, docs/02 T-T19): a device refuses a `keys.json` older than one it has already
 * accepted, for example a revision brought back with Drive's *Manage versions* to undo a revoke. Called only for a
 * list whose MAC verified, so only authentic lists move the watermark; [KeysFile.open] calls [accept] itself, and a
 * device that wrote a new list calls [accept] once Drive confirmed the upload (not before: a failed upload would
 * leave the device ahead of Drive and refusing the real list).
 */
class KeysGuard(private val store: KeysWatermarkStore) {

    /** Throws [KeysException.Kind.ROLLED_BACK] or [KeysException.Kind.WATERMARK_CONFLICT]; changes nothing. */
    fun check(epoch: Int, revision: Long) {
        val seen = store.load() ?: return
        if (revision < seen.revision || epoch < seen.epoch) {
            throw KeysException(KeysException.Kind.ROLLED_BACK, "revision $revision epoch $epoch is older than revision ${seen.revision} epoch ${seen.epoch}")
        }
        // Every write raises the revision, so one revision has one epoch.
        if (revision == seen.revision && epoch != seen.epoch) {
            throw KeysException(KeysException.Kind.WATERMARK_CONFLICT, "revision $revision with another epoch")
        }
    }

    /** [check], then keep the new watermark if it is higher. */
    fun accept(epoch: Int, revision: Long) {
        check(epoch, revision)
        val seen = store.load()
        if (seen == null || revision > seen.revision || epoch > seen.epoch) store.save(KeysWatermark(epoch, revision))
    }
}

/**
 * Which files to skip after a revoke (docs/15 §9.5 iv): a pure rule over the opened key list and what is known of a
 * file (its `dpx/1` header's epoch and writer kid, and when it was written). The caller reports every skip.
 *
 * [writtenAt] is the best time the caller has (Drive's `modifiedTime` for now). It is not authenticated: a revoked
 * device that still has Drive access could backdate a file it writes under its old epoch, and nothing in this rule
 * can tell that file from a real old one (docs/02 T-T19, residual risk).
 */
object RevokedEpochRule {
    enum class Verdict {
        ACCEPT,

        /** Written by a revoked device after its revoke, or under an epoch it never had. */
        SKIP_REVOKED_WRITER,

        /** Written under an epoch older than a revoke, after that revoke (a stale or forged writer). */
        SKIP_OLD_EPOCH_AFTER_REVOKE,

        /** The writer is neither listed nor revoked. */
        SKIP_UNKNOWN_WRITER,

        /** The file's epoch is newer than this list: read `keys.json` again before deciding. */
        NEWER_EPOCH,
    }

    fun check(body: KeysBody, fileEpoch: Int, writerKid: ByteArray, writtenAt: Long): Verdict {
        if (fileEpoch > body.epoch) return Verdict.NEWER_EPOCH
        val revokedWriter = body.revokedEntry(writerKid)
        if (revokedWriter != null) {
            if (fileEpoch >= revokedWriter.revokedAtEpoch || writtenAt > revokedWriter.revokedAt) return Verdict.SKIP_REVOKED_WRITER
        } else if (body.device(writerKid) == null) {
            return Verdict.SKIP_UNKNOWN_WRITER
        }
        for (r in body.revoked) {
            if (fileEpoch < r.revokedAtEpoch && writtenAt > r.revokedAt) return Verdict.SKIP_OLD_EPOCH_AFTER_REVOKE
        }
        return Verdict.ACCEPT
    }
}
