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

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Why `keys.json` was refused or a change to it was not made. */
class KeysException(val kind: Kind, message: String) : Exception("keys ${kind.name}: $message") {
    /**
     * Every kind before the MAC and the pin are checked ([MALFORMED], [NOT_CANONICAL], [UNSUPPORTED_FORMAT],
     * [INVALID_ENTRY], [NOT_ENROLLED], [REVOKED], [UNWRAP_FAILED], [NO_RECOVERY], [RECOVERY_MISMATCH]) comes from
     * bytes anyone in the Google account can write: a caller reports it and stops, and **never acts destructively on it**
     * (no deleting keys, no re-enrolling, no "this device was revoked" clean-up) without a list that passed the pin.
     */
    enum class Kind {
        /** Not JSON, or not the `doorprints-keys/1` structure. */
        MALFORMED,

        /** The structure is right but the bytes are not its canonical form (see [CanonicalJson]). */
        NOT_CANONICAL,

        /** Another `format` (a newer app wrote it): "update the app". */
        UNSUPPORTED_FORMAT,

        /** An entry breaks a rule: a kid that is not its key's, a point not on the curve, a duplicate, a bad name. */
        INVALID_ENTRY,

        /** This device's key is not in the list (unauthenticated: it may be a forged list; see the note above). */
        NOT_ENROLLED,

        /** This device's key is in the revoked list (unauthenticated: it may be a forged list; see the note above). */
        REVOKED,

        /** This device's (or the recovery key's) wrap did not open. */
        UNWRAP_FAILED,

        /** The MAC does not verify under the folder key the wrap gave. */
        MAC_INVALID,

        /** No pin for this folder on this device: only [KeysFile.openFirstPin], the recovery key or a create may make one. */
        NOT_PINNED,

        /** A lower (epoch, revision) than this device already accepted (docs/15 §9.3, docs/02 T-T19). */
        ROLLED_BACK,

        /** A higher epoch whose chain does not end at the folder key this device pinned (a forged list, T-S13). */
        PIN_MISMATCH,

        /** The pinned epoch with another folder key, or the pinned revision with another body: two lists exist. */
        FORK_DETECTED,

        /** The watermark store changed under every try of its compare-and-set. */
        CONCURRENT_UPDATE,

        /** The list has no recovery public key (the person skipped the step). */
        NO_RECOVERY,

        /** The typed recovery key is not the one this list holds. */
        RECOVERY_MISMATCH,

        /** The recovery anchor does not open, or the chain does not lead to the folder key it holds (a forged list). */
        RECOVERY_ANCHOR_INVALID,

        /** A link of the epoch chain did not open. */
        CHAIN_BROKEN,

        /** The device to add is already listed (or is the recovery key). */
        ALREADY_ENROLLED,

        /** The device to revoke or the approver is not in the list. */
        NOT_LISTED,

        /** The change would leave no device and no recovery key that can open the folder. */
        LAST_RECIPIENT,

        /** The revision (2⁵³ − 1) or the epoch (2³¹ − 1) cannot go higher. */
        REVISION_LIMIT,
    }
}

enum class DevicePlatform(val wire: String) {
    ANDROID("android"),
    IOS("ios"),
    WEB("web"),
}

/** An HPKE base-mode wrap (docs/15 §9.2): `enc` (65 bytes) and the sealed 32-byte folder key (48 bytes). */
class HpkeWrap internal constructor(val enc: ByteArray, val ct: ByteArray)

/** An AES-256-GCM wrap with its own random nonce. */
class AeadWrap internal constructor(val nonce: ByteArray, val ct: ByteArray)

class DeviceEntry internal constructor(
    val kid: ByteArray,
    val name: String,
    val platform: DevicePlatform,
    val publicKey: ByteArray,
    val enrolledAt: Long,
    /** The kid of the device (or recovery key) that enrolled it; null for the first device. */
    val enrolledBy: ByteArray?,
    val wrap: HpkeWrap,
)

/**
 * The recovery key's entry. [anchor] is the folder key of [anchorEpoch] (the epoch the recovery key was made in) under
 * a key derived from the recovery key itself, so only its holder could have written it ([KeysFile.openWithRecovery]).
 */
