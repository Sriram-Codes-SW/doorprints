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

package app.doorprints.shared.ai

import app.doorprints.shared.api.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

/** What a provider answered: the status, its `Retry-After` header and the whole body. */
internal class AiReply(val status: Int, val retryAfter: String?, val body: String)

/**
 * The one way both AI adapters (docs/03 §13, ADR-35) call a provider, so one failure is worded one way:
 *  - the time limit covers the answer to the last byte of its body, not only the headers (a provider that sends headers
 *    and then stalls is a 504 [ApiException.Kind.AI_UNAVAILABLE]); a null [timeoutMs] turns it off (tests);
 *  - a network failure (no connection, no such host, refused, TLS) is [ApiException.Kind.AI_UNAVAILABLE] with code
 *    [UNREACHABLE];
 *  - a status is never an exception here and a redirect is never followed (both are set by `ApiHttp.client`, which the
 *    adapters are given), so the key in [headers] goes to the one address the person chose and a 3xx is just a status.
 * The caller maps [AiReply.status] to its own errors. Neither the key, the prompts nor the answer are logged.
 */
internal suspend fun postAiJson(
    http: HttpClient, url: String, headers: Map<String, String>, body: JsonObject, timeoutMs: Long?,
): AiReply {
    suspend fun exchange() = http.preparePost(url) {
        headers.forEach { (name, value) -> header(name, value) }
        contentType(ContentType.Application.Json)
        setBody(body.toString())
    }.execute { AiReply(it.status.value, it.headers["Retry-After"], it.bodyAsText()) }
    return try {
        (if (timeoutMs == null) exchange() else withTimeoutOrNull(timeoutMs) { exchange() })
            ?: throw ApiException(ApiException.Kind.AI_UNAVAILABLE, TIMED_OUT)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        throw e
    } catch (e: Exception) {
        throw ApiException(ApiException.Kind.AI_UNAVAILABLE, UNREACHABLE)
    }
}

/** The code of an [ApiException.Kind.AI_UNAVAILABLE] for a call that never got an answer. */
internal const val UNREACHABLE = 0

/** The code of an [ApiException.Kind.AI_UNAVAILABLE] for a call whose answer did not finish in time. */
internal const val TIMED_OUT = 504

/**
 * What a provider's HTTP status means, the same for every adapter (docs/03 §13.2): 401 and 403 a refused key, 404 an
 * unknown model, 429 a rate limit with the seconds of `Retry-After` when it gave a number, anything else (a 400, a 5xx,
 * Anthropic's 529 overloaded, a 3xx) unavailable with that status.
 */
internal fun aiFailure(status: Int, retryAfter: String?): ApiException = when (status) {
    401, 403 -> ApiException(ApiException.Kind.AI_KEY_REJECTED, status)
    404 -> ApiException(ApiException.Kind.AI_MODEL_NOT_FOUND, status)
    429 -> ApiException(ApiException.Kind.RATE_LIMITED, status, retryAfterSeconds = retryAfter?.trim()?.toLongOrNull())
    else -> ApiException(ApiException.Kind.AI_UNAVAILABLE, status)
}
