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

import app.doorprints.drive.auth.AuthorizerResult
import app.doorprints.drive.auth.ConsentResolver
import app.doorprints.drive.auth.DRIVE_FILE_SCOPE
import app.doorprints.drive.auth.GoogleAuthorizer
import app.doorprints.drive.auth.PendingConsent
import app.doorprints.drive.device.DeviceKeyException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.http.encodeURLParameter
import kotlinx.io.IOException

/** Opens [url] in the person's browser. False: there is none (or it refused). */
fun interface BrowserLauncher {
    /** Opens [url] and returns whether a browser took it; it does not wait for the person. */
    fun launch(url: String): Boolean
}

/** The OAuth client of the build (Android, or the iPhone: `GoogleIOSClientId` in Info.plist): [clientId] comes from build configuration (docs/15 §2.4), never from source. */
class BrowserOAuthConfig(
    val clientId: () -> String?,
    val redirectUri: String = BrowserRedirect.DEFAULT_REDIRECT_URI,
    val authUrl: String = "https://accounts.google.com/o/oauth2/v2/auth",
    /** How long a person may stay in the browser before the request is called CANCELLED. */
    val waitMs: Long = 5 * 60_000L,
)

/**
 * The consent step of the browser path: the Activity's [ConsentResolver] runs it (see [BrowserAwareResolver]). It opens
 * the browser, waits for the redirect and exchanges the code. A background run has no resolver, so the provider answers
 * `CONSENT_REQUIRED` and nothing opens by itself.
 */
class BrowserPendingConsent(private val authorizer: BrowserGoogleAuthorizer) : PendingConsent {
    /** Runs the browser consent (open, wait for the redirect, exchange the code) and returns the resulting grant. */
    suspend fun run(): AuthorizerResult = authorizer.runConsent()
}

/** Resolves [BrowserPendingConsent] itself and passes any other consent (Play services') to [other]. */
class BrowserAwareResolver(private val other: ConsentResolver?) : ConsentResolver {
    override suspend fun resolve(consent: PendingConsent): AuthorizerResult = when {
        consent is BrowserPendingConsent -> consent.run()
        other != null -> other.resolve(consent)
        else -> AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)
    }
}

/**
 * [GoogleAuthorizer] for phones without Google Play services: the system browser, authorisation code and PKCE, a
 * refresh token kept sealed (docs/15 §5.5). Only [DriveTokenProvider][app.doorprints.drive.auth.DriveTokenProvider]'s
 * rules sit above it; this class maps Google's answers into the [AuthorizerResult]s it already understands:
 *
 * - A stored refresh token is used quietly (`authorize` answers with a fresh access token, no screen). No token, or
 *   `invalid_grant` (revoked, expired in Testing status) answers `NeedsConsent`: the browser opens only from a screen.
 * - The scope check fails closed: a token response without exactly-asked `drive.file` is never stored, is revoked at
 *   Google, and is returned as a grant with no scope (the provider says DENIED).
 * - `access_denied` (the person said no) is DENIED; leaving the browser without an answer (timeout, a second try, [cancel])
 *   is `Cancelled`; no network is OFFLINE; any other trouble (no browser, 5xx, bad client) is UNAVAILABLE.
 * - The access token is never stored; the refresh token only through [store]. If [store] cannot keep it, the sign-in
 *   still works for this hour and the next one asks again.
 */
