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

import app.doorprints.drive.driveSha256
import app.doorprints.shared.export.Sha256
import kotlin.io.encoding.Base64

/** [size] bytes from a cryptographically secure source (Android: `SecureRandom`; the iPhone: `SecRandomCopyBytes`). */
typealias RandomBytes = (size: Int) -> ByteArray

/**
 * PKCE (RFC 7636) for the browser sign-ins (docs/15 §5.5): the Android fallback's system browser and the iPhone's
 * `ASWebAuthenticationSession`. A verifier is 32 random bytes in base64url (43 characters, the RFC's minimum length and
 * its recommended form); the challenge is `S256`, never `plain`. Pass a secure source (the callers' default); tests pass a
 * fixed one. Common code since the iPhone's Drive (it was Android's, on `java.security`).
 */
object Pkce {
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** RFC 7636 §4.1: 43 to 128 characters of `[A-Za-z0-9-._~]`. */
    fun newVerifier(random: RandomBytes): String = randomToken(random, VERIFIER_BYTES)

    /** RFC 7636 §4.2: `BASE64URL(SHA256(ASCII(verifier)))`. */
    fun challenge(verifier: String, sha256: () -> Sha256 = ::driveSha256): String =
        b64.encode(sha256().apply { update(verifier.encodeToByteArray()) }.digest())

    /** The `state` nonce that ties the redirect to the request (RFC 6749 §10.12). */
    fun newState(random: RandomBytes): String = randomToken(random, STATE_BYTES)

    /** Whether [v] fits RFC 7636's verifier grammar (length and characters); a check for callers and tests. */
    fun isValidVerifier(v: String): Boolean = v.length in 43..128 && v.all { it in UNRESERVED }

    private fun randomToken(random: RandomBytes, bytes: Int): String {
        val buf = random(bytes)
        require(buf.size == bytes) { "the random source gave ${buf.size} bytes, not $bytes" }
        return b64.encode(buf)
    }

    private const val VERIFIER_BYTES = 32
    private const val STATE_BYTES = 24
    private val UNRESERVED = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')
}
