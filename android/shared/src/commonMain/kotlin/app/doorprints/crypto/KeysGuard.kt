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

/**
 * What a device has accepted of one Drive folder's `keys.json`: the highest (epoch, revision), the **pin** of that
 * epoch's folder key ([keyId], `HKDF(folder key, "doorprints/dpx1/key-id")`, never the key itself) and the SHA-256 of
 * the canonical body at that revision (to tell two different lists of one revision apart).
 */
class KeysWatermark(val epoch: Int, val revision: Long, keyId: ByteArray, bodyHash: ByteArray) {
    private val id = keyId.copyOf()
    private val hash = bodyHash.copyOf()
    val keyId: ByteArray get() = id.copyOf()
    val bodyHash: ByteArray get() = hash.copyOf()

    override fun equals(other: Any?) = other is KeysWatermark && other.epoch == epoch && other.revision == revision &&
        other.id.contentEquals(id) && other.hash.contentEquals(hash)

    override fun hashCode() = 31 * (31 * epoch + revision.hashCode()) + id.contentHashCode()
    override fun toString() = "KeysWatermark(epoch=$epoch, revision=$revision)"
}

/**
 * Where a device keeps its [KeysWatermark] for one folder (sealed on the device; injected, so the rules have no I/O).
 * [compareAndSet] must be atomic: it stores [next] only if the stored value still equals [expected] (null: nothing
 * stored), and says whether it did.
 */
interface KeysWatermarkStore {
    fun load(): KeysWatermark?
    fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark): Boolean
}

/**
 * The trust anchor of a device in one folder (docs/15 §9.3, §9.9; docs/02 T-S13, T-T19). The MAC of `keys.json`
 * proves only that its writer knew *some* folder key, and HPKE base mode does not say who wrapped it, so anyone in
 * the Google account could re-wrap a folder key of their own to every listed public key. What makes a list
 * acceptable is that it leads to the key this device already trusts:
 *
 * - **the same epoch**: its folder key's id equals the pin, and its revision is not lower; the same revision with
 *   another body is a fork ([KeysException.Kind.FORK_DETECTED]);
 * - **a higher epoch**: the whole chain from it down to the pinned epoch opens and ends at the pinned key
 *   ([KeysException.Kind.PIN_MISMATCH] otherwise), whatever the revision (the order is (epoch, revision), so an old
 *   epoch's holder cannot block a new epoch with a huge revision);
 * - **a lower epoch**: [KeysException.Kind.ROLLED_BACK].
 *
 * With no pin yet, [KeysFile.open] refuses ([KeysException.Kind.NOT_PINNED]): the first pin comes only from a path
 * that has its own proof: [KeysFile.openFirstPin] (the folder key received over S4b-BL-126's QR/PSK enrolment),
 * [KeysFile.openWithRecovery] (the recovery anchor) and [pinCreated] (this device created the folder).
 */
class KeysGuard(private val p: CryptoProvider, private val store: KeysWatermarkStore) {

    internal enum class Trust { PINNED, FIRST_PIN, RECOVERY_ANCHOR, CREATED }

    /** The current pin, if any. */
    fun watermark(): KeysWatermark? = store.load()

    /** After this device's own write was confirmed by Drive: the new list must lead to the pin like any other. */
    fun acceptWritten(written: KeysFile.Written) = accept(written.opened, Trust.PINNED)

    /** After [KeysFile.createFirstDevice]'s list was confirmed by Drive: the first pin, only if there is none. */
    fun pinCreated(written: KeysFile.Written) = accept(written.opened, Trust.CREATED)

    internal fun accept(opened: OpenedKeys, trust: Trust) {
        val next = watermarkOf(p, opened)
        repeat(MAX_TRIES) {
            val seen = store.load()
            if (seen == null) {
                if (trust == Trust.PINNED) throw KeysException(KeysException.Kind.NOT_PINNED, "no pin for this folder yet")
            } else {
                verify(seen, opened, next)
                if (!higher(next, seen)) return
            }
            if (store.compareAndSet(seen, next)) return
        }
        throw KeysException(KeysException.Kind.CONCURRENT_UPDATE, "the watermark kept changing")
    }

    private fun verify(seen: KeysWatermark, opened: OpenedKeys, next: KeysWatermark) {
        when (order(seen.epoch, seen.revision, next.epoch, next.revision)) {
            Order.LOWER -> throw KeysException(KeysException.Kind.ROLLED_BACK, "epoch ${next.epoch} revision ${next.revision} is older than epoch ${seen.epoch} revision ${seen.revision}")
            Order.SAME_EPOCH -> {
                if (!constantTimeEquals(next.keyId, seen.keyId)) throw KeysException(KeysException.Kind.FORK_DETECTED, "another folder key for epoch ${next.epoch}")
                if (next.revision == seen.revision && !next.bodyHash.contentEquals(seen.bodyHash)) {
                    throw KeysException(KeysException.Kind.FORK_DETECTED, "another list at revision ${next.revision}")
                }
            }
            Order.HIGHER_EPOCH -> {
                val pinned = try {
                    opened.folderKey(seen.epoch)
                } catch (_: KeysException) {
                    null
                } ?: throw KeysException(KeysException.Kind.PIN_MISMATCH, "the chain does not reach epoch ${seen.epoch}")
                val id = FolderKey.keyId(p, pinned)
                pinned.fill(0)
                if (!constantTimeEquals(id, seen.keyId)) throw KeysException(KeysException.Kind.PIN_MISMATCH, "the chain does not end at the pinned key")
            }
        }
    }

    internal enum class Order { LOWER, SAME_EPOCH, HIGHER_EPOCH }

    internal companion object {
        const val MAX_TRIES = 4

        /** (epoch, revision) ordered lexicographically: a higher epoch always wins, a lower one never does. */
        fun order(seenEpoch: Int, seenRevision: Long, epoch: Int, revision: Long): Order = when {
            epoch < seenEpoch -> Order.LOWER
            epoch > seenEpoch -> Order.HIGHER_EPOCH
            revision < seenRevision -> Order.LOWER
            else -> Order.SAME_EPOCH
        }

        fun higher(next: KeysWatermark, seen: KeysWatermark) =
            next.epoch > seen.epoch || (next.epoch == seen.epoch && next.revision > seen.revision)

        fun watermarkOf(p: CryptoProvider, opened: OpenedKeys): KeysWatermark {
            val key = opened.currentFolderKey()
            val w = KeysWatermark(opened.epoch, opened.revision, FolderKey.keyId(p, key), p.sha256Of(opened.body.json()))
            key.fill(0)
            return w
        }
    }
}

/**
 * Which files to skip after a revoke (docs/15 §9.5 iv): a pure rule over the opened key list and what is known of a
 * file (its `dpx/1` header's epoch and writer kid, and when it was written). The caller reports every skip.
 *
 * [writtenAt] is the best time the caller has (Drive's `modifiedTime` for now). It is not authenticated: a revoked
 * device that still has Drive access could backdate a file it writes under its old epoch, and nothing in this rule
 * can tell that file from a real old one (docs/02 RR-26). The recovery key's kid is not a writer: a file naming it is
 * [Verdict.SKIP_UNKNOWN_WRITER] (a device that opened the folder with the recovery key joins as a device first).
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