class RecoveryEntry internal constructor(
    val kid: ByteArray,
    val publicKey: ByteArray,
    val anchorEpoch: Int,
    val anchor: AeadWrap,
    val wrap: HpkeWrap,
)

class RevokedEntry internal constructor(val kid: ByteArray, val revokedAt: Long, val revokedAtEpoch: Int)

/** The link of epoch [epoch]: the folder key of epoch − 1 under the chain-wrap key of [epoch] (docs/15 §9.1). */
class ChainLink internal constructor(val epoch: Int, val prev: AeadWrap)

/** The MACed body of `keys.json` (docs/15 §9.3). Times are milliseconds since 1970 (UTC). */
class KeysBody internal constructor(
    val revision: Long,
    val epoch: Int,
    val chain: List<ChainLink>,
    val devices: List<DeviceEntry>,
    val recovery: RecoveryEntry?,
    val revoked: List<RevokedEntry>,
) {
    fun device(kid: ByteArray): DeviceEntry? = devices.firstOrNull { it.kid.contentEquals(kid) }
    fun revokedEntry(kid: ByteArray): RevokedEntry? = revoked.firstOrNull { it.kid.contentEquals(kid) }

    internal fun json(): ByteArray {
        val j = CanonicalJson()
        j.raw("{\"revision\":").number(revision).raw(",\"epoch\":").number(epoch.toLong()).raw(",\"chain\":[")
        chain.forEachIndexed { i, c ->
            if (i > 0) j.raw(",")
            j.raw("{\"epoch\":").number(c.epoch.toLong())
                .raw(",\"nonce\":").string(Bytes.b64(c.prev.nonce))
                .raw(",\"ct\":").string(Bytes.b64(c.prev.ct)).raw("}")
        }
        j.raw("],\"devices\":[")
        devices.forEachIndexed { i, d ->
            if (i > 0) j.raw(",")
            j.raw("{\"kid\":").string(Bytes.b64(d.kid))
                .raw(",\"name\":").string(d.name)
                .raw(",\"platform\":").string(d.platform.wire)
                .raw(",\"publicKey\":").string(Bytes.b64(d.publicKey))
                .raw(",\"enrolledAt\":").number(d.enrolledAt)
                .raw(",\"enrolledBy\":")
            if (d.enrolledBy == null) j.raw("null") else j.string(Bytes.b64(d.enrolledBy))
            j.raw(",\"wrap\":")
            wrapJson(j, d.wrap)
            j.raw("}")
        }
        j.raw("],\"recovery\":")
        if (recovery == null) {
            j.raw("null")
        } else {
            j.raw("{\"kid\":").string(Bytes.b64(recovery.kid))
                .raw(",\"publicKey\":").string(Bytes.b64(recovery.publicKey))
                .raw(",\"anchorEpoch\":").number(recovery.anchorEpoch.toLong())
                .raw(",\"anchor\":{\"nonce\":").string(Bytes.b64(recovery.anchor.nonce))
                .raw(",\"ct\":").string(Bytes.b64(recovery.anchor.ct)).raw("}")
                .raw(",\"wrap\":")
            wrapJson(j, recovery.wrap)
            j.raw("}")
        }
        j.raw(",\"revoked\":[")
        revoked.forEachIndexed { i, r ->
            if (i > 0) j.raw(",")
            j.raw("{\"kid\":").string(Bytes.b64(r.kid))
                .raw(",\"revokedAt\":").number(r.revokedAt)
                .raw(",\"revokedAtEpoch\":").number(r.revokedAtEpoch.toLong()).raw("}")
        }
        j.raw("]}")
        return j.bytes()
    }

    private fun wrapJson(j: CanonicalJson, w: HpkeWrap) {
        j.raw("{\"enc\":").string(Bytes.b64(w.enc)).raw(",\"ct\":").string(Bytes.b64(w.ct)).raw("}")
    }
}

/**
 * An opened `keys.json`: its body, checked, and the folder keys of every epoch it chains to. A [FolderKeys] for
 * [Dpx.decrypt]; earlier epochs are unwrapped down the chain on first use and kept for the run.
 */
class OpenedKeys internal constructor(private val p: CryptoProvider, val body: KeysBody, currentKey: ByteArray) : FolderKeys {
    private val keys = HashMap<Int, ByteArray>().apply { put(body.epoch, currentKey.copyOf()) }

