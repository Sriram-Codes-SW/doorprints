package app.doorprints.crypto

/**
 * P-256 arithmetic for the one thing Security.framework cannot do: the public key `d·G` of a private scalar `d`
 * (HPKE's DeriveKeyPair and the recovery key, docs/15 §9.4). `SecKeyCreateWithData` takes a private key only together
 * with its public point (`04 ‖ x ‖ y ‖ d`), CryptoKit is Swift only, and the JVM trick (two ECDH operations) needs a
 * key object made from the scalar alone. Everything else (generation, ECDH) is Security.framework's own.
 *
 * Because `d` is secret, nothing here branches on it or indexes memory by it: the scalar's bits drive a fixed
 * double-and-always-add ladder whose result is chosen with a mask, over the complete projective addition formulas of
 * Renes, Costello and Batina (2016, Algorithm 4 for a = −3), which have no exceptional cases (so no special case for
 * the point at infinity or for doubling). Field elements are 16 limbs of 16 bits in a [LongArray]; products of two
 * limbs and sums of 16 of them stay far inside a `Long`. Only the final inversion uses a public exponent (p − 2).
 *
 * Pure Kotlin on purpose (no `platform.*`): it is checked against known answers by `IosP256Test` on the simulator, and
 * the same source was also run on the JVM against `JvmCryptoProvider` (docs/ops/ios-crypto-notes.md).
 */
internal object P256Base {
    private const val LIMBS = 16
    private const val WORK = 34
    private const val ROUNDS = 12

    private val P = limbs(Bytes.unhex("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff"))
    private val B = limbs(Bytes.unhex("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b"))
    private val GX = limbs(Bytes.unhex("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296"))
    private val GY = limbs(Bytes.unhex("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"))
    private val ONE = LongArray(LIMBS).also { it[0] = 1 }
    private val ZERO = LongArray(LIMBS)

    /** p − 2 as bits, most significant first (a public constant, so the inversion may branch on it). */
    private val P_MINUS_2: BooleanArray = run {
        val bytes = Bytes.unhex("ffffffff00000001000000000000000000000000fffffffffffffffffffffffd")
        BooleanArray(256) { ((bytes[it / 8].toInt() ushr (7 - it % 8)) and 1) == 1 }
    }

    private fun limbs(bigEndian: ByteArray): LongArray = LongArray(LIMBS) {
        val lo = bigEndian[31 - 2 * it].toLong() and 0xFF
        val hi = bigEndian[30 - 2 * it].toLong() and 0xFF
        lo or (hi shl 8)
    }

    private fun bytes(a: LongArray): ByteArray = ByteArray(32) {
        val limb = a[(31 - it) / 2]
        (if ((31 - it) % 2 == 0) limb else limb ushr 8).toByte()
    }

    // ---- field arithmetic modulo p ----

    private fun carry(t: LongArray) {
        for (i in 0 until t.size - 1) {
            val c = t[i] shr 16
            t[i] -= c shl 16
            t[i + 1] += c
        }
    }

    /**
     * Reduces [t] (signed limbs, total value never negative) modulo p to 16 canonical limbs. 2²⁵⁶ ≡ 2²²⁴ − 2¹⁹² −
     * 2⁹⁶ + 1, so each limb above 2²⁵⁶ is added at limbs 14 and 0 and subtracted at 12 and 6 (relative to its own
     * position); a pass from the top folds everything at once and the carries bring a smaller overflow each round (at
     * most 32 bits less, then a single bit), so [ROUNDS] fixed rounds always end below 2²⁵⁶ < 2p.
     */
    private fun reduce(t: LongArray): LongArray {
        carry(t)
        repeat(ROUNDS) {
            for (i in t.size - 1 downTo LIMBS) {
                val h = t[i]
                t[i] = 0
                val s = i - LIMBS
                t[s + 14] += h
                t[s + 12] -= h
                t[s + 6] -= h
                t[s] += h
            }
            carry(t)
        }
        var overflow = 0L
        for (i in LIMBS until t.size) overflow = overflow or t[i]
        check(overflow == 0L) { "P-256 field reduction did not converge" }
        // One conditional subtraction of p (the value is below 2p), by a mask.
        val d = LongArray(LIMBS)
        var borrow = 0L
        for (i in 0 until LIMBS) {
            val x = t[i] - P[i] - borrow
            borrow = (x ushr 63) and 1L
            d[i] = x and 0xFFFF
        }
        val keepT = -borrow // all ones when t < p
        return LongArray(LIMBS) { (t[it] and keepT) or (d[it] and keepT.inv()) }
    }

    private fun add(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(WORK)
        for (i in 0 until LIMBS) t[i] = a[i] + b[i]
        return reduce(t)
    }

    private fun sub(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(WORK)
        for (i in 0 until LIMBS) t[i] = a[i] - b[i] + P[i] // b < p, so never negative
        return reduce(t)
    }

