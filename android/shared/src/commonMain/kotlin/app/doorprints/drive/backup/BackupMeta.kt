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
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.FolderKeys
import app.doorprints.crypto.Hkdf
import app.doorprints.crypto.KeysException
import app.doorprints.crypto.constantTimeEquals
import app.doorprints.drive.DriveFile
import app.doorprints.drive.DriveLayout

/**
 * The authenticated metadata of one backup in Drive (S4b-BL-116; docs/15 §1.4, §5.8), kept in its `appProperties` so
 * a listing tells the backups apart without downloading them:
 *
 * ```
 * kind=backup  state=partial|complete  device=<random id>        (not authenticated: Drive's and the app's bookkeeping)
 * createdAt=<ms>  houses=<n>  epoch=<E>  kid=B64(16)               (authenticated)
 * bm=B64(HMAC-SHA-256(HKDF(folder key of E, "doorprints/dpx1/backup-meta"),
 *        "doorprints-backup-meta/1" ‖ 0x00 ‖ u64 createdAt ‖ u32 houses ‖ u32 E ‖ kid(16) ‖ SHA-256 of the file))
 * ```
 *
 * The SHA-256 is of the `dpx/1` file as Drive stores it, checked against Drive's own `sha256Checksum`, so the
 * metadata only verifies on the exact bytes it was made for: someone who can write to the folder but holds no folder
 * key cannot plant a backup, make an old one look new, move a large backup's numbers onto a small one, or change a
 * byte (docs: notes S4b-BL-116 §0). Every field is fixed-size in the MAC input, so no two values give the same bytes.
 * Values are canonical decimal (no sign, no leading zero) and base64 with padding; anything else is not a backup.
 */
/**
 * The two folder-key uses S4b-BL-116 adds, each its own HKDF-SHA-256 key (empty salt, as `FolderKey` does for the
 * others, docs/15 §9.9 key separation): `doorprints/dpx1/control` MACs `doorprints.json`, `doorprints/dpx1/backup-meta`
 * MACs a backup's metadata. The folder key itself is never an HMAC key.
 */
internal object BackupKeys {
    const val CONTROL = "doorprints/dpx1/control"
    const val BACKUP_META = "doorprints/dpx1/backup-meta"

    fun control(p: CryptoProvider, folderKey: ByteArray): ByteArray = derive(p, folderKey, CONTROL)
    fun backupMeta(p: CryptoProvider, folderKey: ByteArray): ByteArray = derive(p, folderKey, BACKUP_META)

    private fun derive(p: CryptoProvider, folderKey: ByteArray, info: String): ByteArray {
        require(folderKey.size == 32) { "a folder key is 32 bytes" }
        return Hkdf(p).derive(ByteArray(0), folderKey, Bytes.utf8(info), 32)
    }
}

class BackupMeta(
    /** When the writer made the backup (epoch milliseconds): the order of backups and the retention's clock. */
    val createdAt: Long,
    /** Live houses in it, for the shrink guard. */
    val houses: Int,
    /** The epoch of the folder key the file (and this MAC) is under. */
    val epoch: Int,
    writerKid: ByteArray,
    ciphertextSha256: ByteArray,
) {
    private val kid = writerKid.copyOf()
    private val sha = ciphertextSha256.copyOf()
    val writerKid: ByteArray get() = kid.copyOf()
    val ciphertextSha256: ByteArray get() = sha.copyOf()

    init {
        require(createdAt in 0..MAX_TIME && houses in 0..MAX_HOUSES && epoch >= 1)
        require(kid.size == KID_SIZE && sha.size == 32)
    }

    internal fun macInput(): ByteArray = Bytes.concat(
        Bytes.utf8(LABEL), byteArrayOf(0), Bytes.i2osp(createdAt, 8), Bytes.u32(houses.toLong()), Bytes.u32(epoch.toLong()), kid, sha,
    )

    /** The MAC under the folder key of [epoch]. */
    fun mac(p: CryptoProvider, folderKey: ByteArray): ByteArray {
        val k = BackupKeys.backupMeta(p, folderKey)
        return p.hmacSha256(k, macInput()).also { k.fill(0) }
    }

    /** The `appProperties` of a new upload: `state=partial` until the checksum check (docs/15 §1.4 item 2). */
    fun appProperties(mac: ByteArray, deviceId: String): Map<String, String> = mapOf(
        DriveLayout.KIND to KIND_BACKUP,
        DriveLayout.STATE to DriveLayout.STATE_PARTIAL,
        DriveLayout.DEVICE to deviceId,
        DriveLayout.CREATED_AT to createdAt.toString(),
        HOUSES to houses.toString(),
        EPOCH to epoch.toString(),
        KID to Bytes.b64(kid),
        MAC to Bytes.b64(mac),
    )

    companion object {
        const val LABEL = "doorprints-backup-meta/1"
        const val KIND_BACKUP = "backup"
        const val HOUSES = "houses"
        const val EPOCH = "epoch"
        const val KID = "kid"
        const val MAC = "bm"
        const val KID_SIZE = 16

        /** 2⁵³ − 1, so the TypeScript twin reads every value exactly. */
        const val MAX_TIME = 9_007_199_254_740_991L
        const val MAX_HOUSES = 10_000_000

        private val DECIMAL = Regex("^(0|[1-9][0-9]{0,15})$")

        private fun decimal(text: String?, max: Long): Long? =
            text?.takeIf { DECIMAL.matches(it) }?.toLongOrNull()?.takeIf { it <= max }

        /** The unverified fields of [file] and its MAC, or null when they are missing or not canonical. */
        fun read(file: DriveFile): Pair<BackupMeta, ByteArray>? {
            val props = file.appProperties
            if (props[DriveLayout.KIND] != KIND_BACKUP) return null
            val createdAt = decimal(props[DriveLayout.CREATED_AT], MAX_TIME) ?: return null
            val houses = decimal(props[HOUSES], MAX_HOUSES.toLong())?.toInt() ?: return null
            val epoch = decimal(props[EPOCH], Int.MAX_VALUE.toLong())?.toInt()?.takeIf { it >= 1 } ?: return null
            val kid = props[KID]?.let { canonicalB64(it, KID_SIZE) } ?: return null
            val mac = props[MAC]?.let { canonicalB64(it, 32) } ?: return null
            val sha = file.sha256Checksum?.lowercase()?.takeIf { HEX64.matches(it) }?.let(Bytes::unhex) ?: return null
            return BackupMeta(createdAt, houses, epoch, kid, sha) to mac
        }

        private val HEX64 = Regex("^[0-9a-f]{64}$")

        private fun canonicalB64(text: String, size: Int): ByteArray? = Bytes.unb64(text, size)

        /** Whether [mac] is the MAC of [meta] under the folder key of its epoch; false when that epoch is unknown. */
        fun verify(p: CryptoProvider, keys: FolderKeys, meta: BackupMeta, mac: ByteArray): Boolean {
            val key = try {
                keys.folderKey(meta.epoch)
            } catch (_: KeysException) {
                null
            } ?: return false
            return try {
                constantTimeEquals(meta.mac(p, key), mac)
            } finally {
                key.fill(0)
            }
        }
    }
}
