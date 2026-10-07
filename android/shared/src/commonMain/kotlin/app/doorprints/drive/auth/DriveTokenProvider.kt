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

package app.doorprints.drive.auth

import app.doorprints.drive.TokenProvider
import app.doorprints.shared.api.IsoTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Drive access token on the phones, from a [GoogleAuthorizer]: Google Play services on Android, the system browser's PKCE
 * sign-in on the iPhone and on Android without Play (docs/15 §5.5 and §5.6; twin of the website's `GoogleTokenProvider`).
 * Common code since the iPhone's Drive (it was `AndroidDriveTokenProvider`). The rules, each with a test:
 *
 * - **Scope**: exactly [DRIVE_FILE_SCOPE], nothing else is asked.
 * - **The token is in memory only** (never a file, a preference or a log); Play services keeps and refreshes the grant,
 *   so asking again is quiet while it holds. A token is reused for [TOKEN_LIFETIME_MS] (Play's tokens last an hour).
 * - **Fail closed**: an answer whose granted scopes do not include `drive.file` (the person unticked it on Google's page:
 *   granular consent) or that carries no token is [SignInException.Kind.DENIED]; the token is dropped.
 * - **Cancelled is not denied**: a prompt closed without an answer is [SignInException.Kind.CANCELLED].
 * - **Consent** (Google wants a screen): handed to [resolver], which the Activity implements; with none (a background
 *   run) it is [SignInException.Kind.CONSENT_REQUIRED]. The screen's answer is checked by the same rules; it is asked once.
 * - **Rejected** (Drive's 401): the token is dropped here and at Google (which would hand it out again).
 * - **Revoke** clears memory first, then asks Google to revoke; a failure at Google does not undo the clearing.
 */
class DriveTokenProvider(
    private val authorizer: GoogleAuthorizer,
    /** The Activity's resolver while one is on screen, else null. Read at each request. */
    private val resolver: () -> ConsentResolver?,
    private val nowMs: () -> Long = IsoTime::nowMillis,
) : TokenProvider {

    private val lock = Mutex()
    private var token: String? = null
    private var expiresAtMs = 0L

    override suspend fun accessToken(): String = lock.withLock {
        token?.let { if (nowMs() < expiresAtMs) return it }
        token = null
        var answer = authorizer.authorize(SCOPES)
        if (answer is AuthorizerResult.NeedsConsent) {
            val ui = resolver() ?: throw SignInException(SignInException.Kind.CONSENT_REQUIRED)
            answer = ui.resolve(answer.consent)
        }
        val fresh = when (answer) {
            is AuthorizerResult.Granted -> checked(answer)
            is AuthorizerResult.NeedsConsent -> throw SignInException(SignInException.Kind.UNAVAILABLE)
            AuthorizerResult.Cancelled -> throw SignInException(SignInException.Kind.CANCELLED)
            is AuthorizerResult.Failed -> throw SignInException(
                when (answer.kind) {
                    AuthorizerResult.FailureKind.OFFLINE -> SignInException.Kind.OFFLINE
                    AuthorizerResult.FailureKind.UNAVAILABLE -> SignInException.Kind.UNAVAILABLE
                },
            )
        }
        token = fresh
        expiresAtMs = nowMs() + TOKEN_LIFETIME_MS
        fresh
    }

    override suspend fun onRejected(token: String) {
        lock.withLock {
            if (this.token == token) this.token = null
        }
        authorizer.clearToken(token)
    }

    /** *Disconnect this device*: forget the token held in memory. Nothing is asked of Google; the grant there stays. */
    suspend fun forget() {
        lock.withLock {
            token = null
            expiresAtMs = 0L
        }
    }

    /** *Disconnect on all devices*: forget the token, then ask Google to withdraw the grant (a Google failure is swallowed). */
    suspend fun revokeAccess() {
        val old = lock.withLock {
            val t = token
            token = null
            t
        }
        try {
            authorizer.revoke(old, SCOPES)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The memory copy is gone either way; the person can also remove the grant in their Google account.
        }
    }

    private fun checked(granted: AuthorizerResult.Granted): String {
        val value = granted.accessToken
        if (DRIVE_FILE_SCOPE !in granted.grantedScopes || value.isNullOrBlank()) {
            throw SignInException(SignInException.Kind.DENIED)
        }
        return value
    }

    companion object {
        private val SCOPES = listOf(DRIVE_FILE_SCOPE)

        /** Play services' tokens last an hour; reuse one for less, so a request never carries one about to expire. */
        const val TOKEN_LIFETIME_MS = 50 * 60_000L
    }
}
