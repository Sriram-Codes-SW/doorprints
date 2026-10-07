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

/** Why a base URL is refused (docs/03 §13.2, the table of rules, in this order); the wire names are the vectors'. */
enum class BaseUrlReason(val wire: String) {
    EMPTY("empty"), NOT_AN_URL("notAnUrl"), SCHEME("scheme"), USERINFO("userinfo"), QUERY("query"),
    FRAGMENT("fragment"), ENDPOINT("endpoint"), INSECURE_HOST("insecureHost"),
}

sealed interface BaseUrlCheck {
    /** [normalised] has the scheme and host in lower case and no trailing slash; [host] is for the disclosure. */
    data class Valid(val normalised: String, val host: String) : BaseUrlCheck
    data class Invalid(val reason: BaseUrlReason) : BaseUrlCheck
}

/**
 * The one rule set for the address of an OpenAI-compatible endpoint (ADR-35, T-I43, T-I44), held to the table in
 * `parity-vectors.json` (section `baseUrl`) with the website's. Plain text work, because common code has no URL class.
 */
object BaseUrlValidator {
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val PORT = Regex("^[0-9]+$")
    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")
    private const val EMULATOR_HOST = "10.0.2.2"
    private const val ENDPOINT = "/chat/completions"

    /** [android] is true on Android only: the emulator's name for its computer, `10.0.2.2`, may then use http. */
    fun check(input: String, android: Boolean = false): BaseUrlCheck {
        val text = input.trim()
        if (text.isEmpty()) return invalid(BaseUrlReason.EMPTY)
        val match = SCHEME.find(text)
        if (match == null || text.any { it.isWhitespace() }) return invalid(BaseUrlReason.NOT_AN_URL)
        val scheme = match.value.dropLast(3).lowercase()
        if (scheme != "http" && scheme != "https") return invalid(BaseUrlReason.SCHEME)
        val rest = text.substring(match.value.length)
        val authorityEnd = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        if ('@' in authority) return invalid(BaseUrlReason.USERINFO)
        if ('?' in rest) return invalid(BaseUrlReason.QUERY)
        if ('#' in rest) return invalid(BaseUrlReason.FRAGMENT)
        val path = rest.substring(authorityEnd).trimEnd('/')
        if (path.endsWith(ENDPOINT)) return invalid(BaseUrlReason.ENDPOINT)
        val (host, port) = hostAndPort(authority) ?: return invalid(BaseUrlReason.NOT_AN_URL)
        val allowed = scheme == "https" || host in LOCAL_HOSTS || (android && host == EMULATOR_HOST)
        if (!allowed) return invalid(BaseUrlReason.INSECURE_HOST)
        return BaseUrlCheck.Valid("$scheme://$host$port$path", host)
    }

    private fun invalid(reason: BaseUrlReason) = BaseUrlCheck.Invalid(reason)

    /** The lower-case host (an IPv6 address in brackets) and the port as written with its colon, or null if it is no host. */
    private fun hostAndPort(authority: String): Pair<String, String>? {
        val host: String
        val port: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            port = authority.substring(close + 1)
        } else {
            val colon = authority.lastIndexOf(':')
            host = if (colon < 0) authority else authority.substring(0, colon)
            port = if (colon < 0) "" else authority.substring(colon)
        }
        if (host.isEmpty() || host == "[]") return null
        if (port.isNotEmpty() && !(port.startsWith(":") && PORT.matches(port.substring(1)))) return null
        return host.lowercase() to port
    }
}
