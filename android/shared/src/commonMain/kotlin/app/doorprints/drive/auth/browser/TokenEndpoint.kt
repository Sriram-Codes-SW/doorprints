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

package app.doorprints.drive.auth.browser

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What Google's token endpoint answered. No token appears in a `toString`. */
sealed interface TokenResult {
    class Tokens(val accessToken: String, val refreshToken: String?, val scopes: Set<String>) : TokenResult {
        override fun toString() = "Tokens(scopes=$scopes)"
    }

    /** An OAuth error body (`invalid_grant`, `invalid_client`, ...). */
    data class Rejected(val error: String) : TokenResult

    /** A 5xx or an answer that is not understood. */
    data object ServerError : TokenResult
}

/** Google's token and revocation endpoints, for a native client: PKCE, no client secret (docs/15 §2.4). A network failure is an [IOException] (`kotlinx.io`, which is `java.io.IOException` on Android). */
interface TokenEndpoint {
    @Throws(IOException::class, CancellationException::class)
    suspend fun exchangeCode(clientId: String, redirectUri: String, code: String, verifier: String): TokenResult

    @Throws(IOException::class, CancellationException::class)
    suspend fun refresh(clientId: String, refreshToken: String): TokenResult

    /** Revokes a refresh or an access token (and with it the grant). Throws on a network failure or Google's refusal. */
    @Throws(IOException::class, CancellationException::class)
    suspend fun revoke(token: String)
}

/**
 * How Google's token endpoint's answer is read (both platforms' endpoints use it): a 5xx or an unreadable body is
 * [TokenResult.ServerError]; a 4xx with an `error` is [TokenResult.Rejected]; a 2xx needs an `access_token`.
 */
object TokenAnswers {
    fun parse(status: Int, body: String): TokenResult {
        if (status >= 500) return TokenResult.ServerError
        val json: JsonObject = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return TokenResult.ServerError
        }
        if (status !in 200..299) {
            return json["error"]?.jsonPrimitive?.contentOrNull?.let { TokenResult.Rejected(it) } ?: TokenResult.ServerError
        }
        val access = json["access_token"]?.jsonPrimitive?.contentOrNull
        if (access.isNullOrBlank()) return TokenResult.ServerError
        val scopes = json["scope"]?.jsonPrimitive?.contentOrNull.orEmpty().split(' ').filter { it.isNotEmpty() }.toSet()
        return TokenResult.Tokens(access, json["refresh_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }, scopes)
    }

    /** The `application/x-www-form-urlencoded` body of [form] (percent-encoded keys and values). */
    fun formBody(form: List<Pair<String, String>>): String =
        form.joinToString("&") { (k, v) -> k.encodeURLParameter() + "=" + v.encodeURLParameter() }
}

/**
 * [TokenEndpoint] over the app's Ktor client (the iPhone's: `IosApiHttp`'s Darwin engine on `NSURLSession`). Hand it a client
 * that follows no redirect and throws for no status (`ApiHttp.client`). Any failure to reach Google is an [IOException];
 * the answer is read by [TokenAnswers]. Nothing is logged and no exception carries a token or a code.
 */
class KtorTokenEndpoint(
    private val http: HttpClient,
    private val tokenUrl: String = GOOGLE_TOKEN_URL,
    private val revokeUrl: String = GOOGLE_REVOKE_URL,
) : TokenEndpoint {

    override suspend fun exchangeCode(clientId: String, redirectUri: String, code: String, verifier: String): TokenResult {
        val (status, body) = post(
            tokenUrl,
            listOf(
                "client_id" to clientId, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to redirectUri, "code_verifier" to verifier,
            ),
        )
        return TokenAnswers.parse(status, body)
    }

    override suspend fun refresh(clientId: String, refreshToken: String): TokenResult {
        val (status, body) = post(
            tokenUrl,
            listOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken),
        )
        return TokenAnswers.parse(status, body)
    }

    override suspend fun revoke(token: String) {
        val (status, _) = post(revokeUrl, listOf("token" to token))
        if (status !in 200..299) throw IOException("revoke refused ($status)")
    }

    private suspend fun post(url: String, form: List<Pair<String, String>>): Pair<Int, String> =
        try {
            val response = http.request {
                method = HttpMethod.Post
                url(url)
                setBody(TextContent(TokenAnswers.formBody(form), ContentType.Application.FormUrlEncoded))
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            // Whatever the engine threw (a timeout, a TLS or DNS failure): the message could name the URL, never the form.
            throw IOException("the token endpoint could not be reached")
        }

    companion object {
        const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val GOOGLE_REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    }
}