    val epoch: Int get() = body.epoch
    val revision: Long get() = body.revision

    /** The current epoch's folder key, for writing new files (a copy; the caller may zero it after use). */
    fun currentFolderKey(): ByteArray = keys.getValue(body.epoch).copyOf()

    override fun folderKey(epoch: Int): ByteArray? {
        if (epoch < 1 || epoch > body.epoch) return null
        var e = body.epoch
        while (e > epoch) {
            if (keys[e - 1] == null) {
                val link = body.chain.firstOrNull { it.epoch == e } ?: throw KeysException(KeysException.Kind.CHAIN_BROKEN, "no link for epoch $e")
                val prev = try {
                    p.aesGcmOpen(FolderKey.chainWrapKey(p, keys.getValue(e)), link.prev.nonce, WrapAad.chain(e), link.prev.ct)
                } catch (_: CryptoException) {
                    throw KeysException(KeysException.Kind.CHAIN_BROKEN, "link of epoch $e")
                }
                if (prev.size != FolderKey.SIZE) throw KeysException(KeysException.Kind.CHAIN_BROKEN, "link of epoch $e")
                keys[e - 1] = prev
            }
            e--
        }
        return keys.getValue(epoch).copyOf()
    }

    /** Best-effort: overwrites the folder keys held by this object. */
    fun wipe() {
        for (k in keys.values) k.fill(0)
        keys.clear()
    }
}

/**
 * `keys.json` (docs/15 §9.3..§9.5, §9.9): its canonical form, its MAC, and the changes a device makes to it. No I/O:
 * the caller reads and writes the file through Drive.
 *
 * **What makes a list trusted is the device's pin, not the MAC** ([KeysGuard]): HPKE base mode does not say who
 * wrapped a key, so anyone in the Google account can wrap a folder key of their own to every listed public key and
 * MAC the result. [open] accepts a list only if it leads to the pinned key; the first pin comes from [openFirstPin]
 * (S4b-BL-126's enrolment), [openWithRecovery] (the recovery anchor) or [KeysGuard.pinCreated].
 *
 * **Writing** (S4b-BL-126/-118): re-read the head of `keys.json` and open it just before writing, build the change on
 * it, upload, read it back, and call [KeysGuard.acceptWritten] only when what Drive returns is the list written; two
 * writers at once make two lists of one revision, which every device then refuses as [KeysException.Kind.FORK_DETECTED].
 *
 * ```
 * {"format":"doorprints-keys/1","body":{…},"mac":B64(32)}
 * body = {"revision":R,"epoch":E,
 *         "chain":[{"epoch":2,"nonce":B64(12),"ct":B64(48)},…,{"epoch":E,…}],
 *         "devices":[{"kid":B64(16),"name":"…","platform":"android|ios|web","publicKey":B64(65),
 *                     "enrolledAt":ms,"enrolledBy":B64(16)|null,"wrap":{"enc":B64(65),"ct":B64(48)}},…],
 *         "recovery":{"kid":B64(16),"publicKey":B64(65),"anchorEpoch":E0,"anchor":{"nonce":B64(12),"ct":B64(48)},
 *                     "wrap":{…}}|null,
 *         "revoked":[{"kid":B64(16),"revokedAt":ms,"revokedAtEpoch":N},…]}
 * mac    = HMAC-SHA-256(FolderKey.macKey(folder key of epoch E), "doorprints-keys/1" ‖ 0x00 ‖ canonical body)
 * wrap   = HPKE base (RFC 9180, DHKEM(P-256), HKDF-SHA256, AES-256-GCM) to the entry's public key,
 *          info "doorprints/dpx1/wrap", aad WrapAad.folderKey(E, entry kid), plaintext the 32-byte folder key
 * anchor = AES-256-GCM(HKDF(recovery key bytes, "doorprints/dpx1/recovery-anchor"), random nonce,
 *          aad WrapAad.recoveryAnchor(E0, recovery kid), plaintext the folder key of epoch E0)
 * ```
 */
class KeysFile(private val p: CryptoProvider) {
    private val hpke = Hpke(p)

    /** A device to list: its public key (65 bytes), its name (1..64 characters, no control characters), platform. */
    class NewDevice(val publicKey: ByteArray, val name: String, val platform: DevicePlatform)

