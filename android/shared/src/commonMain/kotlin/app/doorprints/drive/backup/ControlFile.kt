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

package app.doorprints.drive.backup

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CanonicalJson
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.FolderKeys
import app.doorprints.crypto.JsonRead
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.OpenedKeys
import app.doorprints.crypto.constantTimeEquals
import app.doorprints.crypto.sha256Of
import kotlinx.serialization.json.JsonObject

/** Why `doorprints.json` was refused. */
class ControlException(val kind: Kind, message: String) : Exception("control ${kind.name}: $message") {
    /** The ways a control file is refused; each one stops the open and leaves the watermark as it was. */
    enum class Kind {
        /** Not JSON, or not the `doorprints-control/1` structure. */
        MALFORMED,

        /** The structure is right but the bytes are not its canonical form. */
        NOT_CANONICAL,

        /** Another `format` (a newer app wrote it): "update the app". */
        UNSUPPORTED_FORMAT,

        /** An `encryption` other than `dpx/1`: never read as "unencrypted is fine" (the downgrade case). */
        UNSUPPORTED_ENCRYPTION,

        /** The file names an epoch this device holds no folder key for. */
        UNKNOWN_EPOCH,

        /** The MAC does not verify: not written by a holder of the folder key. */
        MAC_INVALID,

        /** A lower revision than this device already accepted (an old copy brought back). */
        ROLLED_BACK,

        /** The same revision as accepted, with another body: two control files exist. */
        FORK_DETECTED,

        /** The watermark store changed under every try of its compare-and-set. */
        CONCURRENT_UPDATE,
    }
}

/** The body of `doorprints.json` (docs/15 §5.1, §9.6). Times are epoch milliseconds. */
class ControlBody(
    /** Raised by every write; the rollback watermark goes by it. */
    val revision: Long,
    /** The epoch of the folder key whose derived key MACs it. */
    val epoch: Int,
    val createdAt: Long,
    /** Always `dpx/1` (docs/15 §9.6: once it says so, devices ignore an unencrypted file in the folder). */
    val encryption: String,
    /** *Delete all backups* (S4b-BL-119) sets it; a backup whose `createdAt` is not after it is never listed again. */
    val backupsDeletedAt: Long?,
) {
    /** The body in canonical JSON, the exact bytes the MAC and the watermark hash cover. */
    internal fun json(): ByteArray = CanonicalJson()
        .raw("{\"revision\":").number(revision)
        .raw(",\"epoch\":").number(epoch.toLong())
        .raw(",\"createdAt\":").number(createdAt)
        .raw(",\"encryption\":").string(encryption)
        .raw(",\"backupsDeletedAt\":").raw(backupsDeletedAt?.toString() ?: "null")
        .raw("}")
        .bytes()
}

/** What a device accepted of one folder's `doorprints.json`: the revision, its body's SHA-256, and the deletion mark. */
class ControlWatermark(val revision: Long, bodyHash: ByteArray, val backupsDeletedAt: Long?) {
    private val hash = bodyHash.copyOf()
    val bodyHash: ByteArray get() = hash.copyOf()

    override fun equals(other: Any?) = other is ControlWatermark && other.revision == revision &&
        other.hash.contentEquals(hash) && other.backupsDeletedAt == backupsDeletedAt

    override fun hashCode() = 31 * revision.hashCode() + hash.contentHashCode()
    override fun toString() = "ControlWatermark(revision=$revision)"
}

/** Where a device keeps its [ControlWatermark] for one folder (sealed on the device); [compareAndSet] is atomic. */
interface ControlWatermarkStore {
    /** The watermark this device accepted, or null before the first. */
    fun load(): ControlWatermark?
    /** Stores [next] only if the stored watermark is still [expected]; false when it changed meanwhile. */
    fun compareAndSet(expected: ControlWatermark?, next: ControlWatermark): Boolean
}

/**
 * `doorprints.json`, the folder's control file (docs/15 §5.1, §9.6, §9.9: S4b-BL-116 "with KeysGuard and a MAC of its
 * own label"), web twin `control-file.ts`:
 *
 * ```
 * {"format":"doorprints-control/1",
 *  "body":{"revision":R,"epoch":E,"createdAt":ms,"encryption":"dpx/1","backupsDeletedAt":ms|null},
 *  "mac":B64(HMAC-SHA-256(HKDF(folder key of E, "doorprints/dpx1/control"),
 *                         "doorprints-control/1" ‖ 0x00 ‖ canonical body))}
 * ```
 *
 * Canonical JSON as `keys.json` (a reader writes back what it parsed and requires the same bytes). Read only after
 * `keys.json` passed the pin, with the opened key list; rollback refused by its own watermark ([ControlWatermarkStore]),
 * ordered by revision, the same revision with another body a fork.
 */
class ControlFile(private val p: CryptoProvider) {

    /** A control file ready to upload: its canonical [bytes] and the [body] they encode. */
    class Written(val bytes: ByteArray, val body: ControlBody)

    /** The first control file of a folder this device just made (revision 1, the current epoch). */
    fun create(opened: OpenedKeys, now: Long): Written =
        write(ControlBody(1, opened.epoch, now, ENCRYPTION, null), opened)

