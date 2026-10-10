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

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import platform.Foundation.NSBundle
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The shared crypto vectors on the iPhone simulator's provider (S4b-BL-131; TC-U-129): `docs/schemas/dpx-vectors.json`
 * (the recovery key, typed recovery keys, `dpx/1` files and a `keys.json` life) and the official and regression cases
 * of `docs/schemas/hpke-vectors.json`, run over [platformCryptoProvider], which on iOS is CommonCrypto,
 * Security.framework and the two pure Kotlin pieces (GCM and P-256 scalar multiplication). The JVM twins are
 * `CryptoVectorsTest` and `HpkeVectorsTest`; the website runs the same files, so the three stacks must write the same
 * bytes. The files are found by walking up from the working directory and from the test binary's path to the
 * repository (the simulator process sees the host's file system); a test that cannot find them fails, it never skips.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCryptoVectorsTest {
    private val p = platformCryptoProvider()

    private fun h(s: String) = Bytes.unhex(s)
    private fun ByteArray.hx() = Bytes.hex(this)
    private fun JsonObject.need(key: String) = this[key] ?: error("vector key '$key' is missing; the object has ${keys.sorted()}")
    private fun JsonObject.s(key: String): String = need(key).jsonPrimitive.content
    private fun JsonObject.h(key: String): ByteArray = h(s(key))
    private fun JsonObject.b(key: String): ByteArray = checkNotNull(Bytes.unb64(s(key))) { key }
    private fun JsonObject.arr(key: String): List<JsonObject> = need(key).jsonArray.map { it.jsonObject }

    /** Reports which vector failed and with what, so a K/N stack trace with a doubtful line number is not all there is. */
    private inline fun <T> stage(what: String, block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        throw AssertionError("$what failed: ${e::class.simpleName}: ${e.message}", e)
    }

    private fun load(name: String): JsonObject {
        val starts = listOfNotNull(
            NSFileManager.defaultManager.currentDirectoryPath,
            NSBundle.mainBundle.executablePath,
            NSProcessInfo.processInfo.arguments.firstOrNull() as? String,
        )
        for (start in starts) {
            var dir: String = start
            while (dir.isNotEmpty() && dir != "/") {
                val candidate = "$dir/docs/schemas/$name"
                if (NSFileManager.defaultManager.fileExistsAtPath(candidate)) {
                    val text = NSString.stringWithContentsOfFile(candidate, encoding = NSUTF8StringEncoding, error = null)
                    return Json.parseToJsonElement(checkNotNull(text) { "$candidate could not be read" }).jsonObject
                }
                dir = dir.substringBeforeLast('/', "")
            }
        }
        error("docs/schemas/$name not found from $starts")
    }

    private val dpx: JsonObject by lazy {
        load("dpx-vectors.json").also { assertEquals("doorprints-dpx-vectors/1", it.s("format")) }
    }

    private fun pattern(n: Int) = ByteArray(n) { ((31L * it + 7) and 0xFF).toByte() }

    private class Collect : ByteSink {
        private var out = ByteArray(0)
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            out += buffer.copyOfRange(offset, offset + length)
        }

        fun bytes() = out
    }

    /** A deterministic random stream, as `FakeRandomProvider` (androidHostTest) and the website's `fake-random.ts`. */
    private class FakeRandom(private val real: CryptoProvider, seed: String) : CryptoProvider by real {
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

        override fun p256Generate(): P256PrivateKey = Hpke(this).deriveKeyPair(randomBytes(32))
    }

    private class Marks(var value: KeysWatermark? = null) : KeysWatermarkStore {
        override fun load() = value
        override fun compareAndSet(expected: KeysWatermark?, next: KeysWatermark): Boolean {
            if (value != expected) return false
            value = next
            return true
        }
    }

    @Test
    fun recoveryKeyVectors() {
        for (v in dpx.arr("recovery")) {
            val key = stage("recovery fromBytes") { RecoveryKey.fromBytes(v.h("bytes")) }
            assertEquals(v.s("symbols"), key.symbols)
            assertEquals(v.s("display"), key.display)
            assertContentEquals(v.h("bytes"), RecoveryKey.parse(v.s("display")).bytes)
            assertEquals(v.s("scalar"), stage("recovery scalar") { key.scalar(p).hx() })
            val pair = stage("recovery keyPair (import of the scalar)") { key.keyPair(p) }
            assertEquals(v.s("publicKey"), pair.publicKey.hx())
            assertEquals(v.s("kid"), stage("recovery kid") { kidOf(p, pair.publicKey).hx() })
        }
    }

    @Test
    fun typedRecoveryKeys() {
        for (v in dpx.arr("recoveryParse")) {
            val input = v.s("input")
            if (v.containsKey("bytes")) {
                assertEquals(v.s("bytes"), RecoveryKey.parse(input).bytes.hx(), input)
            } else {
                val e = assertFailsWith<RecoveryKeyException>(input) { RecoveryKey.parse(input) }
                assertEquals(v.s("error"), e.reason.name, input)
            }
        }
    }

    @Test
    fun envelopeVectors() {
        for (v in dpx.arr("envelope")) {
            val name = v.s("name")
            val plaintext = pattern(v.getValue("plaintextSize").jsonPrimitive.int)
            val epoch = v.getValue("epoch").jsonPrimitive.int
            val sink = Collect()
            val w = Dpx(p).encryptWith(
                v.b("folderKey"), epoch, v.b("kid"), v.s("inner"), Dpx.sourceOf(plaintext), sink,
                Long.MAX_VALUE, v.b("contentKey"), v.b("wrapNonce"), v.b("noncePrefix"),
            )
            val file = sink.bytes()
            assertEquals(v.getValue("fileSize").jsonPrimitive.long, file.size.toLong(), name)
            assertEquals(v.s("fileSha256"), p.sha256Of(file).hx(), name)
            assertEquals(v.s("fileSha256"), w.ciphertextSha256.hx(), name)
            assertEquals(v.s("plaintextSha256"), w.plaintextSha256.hx(), name)
            if (v.containsKey("file")) assertContentEquals(v.b("file"), file, name)
            val keys = FolderKeys { e -> if (e == epoch) v.b("folderKey") else null }
            val opened = Dpx(p).decryptBytes(keys, v.s("inner"), file, expectedPlaintextSha256 = h(v.s("plaintextSha256"))).first
            assertContentEquals(plaintext, opened, name)
        }
    }

    @Test
    fun keysFileVectors() {
        val k = dpx.need("keys").jsonObject
        val files = KeysFile(FakeRandom(p, k.s("seed")))
        val devices = k.arr("devices").map { d ->
            val pair = stage("keys deriveKeyPair") { Hpke(p).deriveKeyPair(d.h("ikm")) }
            pair to KeysFile.NewDevice(pair.publicKey, d.s("name"), DevicePlatform.entries.single { it.wire == d.s("platform") })
        }
        var recovery = RecoveryKey.fromBytes(k.h("recoveryBytes"))
        var opened: OpenedKeys? = null
        for (step in k.arr("steps")) {
            val now = step.getValue("now").jsonPrimitive.long
            val written = when (val op = step.s("op")) {
                "create" -> files.createFirstDevice(devices[0].second, recovery, now)
                "addDevice" -> files.addDevice(
                    checkNotNull(opened),
                    kidOf(p, devices[step.getValue("approver").jsonPrimitive.int].second.publicKey),
                    devices[step.getValue("device").jsonPrimitive.int].second, now,
                )
                "newEpoch" -> {
                    recovery = RecoveryKey.fromBytes(step.h("newRecoveryBytes"))
                    files.newEpoch(
                        checkNotNull(opened), now,
                        revokeKid = kidOf(p, devices[step.getValue("revoke").jsonPrimitive.int].second.publicKey),
                        newRecovery = recovery,
                    )
                }
                else -> error(op)
            }
            assertEquals(step.s("file"), Bytes.b64(written.bytes), step.s("op"))
            opened = written.opened
            val folderKeys = step.getValue("folderKeys").jsonObject
            for (d in step.getValue("openers").jsonArray.map { it.jsonPrimitive.int }) {
                val current = checkNotNull(Bytes.unb64(folderKeys.getValue(written.opened.epoch.toString()).jsonPrimitive.content))
                val o = KeysFile(p).openFirstPin(written.bytes, devices[d].first, KeysGuard(p, Marks()), current)
                for ((epoch, key) in folderKeys) assertEquals(key.jsonPrimitive.content, Bytes.b64(o.folderKey(epoch.toInt())!!))
            }
            val r = KeysFile(p).openWithRecovery(written.bytes, recovery, KeysGuard(p, Marks()))
            for ((epoch, key) in folderKeys) assertEquals(key.jsonPrimitive.content, Bytes.b64(r.folderKey(epoch.toInt())!!))
        }
    }

    private fun runHpke(v: JsonObject) {
        assertEquals(0, v.getValue("mode").jsonPrimitive.int)
        val aead = Hpke.Aead.entries.single { it.id == v.getValue("aead_id").jsonPrimitive.int }
        val hpke = Hpke(p, aead)
        val skE = hpke.deriveKeyPair(v.h("ikmE"))
        val skR = hpke.deriveKeyPair(v.h("ikmR"))
        assertEquals(v.s("pkEm"), skE.publicKey.hx())
        assertEquals(v.s("pkRm"), skR.publicKey.hx())
        if (v.containsKey("skEm")) assertEquals(v.s("pkEm"), p.p256FromScalar(v.h("skEm")).publicKey.hx())
        if (v.containsKey("skRm")) assertEquals(v.s("pkRm"), p.p256FromScalar(v.h("skRm")).publicKey.hx())
        val (shared, enc) = hpke.encap(skR.publicKey, skE)
        assertEquals(v.s("enc"), enc.hx())
        assertEquals(v.s("shared_secret"), shared.hx())
        assertEquals(v.s("shared_secret"), hpke.decap(enc, skR).hx())
        val schedule = hpke.keySchedule(Hpke.MODE_BASE, shared, v.h("info"), ByteArray(0), ByteArray(0))
        assertEquals(v.s("key"), schedule.key.hx())
        assertEquals(v.s("base_nonce"), schedule.baseNonce.hx())
        assertEquals(v.s("exporter_secret"), schedule.exporterSecret.hx())
        val sender = hpke.setupBaseS(skR.publicKey, v.h("info"), skE)
        val receiver = hpke.setupBaseR(enc, skR, v.h("info"))
        var seq = 0
        for (e in v.arr("encryptions")) {
            val target = e.getValue("seq").jsonPrimitive.int
            while (seq < target) {
                receiver.open(ByteArray(0), sender.context.seal(ByteArray(0), ByteArray(0)))
                seq++
            }
            val ct = sender.context.seal(e.h("aad"), e.h("pt"))
            assertEquals(e.s("ct"), ct.hx(), "seq $target")
            assertContentEquals(e.h("pt"), receiver.open(e.h("aad"), ct))
            seq++
        }
    }

    @Test
    fun hpkeOfficialAndRegressionVectors() {
        val hpke = load("hpke-vectors.json").need("hpke").jsonObject
        val official = hpke.arr("official")
        val regression = hpke.arr("regression")
        assertTrue(official.isNotEmpty() && regression.isNotEmpty())
        (official + regression).forEachIndexed { i, v -> stage("hpke vector $i (${v["source"]})") { runHpke(v) } }
    }
}
