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

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The fake Drive behind Drive v3's HTTP surface, for Ktor's [MockEngine] (S4b-BL-115): [HttpDriveClient] talks to it as
 * to Google, so `DriveClientContract` runs the same cases on the real client and on [InMemoryFakeDrive]. It reads only
 * what [HttpDriveClient] sends (its `q` grammar, its multipart), answers as Drive does (JSON with sizes as strings,
 * 308 with `Range`, `Location` for a session, Google's error JSON with a reason) and turns a no-answer fault into a
 * lost connection ([IOException]).
 */
class FakeDriveHttp(val server: FakeDriveServer) {
    /** Every request as `METHOD path?query`, for the request-shape tests. */
    val seen = mutableListOf<HttpRequestData>()

    val engine = MockEngine { request -> handle(request) }

    private suspend fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        seen += request
        val url = request.url
        check(url.protocol.name == "https" && url.host == "www.googleapis.com") { "not Google: ${url.host}" }
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ").orEmpty()
        val body = request.body.toByteArray()
        val path = url.encodedPath
        val p = url.parameters
        return try {
            when {
                path == "/drive/v3/about" -> json(aboutJson(server.about(token)))
                path == "/drive/v3/files" && request.method == HttpMethod.Get -> {
                    val page = server.list(token, parseQ(p["q"].orEmpty()), p["pageToken"], p["pageSize"]!!.toInt())
                    json(driveJson.encodeToString(ListWire.serializer(), ListWire(page.files.map(FileWire::of), page.nextPageToken, page.incompleteSearch)))
                }
                path == "/drive/v3/files" && request.method == HttpMethod.Post -> file(server.create(token, newFile(obj(body))))
                path.startsWith("/drive/v3/files/") && path.endsWith("/revisions") -> {
                    val revs = server.revisions(token, path.removePrefix("/drive/v3/files/").removeSuffix("/revisions"))
                    json(driveJson.encodeToString(RevisionsWire.serializer(), RevisionsWire(revs.map {
                        RevisionWire(it.id, app.doorprints.shared.api.IsoTime.format(it.modifiedTime), it.size?.toString())
                    })))
                }
                path.startsWith("/drive/v3/files/") -> {
                    val id = path.removePrefix("/drive/v3/files/")
                    when (request.method) {
                        HttpMethod.Get -> if (p["alt"] == "media") media(token, id, request.headers[HttpHeaders.Range]) else file(server.get(token, id))
                        HttpMethod.Delete -> {
                            server.delete(token, id)
                            respond(ByteArray(0), HttpStatusCode.NoContent)
                        }
                        HttpMethod.Patch -> {
                            val o = obj(body)
                            if (o["trashed"]?.jsonPrimitive?.booleanOrNull == true) file(server.trash(token, id))
                            else file(server.update(token, id, change(o, p["addParents"], p["removeParents"])))
                        }
                        else -> error("unexpected ${request.method}")
                    }
                }
                path.startsWith("/upload/drive/v3/files") && p["upload_id"] != null -> session(token, url.toString(), request, body)
                path.startsWith("/upload/drive/v3/files") -> {
                    val existing = path.removePrefix("/upload/drive/v3/files").removePrefix("/").ifEmpty { null }
                    when (p["uploadType"]) {
                        "multipart" -> {
                            val (meta, mime, content) = multipart(request, body)
                            file(server.upload(token, target(existing, meta, mime), content))
                        }
                        "resumable" -> {
                            val mime = request.headers["X-Upload-Content-Type"]!!
                            val size = request.headers["X-Upload-Content-Length"]!!.toLong()
                            val s = server.startUpload(token, target(existing, obj(body), mime), size)
                            respond(ByteArray(0), HttpStatusCode.OK, headersOf(HttpHeaders.Location, s.uri))
                        }
                        else -> error("uploadType")
                    }
                }
                else -> error("unexpected $path")
            }
        } catch (e: DriveException) {
            if (e.kind == DriveException.Kind.OFFLINE) throw IOException("connection dropped")
            errorAnswer(e)
        }
    }

    private suspend fun MockRequestHandleScope.session(token: String, uri: String, request: HttpRequestData, body: ByteArray): HttpResponseData {
        val range = request.headers[HttpHeaders.ContentRange]!!
        val progress = if (range.startsWith("bytes */")) {
            server.uploadStatus(token, uri)
        } else {
            val offset = range.removePrefix("bytes ").substringBefore('-').toLong()
            server.uploadChunk(token, uri, offset, body)
        }
        return when (progress) {
            is UploadProgress.Complete -> file(progress.file)
            is UploadProgress.Incomplete -> respond(
                ByteArray(0), HttpStatusCode(308, "Resume Incomplete"),
                if (progress.received > 0) headersOf(HttpHeaders.Range, "bytes=0-${progress.received - 1}") else Headers.Empty,
            )
        }
    }

    private suspend fun MockRequestHandleScope.media(token: String, id: String, range: String?): HttpResponseData {
        val r = range?.removePrefix("bytes=")?.split('-')?.let { (a, b) -> a.toLong()..b.toLong() }
        val bytes = server.download(token, id, r)
        return respond(bytes, if (r == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
            headersOf(HttpHeaders.ContentType, "application/octet-stream"))
    }

    private fun MockRequestHandleScope.file(f: DriveFile) = json(driveJson.encodeToString(FileWire.serializer(), FileWire.of(f)))

    private fun MockRequestHandleScope.json(text: String) =
        respond(text, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json; charset=UTF-8"))

    private fun MockRequestHandleScope.errorAnswer(e: DriveException): HttpResponseData {
        val reason = e.reason?.let { ""","errors":[{"domain":"global","reason":"$it","message":"x"}]""" }.orEmpty()
        val headers = buildList {
            add(HttpHeaders.ContentType to listOf("application/json; charset=UTF-8"))
            e.retryAfterMs?.let { add(HttpHeaders.RetryAfter to listOf(((it + 999) / 1000).toString())) }
        }
        return respond(
            """{"error":{"code":${e.httpStatus},"message":"x"$reason}}""",
            HttpStatusCode.fromValue(e.httpStatus), headersOf(*headers.toTypedArray()),
        )
    }

    private fun aboutJson(a: DriveAbout) =
        """{"user":{"emailAddress":"${a.email}","displayName":"${a.displayName}"},""" +
            """"storageQuota":{${a.quotaLimit?.let { "\"limit\":\"$it\"," }.orEmpty()}"usage":"${a.quotaUsage}","usageInDrive":"${a.quotaUsageInDrive}"}}"""

    private fun obj(body: ByteArray): JsonObject =
        if (body.isEmpty()) JsonObject(emptyMap()) else driveJson.parseToJsonElement(body.decodeToString()).jsonObject

    private fun newFile(o: JsonObject) = NewFile(
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        mimeType = o["mimeType"]?.jsonPrimitive?.content ?: "application/octet-stream",
        parents = (o["parents"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
        appProperties = (o["appProperties"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
    )

    private fun change(o: JsonObject, add: String?, remove: String?) = MetadataChange(
        name = o["name"]?.jsonPrimitive?.contentOrNull,
        appProperties = (o["appProperties"] as? JsonObject)?.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.content }.orEmpty(),
        addParents = add?.split(',').orEmpty(),
        removeParents = remove?.split(',').orEmpty(),
    )

    private fun target(existing: String?, meta: JsonObject, mime: String): UploadTarget =
        if (existing != null) UploadTarget.Existing(existing, mime, change(meta, null, null))
        else UploadTarget.New(newFile(meta).copy(mimeType = mime))

    /** Drive's multipart/related: the JSON part, then the content part (read as bytes: the content may be anything). */
    private fun multipart(request: HttpRequestData, body: ByteArray): Triple<JsonObject, String, ByteArray> {
        val boundary = request.body.contentType.toString().substringAfter("boundary=")
        fun find(needle: String, from: Int): Int {
            val n = needle.encodeToByteArray()
            outer@ for (i in from..body.size - n.size) {
                for (j in n.indices) if (body[i + j] != n[j]) continue@outer
                return i
            }
            error("no '$needle' in the multipart body")
        }
        val metaStart = find("\r\n\r\n", 0) + 4
        val metaEnd = find("\r\n--$boundary\r\n", metaStart)
        val secondHead = metaEnd + "\r\n--$boundary\r\n".length
        val contentStart = find("\r\n\r\n", secondHead) + 4
        val mime = body.copyOfRange(secondHead, contentStart - 4).decodeToString().substringAfter("Content-Type: ").trim()
        val end = body.size - "\r\n--$boundary--\r\n".length
        val meta = driveJson.parseToJsonElement(body.copyOfRange(metaStart, metaEnd).decodeToString()).jsonObject
        return Triple(meta, mime, body.copyOfRange(contentStart, end))
    }

    companion object {
        /** Reads back what [DriveQuery.toQ] writes (and nothing else). */
        fun parseQ(q: String): DriveQuery {
            var query = DriveQuery(trashed = null)
            var i = 0
            fun literal(): String {
                check(q[i] == '\'') { "literal at $i" }
                i++
                val out = StringBuilder()
                while (q[i] != '\'') {
                    if (q[i] == '\\') i++
                    out.append(q[i++])
                }
                i++
                return out.toString()
            }
            fun expect(text: String) {
                check(q.startsWith(text, i)) { "expected '$text' at $i in $q" }
                i += text.length
            }
            val props = mutableMapOf<String, String>()
            while (i < q.length) {
                when {
                    q[i] == '\'' -> {
                        val parent = literal()
                        expect(" in parents")
                        query = query.copy(parentId = parent)
                    }
                    q.startsWith("name = ", i) -> { expect("name = "); query = query.copy(name = literal()) }
                    q.startsWith("mimeType = ", i) -> { expect("mimeType = "); query = query.copy(mimeType = literal()) }
                    q.startsWith("appProperties has { key=", i) -> {
                        expect("appProperties has { key=")
                        val k = literal()
                        expect(" and value=")
                        props[k] = literal()
                        expect(" }")
                    }
                    q.startsWith("trashed = ", i) -> {
                        expect("trashed = ")
                        val value = q.startsWith("true", i)
                        i += if (value) 4 else 5
                        query = query.copy(trashed = value)
                    }
                    else -> error("cannot read q at $i: $q")
                }
                if (i < q.length) expect(" and ")
            }
            return query.copy(appProperties = props)
        }
    }
}