    /** A new `keys.json` and the state it opens to. */
    class Written(val bytes: ByteArray, val opened: OpenedKeys)

    /**
     * The first connect to an empty folder: a new random folder key (epoch 1, revision 1) wrapped to [device] and, if
     * the person saved one, to [recovery] (whose anchor it writes). After the upload: [KeysGuard.pinCreated].
     */
    fun createFirstDevice(device: NewDevice, recovery: RecoveryKey?, now: Long): Written {
        checkTime(now)
        val folderKey = p.randomBytes(FolderKey.SIZE)
        try {
            val pub = validPublic(device.publicKey)
            val kid = kidOf(p, pub)
            val entry = DeviceEntry(kid, validName(device.name), device.platform, pub, now, null, wrap(pub, kid, 1, folderKey))
            val rec = recovery?.let { newRecoveryEntry(it, 1, folderKey) }
            if (rec != null && rec.kid.contentEquals(kid)) throw KeysException(KeysException.Kind.INVALID_ENTRY, "recovery key equals the device key")
            return write(KeysBody(1, 1, emptyList(), listOf(entry), rec, emptyList()), folderKey)
        } finally {
            folderKey.fill(0)
        }
    }

    /** Opens [file] with this device's key: MAC, then the pin ([KeysGuard]); no pin is [KeysException.Kind.NOT_PINNED]. */
    fun open(file: ByteArray, device: P256PrivateKey, guard: KeysGuard): OpenedKeys =
        openDevice(file, device, guard, KeysGuard.Trust.PINNED, null)

    /**
     * The **first pin** (S4b-BL-126): opens [file] with this device's key only if its folder key equals
     * [trustedFolderKey], the key this device received over an authenticated channel (the QR code's HPKE PSK wrap),
     * then pins it. Never call it with a key read from Drive.
     */
    fun openFirstPin(file: ByteArray, device: P256PrivateKey, guard: KeysGuard, trustedFolderKey: ByteArray): OpenedKeys =
        openDevice(file, device, guard, KeysGuard.Trust.FIRST_PIN, trustedFolderKey)

    private fun openDevice(file: ByteArray, device: P256PrivateKey, guard: KeysGuard, trust: KeysGuard.Trust, trusted: ByteArray?): OpenedKeys {
        val (body, mac) = parse(file)
        val pub = device.publicKey
        val kid = kidOf(p, pub)
        if (body.revokedEntry(kid) != null) throw KeysException(KeysException.Kind.REVOKED, "this device is in the revoked list")
        val entry = body.device(kid)?.takeIf { it.publicKey.contentEquals(pub) }
            ?: throw KeysException(KeysException.Kind.NOT_ENROLLED, "this device is not in the list")
        val folderKey = unwrap(entry.wrap, device, body.epoch, kid)
        try {
            checkMac(body, mac, folderKey)
            if (trusted != null && !constantTimeEquals(folderKey, trusted)) {
                throw KeysException(KeysException.Kind.PIN_MISMATCH, "not the folder key received at enrolment")
            }
            val opened = OpenedKeys(p, body, folderKey)
            guard.accept(opened, trust)
            return opened
        } finally {
            folderKey.fill(0)
        }
    }

