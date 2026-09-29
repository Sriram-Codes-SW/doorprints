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

package app.doorprints.data

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * A connect link from the owner page's QR code (docs/03 §12.1, ADR-25): `doorprints://connect?server=<origin>&invite=
 * <token>`, or the website's `https://…/connect?server=…&invite=…` when a phone opens that instead. The app shows the
 * server and asks before redeeming the invite, since anyone can make such a link.
 */
data class ConnectLink(val server: String, val invite: String) {
    companion object {
        /** Invites are 32 random bytes in base64url (43 characters); anything else is not one. */
        private val INVITE = Regex("^[A-Za-z0-9_-]{20,100}$")

        /**
         * The link's server and invite, or null when it is not a connect link or not usable: the server must pass
         * [ServerUrl.check] (https, or a local development host) and be an origin with no path, query or
         * credentials; the invite must look like one.
         */
        fun parse(link: String?): ConnectLink? {
            if (link.isNullOrBlank()) return null
            val url = runCatching { Url(link.trim()) }.getOrNull() ?: return null
            val isAppLink = url.protocol.name == "doorprints" && url.host == "connect"
            val isWebLink = url.protocol == URLProtocol.HTTPS && url.encodedPath.trimEnd('/').endsWith("/connect")
            if (!isAppLink && !isWebLink) return null
            val invite = url.parameters["invite"] ?: return null
            if (!INVITE.matches(invite)) return null
            val server = serverOrigin(url.parameters["server"]) ?: return null
            return ConnectLink(server, invite)
        }

        private fun serverOrigin(server: String?): String? {
            if (server.isNullOrBlank()) return null
            val checked = ServerUrl.check(server) as? ServerUrl.Result.Ok ?: return null
            val url = runCatching { Url(checked.url) }.getOrNull() ?: return null
            if (url.user != null || url.password != null) return null
            if (url.encodedPath.trimEnd('/').isNotEmpty() || url.encodedQuery.isNotEmpty() || url.fragment.isNotEmpty()) {
                return null
            }
            return checked.url.trimEnd('/')
        }
    }
}
