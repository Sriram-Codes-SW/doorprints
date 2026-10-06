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
 * A small QR encoder for the enrolment code (S4b-BL-126; web twin: `qr-code.ts`): byte mode, error correction M,
 * versions 1 to 10, mask 0. No library. The matrix is indexed `[y][x]`, `true` for a dark module; the screen draws it
 * (with a four-module quiet zone, which [qrSvg] shows) or the person types the same text, since the code carries it.
 */
/** GF(2^8) with polynomial 0x11d: antilog and log tables (an object, so they exist before any use on every platform). */
private object Gf {
    val exp = IntArray(512)
    val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }
}

/** EXP[8] of GF(2^8) with polynomial 0x11d. Tests pin this so the table cannot drift. */
val QR_GF_EXP8: Int get() = Gf.exp[8]

private fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else Gf.exp[Gf.log[a] + Gf.log[b]]

/** Version information bits for versions 7 to 40 (ISO/IEC 18004). */
fun qrVersionBits(version: Int): Int {
    var rem = version
    for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1f25)
    return (version shl 12) or (rem and 0xfff)
}

private class EccSpec(val ecc: Int, val groups: List<Pair<Int, Int>>)

/** Error correction M, versions 1 to 10 (blocks: count, data codewords). */
private val ECC = listOf(
    EccSpec(10, listOf(1 to 16)),
    EccSpec(16, listOf(1 to 28)),
    EccSpec(26, listOf(1 to 44)),
    EccSpec(18, listOf(2 to 32)),
    EccSpec(24, listOf(2 to 43)),
    EccSpec(16, listOf(4 to 27)),
    EccSpec(18, listOf(4 to 31)),
    EccSpec(22, listOf(2 to 38, 2 to 39)),
    EccSpec(22, listOf(3 to 36, 2 to 37)),
    EccSpec(26, listOf(4 to 43, 1 to 44)),
)

private val REMAINDER = intArrayOf(0, 7, 7, 7, 7, 7, 0, 0, 0, 0)

private val ALIGN = listOf(
    intArrayOf(),
    intArrayOf(6, 18),
    intArrayOf(6, 22),
    intArrayOf(6, 26),
    intArrayOf(6, 30),
    intArrayOf(6, 34),
    intArrayOf(6, 22, 38),
    intArrayOf(6, 24, 42),
    intArrayOf(6, 26, 46),
    intArrayOf(6, 28, 50),
)

private fun rsDivisor(degree: Int): IntArray {
    val result = IntArray(degree)
    result[degree - 1] = 1
    var root = 1
    for (i in 0 until degree) {
        for (j in result.indices) {
            result[j] = mul(result[j], root)
            if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
        }
        root = mul(root, 2)
    }
    return result
}

private fun rsRemainder(data: IntArray, divisor: IntArray): IntArray {
    val result = IntArray(divisor.size)
    for (b in data) {
        val factor = b xor result[0]
        result.copyInto(result, 0, 1, result.size)
        result[result.size - 1] = 0
        for (i in result.indices) result[i] = result[i] xor mul(divisor[i], factor)
    }
    return result
}

private fun dataCodewords(version: Int): Int = ECC[version - 1].groups.sumOf { it.first * it.second }

private fun byteCapacity(version: Int): Int {
    val countBits = if (version <= 9) 8 else 16
    return (dataCodewords(version) * 8 - 4 - countBits) / 8
}

/** The smallest version that holds [length] bytes; a longer payload is a [IllegalArgumentException]. */
private fun chooseVersion(length: Int): Int {
    for (v in 1..10) if (byteCapacity(v) >= length) return v
    throw IllegalArgumentException("qr payload is longer than version 10")
}

private fun packData(version: Int, bytes: ByteArray): IntArray {
    val countBits = if (version <= 9) 8 else 16
    val capacity = dataCodewords(version)
    val bits = ArrayList<Int>()
    fun push(value: Int, len: Int) {
        for (i in len - 1 downTo 0) bits.add((value ushr i) and 1)
    }
    push(0b0100, 4)
    push(bytes.size, countBits)
    for (b in bytes) push(b.toInt() and 0xff, 8)
    val limit = capacity * 8
    val term = minOf(4, limit - bits.size)
    repeat(term) { bits.add(0) }
    while (bits.size % 8 != 0) bits.add(0)
    val out = IntArray(capacity)
    for (i in 0 until bits.size / 8) {
        var v = 0
        for (b in 0 until 8) v = (v shl 1) or bits[i * 8 + b]
        out[i] = v
    }
    var pad = 0
    for (i in bits.size / 8 until capacity) {
        out[i] = if (pad % 2 == 0) 0xec else 0x11
        pad++
    }
    return out
}

private fun syndromeClear(data: IntArray, ecc: IntArray): Boolean {
    val cw = data + ecc
    for (i in ecc.indices) {
        var s = 0
        val a = Gf.exp[i]
        for (c in cw) s = mul(s, a) xor c
        if (s != 0) return false
    }
    return true
}