    /**
     * Opens [file] with the typed recovery key (docs/15 §9.4): the wrap, the MAC, then the **anchor**: the chain from the
     * current epoch down to the anchor's epoch must end at the folder key the anchor holds, which only the recovery
     * key's holder could have written. A device with a pin also checks it; one without is pinned by this.
     */
    fun openWithRecovery(file: ByteArray, recovery: RecoveryKey, guard: KeysGuard): OpenedKeys {
        val (body, mac) = parse(file)
        val listed = body.recovery ?: throw KeysException(KeysException.Kind.NO_RECOVERY, "no recovery key in the list")
        val pair = recovery.keyPair(p)
        if (!listed.publicKey.contentEquals(pair.publicKey)) throw KeysException(KeysException.Kind.RECOVERY_MISMATCH, "another recovery key")
        val folderKey = unwrap(listed.wrap, pair, body.epoch, listed.kid)
        try {
            checkMac(body, mac, folderKey)
            val opened = OpenedKeys(p, body, folderKey)
            val bottom = try {
                opened.folderKey(listed.anchorEpoch)
            } catch (_: KeysException) {
                null
            } ?: throw KeysException(KeysException.Kind.RECOVERY_ANCHOR_INVALID, "the chain does not reach the anchor")
            val anchored = try {
                p.aesGcmOpen(recovery.anchorKey(p), listed.anchor.nonce, WrapAad.recoveryAnchor(listed.anchorEpoch, listed.kid), listed.anchor.ct)
            } catch (_: CryptoException) {
                throw KeysException(KeysException.Kind.RECOVERY_ANCHOR_INVALID, "the anchor does not open")
            }
            val same = constantTimeEquals(anchored, bottom)
            anchored.fill(0)
            bottom.fill(0)
            if (!same) throw KeysException(KeysException.Kind.RECOVERY_ANCHOR_INVALID, "the chain does not end at the anchored key")
            guard.accept(opened, KeysGuard.Trust.RECOVERY_ANCHOR)
            return opened
        } finally {
            folderKey.fill(0)
        }
    }

    /**
     * Lists a new device (S4b-BL-126 brings it here, by QR code or the code fallback, after the approver's L2
     * check): the current folder key HPKE-wrapped for exactly [device]'s public key; revision + 1, same epoch.
     */
    fun addDevice(opened: OpenedKeys, approverKid: ByteArray, device: NewDevice, now: Long): Written {
        checkTime(now)
        val body = opened.body
        if (body.device(approverKid) == null && body.recovery?.kid?.contentEquals(approverKid) != true) {
            throw KeysException(KeysException.Kind.NOT_LISTED, "the approver is not in the list")
        }
        val pub = validPublic(device.publicKey)
        val kid = kidOf(p, pub)
        if (body.revokedEntry(kid) != null) throw KeysException(KeysException.Kind.REVOKED, "a revoked key cannot be listed again")
        if (body.device(kid) != null || body.recovery?.kid?.contentEquals(kid) == true) {
            throw KeysException(KeysException.Kind.ALREADY_ENROLLED, "already listed")
        }
        val folderKey = opened.currentFolderKey()
        try {
            val entry = DeviceEntry(kid, validName(device.name), device.platform, pub, now, approverKid.copyOf(), wrap(pub, kid, body.epoch, folderKey))
            return write(KeysBody(nextRevision(body), body.epoch, body.chain, body.devices + entry, body.recovery, body.revoked), folderKey)
        } finally {
            folderKey.fill(0)
        }
    }

    /**
     * A new epoch (docs/15 §9.5 iv): a new random folder key, chained to the current one, wrapped to every remaining
     * device and to the recovery public key (nobody types it; its anchor stays). [revokeKid] moves that device to the
     * revoked list (`revokedAt` = [now], `revokedAtEpoch` = the new epoch). [newRecovery] replaces the recovery key
     * (*Make a recovery key*): a new anchor at the new epoch, and the old recovery kid joins the revoked list, so the old
     * key opens nothing written afterwards.
     */
    fun newEpoch(opened: OpenedKeys, now: Long, revokeKid: ByteArray? = null, newRecovery: RecoveryKey? = null): Written {
        checkTime(now)
        val body = opened.body
        if (body.epoch == Dpx.MAX_EPOCH) throw KeysException(KeysException.Kind.REVISION_LIMIT, "epoch limit")
        if (revokeKid != null && body.device(revokeKid) == null) throw KeysException(KeysException.Kind.NOT_LISTED, "no such device")
        val revision = nextRevision(body)
        val remaining = body.devices.filter { revokeKid == null || !it.kid.contentEquals(revokeKid) }
        val epoch = body.epoch + 1
        val oldKey = opened.currentFolderKey()
        val newKey = p.randomBytes(FolderKey.SIZE)
        try {
            val chainNonce = p.randomBytes(12)
            val link = ChainLink(epoch, AeadWrap(chainNonce, p.aesGcmSeal(FolderKey.chainWrapKey(p, newKey), chainNonce, WrapAad.chain(epoch), oldKey)))
            val devices = remaining.map { DeviceEntry(it.kid, it.name, it.platform, it.publicKey, it.enrolledAt, it.enrolledBy, wrap(it.publicKey, it.kid, epoch, newKey)) }
            val old = body.recovery
            val recovery = when {
                newRecovery != null -> newRecoveryEntry(newRecovery, epoch, newKey)
                old != null -> RecoveryEntry(old.kid, old.publicKey, old.anchorEpoch, old.anchor, wrap(old.publicKey, old.kid, epoch, newKey))
                else -> null
            }
            if (recovery != null && (devices.any { it.kid.contentEquals(recovery.kid) } || body.revokedEntry(recovery.kid) != null)) {
                throw KeysException(KeysException.Kind.INVALID_ENTRY, "recovery key equals a device key or a revoked one")
            }
            if (devices.isEmpty() && recovery == null) throw KeysException(KeysException.Kind.LAST_RECIPIENT, "nobody could open the folder")
            var revoked = body.revoked
            if (revokeKid != null) revoked = revoked + RevokedEntry(revokeKid.copyOf(), now, epoch)
            if (newRecovery != null && old != null && !old.kid.contentEquals(recovery!!.kid)) revoked = revoked + RevokedEntry(old.kid, now, epoch)
            return write(KeysBody(revision, epoch, body.chain + link, devices, recovery, revoked), newKey)
        } finally {
            oldKey.fill(0)
            newKey.fill(0)
        }
    }

