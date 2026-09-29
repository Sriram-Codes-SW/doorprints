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
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.setHTTPShouldHandleCookies

/**
 * The iOS HTTP stack (CMP-8b): Ktor's Darwin engine on an `NSURLSession` with the system's default configuration and
 * Ktor's own session delegate, set to match [AndroidApiHttp] where NSURLSession has the same setting:
 *  - 90 s without data before a request fails (`timeoutIntervalForRequest`, on the session and on each request: free
 *    hosts can take 30-60 s to wake a sleeping app). NSURLSession has no separate connect timeout, so the connect
 *    phase falls under the same 90 s where OkHttp gives up after 20 s; the 4-minute limit for a whole call lives in
 *    [ApiClient], as on Android.
 *  - no redirects: [ApiHttp.client] turns them off, and the Darwin engine then refuses NSURLSession's redirect.
 *  - no disk cache and no cookies: OkHttp had neither, and an API answer (houses, addresses) must not be left in
 *    `Library/Caches` (threat model F-03).
 *
 * App Transport Security stays at its default (no exceptions in Info.plist, ios/). ATS refuses plain `http://` to a
 * domain name: iOS fails the call as a network error before any byte is sent. A server on a LAN IP address
 * (`http://192.168.1.20:8080`) or a `.local` name is not covered by ATS, so it connects without TLS, as on Android
 * (network security config); iOS first asks the user for local network access (`NSLocalNetworkUsageDescription`).
 *
 * Create one client for the whole app and share it, as on Android.
 */
object IosApiHttp {
    /** Seconds without data before a request fails; Android's read timeout. */
    private const val IDLE_TIMEOUT_SECONDS = 90.0

    fun create(): HttpClient = ApiHttp.client(
        Darwin.create {
            configureSession {
                timeoutIntervalForRequest = IDLE_TIMEOUT_SECONDS
                URLCache = null
                HTTPCookieStorage = null
                HTTPShouldSetCookies = false
                requestCachePolicy = NSURLRequestReloadIgnoringLocalCacheData
            }
            configureRequest {
                // A request's own interval is what NSURLSession applies; set it too so the default 60 s cannot win.
                setTimeoutInterval(IDLE_TIMEOUT_SECONDS)
                setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
                setHTTPShouldHandleCookies(false)
            }
        },
    )
}
