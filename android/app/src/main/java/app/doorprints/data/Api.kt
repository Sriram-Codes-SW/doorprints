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

import android.util.Log
import app.doorprints.shared.ai.GeminiClient
import app.doorprints.shared.api.AndroidApiHttp
import app.doorprints.shared.api.ApiClient
import io.ktor.client.HttpClient

/**
 * Creates API clients. The client itself (DTOs, retries, captive-portal detection, error mapping) lives in :shared
 * (app.doorprints.shared.api.ApiClient, Ktor since Sprint 3.5); this only holds the app-wide HTTP stack so every
 * client shares one OkHttp connection pool, and wires debug logging to logcat.
 */
object Api {
    private const val TAG = "DoorprintsApi"

    /** One pool and dispatcher for the whole app, created on first use. */
    private val http: HttpClient by lazy { AndroidApiHttp.create() }

    fun client(baseUrl: String, apiKey: String): ApiClient = ApiClient(
        baseUrl = baseUrl,
        apiKey = apiKey,
        http = http,
        // Status, method and path only (never bodies or the key); visible with `adb shell setprop log.tag.DoorprintsApi DEBUG`.
        debugLog = { message -> if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message) },
    )

    /** Gemini with the person's own key, for on-device AI (docs/03 §13.1), on the same pool. Nothing is logged. */
    fun gemini(apiKey: String): GeminiClient = GeminiClient(http, apiKey)
}