    private fun nextRevision(body: KeysBody): Long {
        if (body.revision >= CanonicalJson.MAX_SAFE) throw KeysException(KeysException.Kind.REVISION_LIMIT, "revision limit")
        return body.revision + 1
    }

    /** The recovery entry of a new recovery key at [epoch]: the anchor first, then the wrap (the order of random draws). */
    private fun newRecoveryEntry(recovery: RecoveryKey, epoch: Int, folderKey: ByteArray): RecoveryEntry {
        val pub = validPublic(recovery.keyPair(p).publicKey)
        val kid = kidOf(p, pub)
        val nonce = p.randomBytes(12)
        val anchor = AeadWrap(nonce, p.aesGcmSeal(recovery.anchorKey(p), nonce, WrapAad.recoveryAnchor(epoch, kid), folderKey))
        return RecoveryEntry(kid, pub, epoch, anchor, wrap(pub, kid, epoch, folderKey))
    }

    private fun wrap(pub: ByteArray, kid: ByteArray, epoch: Int, folderKey: ByteArray): HpkeWrap {
        val sealed = hpke.seal(pub, WrapAad.HPKE_INFO, WrapAad.folderKey(epoch, kid), folderKey)
        return HpkeWrap(sealed.enc, sealed.ciphertext)
    }

    private fun unwrap(w: HpkeWrap, key: P256PrivateKey, epoch: Int, kid: ByteArray): ByteArray {
        val folderKey = try {
            hpke.open(w.enc, key, WrapAad.HPKE_INFO, WrapAad.folderKey(epoch, kid), w.ct)
        } catch (_: CryptoException) {
            throw KeysException(KeysException.Kind.UNWRAP_FAILED, "wrap did not open")
        }
        if (folderKey.size != FolderKey.SIZE) throw KeysException(KeysException.Kind.UNWRAP_FAILED, "wrap length")
        return folderKey
    }

    private fun checkMac(body: KeysBody, mac: ByteArray, folderKey: ByteArray) {
        if (!constantTimeEquals(mac(folderKey, body), mac)) throw KeysException(KeysException.Kind.MAC_INVALID, "MAC")
    }

    private fun mac(folderKey: ByteArray, body: KeysBody): ByteArray {
        val k = FolderKey.macKey(p, folderKey)
        return p.hmacSha256(k, Bytes.concat(Bytes.utf8(FORMAT), byteArrayOf(0), body.json())).also { k.fill(0) }
    }

    private fun write(body: KeysBody, folderKey: ByteArray): Written {
        checkRules(body)
        val bytes = encode(body, mac(folderKey, body))
        return Written(bytes, OpenedKeys(p, body, folderKey))
    }

    private fun encode(body: KeysBody, mac: ByteArray): ByteArray = Bytes.concat(
        CanonicalJson().raw("{\"format\":").string(FORMAT).raw(",\"body\":").bytes(),
        body.json(),
        CanonicalJson().raw(",\"mac\":").string(Bytes.b64(mac)).raw("}").bytes(),
    )

