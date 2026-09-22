package com.househunt.app.data

import android.util.Log
import com.househunt.shared.api.AndroidApiHttp
import com.househunt.shared.api.ApiClient
import io.ktor.client.HttpClient

/**
 * Creates API clients. The client itself (DTOs, retries, captive-portal detection, error mapping) lives in :shared
 * (com.househunt.shared.api.ApiClient, Ktor since Sprint 3.5); this only holds the app-wide HTTP stack so every
 * client shares one OkHttp connection pool, and wires debug logging to logcat.
 */
object Api {
    private const val TAG = "HouseHuntApi"

    /** One pool and dispatcher for the whole app, created on first use. */
    private val http: HttpClient by lazy { AndroidApiHttp.create() }

    fun client(baseUrl: String, apiKey: String): ApiClient = ApiClient(
        baseUrl = baseUrl,
        apiKey = apiKey,
        http = http,
        // Status, method and path only (never bodies or the key); visible with `adb shell setprop log.tag.HouseHuntApi DEBUG`.
        debugLog = { message -> if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message) },
    )
}
