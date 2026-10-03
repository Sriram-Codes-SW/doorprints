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
 * HPKE (RFC 9180) with DHKEM(P-256, HKDF-SHA256) (KEM 0x0010) and HKDF-SHA256 (KDF 0x0001), written once over a
 * [CryptoProvider] (docs/15 §9.2). Doorprints uses AES-256-GCM (AEAD 0x0002, [Aead.AES_256_GCM]); AES-128-GCM
 * (0x0001) exists only so the official RFC 9180 Appendix A.3 vectors can be run.
 *
 * Only the base mode is public ([setupBaseS], [setupBaseR], [seal], [open]). The key schedule already takes the mode,
 * `psk` and `psk_id` (RFC 9180 §5.1), so the PSK mode of the QR enrolment (S4b-BL-126, docs/15 §9.5 i) is a new
 * public pair of functions over [keySchedule], with no change to it.
 */
class Hpke internal constructor(private val p: CryptoProvider, internal val aead: Aead) {

    /** HPKE for Doorprints' suite (AES-256-GCM). */
    constructor(p: CryptoProvider) : this(p, Aead.AES_256_GCM)

    /** AES-128-GCM exists only for the RFC 9180 A.3.1 vector; nothing outside the tests can pick it. */
    internal enum class Aead(val id: Int, val keySize: Int) {
        /** For RFC 9180's A.3 test vectors only. */
        AES_128_GCM(0x0001, 16),
        AES_256_GCM(0x0002, 32),
    }

    /** What [keySchedule] makes (RFC 9180 §5.1); internal so the vector tests can check each value. */
    internal class Schedule(val key: ByteArray, val baseNonce: ByteArray, val exporterSecret: ByteArray)

    /** An encryption context; one per [setupBaseS]/[setupBaseR], not shared between threads. */
    class Context internal constructor(
        private val p: CryptoProvider,
        private val key: AesKey,
        private val baseNonce: ByteArray,
    ) {
        private var seq = 0L

        private fun nonce(): ByteArray {
            // seq < 2^63 here (the check below), so 12 bytes always hold it.
            val s = Bytes.concat(ByteArray(4), Bytes.i2osp(seq, 8))
            return Bytes.xor(baseNonce, s)
        }

        private fun increment() {
            if (seq == Long.MAX_VALUE) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "HPKE message limit")
            seq++
        }

        fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
            val ct = p.aesGcmSeal(key, nonce(), aad, plaintext)
            increment()
            return ct
        }

        fun open(aad: ByteArray, ciphertext: ByteArray): ByteArray {
            val pt = p.aesGcmOpen(key, nonce(), aad, ciphertext)
            increment()
            return pt
        }
    }

    /** The sender's side: the encapsulated key `enc` (65 bytes) and its context. */
    class Sender(val enc: ByteArray, val context: Context)

    /** A single-shot result: `enc` and the ciphertext. */
    class Sealed(val enc: ByteArray, val ciphertext: ByteArray)

    private val hkdf = Hkdf(p)
    private val kemSuiteId = Bytes.concat(Bytes.utf8("KEM"), Bytes.i2osp(KEM_ID.toLong(), 2))
    private val suiteId = Bytes.concat(
        Bytes.utf8("HPKE"),
        Bytes.i2osp(KEM_ID.toLong(), 2),
        Bytes.i2osp(KDF_ID.toLong(), 2),
        Bytes.i2osp(aead.id.toLong(), 2),
    )

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        hkdf.extract(salt, Bytes.concat(HPKE_V1, suite, Bytes.utf8(label), ikm))

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray =
        hkdf.expand(prk, Bytes.concat(Bytes.i2osp(length.toLong(), 2), HPKE_V1, suite, Bytes.utf8(label), info), length)

    /** DeriveKeyPair (RFC 9180 §7.1.3, the NIST curves' rejection sampling; P-256's bitmask is 0xFF). */
    fun deriveKeyPair(ikm: ByteArray): P256PrivateKey {
        if (ikm.size < N_SK) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "DeriveKeyPair ikm shorter than Nsk")
        val dkpPrk = labeledExtract(kemSuiteId, ByteArray(0), "dkp_prk", ikm)
        for (counter in 0..255) {
            val candidate = labeledExpand(kemSuiteId, dkpPrk, "candidate", Bytes.i2osp(counter.toLong(), 1), N_SK)
            // bitmask 0xFF for P-256: candidate[0] &= 0xFF changes nothing.
            if (P256Scalar.isValid(candidate)) return p.p256FromScalar(candidate)
        }
        throw CryptoException(CryptoException.Kind.INVALID_KEY, "DeriveKeyPair found no scalar")
    }

    /**
     * GenerateKeyPair: the platform's own key generation ([CryptoProvider.p256Generate]), so a wrap never depends on
     * importing a raw scalar. The vectors inject their ephemeral key instead (the `internal` overloads), and
     * `FakeRandomProvider` makes `p256Generate` a DeriveKeyPair over its stream.
     */
    fun generateKeyPair(): P256PrivateKey = p.p256Generate()

    private fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val eaePrk = labeledExtract(kemSuiteId, ByteArray(0), "eae_prk", dh)
        return labeledExpand(kemSuiteId, eaePrk, "shared_secret", kemContext, N_SECRET)
    }

    /** Encap (RFC 9180 §4.1) with the given ephemeral key; returns `shared_secret` and `enc`. */
    internal fun encap(pkR: ByteArray, ephemeral: P256PrivateKey): Pair<ByteArray, ByteArray> {
        val recipient = p.p256ValidatePublic(pkR)
        val dh = p.p256Agree(ephemeral, recipient)
        val enc = ephemeral.publicKey
        return extractAndExpand(dh, Bytes.concat(enc, recipient)) to enc
    }

    /** Decap (RFC 9180 §4.1); `enc` is validated as a point first. */
    internal fun decap(enc: ByteArray, skR: P256PrivateKey): ByteArray {
        if (enc.size != N_ENC) throw CryptoException(CryptoException.Kind.INVALID_KEY, "enc length")
        val pkE = p.p256ValidatePublic(enc)
        val dh = p.p256Agree(skR, pkE)
        return extractAndExpand(dh, Bytes.concat(pkE, skR.publicKey))
    }

    /**
     * KeySchedule (RFC 9180 §5.1) for any mode; VerifyPSKInputs included. Base mode passes an empty [psk] and
     * [pskId].
     */
    internal fun keySchedule(mode: Int, sharedSecret: ByteArray, info: ByteArray, psk: ByteArray, pskId: ByteArray): Schedule {
        val gotPsk = psk.isNotEmpty()
        val gotPskId = pskId.isNotEmpty()
        if (gotPsk != gotPskId) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "inconsistent PSK inputs")
        if (gotPsk && (mode == MODE_BASE || mode == MODE_AUTH)) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "PSK input provided when not needed")
        if (!gotPsk && (mode == MODE_PSK || mode == MODE_AUTH_PSK)) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "missing required PSK input")
        if (gotPsk && psk.size < 32) throw CryptoException(CryptoException.Kind.INVALID_INPUT, "PSK shorter than 32 bytes")

        val pskIdHash = labeledExtract(suiteId, ByteArray(0), "psk_id_hash", pskId)
        val infoHash = labeledExtract(suiteId, ByteArray(0), "info_hash", info)
        val context = Bytes.concat(byteArrayOf(mode.toByte()), pskIdHash, infoHash)
        val secret = labeledExtract(suiteId, sharedSecret, "secret", psk)
        return Schedule(
            key = labeledExpand(suiteId, secret, "key", context, aead.keySize),
            baseNonce = labeledExpand(suiteId, secret, "base_nonce", context, N_N),
            exporterSecret = labeledExpand(suiteId, secret, "exp", context, N_H),
        )
    }

    internal fun context(s: Schedule) = Context(p, p.aesKey(s.key), s.baseNonce)

    /** SetupBaseS with a fresh ephemeral key ([generateKeyPair]). */
    fun setupBaseS(pkR: ByteArray, info: ByteArray): Sender = setupBaseS(pkR, info, generateKeyPair())

    internal fun setupBaseS(pkR: ByteArray, info: ByteArray, ephemeral: P256PrivateKey): Sender {
        val (shared, enc) = encap(pkR, ephemeral)
        return Sender(enc, context(keySchedule(MODE_BASE, shared, info, ByteArray(0), ByteArray(0))))
    }

    fun setupBaseR(enc: ByteArray, skR: P256PrivateKey, info: ByteArray): Context =
        context(keySchedule(MODE_BASE, decap(enc, skR), info, ByteArray(0), ByteArray(0)))

    /** Single-shot base-mode seal (RFC 9180 §6.1). */
    fun seal(pkR: ByteArray, info: ByteArray, aad: ByteArray, plaintext: ByteArray): Sealed {
        val s = setupBaseS(pkR, info)
        return Sealed(s.enc, s.context.seal(aad, plaintext))
    }

    /** Single-shot base-mode open; a wrong key, `enc`, info, AAD or byte throws [CryptoException]. */
    fun open(enc: ByteArray, skR: P256PrivateKey, info: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        setupBaseR(enc, skR, info).open(aad, ciphertext)

    companion object {
        const val KEM_ID = 0x0010
        const val KDF_ID = 0x0001
        const val MODE_BASE = 0x00
        const val MODE_PSK = 0x01
        const val MODE_AUTH = 0x02
        const val MODE_AUTH_PSK = 0x03
        const val N_SECRET = 32
        const val N_ENC = 65
        const val N_SK = 32
        const val N_N = 12
        const val N_H = 32
        private val HPKE_V1 = "HPKE-v1".encodeToByteArray()
    }
}
