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
