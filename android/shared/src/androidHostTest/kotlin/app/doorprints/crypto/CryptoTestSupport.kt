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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Test hooks for the crypto code. [FakeRandomProvider] replaces only the random generator of a real provider with a
 * deterministic stream (block i = SHA-256("doorprints-fake-random" ‖ seed ‖ u32 i)), the same in `fake-random.ts`,
 * so a whole `keys.json` or `dpx/1` file can be a byte-exact vector on both stacks. Test code only.
 */
class FakeRandomProvider(private val real: CryptoProvider, seed: String) : CryptoProvider by real {
    private val seedBytes = seed.encodeToByteArray()
    private var block = 0L
    private var buffer = ByteArray(0)
    private var at = 0

    override fun randomBytes(size: Int): ByteArray {
        val out = ByteArray(size)
        for (i in 0 until size) {
            if (at == buffer.size) {
                buffer = real.sha256Of("doorprints-fake-random".encodeToByteArray(), seedBytes, Bytes.u32(block++))
                at = 0
            }
            out[i] = buffer[at++]
        }
        return out
    }

    /** Device keys in vectors come from HPKE's DeriveKeyPair over the stream, so they are deterministic too. */
    override fun p256Generate(): P256PrivateKey = Hpke(this).deriveKeyPair(randomBytes(32))
}

internal fun hex(s: String): ByteArray = Bytes.unhex(s)
internal fun ByteArray.hex(): String = Bytes.hex(this)

/** The pattern every vector plaintext follows: byte i = (31 · i + 7) mod 256. */
internal fun patternBytes(size: Int): ByteArray = ByteArray(size) { ((31L * it + 7) and 0xFF).toByte() }

internal object Vectors {
    fun file(name: String): File {
        var dir: File? = File("").absoluteFile
        val rel = "docs/schemas/$name"
        while (dir != null && !File(dir, rel).exists()) dir = dir.parentFile
        return File(checkNotNull(dir) { "$rel not found" }, rel)
    }

    fun load(name: String): JsonObject = Json.parseToJsonElement(file(name).readText()).jsonObject
}

internal fun JsonObject.s(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.h(key: String): ByteArray = hex(s(key))

/** An in-memory watermark store. */
class MemoryWatermarkStore(var value: KeysWatermark? = null) : KeysWatermarkStore {
    override fun load() = value
    override fun save(watermark: KeysWatermark) {
        value = watermark
    }
}

/** A source that hands out at most [step] bytes per read, to prove the streaming code does not assume full reads. */
internal fun trickle(bytes: ByteArray, step: Int): ByteSource {
    var at = 0
    return ByteSource { b, o, l ->
        if (at >= bytes.size) {
            -1
        } else {
            val n = minOf(l, step, bytes.size - at)
            bytes.copyInto(b, o, at, at + n)
            at += n
            n
        }
    }
}

internal class CollectingSink : ByteSink {
    private val out = java.io.ByteArrayOutputStream()
    override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
    fun bytes(): ByteArray = out.toByteArray()
}

internal fun sha256(p: CryptoProvider, b: ByteArray): ByteArray = p.sha256Of(b)