    /** Parses and checks the structure and every entry; the MAC is checked by the caller once it has the key. */
    internal fun parse(file: ByteArray): Pair<KeysBody, ByteArray> {
        if (file.size > MAX_FILE) throw KeysException(KeysException.Kind.MALFORMED, "too large")
        val root = CanonicalJson.parse(file) as? JsonObject ?: throw KeysException(KeysException.Kind.MALFORMED, "not a JSON object")
        val format = JsonRead.string(root["format"]) ?: malformed("format")
        if (format != FORMAT) throw KeysException(KeysException.Kind.UNSUPPORTED_FORMAT, "format")
        JsonRead.obj(root, "format", "body", "mac") ?: malformed("fields")
        val mac = JsonRead.string(root["mac"])?.let { Bytes.unb64(it, 32) } ?: malformed("mac")
        val body = parseBody(root["body"])
        if (!encode(body, mac).contentEquals(file)) throw KeysException(KeysException.Kind.NOT_CANONICAL, "not canonical")
        checkRules(body)
        return body to mac
    }

    private fun malformed(what: String): Nothing = throw KeysException(KeysException.Kind.MALFORMED, what)

    private fun b64(e: JsonElement?, size: Int, what: String): ByteArray =
        JsonRead.string(e)?.let { Bytes.unb64(it, size) } ?: malformed(what)

    private fun hpkeWrap(e: JsonElement?): HpkeWrap {
        val o = JsonRead.obj(e, "enc", "ct") ?: malformed("wrap")
        return HpkeWrap(b64(o["enc"], 65, "wrap.enc"), b64(o["ct"], 48, "wrap.ct"))
    }

    private fun parseBody(e: JsonElement?): KeysBody {
        val o = JsonRead.obj(e, "revision", "epoch", "chain", "devices", "recovery", "revoked") ?: malformed("body")
        val revision = JsonRead.long(o["revision"], 1, CanonicalJson.MAX_SAFE) ?: malformed("revision")
        val epoch = JsonRead.long(o["epoch"], 1, Dpx.MAX_EPOCH.toLong())?.toInt() ?: malformed("epoch")
        val chain = (JsonRead.array(o["chain"]) ?: malformed("chain")).map {
            val c = JsonRead.obj(it, "epoch", "nonce", "ct") ?: malformed("chain entry")
            ChainLink(
                JsonRead.long(c["epoch"], 2, Dpx.MAX_EPOCH.toLong())?.toInt() ?: malformed("chain.epoch"),
                AeadWrap(b64(c["nonce"], 12, "chain.nonce"), b64(c["ct"], 48, "chain.ct")),
            )
        }
        val devices = (JsonRead.array(o["devices"]) ?: malformed("devices")).map {
            val d = JsonRead.obj(it, "kid", "name", "platform", "publicKey", "enrolledAt", "enrolledBy", "wrap") ?: malformed("device")
            val platform = JsonRead.string(d["platform"]).let { w -> DevicePlatform.entries.firstOrNull { it.wire == w } } ?: malformed("platform")
            DeviceEntry(
                kid = b64(d["kid"], 16, "kid"),
                name = JsonRead.string(d["name"]) ?: malformed("name"),
                platform = platform,
                publicKey = b64(d["publicKey"], 65, "publicKey"),
                enrolledAt = JsonRead.long(d["enrolledAt"], 0, CanonicalJson.MAX_SAFE) ?: malformed("enrolledAt"),
                enrolledBy = if (JsonRead.isNull(d["enrolledBy"])) null else b64(d["enrolledBy"], 16, "enrolledBy"),
                wrap = hpkeWrap(d["wrap"]),
            )
        }
        val recovery = if (JsonRead.isNull(o["recovery"])) {
            null
        } else {
            val r = JsonRead.obj(o["recovery"], "kid", "publicKey", "anchorEpoch", "anchor", "wrap") ?: malformed("recovery")
            val a = JsonRead.obj(r["anchor"], "nonce", "ct") ?: malformed("recovery.anchor")
            RecoveryEntry(
                b64(r["kid"], 16, "recovery.kid"),
                b64(r["publicKey"], 65, "recovery.publicKey"),
                JsonRead.long(r["anchorEpoch"], 1, Dpx.MAX_EPOCH.toLong())?.toInt() ?: malformed("recovery.anchorEpoch"),
                AeadWrap(b64(a["nonce"], 12, "recovery.anchor.nonce"), b64(a["ct"], 48, "recovery.anchor.ct")),
                hpkeWrap(r["wrap"]),
            )
        }
        val revoked = (JsonRead.array(o["revoked"]) ?: malformed("revoked")).map {
            val r = JsonRead.obj(it, "kid", "revokedAt", "revokedAtEpoch") ?: malformed("revoked entry")
            RevokedEntry(
                b64(r["kid"], 16, "revoked.kid"),
                JsonRead.long(r["revokedAt"], 0, CanonicalJson.MAX_SAFE) ?: malformed("revokedAt"),
                JsonRead.long(r["revokedAtEpoch"], 2, Dpx.MAX_EPOCH.toLong())?.toInt() ?: malformed("revokedAtEpoch"),
            )
        }
        return KeysBody(revision, epoch, chain, devices, recovery, revoked)
    }

