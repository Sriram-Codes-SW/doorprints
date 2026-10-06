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

import app.doorprints.testing.blocking
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** [KtorTokenEndpoint] against a mock engine, and [TokenAnswers] (the reading Android's `HttpTokenEndpoint` shares). */
class KtorTokenEndpointTest {
    private val seen = mutableListOf<HttpRequestData>()
    private var status = HttpStatusCode.OK
    private var body = ""
    private var failWith: Throwable? = null

    private val endpoint = KtorTokenEndpoint(
        HttpClient(
            MockEngine { request ->
                seen += request
                failWith?.let { throw it }
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        ),
    )

    private val good = """{"access_token":"acc","refresh_token":"ref","scope":"https://www.googleapis.com/auth/drive.file","token_type":"Bearer"}"""

    private fun form(r: HttpRequestData): Map<String, String> =
        blocking { r.body.toByteArray().decodeToString() }.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun theCodeExchangePostsAFormWithThePkceVerifierAndNoSecret() {
        body = good
        val answer = blocking { endpoint.exchangeCode("cid.apps.googleusercontent.com", "com.googleusercontent.apps.cid:/oauth2redirect", "4/0a b", "ver ifier") }
        val tokens = assertIs<TokenResult.Tokens>(answer)
        assertEquals("acc", tokens.accessToken)
        assertEquals("ref", tokens.refreshToken)
        assertEquals(setOf("https://www.googleapis.com/auth/drive.file"), tokens.scopes)
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://oauth2.googleapis.com/token", request.url.toString())
        assertTrue(request.body.contentType.toString().startsWith("application/x-www-form-urlencoded"))
        val f = form(request)
        assertEquals("authorization_code", f["grant_type"])
        assertEquals("cid.apps.googleusercontent.com", f["client_id"])
        assertEquals("4%2F0a%20b", f["code"])
        assertEquals("ver%20ifier", f["code_verifier"])
        assertEquals("com.googleusercontent.apps.cid%3A%2Foauth2redirect", f["redirect_uri"])
        assertNull(f["client_secret"], "a native client has no secret")
    }

    @Test
    fun theRefreshPostsTheRefreshTokenAndOnlyThat() {
        body = good
        assertIs<TokenResult.Tokens>(blocking { endpoint.refresh("cid", "1//refresh") })
        val f = form(seen.single())
        assertEquals("refresh_token", f["grant_type"])
        assertEquals("1%2F%2Frefresh", f["refresh_token"])
        assertEquals(setOf("grant_type", "client_id", "refresh_token"), f.keys)
    }

    @Test
    fun anOauthErrorIsRejectedAndAServerFaultOrJunkIsAServerError() {
        status = HttpStatusCode.BadRequest
        body = """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""
        assertEquals(TokenResult.Rejected("invalid_grant"), blocking { endpoint.refresh("cid", "r") })
        status = HttpStatusCode.ServiceUnavailable
        assertEquals(TokenResult.ServerError, blocking { endpoint.refresh("cid", "r") })
        status = HttpStatusCode.OK
        body = "<html>captive portal</html>"
        assertEquals(TokenResult.ServerError, blocking { endpoint.refresh("cid", "r") })
        body = """{"refresh_token":"r"}"""
        assertEquals(TokenResult.ServerError, blocking { endpoint.refresh("cid", "r") }, "an answer with no access token is no answer")
    }

    @Test
    fun aRefreshTokenThatComesBackBlankIsNoNewToken() {
        body = """{"access_token":"a","refresh_token":" ","scope":"s"}"""
        assertNull(assertIs<TokenResult.Tokens>(blocking { endpoint.refresh("cid", "r") }).refreshToken)
    }

    @Test
    fun revokePostsTheTokenAndAFailureThrows() {
        blocking { endpoint.revoke("tok") }
        assertEquals("https://oauth2.googleapis.com/revoke", seen.single().url.toString())
        assertEquals(mapOf("token" to "tok"), form(seen.single()))
        status = HttpStatusCode.BadRequest
        assertFailsWith<IOException> { blocking { endpoint.revoke("tok") } }
    }

    @Test
    fun anyFailureToReachGoogleIsAnIoExceptionThatNamesNoTokenOrCode() {
        failWith = IllegalStateException("tls failed for code=SECRET-CODE")
        val e = assertFailsWith<IOException> { blocking { endpoint.exchangeCode("cid", "r", "SECRET-CODE", "v") } }
        assertTrue("SECRET" !in (e.message ?: ""))
        failWith = IOException("offline")
        assertFailsWith<IOException> { blocking { endpoint.refresh("cid", "r") } }
    }

    @Test
    fun aCancelledCallIsNotTurnedIntoAnIoException() {
        failWith = CancellationException("stop")
        try {
            blocking { endpoint.refresh("cid", "r") }
            fail("a cancellation must reach the caller")
        } catch (_: CancellationException) {
            // expected
        } catch (e: IOException) {
            fail("a cancellation became an IOException")
        }
    }

    @Test
    fun theFormBodyPercentEncodesKeysAndValues() {
        assertEquals("a=1&b%20c=%26%3D%2B%25", TokenAnswers.formBody(listOf("a" to "1", "b c" to "&=+%")))
        assertEquals("", TokenAnswers.formBody(emptyList()))
    }

    @Test
    fun aTokensToStringNamesNoToken() {
        val text = TokenResult.Tokens("SECRET-ACCESS", "SECRET-REFRESH", setOf("s")).toString()
        assertTrue("SECRET" !in text)
    }
}
