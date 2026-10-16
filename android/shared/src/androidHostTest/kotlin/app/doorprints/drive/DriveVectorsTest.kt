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

package app.doorprints.drive

import app.doorprints.shared.api.ApiHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared Drive vectors (`docs/schemas/drive-vectors.json`, docs/schemas/README.md section 6.2, S4b-BL-115): the
 * `q` strings, every request and answer of the exchanges, the error mapping and the backoff rule, the same here as in
 * the website's `drive-vectors.spec.ts`, so [HttpDriveClient] and `FetchDriveClient` say the same things to Drive.
 */
class DriveVectorsTest {

    private val root: JsonObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, VECTORS).exists()) dir = dir.parentFile
        val file = File(checkNotNull(dir) { "$VECTORS not found" }, VECTORS)
        Json.parseToJsonElement(file.readText()).jsonObject.also {
            assertEquals("doorprints-drive-vectors/1", it.getValue("format").jsonPrimitive.content)
        }
    }

    private fun cases(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.name() = getValue("name").jsonPrimitive.content
    private fun JsonObject.str(key: String) = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.obj(key: String) = get(key)?.takeIf { it !is JsonNull }?.jsonObject
    private fun JsonObject.strings(key: String): Map<String, String> = obj(key)?.mapValues { it.value.jsonPrimitive.content }.orEmpty()

    private fun query(o: JsonObject) = DriveQuery(
        parentId = o.str("parentId"),
        appProperties = o.strings("appProperties"),
        mimeType = o.str("mimeType"),
        name = o.str("name"),
        trashed = if ("trashed" in o) o["trashed"]?.jsonPrimitive?.booleanOrNull else false,
    )

    private fun newFile(o: JsonObject) = NewFile(
        o.getValue("name").jsonPrimitive.content, o.getValue("mimeType").jsonPrimitive.content,
        o["parents"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(), o.strings("appProperties"),
    )

    private fun content(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    @Test
    fun everyQueryIsWrittenTheSame() {
        for (c in cases("queries")) assertEquals(c.name(), c.str("q"), query(c.getValue("query").jsonObject).toQ())
    }

    @Test
    fun everyErrorMapsTheSame() = runTest {
        for (c in cases("errors")) {
            val headers = c.strings("headers")
            val body = c["body"]?.takeIf { it !is JsonNull }?.let { if (it is JsonPrimitive) it.content else it.toString() }
            val engine = MockEngine {
                respond(body.orEmpty(), HttpStatusCode.fromValue(c.getValue("status").jsonPrimitive.int),
                    HeadersBuilder().apply { headers.forEach { (k, v) -> append(k, v) } }.build())
            }
            val now = c["nowMs"]?.jsonPrimitive?.long ?: 0
            val client = HttpDriveClient(ApiHttp.client(engine), FakeTokenProvider(), DriveRetry(maxAttempts = 1), now = { now })
            val e = try {
                client.getFile("fA")
                fail(c.name())
                error("unreachable")
            } catch (e: DriveException) {
                e
            }
            assertEquals(c.name(), c.str("kind"), e.kind.name)
            assertEquals(c.name(), c["retryAfterMs"]?.jsonPrimitive?.longOrNull, e.retryAfterMs)
            assertEquals(c.name(), c.str("reason"), e.reason)
            assertEquals(c.name(), c.getValue("retryable").jsonPrimitive.boolean, DriveRetry().waitFor(1, e) != null)
            assertTrue("no token in the message", "token" !in e.message.orEmpty())
        }
    }

    @Test
    fun theBackoffWaitsTheSame() {
        val config = root.getValue("backoff").jsonObject.getValue("config").jsonObject
        for (c in root.getValue("backoff").jsonObject.getValue("cases").jsonArray.map { it.jsonObject }) {
            val retry = DriveRetry(
                maxAttempts = c["maxAttempts"]?.jsonPrimitive?.int ?: config.getValue("maxAttempts").jsonPrimitive.int,
                baseMs = config.getValue("baseMs").jsonPrimitive.long,
                capMs = config.getValue("capMs").jsonPrimitive.long,
                maxRetryAfterMs = config.getValue("maxRetryAfterMs").jsonPrimitive.long,
                random = { c.getValue("random").jsonPrimitive.double },
            )
            val error = DriveException(DriveException.Kind.valueOf(c.str("kind")!!), retryAfterMs = c["retryAfterMs"]?.jsonPrimitive?.longOrNull)
            assertEquals(c.toString(), c["waitMs"]?.jsonPrimitive?.longOrNull, retry.waitFor(c.getValue("attempt").jsonPrimitive.int, error))
        }
        assertEquals(DriveRetry().maxAttempts, config.getValue("maxAttempts").jsonPrimitive.int)
        assertEquals(DriveRetry().capMs, config.getValue("capMs").jsonPrimitive.long)
    }

    @Test
    fun everyExchangeAsksAndReadsTheSame() = runTest {
        for (c in cases("exchanges")) {
            val steps = c.getValue("steps").jsonArray.map { it.jsonObject }
            val call = c.getValue("call").jsonObject
            val upload = call["size"]?.let { content(it.jsonPrimitive.int, call.getValue("seed").jsonPrimitive.int) }
            var n = 0
            val engine = MockEngine { request ->
                val step = steps.getOrNull(n++) ?: error("${c.name()}: request ${n} not in the vector")
                val want = step.getValue("request").jsonObject
                val where = "${c.name()} step $n"
                assertEquals(where, want.str("method"), request.method.value)
                assertEquals(where, want.str("url"), "${request.url.protocol.name}://${request.url.host}${request.url.encodedPath}")
                val params = request.url.parameters.entries().associate { (k, v) -> k to v.single() }
                assertEquals(where, want.strings("params"), params)
                for ((k, v) in want.strings("headers")) assertEquals("$where $k", v, request.headers[k])
                val body = request.body.toByteArray()
                want["json"]?.let { expected ->
                    assertTrue(where, request.body.contentType.toString().startsWith("application/json"))
                    assertEquals(where, expected, Json.parseToJsonElement(body.decodeToString()))
                }
                want["bodyLength"]?.let { len ->
                    val from = want.getValue("bodyFrom").jsonPrimitive.int
                    assertEquals(where, upload!!.copyOfRange(from, from + len.jsonPrimitive.int).toList(), body.toList())
                }
                want.obj("multipart")?.let { m ->
                    val type = request.body.contentType.toString()
                    assertTrue(where, type.startsWith("multipart/related; boundary="))
                    val boundary = type.substringAfter("boundary=")
                    val text = body.decodeToString()
                    val json = text.substringAfter("Content-Type: application/json; charset=UTF-8\r\n\r\n").substringBefore("\r\n--$boundary")
                    assertEquals(where, m.getValue("json"), Json.parseToJsonElement(json))
                    assertTrue(where, "\r\n--$boundary\r\nContent-Type: ${m.str("mimeType")}\r\n\r\n" in text)
                    assertTrue(where, text.endsWith("\r\n--$boundary--\r\n"))
                    assertEquals(where, upload!!.toList(), body.copyOfRange(body.size - "\r\n--$boundary--\r\n".length - upload.size, body.size - "\r\n--$boundary--\r\n".length).toList())
                }
                if (step["drop"]?.jsonPrimitive?.boolean == true) throw IOException("dropped")
                val answer = step.getValue("response").jsonObject
                val text = answer["body"]?.takeIf { it !is JsonNull }?.toString().orEmpty()
                respond(text, HttpStatusCode.fromValue(answer.getValue("status").jsonPrimitive.int),
                    HeadersBuilder().apply { answer.strings("headers").forEach { (k, v) -> append(k, v) } }.build())
            }
            val sleeps = mutableListOf<Long>()
            val tokens = FakeTokenProvider()
            val drive = HttpDriveClient(ApiHttp.client(engine), tokens, DriveRetry(random = { 0.5 }, sleep = { sleeps += it }))
            val result = c.getValue("result").jsonObject
            try {
                when (call.str("op")) {
                    "listAll" -> {
                        val files = drive.listAll(query(call.getValue("query").jsonObject), call.getValue("pageSize").jsonPrimitive.int)
                        assertEquals(result.getValue("ids").jsonArray.map { it.jsonPrimitive.content }, files.map { it.id })
                        val first = result.getValue("first").jsonObject
                        assertEquals(first.getValue("size").jsonPrimitive.long, files[0].size)
                        assertEquals(first.str("sha256Checksum"), files[0].sha256Checksum)
                        assertEquals(first.getValue("createdTime").jsonPrimitive.long, files[0].createdTime)
                        assertEquals(first.strings("appProperties"), files[0].appProperties)
                        assertEquals(first.getValue("parents").jsonArray.map { it.jsonPrimitive.content }, files[0].parents)
                    }
                    "getFile" -> assertEquals(result.str("id"), drive.getFile(call.str("fileId")!!).id)
                    "about" -> {
                        val about = drive.about()
                        assertEquals(result.str("email"), about.email)
                        assertEquals(result.getValue("quotaLimit").jsonPrimitive.long, about.quotaLimit)
                        assertEquals(result.getValue("quotaUsage").jsonPrimitive.long, about.quotaUsage)
                    }
                    "uploadResumable" -> {
                        val file = drive.uploadResumable(
                            UploadTarget.New(newFile(call.getValue("file").jsonObject)), upload!!.size.toLong(),
                            { offset, length -> upload.copyOfRange(offset.toInt(), offset.toInt() + length) },
                            chunkSize = call.getValue("chunkSize").jsonPrimitive.int,
                        )
                        assertEquals(result.str("id"), file.id)
                        assertEquals(result.str("sha256Checksum"), file.sha256Checksum)
                        assertEquals(sha256HexOf(upload), file.sha256Checksum)
                    }
                    "updateMetadata" -> {
                        val change = call.getValue("change").jsonObject
                        val props = change.getValue("appProperties").jsonObject.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
                        assertEquals(result.str("id"), drive.updateMetadata(call.str("fileId")!!, MetadataChange(change.str("name"), props)).id)
                    }
                    "delete" -> drive.delete(call.str("fileId")!!)
                    "upload" -> drive.upload(UploadTarget.New(newFile(call.getValue("file").jsonObject)), upload!!)
                    else -> fail("unknown op in ${c.name()}")
                }
                assertNull(c.name(), result.str("error"))
            } catch (e: DriveException) {
                assertEquals(c.name(), result.str("error"), e.kind.name)
            }
            assertEquals(c.name(), steps.size, n)
            assertEquals(c.name(), result.getValue("sleeps").jsonArray.map { it.jsonPrimitive.long }, sleeps)
            result["rejected"]?.let { r -> assertEquals(c.name(), r.jsonArray.map { it.jsonPrimitive.content }, tokens.rejected) }
        }
    }

    private companion object {
        const val VECTORS = "docs/schemas/drive-vectors.json"
    }
}
