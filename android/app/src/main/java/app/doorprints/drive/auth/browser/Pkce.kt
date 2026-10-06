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

import java.security.MessageDigest
import java.util.Base64
import java.util.Random

/**
 * PKCE (RFC 7636) for the system-browser sign-in (docs/15 §5.5). A verifier is 32 random bytes in base64url (43
 * characters, the RFC's minimum length and its recommended form); the challenge is `S256`, never `plain`.
 * Pass a `SecureRandom` (the default of the callers); tests pass a fixed one.
 */
object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** RFC 7636 §4.1: 43 to 128 characters of `[A-Za-z0-9-._~]`. */
    fun newVerifier(random: Random): String = randomToken(random, VERIFIER_BYTES)

    /** RFC 7636 §4.2: `BASE64URL(SHA256(ASCII(verifier)))`. */
    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The `state` nonce that ties the redirect to the request (RFC 6749 §10.12). */
    fun newState(random: Random): String = randomToken(random, STATE_BYTES)

    fun isValidVerifier(v: String): Boolean = v.length in 43..128 && v.all { it in UNRESERVED }

    private fun randomToken(random: Random, bytes: Int): String {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return b64.encodeToString(buf)
    }

    private const val VERIFIER_BYTES = 32
    private const val STATE_BYTES = 24
    private val UNRESERVED = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
}
