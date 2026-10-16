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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** [TokenEndpoint] over `java.net` (no library). Tests point [tokenUrl] and [revokeUrl] at a local server. */
class HttpTokenEndpoint(
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
    private val revokeUrl: String = "https://oauth2.googleapis.com/revoke",
    private val timeoutMs: Int = 20_000,
) : TokenEndpoint {

    override suspend fun exchangeCode(clientId: String, redirectUri: String, code: String, verifier: String): TokenResult =
        parse(
            post(
                tokenUrl,
                "client_id" to clientId, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to redirectUri, "code_verifier" to verifier,
            ),
        )

    override suspend fun refresh(clientId: String, refreshToken: String): TokenResult =
        parse(post(tokenUrl, "client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken))

    override suspend fun revoke(token: String) {
        val (status, _) = post(revokeUrl, "token" to token)
        if (status !in 200..299) throw IOException("revoke refused ($status)")
    }

    private fun parse(answer: Pair<Int, String>): TokenResult = TokenAnswers.parse(answer.first, answer.second)

    private suspend fun post(url: String, vararg form: Pair<String, String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        val body = form.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            status to (stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty())
        } finally {
            c.disconnect()
        }
    }
}
