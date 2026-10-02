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

package app.doorprints.drive.connect

import app.doorprints.drive.TokenProvider

/**
 * Connecting Google Drive (S4b-BL-117; docs/15 §5.5, §5.6). The seams between the Settings > Google Drive screens and
 * whatever holds the grant on a platform:
 *  - Android (default): the system browser with the authorisation-code flow and PKCE, no secret ([PkceGoogleSignIn]);
 *    the Play-services fallback only behind [PlayServicesAuthorizer] (no library is added);
 *  - iPhone: the same flow through a Swift-provided [AuthBrowser] (ASWebAuthenticationSession), failing closed;
 *  - website: Google Identity Services' token model (`google-token.ts`), memory only.
 *
 * Nothing here ever logs, prints or returns a token except [TokenProvider.accessToken] to the Drive client.
 */

/** The OAuth client of one platform. Ids are build-time configuration, never in the repository (docs/15 §2.4). */
data class GoogleAuthConfig(
    /** The platform's OAuth client id (`...apps.googleusercontent.com`); blank when the owner has not supplied one. */
    val clientId: String,
    /** Where the browser sends the person back: a custom scheme or an App Link the platform routes to the app. */
    val redirectUri: String,
) {
    /** The Connect feature is shown only when this is true: no id, no Connect (docs/15 §2.4). */
    val isConfigured: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()

    override fun toString() = "GoogleAuthConfig(configured=$isConfigured)"

    companion object {
        /** The only scope Doorprints asks for (docs/15 §2.2). */
        const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
        const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

        val NONE = GoogleAuthConfig("", "")
    }
}

/** Why a sign-in did not connect. No variant carries a token or a code. */
enum class SignInFailure {
    /** No client id was supplied at build time: the feature is hidden, this is the fail-closed answer. */
    NOT_CONFIGURED,

    /** This phone cannot do it (no browser, no Play services, the iPhone's bridge missing). */
    NOT_AVAILABLE,

    /** The network failed while exchanging the code. */
    OFFLINE,

    /** Google (or the person) refused: `access_denied` and the like. */
    DENIED,

    /** The answer came back with a `state` that is not this attempt's: forged or stale. Nothing is stored. */
    STATE_MISMATCH,

    /** Google's answer was not what the flow expects (no refresh token, no access token, unreadable). */
    BAD_ANSWER,

    /** The grant lacks `drive.file`, or carries a wider scope than we asked for. Nothing is kept. */
    WRONG_SCOPE,

    /** The sealed token store could not be written (a Keystore failure, the lock not there). Nothing is kept. */
    STORE_UNAVAILABLE,
}

sealed interface SignInResult {
    data object Connected : SignInResult

    /** The person closed the browser or the Google screen. */
    data object Cancelled : SignInResult

    data class Failed(val reason: SignInFailure) : SignInResult
}

interface GoogleSignIn : TokenProvider {
    /** False hides the Connect feature (no id supplied). */
    val configured: Boolean

    /** There is a grant this device can use (a sealed refresh token, a live Play-services grant). */
    fun isConnected(): Boolean

    /** Opens Google's consent and stores the grant. Never throws for an expected failure. */
    suspend fun connect(): SignInResult

    /** Forgets the grant on this device and asks Google to revoke it, best effort. Always ends disconnected. */
    suspend fun disconnect()

    /** Forgets what is held on this device without a network call (the lock was removed, the keys dropped). */
    fun forgetLocally()
}

/** A sign-in that is not there: [configured] is false, so the screens hide Connect. The default in tests and builds. */
object NoGoogleSignIn : GoogleSignIn {
    override val configured: Boolean get() = false
    override fun isConnected(): Boolean = false
    override suspend fun connect(): SignInResult = SignInResult.Failed(SignInFailure.NOT_CONFIGURED)
    override suspend fun disconnect() = Unit
    override fun forgetLocally() = Unit
    override suspend fun accessToken(): String =
        throw app.doorprints.drive.DriveException(app.doorprints.drive.DriveException.Kind.UNAUTHORIZED)
}