private fun interleave(version: Int, data: IntArray): IntArray {
    val spec = ECC[version - 1]
    val divisor = rsDivisor(spec.ecc)
    val blocks = ArrayList<IntArray>()
    val eccs = ArrayList<IntArray>()
    var offset = 0
    for ((count, len) in spec.groups) {
        repeat(count) {
            val block = data.copyOfRange(offset, offset + len)
            offset += len
            val ecc = rsRemainder(block, divisor)
            check(syndromeClear(block, ecc)) { "qr ecc" }
            blocks.add(block)
            eccs.add(ecc)
        }
    }
    val out = ArrayList<Int>()
    val max = blocks.maxOf { it.size }
    for (i in 0 until max) for (b in blocks) if (i < b.size) out.add(b[i])
    for (i in 0 until spec.ecc) for (e in eccs) out.add(e[i])
    return out.toIntArray()
}

private fun draw(version: Int, codewords: IntArray): Array<BooleanArray> {
    val size = 21 + 4 * (version - 1)
    val dark = Array(size) { BooleanArray(size) }
    val fn = Array(size) { BooleanArray(size) }
    fun set(x: Int, y: Int, on: Boolean) {
        dark[y][x] = on
        fn[y][x] = true
    }
    fun finder(ox: Int, oy: Int) {
        for (dy in -1..7) {
            for (dx in -1..7) {
                val x = ox + dx
                val y = oy + dy
                if (x < 0 || y < 0 || x >= size || y >= size) continue
                val inBox = dx in 0..6 && dy in 0..6
                val border = dx == 0 || dx == 6 || dy == 0 || dy == 6
                val core = dx in 2..4 && dy in 2..4
                set(x, y, inBox && (border || core))
            }
        }
    }
    finder(0, 0)
    finder(size - 7, 0)
    finder(0, size - 7)
    for (i in 0 until size) {
        if (!fn[6][i]) set(i, 6, i % 2 == 0)
        if (!fn[i][6]) set(6, i, i % 2 == 0)
    }
    val positions = ALIGN[version - 1]
    if (positions.isNotEmpty()) {
        val last = positions[positions.size - 1]
        for (cy in positions) {
            for (cx in positions) {
                if ((cx == 6 && cy == 6) || (cx == 6 && cy == last) || (cy == 6 && cx == last)) continue
                for (dy in -2..2) {
                    for (dx in -2..2) set(cx + dx, cy + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                }
            }
        }
    }
    fun reserve(x: Int, y: Int) {
        fn[y][x] = true
    }
    for (i in 0..5) reserve(8, i)
    reserve(8, 7)
    reserve(8, 8)
    reserve(7, 8)
    for (i in 9 until 15) reserve(14 - i, 8)
    for (i in 0 until 8) reserve(size - 1 - i, 8)
    for (i in 8 until 15) reserve(8, size - 15 + i)
    reserve(8, size - 8)
    if (version >= 7) {
        for (i in 0 until 18) {
            val a = size - 11 + (i % 3)
            val b = i / 3
            reserve(a, b)
            reserve(b, a)
        }
    }
    val totalBits = codewords.size * 8 + REMAINDER[version - 1]
    var bit = 0
    var right = size - 1
    while (right >= 1) {
        if (right == 6) right = 5
        for (vert in 0 until size) {
            for (j in 0 until 2) {
                val x = right - j
                val upward = ((right + 1) and 2) == 0
                val y = if (upward) size - 1 - vert else vert
                if (fn[y][x] || bit >= totalBits) continue
                val byte = bit ushr 3
                val on = byte < codewords.size && ((codewords[byte] ushr (7 - (bit and 7))) and 1) == 1
                dark[y][x] = if ((x + y) % 2 == 0) !on else on
                bit++
            }
        }
        right -= 2
    }
    check(bit == totalBits) { "qr placement" }
    val format = 0x5412
    fun bitAt(i: Int) = ((format ushr i) and 1) == 1
    for (i in 0..5) set(8, i, bitAt(i))
    set(8, 7, bitAt(6))
    set(8, 8, bitAt(7))
    set(7, 8, bitAt(8))
    for (i in 9 until 15) set(14 - i, 8, bitAt(i))
    for (i in 0 until 8) set(size - 1 - i, 8, bitAt(i))
    for (i in 8 until 15) set(8, size - 15 + i, bitAt(i))
    set(8, size - 8, true)
    if (version >= 7) {
        val info = qrVersionBits(version)
        for (i in 0 until 18) {
            val on = ((info ushr i) and 1) == 1
            val a = size - 11 + (i % 3)
            val b = i / 3
            set(a, b, on)
            set(b, a, on)
        }
    }
    return dark
}

/** Dark modules of a QR symbol for [bytes] (byte mode, ECC M, mask 0), `[y][x]`. */
fun encodeQr(bytes: ByteArray): Array<BooleanArray> {
    val version = chooseVersion(bytes.size)
    return draw(version, interleave(version, packData(version, bytes)))
}

/** The same symbol as an SVG, with a four-module quiet zone. */
fun qrSvg(bytes: ByteArray): String {
    val modules = encodeQr(bytes)
    val n = modules.size
    val quiet = 4
    val size = n + quiet * 2
    val sb = StringBuilder()
    sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(size).append(' ').append(size)
        .append("\" shape-rendering=\"crispEdges\">")
    for (y in 0 until n) {
        for (x in 0 until n) {
            if (modules[y][x]) sb.append("<rect x=\"").append(x + quiet).append("\" y=\"").append(y + quiet).append("\" width=\"1\" height=\"1\"/>")
        }
    }
    return sb.append("</svg>").toString()
}