    private fun checkRules(b: KeysBody) {
        fun bad(why: String): Nothing = throw KeysException(KeysException.Kind.INVALID_ENTRY, why)
        if (b.chain.size != b.epoch - 1) bad("chain length")
        b.chain.forEachIndexed { i, c -> if (c.epoch != i + 2) bad("chain order") }
        if (b.devices.isEmpty() && b.recovery == null) bad("no recipient")
        if (b.devices.size > MAX_DEVICES || b.revoked.size > MAX_REVOKED) bad("too many entries")
        val kids = ArrayList<ByteArray>()
        for (d in b.devices) {
            if (!d.kid.contentEquals(kidOf(p, validPublic(d.publicKey)))) bad("device kid")
            if (validNameOrNull(d.name) == null) bad("device name")
            if (d.enrolledBy != null && d.enrolledBy.contentEquals(d.kid)) bad("enrolled by itself")
            kids += d.kid
        }
        b.recovery?.let { r ->
            if (!r.kid.contentEquals(kidOf(p, validPublic(r.publicKey)))) bad("recovery kid")
            if (r.anchorEpoch > b.epoch) bad("recovery anchor in a future epoch")
            kids += r.kid
        }
        for (r in b.revoked) {
            if (r.revokedAtEpoch > b.epoch) bad("revoked in a future epoch")
            kids += r.kid
        }
        for (i in kids.indices) for (j in i + 1 until kids.size) if (kids[i].contentEquals(kids[j])) bad("duplicate kid")
        // enrolledBy names a listed device, the recovery key or a revoked kid (an old device or an old recovery key).
        for (d in b.devices) {
            val by = d.enrolledBy ?: continue
            if (kids.none { it.contentEquals(by) }) bad("enrolled by an unknown kid")
        }
    }

    private fun validPublic(pub: ByteArray): ByteArray = try {
        p.p256ValidatePublic(pub)
    } catch (_: CryptoException) {
        throw KeysException(KeysException.Kind.INVALID_ENTRY, "public key")
    }

    private fun validName(name: String): String = validNameOrNull(name) ?: throw KeysException(KeysException.Kind.INVALID_ENTRY, "device name")

    private fun checkTime(now: Long) {
        if (now < 0 || now > CanonicalJson.MAX_SAFE) throw KeysException(KeysException.Kind.INVALID_ENTRY, "time")
    }

    companion object {
        const val FORMAT = "doorprints-keys/1"
        const val MAX_FILE = 256 * 1024
        const val MAX_DEVICES = 64
        const val MAX_REVOKED = 1024

        /** 1..64 UTF-16 units, no control characters, no unpaired surrogate. */
        internal fun validNameOrNull(name: String): String? {
            if (name.isEmpty() || name.length > 64) return null
            var i = 0
            while (i < name.length) {
                val c = name[i]
                if (c < ' ' || c in '\u007F'..'\u009F') return null
                if (c.isHighSurrogate()) {
                    if (i + 1 >= name.length || !name[i + 1].isLowSurrogate()) return null
                    i += 2
                    continue
                }
                if (c.isLowSurrogate()) return null
                i++
            }
            return name
        }
    }
}