    private fun mul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(WORK)
        for (i in 0 until LIMBS) {
            val ai = a[i]
            for (j in 0 until LIMBS) t[i + j] += ai * b[j]
        }
        return reduce(t)
    }

    private fun pow(a: LongArray, exponentBits: BooleanArray): LongArray {
        var r = ONE
        for (bit in exponentBits) {
            r = mul(r, r)
            if (bit) r = mul(r, a)
        }
        return r
    }

    private fun select(bit: Long, whenOne: LongArray, whenZero: LongArray): LongArray {
        val m = -bit
        return LongArray(LIMBS) { (whenOne[it] and m) or (whenZero[it] and m.inv()) }
    }

    /** Field operations on 32-byte big-endian elements below p, exposed for the known-answer tests. */
    fun fieldAdd(a: ByteArray, b: ByteArray): ByteArray = bytes(add(limbs(a), limbs(b)))
    fun fieldSub(a: ByteArray, b: ByteArray): ByteArray = bytes(sub(limbs(a), limbs(b)))
    fun fieldMul(a: ByteArray, b: ByteArray): ByteArray = bytes(mul(limbs(a), limbs(b)))

    // ---- points: projective (X : Y : Z), the point at infinity is (0 : 1 : 0) ----

    private class Point(val x: LongArray, val y: LongArray, val z: LongArray)

    /** Complete addition (RCB 2016, Algorithm 4, a = −3); also correct for P = Q and for either input at infinity. */
    private fun add(p: Point, q: Point): Point {
        var t0 = mul(p.x, q.x)
        var t1 = mul(p.y, q.y)
        var t2 = mul(p.z, q.z)
        var t3 = add(p.x, p.y)
        var t4 = add(q.x, q.y)
        t3 = mul(t3, t4)
        t4 = add(t0, t1)
        t3 = sub(t3, t4)
        t4 = add(p.y, p.z)
        var x3 = add(q.y, q.z)
        t4 = mul(t4, x3)
        x3 = add(t1, t2)
        t4 = sub(t4, x3)
        x3 = add(p.x, p.z)
        var y3 = add(q.x, q.z)
        x3 = mul(x3, y3)
        y3 = add(t0, t2)
        y3 = sub(x3, y3)
        var z3 = mul(B, t2)
        x3 = sub(y3, z3)
        z3 = add(x3, x3)
        x3 = add(x3, z3)
        z3 = sub(t1, x3)
        x3 = add(t1, x3)
        y3 = mul(B, y3)
        t1 = add(t2, t2)
        t2 = add(t1, t2)
        y3 = sub(y3, t2)
        y3 = sub(y3, t0)
        t1 = add(y3, y3)
        y3 = add(t1, y3)
        t1 = add(t0, t0)
        t0 = add(t1, t0)
        t0 = sub(t0, t2)
        t1 = mul(t4, y3)
        t2 = mul(t0, y3)
        y3 = mul(x3, z3)
        y3 = add(y3, t2)
        x3 = mul(t3, x3)
        x3 = sub(x3, t1)
        z3 = mul(t4, z3)
        t1 = mul(t3, t0)
        z3 = add(z3, t1)
        return Point(x3, y3, z3)
    }

    /**
     * The uncompressed SEC1 public key `04 ‖ x ‖ y` of [scalar] (32 bytes, big-endian, 1 ≤ d < n; the caller checks
     * the range with [P256Scalar.isValid]), in time that does not depend on the scalar.
     */
    fun publicKey(scalar: ByteArray): ByteArray {
        require(scalar.size == 32) { "scalar must be 32 bytes" }
        val g = Point(GX, GY, ONE)
        var r = Point(ZERO, ONE, ZERO)
        for (i in 0 until 256) {
            val bit = ((scalar[i / 8].toInt() ushr (7 - i % 8)) and 1).toLong()
            r = add(r, r)
            val sum = add(r, g)
            r = Point(select(bit, sum.x, r.x), select(bit, sum.y, r.y), select(bit, sum.z, r.z))
        }
        // d is not 0 mod n, so r is not at infinity and z is invertible.
        val zInverse = pow(r.z, P_MINUS_2)
        val x = bytes(mul(r.x, zInverse))
        val y = bytes(mul(r.y, zInverse))
        check(isOnCurve(x, y)) { "P-256 public key is not on the curve" }
        return Bytes.concat(byteArrayOf(0x04), x, y)
    }

    /** True when x and y (32 bytes each, big-endian) are below p and satisfy y² = x³ − 3x + b. Public values only. */
    fun isOnCurve(x: ByteArray, y: ByteArray): Boolean {
        if (x.size != 32 || y.size != 32) return false
        if (!lessThanP(x) || !lessThanP(y)) return false
        val fx = limbs(x)
        val fy = limbs(y)
        val x3 = mul(mul(fx, fx), fx)
        val rhs = add(sub(x3, add(add(fx, fx), fx)), B)
        val lhs = mul(fy, fy)
        return lhs.contentEquals(rhs)
    }

    /** True when [bigEndian] (32 bytes) is a canonical field element, below p. */
    fun lessThanP(bigEndian: ByteArray): Boolean {
        val v = limbs(bigEndian)
        for (i in LIMBS - 1 downTo 0) {
            if (v[i] != P[i]) return v[i] < P[i]
        }
        return false // equal to p
    }
}
