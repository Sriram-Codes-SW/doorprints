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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The cross-platform parity vectors (`docs/schemas/dpx-vectors.json`, docs/schemas/README.md section 6.3; TC-U-129):
 * the recovery key (bytes, text, scalar, public key), typed recovery keys, `dpx/1` files in deterministic mode and a
 * `keys.json` life (create, two devices added, a revoke with a new epoch) over [FakeRandomProvider]. The website's
 * `crypto-vectors.spec.ts` runs the same file, so the two stacks write the same bytes.
 *
 * Regenerating (only after a deliberate format change): `DPX_VECTORS_OUT=/path ./gradlew :shared:testAndroidHostTest
 * --tests '*CryptoVectorsTest*'` writes the generated values there; they are checked against an independent
 * implementation before they replace the file (docs/schemas/README.md section 6.3).
 */
class CryptoVectorsTest {
    private val p = JvmCryptoProvider
    private val vectors: JsonObject by lazy { Vectors.load("dpx-vectors.json").also { assertEquals("doorprints-dpx-vectors/1", it.s("format")) } }

    private fun JsonObject.arr(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.b(key: String): ByteArray = checkNotNull(Bytes.unb64(s(key))) { key }

    @Test
    fun recoveryKeyVectors() {
        for (v in vectors.arr("recovery")) {
            val key = RecoveryKey.fromBytes(v.h("bytes"))
            assertEquals(v.s("symbols"), key.symbols)
            assertEquals(v.s("display"), key.display)
            assertArrayEquals(v.h("bytes"), RecoveryKey.parse(v.s("display")).bytes)
            assertEquals(v.s("scalar"), key.scalar(p).hex())
            val pair = key.keyPair(p)
            assertEquals(v.s("publicKey"), pair.publicKey.hex())
            assertEquals(v.s("kid"), kidOf(p, pair.publicKey).hex())
        }
    }

    @Test
    fun typedRecoveryKeys() {
        for (v in vectors.arr("recoveryParse")) {
            val input = v.s("input")
            if (v.containsKey("bytes")) {
                assertEquals(input, v.s("bytes"), RecoveryKey.parse(input).bytes.hex())
            } else {
                try {
                    RecoveryKey.parse(input)
                    fail("accepted $input")
                } catch (e: RecoveryKeyException) {
                    assertEquals(input, v.s("error"), e.reason.name)
                }
            }
        }
    }

    @Test
    fun envelopeVectors() {
        for (v in vectors.arr("envelope")) {
            val plaintext = patternBytes(v.getValue("plaintextSize").jsonPrimitive.int)
            val sink = CollectingSink()
            val w = Dpx(p).encryptWith(
                v.b("folderKey"), v.getValue("epoch").jsonPrimitive.int, v.b("kid"), v.s("inner"), Dpx.sourceOf(plaintext), sink,
                Long.MAX_VALUE, v.b("contentKey"), v.b("wrapNonce"), v.b("noncePrefix"),
            )
            val file = sink.bytes()
            val name = v.s("name")
            assertEquals(name, v.getValue("fileSize").jsonPrimitive.long, file.size.toLong())
            assertEquals(name, v.s("fileSha256"), sha256(p, file).hex())
            assertEquals(name, v.s("fileSha256"), w.ciphertextSha256.hex())
            assertEquals(name, v.s("plaintextSha256"), w.plaintextSha256.hex())
            if (v.containsKey("file")) assertArrayEquals(name, v.b("file"), file)
            val keys = FolderKeys { e -> if (e == v.getValue("epoch").jsonPrimitive.int) v.b("folderKey") else null }
            assertArrayEquals(name, plaintext, Dpx(p).decryptBytes(keys, v.s("inner"), file, expectedPlaintextSha256 = hex(v.s("plaintextSha256"))).first)
        }
    }

    @Test
    fun keysFileVectors() {
        val k = vectors.getValue("keys").jsonObject
        val rp = FakeRandomProvider(p, k.s("seed"))
        val files = KeysFile(rp)
        val devices = k.arr("devices").map { Hpke(p).deriveKeyPair(it.h("ikm")) to KeysFile.NewDevice(Hpke(p).deriveKeyPair(it.h("ikm")).publicKey, it.s("name"), DevicePlatform.entries.single { e -> e.wire == it.s("platform") }) }
        val recovery = RecoveryKey.fromBytes(k.h("recoveryBytes"))
        var opened: OpenedKeys? = null
        for (step in k.arr("steps")) {
            val now = step.getValue("now").jsonPrimitive.long
            val written = when (val op = step.s("op")) {
                "create" -> files.createFirstDevice(devices[0].second, recovery, now)
                "addDevice" -> files.addDevice(opened!!, kidOf(p, devices[step.getValue("approver").jsonPrimitive.int].second.publicKey), devices[step.getValue("device").jsonPrimitive.int].second, now)
                "newEpoch" -> files.newEpoch(opened!!, now, revokeKid = kidOf(p, devices[step.getValue("revoke").jsonPrimitive.int].second.publicKey))
                else -> error(op)
            }
            assertEquals(step.s("op"), step.s("file"), Bytes.b64(written.bytes))
            opened = written.opened
            // Every listed device and the recovery key open it, and see the same folder keys.
            for (d in step.getValue("openers").jsonArray.map { it.jsonPrimitive.int }) {
                val current = checkNotNull(Bytes.unb64(step.getValue("folderKeys").jsonObject.getValue(written.opened.epoch.toString()).jsonPrimitive.content))
                val o = KeysFile(p).openFirstPin(written.bytes, devices[d].first, KeysGuard(p, MemoryWatermarkStore()), current)
                for ((epoch, key) in step.getValue("folderKeys").jsonObject) assertEquals(Bytes.b64(o.folderKey(epoch.toInt())!!), key.jsonPrimitive.content)
            }
            val r = KeysFile(p).openWithRecovery(written.bytes, recovery, KeysGuard(p, MemoryWatermarkStore()))
            for ((epoch, key) in step.getValue("folderKeys").jsonObject) assertEquals(Bytes.b64(r.folderKey(epoch.toInt())!!), key.jsonPrimitive.content)
        }
    }

    /** Writes the generated values when DPX_VECTORS_OUT is set; otherwise does nothing. */
    @Test
    fun generateWhenAsked() {
        val out = System.getenv("DPX_VECTORS_OUT") ?: return
        val root = buildJsonObject {
            put("format", "doorprints-dpx-vectors/1")
            put("recovery", buildJsonArray { recoveryCases().forEach { add(it) } })
            put("recoveryParse", buildJsonArray { parseCases().forEach { add(it) } })
            put("envelope", buildJsonArray { envelopeCases().forEach { add(it) } })
            put("keys", keysCase())
            put("hpkeRegression", buildJsonArray { hpkeCases().forEach { add(it) } })
        }
        File(out).writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), root))
    }

    private fun digest(text: String) = sha256(p, text.encodeToByteArray())

    private fun recoveryCases(): List<JsonObject> = listOf(
        ByteArray(16),
        ByteArray(16) { -1 },
        hex("00112233445566778899aabbccddeeff"),
        digest("doorprints recovery vector 3").copyOf(16),
    ).map { b ->
        val key = RecoveryKey.fromBytes(b)
        val pair = key.keyPair(p)
        buildJsonObject {
            put("bytes", b.hex())
            put("symbols", key.symbols)
            put("display", key.display)
            put("scalar", key.scalar(p).hex())
            put("publicKey", pair.publicKey.hex())
            put("kid", kidOf(p, pair.publicKey).hex())
        }
    }

    private fun parseCases(): List<JsonObject> {
        val key = RecoveryKey.fromBytes(hex("00112233445566778899aabbccddeeff"))
        val s = key.symbols
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        fun ok(input: String) = buildJsonObject {
            put("input", input)
            put("bytes", key.bytes.hex())
        }
        fun bad(input: String, error: String) = buildJsonObject {
            put("input", input)
            put("error", error)
        }
        val changed = s.substring(0, 5) + alphabet[(alphabet.indexOf(s[5]) + 1) % 32] + s.substring(6)
        var swapAt = 6
        while (s[swapAt] == s[swapAt + 1]) swapAt++
        val swapped = s.substring(0, swapAt) + s[swapAt + 1] + s[swapAt] + s.substring(swapAt + 2)
        return listOf(
            ok(key.display),
            ok(s),
            ok(key.display.lowercase()),
            ok(" " + key.display.replace("-", " ") + " "),
            ok(key.display.replace('0', 'O').replace('1', 'l')),
            ok(key.display.lowercase().replace('0', 'o').replace('1', 'i')),
            bad(s.dropLast(1), "WRONG_LENGTH"),
            bad(s + "0", "WRONG_LENGTH"),
            bad(changed, "CHECK_MISMATCH"),
            bad(swapped, "CHECK_MISMATCH"),
            bad("U" + s.substring(1), "INVALID_CHARACTER"),
            bad(s.substring(0, 10) + "*" + s.substring(11), "INVALID_CHARACTER"),
            bad("8" + s.substring(1), "OUT_OF_RANGE"),
        )
    }

    private fun envelopeCases(): List<JsonObject> {
        val folderKey = ByteArray(32) { it.toByte() }
        val kid = ByteArray(16) { (0xA0 + it).toByte() }
        return listOf(
            Triple("empty", 0, "sync/1"),
            Triple("one byte", 1, "sync/1"),
            Triple("small photo", 33, "photo/1"),
            Triple("chunk - 1", 65535, "doorprints-backup/2"),
            Triple("one chunk", 65536, "doorprints-backup/2"),
            Triple("chunk + 1", 65537, "doorprints-backup/2"),
            Triple("three chunks + 100", 3 * 65536 + 100, "doorprints-backup/3"),
        ).map { (name, size, inner) ->
            val contentKey = digest("content $name")
            val wrapNonce = digest("wrap $name").copyOf(12)
            val prefix = digest("prefix $name").copyOf(7)
            val sink = CollectingSink()
            val plaintext = patternBytes(size)
            val w = Dpx(p).encryptWith(folderKey, 3, kid, inner, Dpx.sourceOf(plaintext), sink, Long.MAX_VALUE, contentKey, wrapNonce, prefix)
            val file = sink.bytes()
            buildJsonObject {
                put("name", name)
                put("folderKey", Bytes.b64(folderKey))
                put("epoch", 3)
                put("kid", Bytes.b64(kid))
                put("inner", inner)
                put("contentKey", Bytes.b64(contentKey))
                put("wrapNonce", Bytes.b64(wrapNonce))
                put("noncePrefix", Bytes.b64(prefix))
                put("plaintextSize", size)
                put("plaintextSha256", w.plaintextSha256.hex())
                put("fileSize", file.size)
                put("fileSha256", sha256(p, file).hex())
                if (file.size <= 4096) put("file", Bytes.b64(file))
            }
        }
    }

    private fun keysCase(): JsonObject {
        val seed = "doorprints dpx vectors keys/1"
        val rp = FakeRandomProvider(p, seed)
        val files = KeysFile(rp)
        val deviceSpecs = listOf(
            Triple("device 0", "Pixel 8", DevicePlatform.ANDROID),
            Triple("device 1", "फ़ोन \"iPhone\"", DevicePlatform.IOS),
            Triple("device 2", "Chrome on the laptop", DevicePlatform.WEB),
        )
        val keys = deviceSpecs.map { Hpke(p).deriveKeyPair(digest(it.first)) }
        val news = deviceSpecs.mapIndexed { i, d -> KeysFile.NewDevice(keys[i].publicKey, d.second, d.third) }
        val recovery = RecoveryKey.fromBytes(hex("00112233445566778899aabbccddeeff"))
        val steps = ArrayList<JsonObject>()
        fun record(op: String, now: Long, w: KeysFile.Written, openers: List<Int>, extra: Map<String, Int>) {
            steps += buildJsonObject {
                put("op", op)
                put("now", now)
                extra.forEach { (k, v) -> put(k, v) }
                put("file", Bytes.b64(w.bytes))
                put("openers", JsonArray(openers.map { JsonPrimitive(it) }))
                put("folderKeys", buildJsonObject { for (e in 1..w.opened.epoch) put(e.toString(), Bytes.b64(w.opened.folderKey(e)!!)) })
            }
        }
        var w = files.createFirstDevice(news[0], recovery, 1790000000000)
        record("create", 1790000000000, w, listOf(0), emptyMap())
        w = files.addDevice(w.opened, kidOf(p, news[0].publicKey), news[1], 1790000100000)
        record("addDevice", 1790000100000, w, listOf(0, 1), mapOf("approver" to 0, "device" to 1))
        w = files.addDevice(w.opened, kidOf(p, news[0].publicKey), news[2], 1790000200000)
        record("addDevice", 1790000200000, w, listOf(0, 1, 2), mapOf("approver" to 0, "device" to 2))
        w = files.newEpoch(w.opened, 1790000300000, revokeKid = kidOf(p, news[1].publicKey))
        record("newEpoch", 1790000300000, w, listOf(0, 2), mapOf("revoke" to 1))
        return buildJsonObject {
            put("seed", seed)
            put("recoveryBytes", recovery.bytes.hex())
            put("devices", buildJsonArray {
                deviceSpecs.forEach { d ->
                    add(buildJsonObject {
                        put("ikm", digest(d.first).hex())
                        put("name", d.second)
                        put("platform", d.third.wire)
                    })
                }
            })
            put("steps", JsonArray(steps))
        }
    }

    private fun hpkeCases(): List<JsonObject> {
        val a31 = Vectors.load("hpke-vectors.json").getValue("hpke").jsonObject.getValue("official").jsonArray[0].jsonObject
        val cases = listOf(
            Triple(a31.h("ikmE"), a31.h("ikmR"), a31.h("info")) to listOf(a31.getValue("encryptions").jsonArray[0].jsonObject.let { it.h("aad") to it.h("pt") }, "436f756e742d31".let { hex(it) } to a31.getValue("encryptions").jsonArray[1].jsonObject.h("pt")),
            Triple(digest("doorprints hpke regression E2"), digest("doorprints hpke regression R2"), "doorprints/dpx1/wrap".encodeToByteArray()) to listOf(WrapAad.folderKey(1, ByteArray(16) { 7 }) to ByteArray(32) { (it * 3).toByte() }),
            Triple(digest("doorprints hpke regression E3"), digest("doorprints hpke regression R3"), ByteArray(0)) to listOf(ByteArray(0) to ByteArray(0), ByteArray(0) to "x".encodeToByteArray()),
        )
        val h = Hpke(p, Hpke.Aead.AES_256_GCM)
        return cases.mapIndexed { i, (ikms, msgs) ->
            val (ikmE, ikmR, info) = ikms
            val skE = h.deriveKeyPair(ikmE)
            val skR = h.deriveKeyPair(ikmR)
            val (shared, enc) = h.encap(skR.publicKey, skE)
            val s = h.keySchedule(Hpke.MODE_BASE, shared, info, ByteArray(0), ByteArray(0))
            val ctx = h.setupBaseS(skR.publicKey, info, skE).context
            buildJsonObject {
                put("source", "regression ${i + 1}: produced by this implementation (Kotlin), checked against python 'cryptography' (not an official vector)")
                put("mode", 0)
                put("kem_id", Hpke.KEM_ID)
                put("kdf_id", Hpke.KDF_ID)
                put("aead_id", Hpke.Aead.AES_256_GCM.id)
                put("info", info.hex())
                put("ikmE", ikmE.hex())
                put("pkEm", skE.publicKey.hex())
                put("ikmR", ikmR.hex())
                put("pkRm", skR.publicKey.hex())
                put("enc", enc.hex())
                put("shared_secret", shared.hex())
                put("key", s.key.hex())
                put("base_nonce", s.baseNonce.hex())
                put("exporter_secret", s.exporterSecret.hex())
                put("encryptions", buildJsonArray {
                    msgs.forEachIndexed { seq, (aad, pt) ->
                        add(buildJsonObject {
                            put("seq", seq)
                            put("pt", pt.hex())
                            put("aad", aad.hex())
                            put("ct", ctx.seal(aad, pt).hex())
                        })
                    }
                })
            }
        }
    }
}