    /** The next revision of [current], under the current epoch; [backupsDeletedAt] changed when given (S4b-BL-119). */
    fun next(opened: OpenedKeys, current: ControlBody, backupsDeletedAt: Long? = current.backupsDeletedAt): Written {
        if (current.revision >= CanonicalJson.MAX_SAFE) throw ControlException(ControlException.Kind.MALFORMED, "revision limit")
        return write(ControlBody(current.revision + 1, opened.epoch, current.createdAt, ENCRYPTION, backupsDeletedAt), opened)
    }

    private fun write(body: ControlBody, opened: OpenedKeys): Written {
        val key = opened.currentFolderKey()
        try {
            return Written(encode(body, mac(key, body)), body)
        } finally {
            key.fill(0)
        }
    }

    /** Opens [file] with the folder keys of an opened `keys.json`, then moves the watermark in [store]. */
    fun open(file: ByteArray, keys: FolderKeys, store: ControlWatermarkStore): ControlBody {
        val (body, mac) = parse(file)
        val key = try {
            keys.folderKey(body.epoch)
        } catch (_: KeysException) {
            null
        } ?: throw ControlException(ControlException.Kind.UNKNOWN_EPOCH, "epoch ${body.epoch}")
        try {
            if (!constantTimeEquals(mac(key, body), mac)) throw ControlException(ControlException.Kind.MAC_INVALID, "MAC")
        } finally {
            key.fill(0)
        }
        if (body.encryption != ENCRYPTION) throw ControlException(ControlException.Kind.UNSUPPORTED_ENCRYPTION, "encryption")
        accept(body, store)
        return body
    }

    /** Moves the watermark to [body] (after an [open], or after this device's own write was read back). */
    fun accept(body: ControlBody, store: ControlWatermarkStore) {
        val next = ControlWatermark(body.revision, p.sha256Of(body.json()), body.backupsDeletedAt)
        repeat(MAX_TRIES) {
            val seen = store.load()
            if (seen != null) {
                if (body.revision < seen.revision) throw ControlException(ControlException.Kind.ROLLED_BACK, "revision ${body.revision} < ${seen.revision}")
                if (body.revision == seen.revision) {
                    if (!seen.bodyHash.contentEquals(next.bodyHash)) throw ControlException(ControlException.Kind.FORK_DETECTED, "revision ${body.revision}")
                    return
                }
            }
            if (store.compareAndSet(seen, next)) return
        }
        throw ControlException(ControlException.Kind.CONCURRENT_UPDATE, "the watermark kept changing")
    }

    private fun mac(folderKey: ByteArray, body: ControlBody): ByteArray {
        val k = BackupKeys.control(p, folderKey)
        return p.hmacSha256(k, Bytes.concat(Bytes.utf8(FORMAT), byteArrayOf(0), body.json())).also { k.fill(0) }
    }

    private fun encode(body: ControlBody, mac: ByteArray): ByteArray = Bytes.concat(
        CanonicalJson().raw("{\"format\":").string(FORMAT).raw(",\"body\":").bytes(),
        body.json(),
        CanonicalJson().raw(",\"mac\":").string(Bytes.b64(mac)).raw("}").bytes(),
    )

    /**
      * Reads [file] strictly: size limit, the exact fields and ranges, and the bytes must equal the canonical
      * re-encoding.
     * The MAC is not checked here ([open] does it); returns the body and the MAC bytes.
     */
    internal fun parse(file: ByteArray): Pair<ControlBody, ByteArray> {
        fun bad(what: String): Nothing = throw ControlException(ControlException.Kind.MALFORMED, what)
        if (file.size > MAX_FILE) bad("too large")
        val root = CanonicalJson.parse(file) as? JsonObject ?: bad("not a JSON object")
        val format = JsonRead.string(root["format"]) ?: bad("format")
        if (format != FORMAT) throw ControlException(ControlException.Kind.UNSUPPORTED_FORMAT, "format")
        JsonRead.obj(root, "format", "body", "mac") ?: bad("fields")
        val mac = JsonRead.string(root["mac"])?.let { Bytes.unb64(it, 32) } ?: bad("mac")
        val o = JsonRead.obj(root["body"], "revision", "epoch", "createdAt", "encryption", "backupsDeletedAt") ?: bad("body")
        val body = ControlBody(
            revision = JsonRead.long(o["revision"], 1, CanonicalJson.MAX_SAFE) ?: bad("revision"),
            epoch = JsonRead.long(o["epoch"], 1, Int.MAX_VALUE.toLong())?.toInt() ?: bad("epoch"),
            createdAt = JsonRead.long(o["createdAt"], 0, CanonicalJson.MAX_SAFE) ?: bad("createdAt"),
            encryption = JsonRead.string(o["encryption"])?.takeIf { it.length in 1..32 } ?: bad("encryption"),
            backupsDeletedAt = if (JsonRead.isNull(o["backupsDeletedAt"])) null else JsonRead.long(o["backupsDeletedAt"], 0, CanonicalJson.MAX_SAFE) ?: bad("backupsDeletedAt"),
        )
        if (!encode(body, mac).contentEquals(file)) throw ControlException(ControlException.Kind.NOT_CANONICAL, "not canonical")
        return body to mac
    }

    companion object {
        const val FORMAT = "doorprints-control/1"
        const val ENCRYPTION = "dpx/1"
        const val MAX_FILE = 4096
        private const val MAX_TRIES = 4
    }
}