class BrowserGoogleAuthorizer(
    private val config: BrowserOAuthConfig,
    private val redirect: BrowserRedirect,
    private val launcher: BrowserLauncher,
    private val endpoint: TokenEndpoint,
    private val store: RefreshTokenStore,
    private val random: RandomBytes,
) : GoogleAuthorizer {

    private val quiet = Mutex()

    /**
     * Quiet path only: refreshes with the stored refresh token and never opens a screen. Only the one Drive scope is
      * served; anything else, or a missing client id, is UNAVAILABLE. Calls are serialised so two refreshes do not race
      * on
     * the stored token.
     */
    override suspend fun authorize(scopes: List<String>): AuthorizerResult {
        if (scopes != listOf(DRIVE_FILE_SCOPE)) return unavailable()
        val clientId = clientId() ?: return unavailable()
        return quiet.withLock {
            val stored = try {
                store.read()
            } catch (e: DeviceKeyException) {
                if (e.kind == DeviceKeyException.Kind.NEEDS_UNLOCK) return@withLock unavailable()
                runCatching { store.clear() }
                null
            } ?: return@withLock AuthorizerResult.NeedsConsent(BrowserPendingConsent(this))
            val answer = try {
                endpoint.refresh(clientId, stored)
            } catch (_: IOException) {
                return@withLock AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE)
            }
            when (answer) {
                is TokenResult.Tokens -> {
                    if (DRIVE_FILE_SCOPE !in answer.scopes) {
                        dropGrant(stored)
                        AuthorizerResult.Granted(null, answer.scopes)
                    } else {
                        answer.refreshToken?.takeIf { it != stored }?.let { keep(it) }
                        AuthorizerResult.Granted(answer.accessToken, answer.scopes)
                    }
                }
                is TokenResult.Rejected -> if (answer.error == INVALID_GRANT) {
                    runCatching { store.clear() }
                    AuthorizerResult.NeedsConsent(BrowserPendingConsent(this))
                } else {
                    unavailable()
                }
                TokenResult.ServerError -> unavailable()
            }
        }
    }

    /** Google's access tokens are not kept by Google for us to drop; the provider already forgot [accessToken], and the next call refreshes. */
    override suspend fun clearToken(accessToken: String) = Unit

    /** *Disconnect on all devices*: the sealed token is deleted first, then Google is asked to revoke it (a failure there throws; the caller swallows it). */
    override suspend fun revoke(accessToken: String?, scopes: List<String>) {
        val refresh = try {
            store.read()
        } catch (_: DeviceKeyException) {
            null
        }
        runCatching { store.clear() }
        redirect.cancelPending()
        val token = refresh ?: accessToken ?: return
        endpoint.revoke(token)
    }

    /** The person pressed *Cancel* in Doorprints while the browser was open. */
    fun cancel() = redirect.cancelPending()

    /**
      * The loud path, started from a screen: opens Google's consent page with a fresh PKCE verifier and state, waits up
      * to
     * [BrowserOAuthConfig.waitMs] for the redirect and trades the code for tokens. The pending redirect is always
     * abandoned afterwards, so a later attempt starts clean.
     */
    internal suspend fun runConsent(): AuthorizerResult {
        val clientId = clientId() ?: return unavailable()
        val verifier = Pkce.newVerifier(random)
        val request = redirect.begin(Pkce.newState(random))
        val url = authUrl(clientId, Pkce.challenge(verifier), request.state)
        val launched = try {
            launcher.launch(url)
        } catch (e: CancellationException) {
            redirect.abandon(request)
            throw e
        } catch (_: Exception) {
            false
        }
        if (!launched) {
            redirect.abandon(request)
            return unavailable()
        }
        val outcome = try {
            withTimeoutOrNull(config.waitMs) { request.await() }
        } finally {
            redirect.abandon(request)
        }
        return when (outcome) {
            null, BrowserRedirect.Outcome.Cancelled -> AuthorizerResult.Cancelled
            is BrowserRedirect.Outcome.Error ->
                if (outcome.error == ACCESS_DENIED) AuthorizerResult.Granted(null, emptySet()) else unavailable()
            is BrowserRedirect.Outcome.Code -> exchange(clientId, outcome.code, verifier)
        }
    }

    private suspend fun exchange(clientId: String, code: String, verifier: String): AuthorizerResult {
        val answer = try {
            endpoint.exchangeCode(clientId, config.redirectUri, code, verifier)
        } catch (_: IOException) {
            return AuthorizerResult.Failed(AuthorizerResult.FailureKind.OFFLINE)
        }
        return when (answer) {
            is TokenResult.Tokens -> {
                if (DRIVE_FILE_SCOPE !in answer.scopes) {
                    // The person unticked Drive on Google's page (granular consent): keep nothing, withdraw what was given.
                    dropGrant(answer.refreshToken ?: answer.accessToken)
                    AuthorizerResult.Granted(null, answer.scopes)
                } else {
                    answer.refreshToken?.let { keep(it) }
                    AuthorizerResult.Granted(answer.accessToken, answer.scopes)
                }
            }
            is TokenResult.Rejected, TokenResult.ServerError -> unavailable()
        }
    }

    private fun keep(refreshToken: String) {
        try {
            store.write(refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No safe place: the token stays out of storage, the sign-in lasts this hour, the next one asks again.
        }
    }

    private suspend fun dropGrant(token: String) {
        runCatching { store.clear() }
        try {
            endpoint.revoke(token)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best effort: nothing is kept here either way.
        }
    }

    private fun authUrl(clientId: String, challenge: String, state: String): String {
        fun e(v: String) = v.encodeURLParameter()
        return config.authUrl + "?" + listOf(
            "client_id" to clientId,
            "redirect_uri" to config.redirectUri,
            "response_type" to "code",
            "scope" to DRIVE_FILE_SCOPE,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "prompt" to "consent",
        ).joinToString("&") { (k, v) -> "$k=${e(v)}" }
    }

    private fun clientId(): String? = config.clientId()?.trim()?.takeIf { it.isNotEmpty() }

    private fun unavailable() = AuthorizerResult.Failed(AuthorizerResult.FailureKind.UNAVAILABLE)

    private companion object {
        const val INVALID_GRANT = "invalid_grant"
        const val ACCESS_DENIED = "access_denied"
    }
}
