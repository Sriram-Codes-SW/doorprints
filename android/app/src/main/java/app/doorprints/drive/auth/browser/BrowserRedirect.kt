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

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * The way back from the system browser (docs/15 §5.5): Google redirects to [redirectUri] (a custom scheme the app owns,
 * `app.doorprints:/oauth2redirect`, which Google accepts for an Android client with the custom scheme switched on),
 * Android opens `MainActivity`, and its `onNewIntent` (and `onCreate`'s first intent) hands the intent to
 * [onNewIntent]. This class holds **at most one pending request**; a redirect completes it only when it is the right
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

    private val lock = Any()
    private var pending: Request? = null
    private val target = URI(redirectUri)

    /** Starts a request; a request still waiting is ended as [Outcome.Cancelled] (the person left the browser and tried again). */
    fun begin(state: String): Request {
        val request = Request(state, CompletableDeferred())
        val old = synchronized(lock) { pending.also { pending = request } }
        old?.result?.complete(Outcome.Cancelled)
        return request
    }

    /** Ends [request] without an answer (timeout, launch failure, coroutine cancelled). Only if it is still the current one. */
    fun abandon(request: Request) {
        synchronized(lock) { if (pending === request) pending = null }
        request.result.complete(Outcome.Cancelled)
    }

    /** Ends the waiting request, if any, as [Outcome.Cancelled]. */
    fun cancelPending() {
        val old = synchronized(lock) { pending.also { pending = null } }
        old?.result?.complete(Outcome.Cancelled)
    }

    /** The Activity's hook: true when [intent] was this app's pending redirect (and was consumed). */
    fun onNewIntent(intent: Intent?): Boolean {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return false
        return deliver(intent.dataString)
    }

    /** True when [uri] completed the pending request. Never throws. */
    fun deliver(uri: String?): Boolean {
        if (uri == null) return false
        val parsed = try {
            URI(uri)
        } catch (_: Exception) {
            return false
        }
        if (!parsed.scheme.equals(target.scheme, ignoreCase = true) || !parsed.host.isNullOrEmpty() ||
            parsed.path != target.path || parsed.rawFragment != null
        ) return false
        val params = query(parsed.rawQuery) ?: return false
        val outcome = synchronized(lock) {
            val current = pending ?: return false
            val state = params["state"] ?: return false
            if (!MessageDigest.isEqual(state.toByteArray(), current.state.toByteArray())) return false
            val error = params["error"]
            val code = params["code"]
            val made = when {
                error != null -> Outcome.Error(error)
                !code.isNullOrBlank() -> Outcome.Code(code)
                else -> return false
            }
            pending = null
            current to made
        }
        outcome.first.result.complete(outcome.second)
        return true
    }

    /** A repeated parameter is refused as a whole (parameter pollution), not resolved by taking the first or last. */
    private fun query(raw: String?): Map<String, String>? {
        val out = HashMap<String, String>()
        if (raw.isNullOrEmpty()) return out
        for (pair in raw.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val key = URLDecoder.decode(if (i < 0) pair else pair.substring(0, i), "UTF-8")
            val value = if (i < 0) "" else URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            if (out.put(key, value) != null) return null
        }
        return out
    }

    companion object {
        const val DEFAULT_REDIRECT_URI = "app.doorprints:/oauth2redirect"
    }
}
