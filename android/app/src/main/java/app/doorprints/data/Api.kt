package app.doorprints.data

import android.util.Log
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
}
