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

/** Why a typed recovery key was refused (the screen words come with S4b-BL-126). */
class RecoveryKeyException(val reason: Reason, message: String) : CryptoException(CryptoException.Kind.INVALID_INPUT, message) {
    enum class Reason {
        /** Not 27 symbols once spaces and hyphens are taken out. */
        WRONG_LENGTH,

        /** A character that is not a Crockford base32 symbol (or, last, a check symbol). */
        INVALID_CHARACTER,

        /** The first symbol is above 7: more than 128 bits, so not a key Doorprints made. */
        OUT_OF_RANGE,

        /** The check symbol does not match: a symbol was mistyped or two were swapped. */
        CHECK_MISMATCH,
    }
}

/**
 * The recovery key (docs/15 §9.4): 128 random bits, written as 26 Crockford base32 symbols (the 128-bit value as a
 * big-endian number after two zero bits, so the first symbol is 0..7) plus Crockford's check symbol (the value mod 37, from
 * `0-9 A-Z` without I, L, O, U, then `* ~ $ = U`), shown in groups of four: `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXX`.
 * The app never stores it; [bytes] lives only while it is shown or typed.
 */
class RecoveryKey private constructor(private val raw: ByteArray) {
    /** The 16 key bytes (a copy). */
    val bytes: ByteArray get() = raw.copyOf()

    /** The 27 symbols without separators. */
    val symbols: String get() = encodeSymbols(raw)

    /** The form shown and printed. */
    val display: String get() = symbols.chunked(4).joinToString("-")

    override fun toString() = "RecoveryKey(…)"

    /**
     * The recovery key pair (docs/15 §9.4, FIPS 186-5 A.2.1): `seed = HKDF-SHA-256(ikm = the 16 key bytes,
     * salt = "doorprints/dpx1/recovery", info = "p256", L = 48)`, `d = (seed mod (n − 1)) + 1`, the public key from
     * the platform ([CryptoProvider.p256FromScalar]).
     */
    fun keyPair(p: CryptoProvider): P256PrivateKey {
        val d = scalar(p)
        return try {
            p.p256FromScalar(d)
        } finally {
            d.fill(0)
        }
    }

    /** The key of the recovery anchor: `HKDF-SHA-256(ikm = the 16 key bytes, salt = "", info = "doorprints/dpx1/recovery-anchor")`. */
    internal fun anchorKey(p: CryptoProvider): AesKey {
        val k = Hkdf(p).derive(ByteArray(0), raw, Bytes.utf8(ANCHOR_INFO), 32)
        return p.aesKey(k).also { k.fill(0) }
    }

    internal fun scalar(p: CryptoProvider): ByteArray {
        val seed = Hkdf(p).derive(Bytes.utf8(SALT), raw, Bytes.utf8(INFO), SEED_LEN)
        return P256Scalar.reduceToScalar(seed).also { seed.fill(0) }
    }

    companion object {
        const val SIZE = 16
        const val SYMBOLS = 27
        private const val SALT = "doorprints/dpx1/recovery"
        private const val INFO = "p256"
        private const val ANCHOR_INFO = "doorprints/dpx1/recovery-anchor"
        private const val SEED_LEN = 48
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CHECK_ALPHABET = ALPHABET + "*~$=U"

        fun generate(p: CryptoProvider): RecoveryKey = fromBytes(p.randomBytes(SIZE))

        fun fromBytes(bytes: ByteArray): RecoveryKey {
            require(bytes.size == SIZE) { "a recovery key is 16 bytes" }
            return RecoveryKey(bytes.copyOf())
        }

        /**
         * Reads a typed key: case, spaces and hyphens do not matter, and Crockford's look-alikes are read as he
         * defines them (O as 0, I and L as 1); the check symbol must match.
         */
        fun parse(text: String): RecoveryKey {
            // Symbols are counted by code point, as the website counts them (a character outside the BMP is one).
            val symbols = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                val cp = if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    i++
                    0x10000 + ((c.code - 0xD800) shl 10) + (text[i].code - 0xDC00)
                } else {
                    c.code
                }
                i++
                when (cp) {
                    ' '.code, '-'.code, '\t'.code, '\n'.code, '\r'.code, 0xA0 -> continue
                    else -> symbols += cp
                }
            }
            if (symbols.size != SYMBOLS) throw RecoveryKeyException(RecoveryKeyException.Reason.WRONG_LENGTH, "expected $SYMBOLS symbols")
            val values = IntArray(SYMBOLS)
            for (k in 0 until SYMBOLS) {
                val cp = symbols[k]
                val v = if (cp > 0x7F) -1 else {
                    val c = normalise(cp.toChar())
                    if (k < SYMBOLS - 1) ALPHABET.indexOf(c) else CHECK_ALPHABET.indexOf(c)
                }
                if (v < 0) throw RecoveryKeyException(RecoveryKeyException.Reason.INVALID_CHARACTER, "symbol ${k + 1}")
                values[k] = v
            }
            if (values[0] > 7) throw RecoveryKeyException(RecoveryKeyException.Reason.OUT_OF_RANGE, "more than 128 bits")
            val data = values.copyOfRange(0, SYMBOLS - 1)
            if (checkOf(data) != values[SYMBOLS - 1]) throw RecoveryKeyException(RecoveryKeyException.Reason.CHECK_MISMATCH, "check symbol")
            return RecoveryKey(fromDigits(data))
        }

        private fun normalise(c: Char): Char = when (val u = if (c in 'a'..'z') c - 32 else c) {
            'O' -> '0'
            'I', 'L' -> '1'
            else -> u
        }

        /** The 26 five-bit digits of the 128-bit value (two zero bits in front). */
        private fun digitsOf(bytes: ByteArray): IntArray = IntArray(SYMBOLS - 1) { k ->
            var v = 0
            for (j in 0 until 5) {
                val bit = k * 5 + j - 2 // bit index into the 128 data bits, from the top
                val b = if (bit < 0) 0 else (bytes[bit / 8].toInt() ushr (7 - bit % 8)) and 1
                v = (v shl 1) or b
            }
            v
        }

        private fun fromDigits(digits: IntArray): ByteArray {
            val out = ByteArray(SIZE)
            for (k in digits.indices) for (j in 0 until 5) {
                val bit = k * 5 + j - 2
                if (bit < 0) continue
                if ((digits[k] ushr (4 - j)) and 1 == 1) {
                    out[bit / 8] = (out[bit / 8].toInt() or (1 shl (7 - bit % 8))).toByte()
                }
            }
            return out
        }

        /** The value mod 37, by Horner's rule over the base-32 digits. */
        private fun checkOf(digits: IntArray): Int {
            var c = 0
            for (d in digits) c = (c * 32 + d) % 37
            return c
        }

        private fun encodeSymbols(bytes: ByteArray): String {
            val digits = digitsOf(bytes)
            val sb = StringBuilder(SYMBOLS)
            for (d in digits) sb.append(ALPHABET[d])
            sb.append(CHECK_ALPHABET[checkOf(digits)])
            return sb.toString()
        }
    }
}
