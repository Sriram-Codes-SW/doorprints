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

import app.doorprints.shared.api.IsoTime
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * [DriveClient] over Ktor against Google's Drive v3 endpoints (S4b-BL-115; web: `FetchDriveClient`). Give it the app's
 * shared [HttpClient] made by `ApiHttp.client(engine)` (no redirects followed, no exception for a status): a redirect
 * is reported as [DriveException.Kind.OFFLINE] (a captive portal), never followed with the token.
 *
 * - The `Authorization` header goes only to `https://www.googleapis.com` ([isGoogleApi]); a session URI from anywhere
 *   else is refused as [DriveException.Kind.CORRUPT] before anything is sent.
 * - File ids are checked ([checkId]) before they go into a path.
 * - Nothing is logged; the exceptions carry the status and Drive's reason only.
 */
class HttpDriveClient(
    private val http: HttpClient,
    private val tokens: TokenProvider,
    override val retry: DriveRetry = DriveRetry(),
    private val now: () -> Long = IsoTime::nowMillis,
    private val random: Random = Random.Default,
) : DriveClient {

    private class Exchange(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    override suspend fun about(): DriveAbout =
        json(HttpMethod.Get, url(API, "about", "fields" to DriveFields.ABOUT), AboutWire.serializer()).toModel()

    override suspend fun list(query: DriveQuery, pageToken: String?, pageSize: Int): DrivePage {
        require(pageSize in 1..1000)
        val target = url(
            API, "files",
            "q" to query.toQ(), "fields" to DriveFields.LIST, "pageSize" to pageSize.toString(),
            "orderBy" to "createdTime", "spaces" to "drive", "pageToken" to pageToken,
        )
        val page = json(HttpMethod.Get, target, ListWire.serializer())
        return DrivePage(page.files.map { it.toModel() }, page.nextPageToken, page.incompleteSearch)
    }

    override suspend fun getFile(fileId: String): DriveFile =
        json(HttpMethod.Get, url(API, "files/${checkId(fileId)}", "fields" to DriveFields.FILE), FileWire.serializer())
            .toModel()

    override suspend fun createFile(file: NewFile): DriveFile =
        json(HttpMethod.Post, url(API, "files", "fields" to DriveFields.FILE), FileWire.serializer(), jsonBody(newFileJson(file)))
            .toModel()

    override suspend fun upload(target: UploadTarget, content: ByteArray): DriveFile {
        require(content.size <= DriveClient.MULTIPART_LIMIT) { "use uploadResumable above 5 MiB" }
        val (method, path, meta) = uploadRequest(target)
        val boundary = boundaryFor(content)
        val body = multipart(boundary, meta.toString(), target.mimeType, content)
        return json(
            method, url(UPLOAD, path, "uploadType" to "multipart", "fields" to DriveFields.FILE), FileWire.serializer(),
            { ByteArrayContent(body, ContentType.parse("multipart/related; boundary=$boundary")) },
        ).toModel()
    }

    override suspend fun startUpload(target: UploadTarget, size: Long): UploadSession {
        require(size > 0)
        val (method, path, meta) = uploadRequest(target)
        val answer = call(
            method, url(UPLOAD, path, "uploadType" to "resumable", "fields" to DriveFields.FILE),
            mapOf("X-Upload-Content-Type" to target.mimeType, "X-Upload-Content-Length" to size.toString()),
            jsonBody(meta),
        )
        val location = answer.header(HttpHeaders.Location) ?: throw corrupt("noSession")
        if (!isGoogleApi(location)) throw corrupt("insecureUrl")
        return UploadSession(location, size, (target as? UploadTarget.Existing)?.fileId)
    }

    override suspend fun uploadChunk(session: UploadSession, offset: Long, bytes: ByteArray): UploadProgress {
        require(bytes.isNotEmpty() && offset >= 0 && offset + bytes.size <= session.size)
        val range = "bytes $offset-${offset + bytes.size - 1}/${session.size}"
        return progress(session, range) { ByteArrayContent(bytes, ContentType.Application.OctetStream) }
    }

    override suspend fun uploadStatus(session: UploadSession): UploadProgress =
        progress(session, "bytes */${session.size}", null)

    override suspend fun updateMetadata(fileId: String, change: MetadataChange): DriveFile {
        val target = url(
            API, "files/${checkId(fileId)}", "fields" to DriveFields.FILE,
            "addParents" to change.addParents.takeIf { it.isNotEmpty() }?.onEach(::checkId)?.joinToString(","),
            "removeParents" to change.removeParents.takeIf { it.isNotEmpty() }?.onEach(::checkId)?.joinToString(","),
        )
        return json(HttpMethod.Patch, target, FileWire.serializer(), jsonBody(changeJson(change))).toModel()
    }

    override suspend fun download(fileId: String, range: LongRange?): ByteArray {
        if (range != null) require(range.first >= 0 && range.last >= range.first)
        val headers = range?.let { mapOf(HttpHeaders.Range to "bytes=${it.first}-${it.last}") }.orEmpty()
        return call(HttpMethod.Get, url(API, "files/${checkId(fileId)}", "alt" to "media"), headers).body
    }

    override suspend fun delete(fileId: String) {
        try {
            call(HttpMethod.Delete, url(API, "files/${checkId(fileId)}"))
        } catch (e: DriveException) {
            if (e.kind != DriveException.Kind.NOT_FOUND) throw e
        }
    }

    override suspend fun trash(fileId: String): DriveFile =
        json(
            HttpMethod.Patch, url(API, "files/${checkId(fileId)}", "fields" to DriveFields.FILE), FileWire.serializer(),
            jsonBody(JsonObject(mapOf("trashed" to JsonPrimitive(true)))),
        ).toModel()

    override suspend fun revisions(fileId: String): List<DriveRevision> {
        val out = mutableListOf<DriveRevision>()
        var token: String? = null
        do {
            val target = url(
                API, "files/${checkId(fileId)}/revisions",
                "fields" to DriveFields.REVISIONS, "pageSize" to "200", "pageToken" to token,
            )
            val page = json(HttpMethod.Get, target, RevisionsWire.serializer())
            out += page.revisions.map { it.toModel() }
            token = page.nextPageToken
        } while (token != null && out.size < MAX_REVISIONS)
        return out
    }

    // --- requests ---

    /** POST a new file or PATCH an existing one, on the upload endpoint; the metadata part as JSON. */
    private fun uploadRequest(target: UploadTarget): Triple<HttpMethod, String, JsonObject> = when (target) {
        is UploadTarget.New -> Triple(HttpMethod.Post, "files", newFileJson(target.file))
        is UploadTarget.Existing -> Triple(HttpMethod.Patch, "files/${checkId(target.fileId)}", changeJson(target.change))
    }

    /** One try of a session request (no [DriveRetry]: [uploadResumable] recovers), with the 401 rule. */
    private suspend fun progress(session: UploadSession, range: String, body: (() -> OutgoingContent)?): UploadProgress {
        if (!isGoogleApi(session.uri)) throw corrupt("insecureUrl")
        val answer = authorized(tokens) { token ->
            exchange(HttpMethod.Put, session.uri, mapOf(HttpHeaders.ContentRange to range), body ?: { EMPTY }, token,
                allowResume = true)
        }
        if (answer.status == 308) {
            // "Range: bytes=0-N" is what Drive has; none means nothing yet.
            val have = answer.header(HttpHeaders.Range)?.let { RANGE.matchEntire(it.trim()) }
            return UploadProgress.Incomplete(have?.groupValues?.get(1)?.toLong()?.plus(1) ?: 0)
        }
        return UploadProgress.Complete(decode(answer, FileWire.serializer()).toModel())
    }

    private suspend fun <T> json(
        method: HttpMethod,
        target: String,
        serializer: KSerializer<T>,
        body: (() -> OutgoingContent)? = null,
    ): T = decode(call(method, target, emptyMap(), body), serializer)

    private fun <T> decode(answer: Exchange, serializer: KSerializer<T>): T {
        val type = answer.header(HttpHeaders.ContentType).orEmpty().lowercase()
        if (!type.startsWith("application/json")) throw corrupt("notJson")
        return try {
            driveJson.decodeFromString(serializer, answer.body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw corrupt()
        }
    }

    /** One request with the retry rule and the 401 rule. */
    private suspend fun call(
        method: HttpMethod,
        target: String,
        headers: Map<String, String> = emptyMap(),
        body: (() -> OutgoingContent)? = null,
    ): Exchange = retry.run { authorized(tokens) { token -> exchange(method, target, headers, body, token) } }

    private suspend fun exchange(
        method: HttpMethod,
        target: String,
        headers: Map<String, String>,
        body: (() -> OutgoingContent)?,
        token: String,
        allowResume: Boolean = false,
    ): Exchange {
        if (!isGoogleApi(target)) throw corrupt("insecureUrl")
        val answer = try {
            val response = http.request {
                this.method = method
                url(target)
                header(HttpHeaders.Authorization, "Bearer $token")
                headers.forEach { (name, value) -> header(name, value) }
                if (body != null) setBody(body())
            }
            Exchange(
                response.status.value,
                response.headers.entries().associate { (name, values) -> name.lowercase() to values.joinToString(",") },
                response.bodyAsBytes(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw DriveException(DriveException.Kind.OFFLINE, cause = e)
        }
        if (answer.status in 200..299 || (allowResume && answer.status == 308)) return answer
        val text = if (answer.header(HttpHeaders.ContentType).orEmpty().contains("json")) answer.body.decodeToString() else null
        throw DriveException.fromHttp(answer.status, answer.header(HttpHeaders.RetryAfter), text, now())
    }

    private fun jsonBody(value: JsonObject): () -> OutgoingContent =
        { TextContent(value.toString(), ContentType.Application.Json.withParameter("charset", "UTF-8")) }

    private fun boundaryFor(content: ByteArray): String {
        while (true) {
            val boundary = "doorprints-" + random.nextLong().toULong().toString(16)
            if (!contains(content, boundary.encodeToByteArray())) return boundary
        }
    }

    companion object {
        const val API = "https://www.googleapis.com/drive/v3"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"

        private const val MAX_REVISIONS = 10_000
        private val RANGE = Regex("""^bytes=0-(\d+)$""")
        private val ID = Regex("^[A-Za-z0-9_-]{1,256}$")
        private val EMPTY = ByteArrayContent(ByteArray(0))

        /** Only Google's API host, over https, ever sees the token. */
        fun isGoogleApi(target: String): Boolean {
            val url = runCatching { Url(target) }.getOrNull() ?: return false
            return url.protocol.name == "https" && url.host == "www.googleapis.com" &&
                (url.specifiedPort == 0 || url.specifiedPort == 443)
        }

        /** A Drive id is letters, digits, `-` and `_`; anything else is refused before it reaches a URL path. */
        fun checkId(id: String): String {
            if (!ID.matches(id)) throw DriveException(DriveException.Kind.BAD_REQUEST, reason = "badId")
            return id
        }

        /** [base]/[path] with the query [params] (null values left out), Ktor's encoding. */
        internal fun url(base: String, path: String, vararg params: Pair<String, String?>): String =
            URLBuilder("$base/$path").apply {
                params.forEach { (k, v) -> if (v != null) parameters.append(k, v) }
            }.buildString()

        /** A `multipart/related` body: the metadata JSON, then the content (Drive's multipart upload). */
        internal fun multipart(boundary: String, metadata: String, mimeType: String, content: ByteArray): ByteArray {
            val head = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
                "--$boundary\r\nContent-Type: $mimeType\r\n\r\n"
            return head.encodeToByteArray() + content + "\r\n--$boundary--\r\n".encodeToByteArray()
        }

        private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
            if (needle.size > haystack.size) return false
            outer@ for (i in 0..haystack.size - needle.size) {
                for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
                return true
            }
            return false
        }
    }
}
