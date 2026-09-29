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

package app.doorprints.shared.api

import kotlinx.io.IOException

/**
 * A failed API call. [kind] drives the message shown to the user; the server's response body is never shown
 * (threat model F-12), only its status is logged in debug builds.
 *
 * Extends kotlinx.io.IOException, which on Android/JVM is a typealias of java.io.IOException, so existing
 * `catch (e: IOException)` code and SyncOutcome.fromError treat it exactly as before.
 */
class ApiException(
    val kind: Kind,
    val code: Int = 0,
    val retryAfterSeconds: Long? = null,
) : IOException("HTTP $code ($kind)") {
    enum class Kind { AUTH, CAPTIVE_PORTAL, RATE_LIMITED, NOT_FOUND, CONFLICT, CLIENT, SERVER, AI_UNAVAILABLE }

    companion object {
        /** Maps a non-2xx status to a [Kind]. [encodedPath] is the request's URL path (without the query). */
        fun kindFor(status: Int, encodedPath: String): Kind = when (status) {
            401, 403 -> Kind.AUTH
            404 -> Kind.NOT_FOUND
            409 -> Kind.CONFLICT
            429 -> Kind.RATE_LIMITED
            503 -> if (encodedPath.startsWith("/api/ai/")) Kind.AI_UNAVAILABLE else Kind.SERVER
            // Redirects are never followed; a captive portal answers with one (hotel/airport Wi-Fi).
            in 300..399 -> Kind.CAPTIVE_PORTAL
            in 400..499 -> Kind.CLIENT
            else -> Kind.SERVER
        }
    }
}

/**
 * The whole call, retries included, took longer than ApiClient's call timeout (4 minutes). An IOException like
 * OkHttp's own call timeout was, so sync reports it as a network problem and WorkManager retries later.
 */
class ApiTimeoutException(timeoutMs: Long) : IOException("timeout after $timeoutMs ms")
