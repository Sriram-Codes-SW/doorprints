package app.doorprints.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure-Kotlin P-256 base-point multiplication of the iOS provider (S4b-BL-131) against known multiples (NIST and
 * RFC 5903 values, and Node's `crypto` for the rest). The same source ran on the JVM against `JvmCryptoProvider` on
 * thousands of random and edge scalars while it was written (docs/ops/ios-crypto-notes.md).
 */
class IosP256Test {
    private fun pub(d: String) = Bytes.hex(P256Base.publicKey(Bytes.unhex(d)))

    @Test
    fun smallAndExtremeMultiplesOfTheBasePoint() {
        assertEquals(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5",
            pub("0000000000000000000000000000000000000000000000000000000000000001"),
        )
        assertEquals(
            "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc4766997807775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1",
            pub("0000000000000000000000000000000000000000000000000000000000000002"),
        )
        assertEquals(
            "045ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032",
            pub("0000000000000000000000000000000000000000000000000000000000000003"),
        )
        // n - 1 is -G: the same x, the negated y (p - Gy).
        assertEquals(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296b01cbd1c01e58065711814b583f061e9d431cca994cea1313449bf97c840ae0a",
            pub("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550"),
        )
        assertEquals(
            "0477b20a912e6b23135066e911891524bc4efe3560e3e92350b52dec8f375f2b54a3dc291825cea3f7f7b10bfcdd038a72df623da1e850e0f1caa801fcd6cc67ff",
            pub("8000000000000000000000000000000000000000000000000000000000000000"),
        )
    }

    @Test
    fun rfc5903Keys() {
        assertEquals(
            "04dad0b65394221cf9b051e1feca5787d098dfe637fc90b9ef945d0c37725811805271a0461cdb8252d61f1c456fa3e59ab1f45b33accf5f58389e0577b8990bb3",
            pub("c88f01f510d9ac3f70a292daa2316de544e9aab8afe84049c62a9c57862d1433"),
        )
        assertEquals(
            "04d12dfb5289c8d4f81208b70270398c342296970a0bccb74c736fc7554494bf6356fbf3ca366cc23e8157854c13c58d6aac23f046ada30f8353e74f33039872ab",
            pub("c6ef9c5d78ae012a011164acb397ce2088685d8f06bf9be0b283ab46476bee53"),
        )
    }

    @Test
    fun everyPowerOfTwoLandsOnTheCurve() {
        // 2^k for k in 0..254: exercises every bit position of the ladder; each result must satisfy the curve equation.
        for (k in 0..254) {
            val d = ByteArray(32)
            d[31 - k / 8] = (1 shl (k % 8)).toByte()
            val p = P256Base.publicKey(d)
            assertTrue(P256Base.isOnCurve(p.copyOfRange(1, 33), p.copyOfRange(33, 65)), "2^$k")
        }
    }

    @Test
    fun curveMembership() {
        val g = P256Base.publicKey(ByteArray(32).also { it[31] = 1 })
        val x = g.copyOfRange(1, 33)
        val y = g.copyOfRange(33, 65)
        assertTrue(P256Base.isOnCurve(x, y))
        assertFalse(P256Base.isOnCurve(x, y.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }))
        assertFalse(P256Base.isOnCurve(ByteArray(32), ByteArray(32)))
        assertFalse(P256Base.isOnCurve(x.copyOf(31), y))
        // p itself is not a canonical coordinate.
        val p = Bytes.unhex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
        assertFalse(P256Base.isOnCurve(p, y))
        assertFalse(P256Base.isOnCurve(x, p))
        assertFalse(P256Base.isOnCurve(ByteArray(32) { 0xFF.toByte() }, y))
        assertFalse(P256Base.isOnCurve(x.copyOf().also { it[31] = (it[31].toInt() - 1).toByte() }, y))
    }

    private val p = Bytes.unhex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff")
    private fun fe(v: Int) = ByteArray(32).also { it[31] = v.toByte() }
    private fun pMinus(v: Int) = p.copyOf().also { it[31] = (it[31].toInt() - v).toByte() }

    @Test
    fun fieldArithmeticAtTheEdges() {
        val zero = ByteArray(32)
        // p - 1 + 1 = p = 0, and a - a = 0 (the 3p offset must be taken back by the final subtraction).
        assertEquals(Bytes.hex(zero), Bytes.hex(P256Base.fieldAdd(pMinus(1), fe(1))))
        assertEquals(Bytes.hex(fe(1)), Bytes.hex(P256Base.fieldAdd(pMinus(1), fe(2))))
        assertEquals(Bytes.hex(zero), Bytes.hex(P256Base.fieldSub(pMinus(1), pMinus(1))))
        assertEquals(Bytes.hex(zero), Bytes.hex(P256Base.fieldSub(zero, zero)))
        assertEquals(Bytes.hex(pMinus(1)), Bytes.hex(P256Base.fieldSub(zero, fe(1))))
        assertEquals(Bytes.hex(fe(1)), Bytes.hex(P256Base.fieldSub(fe(1), zero)))
        // (p - 1)^2 = 1, 0 * a = 0, (p - 2) * (p - 3) = 6.
        assertEquals(Bytes.hex(fe(1)), Bytes.hex(P256Base.fieldMul(pMinus(1), pMinus(1))))
        assertEquals(Bytes.hex(zero), Bytes.hex(P256Base.fieldMul(zero, pMinus(1))))
        assertEquals(Bytes.hex(fe(6)), Bytes.hex(P256Base.fieldMul(pMinus(2), pMinus(3))))
        // 2^255 * 2 = 2^256 = 2^224 - 2^192 - 2^96 + 1 (mod p).
        val twoPow255 = ByteArray(32).also { it[0] = 0x80.toByte() }
        assertEquals(
            "00000000fffffffeffffffffffffffffffffffff000000000000000000000001",
            Bytes.hex(P256Base.fieldAdd(twoPow255, twoPow255)),
        )
    }

    @Test
    fun canonicalFieldElementsAreBelowP() {
        assertTrue(P256Base.lessThanP(ByteArray(32)))
        assertTrue(P256Base.lessThanP(pMinus(1)))
        assertFalse(P256Base.lessThanP(p))
        assertFalse(P256Base.lessThanP(Bytes.unhex("ffffffff00000001000000000000000000000001000000000000000000000000")))
        assertFalse(P256Base.lessThanP(ByteArray(32) { 0xFF.toByte() }))
        // Differing only in the lowest limb or only in the highest one.
        assertTrue(P256Base.lessThanP(p.copyOf().also { it[0] = 0xFE.toByte() }))
        assertTrue(P256Base.lessThanP(p.copyOf().also { it[1] = 0xFE.toByte() }))
    }

    @Test
    fun theScalarMustBe32Bytes() {
        assertFailsWith<IllegalArgumentException> { P256Base.publicKey(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { P256Base.publicKey(ByteArray(33)) }
    }
}
