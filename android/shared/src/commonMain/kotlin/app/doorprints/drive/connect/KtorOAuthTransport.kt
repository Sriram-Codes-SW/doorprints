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

package app.doorprints.drive.connect

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.parameters

/** [OAuthTransport] on the shared Ktor client (`ApiHttp.client(engine)`: no redirects followed, no exception for a status). */
class KtorOAuthTransport(private val http: HttpClient) : OAuthTransport {
    override suspend fun postForm(url: String, fields: Map<String, String>): OAuthResponse {
        val form: Parameters = parameters { fields.forEach { (k, v) -> append(k, v) } }
        val r = http.post(url) { setBody(FormDataContent(form)) }
        return OAuthResponse(r.status.value, r.bodyAsText())
    }
}
