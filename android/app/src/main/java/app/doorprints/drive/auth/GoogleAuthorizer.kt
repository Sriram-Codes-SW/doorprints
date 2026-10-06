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

/*
 * What the Android Drive token provider needs from Google, as small interfaces so the decisions are plain Kotlin and the
 * tests run on a fake (no Google Play services call in a test). The real one is [PlayGoogleAuthorizer]. docs/15 §5.5
 * (Android, Play services row), §5.6; the website's twin is `google-token-provider.ts`.
 */

/** The only scope Doorprints asks Google for (docs/15 §2.2). */
const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** A consent screen Google wants shown (Play services' `PendingIntent`); opaque to the logic, the UI layer launches it. */
interface PendingConsent

/** What Google answered to one authorization request. A token never appears in [toString]. */
sealed interface AuthorizerResult {
    /** Google returned an answer: [grantedScopes] is what the person really allowed (granular consent can untick one). */
    class Granted(val accessToken: String?, val grantedScopes: Set<String>) : AuthorizerResult {
        override fun toString() = "Granted(scopes=$grantedScopes)"
    }

    /** Google needs the person to see [consent] first (first connect, or the grant is gone). */
    class NeedsConsent(val consent: PendingConsent) : AuthorizerResult {
        override fun toString() = "NeedsConsent"
    }

    /** The person closed the prompt without answering (back, tap outside). Not a refusal. */
    data object Cancelled : AuthorizerResult

    /** Google could not answer. */
    data class Failed(val kind: FailureKind) : AuthorizerResult

    enum class FailureKind { OFFLINE, UNAVAILABLE }
}

/** Google's authorization API, narrowed to what Doorprints uses. */
interface GoogleAuthorizer {
    /** Asks for exactly [scopes]; answers at once, without a screen, while the grant holds. */
    suspend fun authorize(scopes: List<String>): AuthorizerResult

    /** Drive refused [accessToken] (401): Google keeps tokens, so it must be told to drop this one or it hands it out again. */
    suspend fun clearToken(accessToken: String)

    /** *Disconnect on all devices*: withdraw the grant of [scopes] at Google. [accessToken] may be null (a restarted app). */
    suspend fun revoke(accessToken: String?, scopes: List<String>)
}

/**
 * Implemented by the Activity (the UI layer): shows Google's consent screen for [consent] and returns what it
 * answered. The provider asks only when somebody is there to see it; a background worker has no resolver.
 */
fun interface ConsentResolver {
    suspend fun resolve(consent: PendingConsent): AuthorizerResult
}
