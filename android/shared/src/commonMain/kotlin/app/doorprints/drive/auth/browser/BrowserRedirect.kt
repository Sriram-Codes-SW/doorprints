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

package app.doorprints.drive.auth.browser

import app.doorprints.concurrent.PlatformLock
import app.doorprints.crypto.constantTimeEquals
import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.CompletableDeferred

/**
 * The way back from the system browser (docs/15 §5.5): Google redirects to [redirectUri] (a custom scheme the app owns,
 * `app.doorprints:/oauth2redirect`, which Google accepts for an Android client with the custom scheme switched on),
 * Android opens `MainActivity`, and its `onNewIntent` (and `onCreate`'s first intent) hands the intent to
 * `onNewIntent` (an extension in `:app`, [deliver]'s Android door). On the iPhone the redirect is
 * `com.googleusercontent.apps.<id>:/oauth2redirect` and `ASWebAuthenticationSession`'s completion handler calls [deliver]
 * with the callback URL. This class (common code since the iPhone's Drive) holds **at most one pending request**; a redirect completes it only when it is the right
 * address and carries the pending request's `state`. Anything else (a stale redirect after the app was killed, a
 * foreign app's intent, a replay of an answered one, another state) is refused and leaves the pending request alone,
 * so a stranger cannot cancel a sign-in. A redirect is single use: answering clears the request.
 */
class BrowserRedirect(val redirectUri: String = DEFAULT_REDIRECT_URI) {

    /** What the redirect said. */
    sealed interface Outcome {
        class Code(val code: String) : Outcome {
            override fun toString() = "Code"
        }

        /** Google's `error` parameter (`access_denied` when the person refuses). */
        data class Error(val error: String) : Outcome

        /** No answer will come: the request was replaced, abandoned or cancelled. */
        data object Cancelled : Outcome
    }

    /** One waiting sign-in. */
    class Request internal constructor(val state: String, internal val result: CompletableDeferred<Outcome>) {
        suspend fun await(): Outcome = result.await()
    }

    private val lock = PlatformLock()
    private var pending: Request? = null
    private val target = ParsedRedirect.of(redirectUri) ?: throw IllegalArgumentException("not a redirect address")

    /** Starts a request; a request still waiting is ended as [Outcome.Cancelled] (the person left the browser and tried again). */
    fun begin(state: String): Request {
        val request = Request(state, CompletableDeferred())
        val old = lock.withLock { pending.also { pending = request } }
        old?.result?.complete(Outcome.Cancelled)
        return request
    }

    /** Ends [request] without an answer (timeout, launch failure, coroutine cancelled). Only if it is still the current one. */
    fun abandon(request: Request) {
        lock.withLock { if (pending === request) pending = null }
        request.result.complete(Outcome.Cancelled)
    }

    /** Ends the waiting request, if any, as [Outcome.Cancelled]. */
    fun cancelPending() {
        val old = lock.withLock { pending.also { pending = null } }
        old?.result?.complete(Outcome.Cancelled)
    }

    /** True when [uri] completed the pending request. Never throws. */
    fun deliver(uri: String?): Boolean {
        if (uri == null) return false
        val parsed = ParsedRedirect.of(uri) ?: return false
        if (!parsed.scheme.equals(target.scheme, ignoreCase = true) || parsed.path != target.path) return false
        val params = query(parsed.query) ?: return false
        val outcome = lock.withLock { take(params) } ?: return false
        outcome.first.result.complete(outcome.second)
        return true
    }

    /** The pending request and its outcome when [params] answer it (then the request is no longer pending), else null. */
    private fun take(params: Map<String, String>): Pair<Request, Outcome>? {
        val current = pending ?: return null
        val state = params["state"] ?: return null
        if (!constantTimeEquals(state.encodeToByteArray(), current.state.encodeToByteArray())) return null
        val error = params["error"]
        val code = params["code"]
        val made = when {
            error != null -> Outcome.Error(error)
            !code.isNullOrBlank() -> Outcome.Code(code)
            else -> return null
        }
        pending = null
        return current to made
    }

    /**
     * A repeated parameter is refused as a whole (parameter pollution), not resolved by taking the first or last; so is a
     * malformed percent escape.
     */
    private fun query(raw: String?): Map<String, String>? {
        val out = HashMap<String, String>()
        if (raw.isNullOrEmpty()) return out
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val key = decode(if (i < 0) pair else pair.substring(0, i)) ?: return null
            val value = if (i < 0) "" else decode(pair.substring(i + 1)) ?: return null
            if (out.put(key, value) != null) return null
        }
        return out
    }

    /** Form decoding (`+` is a space, `%XX` UTF-8); null for a malformed escape. */
    private fun decode(text: String): String? = try {
        text.decodeURLQueryComponent(plusIsSpace = true)
    } catch (_: Exception) {
        null
    }

    /**
     * An address of the one shape a redirect has: `scheme:/path` with an optional `?query`, no authority (`//host`), no
     * fragment, and no space, control character or other character an address may not carry. Null for anything else.
     */
    private class ParsedRedirect(val scheme: String, val path: String, val query: String?) {
        companion object {
            private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*")
            private const val FORBIDDEN = "\"<>\\^`{|}#"

            fun of(address: String): ParsedRedirect? {
                if (address.any { it <= ' ' || it.code == 0x7f || it in FORBIDDEN }) return null
                val colon = address.indexOf(':')
                if (colon <= 0) return null
                val scheme = address.substring(0, colon)
                if (!SCHEME.matches(scheme)) return null
                val rest = address.substring(colon + 1)
                if (rest.startsWith("//")) return null
                val q = rest.indexOf('?')
                val path = if (q < 0) rest else rest.substring(0, q)
                val query = if (q < 0) null else rest.substring(q + 1)
                if (path.isEmpty() || !path.startsWith("/")) return null
                return ParsedRedirect(scheme, path, query)
            }
        }
    }

    companion object {
        const val DEFAULT_REDIRECT_URI = "app.doorprints:/oauth2redirect"
    }
}
