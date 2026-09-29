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

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.util.concurrent.TimeUnit

/**
 * The Android HTTP stack: Ktor's OkHttp engine with the socket settings the pre-KMP OkHttp client had (Sprint 3.5
 * migration keeps them byte-for-byte): one connection pool (keep-alive, HTTP/2 when offered, transparent gzip),
 * connect 20 s, read 90 s (free hosts can take 30-60 s to wake a sleeping app), write 60 s, no redirects, OkHttp's
 * silent retry of a failed connection attempt. The 4-minute limit for a whole call lives in [ApiClient] so it also
 * covers retries, as OkHttp's callTimeout did.
 *
 * Create one client for the whole app and share it (see :app data/Api.kt).
 */
object AndroidApiHttp {
    fun create(): HttpClient = ApiHttp.client(
        OkHttp.create {
            config {
                connectTimeout(20, TimeUnit.SECONDS)
                readTimeout(90, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(true)
            }
        },
    )
}
