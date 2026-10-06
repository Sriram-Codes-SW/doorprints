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

import app.doorprints.drive.auth.DRIVE_FILE_SCOPE
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

/** The real `java.net` client against a local server (loopback only; no network). */
class HttpTokenEndpointTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val bodies = mutableListOf<Map<String, String>>()
    private var status = 200
    private var reply = ""

    init {
        server.createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            bodies += body.split('&').filter { it.isNotEmpty() }.associate {
                val i = it.indexOf('=')
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/token"
    private fun endpoint() = HttpTokenEndpoint(url, url, timeoutMs = 2_000)

    @Test
    fun codeExchangeSendsPkceAndNoSecret() {
        reply = """{"access_token":"a","refresh_token":"r","scope":"$DRIVE_FILE_SCOPE","expires_in":3599,"token_type":"Bearer"}"""
        val r = runBlocking { endpoint().exchangeCode("cid", "app.doorprints:/oauth2redirect", "the code/1", "ver") } as TokenResult.Tokens
        assertEquals("a", r.accessToken)
        assertEquals("r", r.refreshToken)
        assertEquals(setOf(DRIVE_FILE_SCOPE), r.scopes)
        assertEquals(
            mapOf(
                "client_id" to "cid", "grant_type" to "authorization_code", "code" to "the code/1",
                "redirect_uri" to "app.doorprints:/oauth2redirect", "code_verifier" to "ver",
            ),
            bodies.single(),
        )
    }

    @Test
    fun refreshSendsTheRefreshGrantAndReadsNoNewRefreshToken() {
        reply = """{"access_token":"a2","scope":"$DRIVE_FILE_SCOPE"}"""
        val r = runBlocking { endpoint().refresh("cid", "rt") } as TokenResult.Tokens
        assertNull(r.refreshToken)
        assertEquals(mapOf("client_id" to "cid", "grant_type" to "refresh_token", "refresh_token" to "rt"), bodies.single())
    }

    @Test
    fun anOauthErrorIsRejected() {
        status = 400
        reply = """{"error":"invalid_grant","error_description":"Bad Request"}"""
        assertEquals(TokenResult.Rejected("invalid_grant"), runBlocking { endpoint().refresh("cid", "rt") })
    }

    @Test
    fun aServerFaultAndGarbageAreServerError() {
        status = 503
        reply = "<html>"
        assertEquals(TokenResult.ServerError, runBlocking { endpoint().refresh("cid", "rt") })
        status = 200
        assertEquals(TokenResult.ServerError, runBlocking { endpoint().refresh("cid", "rt") })
        reply = """{"scope":"x"}"""
        assertEquals(TokenResult.ServerError, runBlocking { endpoint().refresh("cid", "rt") })
    }

    @Test
    fun revokePostsTheToken() {
        reply = "{}"
        runBlocking { endpoint().revoke("tok") }
        assertEquals(mapOf("token" to "tok"), bodies.single())
    }

    @Test
    fun aRefusedRevokeThrows() {
        status = 400
        reply = """{"error":"invalid_token"}"""
        try {
            runBlocking { endpoint().revoke("tok") }
            fail("a refusal must reach the caller")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("400"))
        }
    }

    @Test
    fun aClosedServerIsAnIoException() {
        val dead = HttpTokenEndpoint("http://127.0.0.1:1/token", "http://127.0.0.1:1/r", timeoutMs = 500)
        try {
            runBlocking { dead.refresh("cid", "rt") }
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun tokensNeverShowInToString() {
        assertTrue(!TokenResult.Tokens("secret-a", "secret-r", setOf("s")).toString().contains("secret"))
    }
}
