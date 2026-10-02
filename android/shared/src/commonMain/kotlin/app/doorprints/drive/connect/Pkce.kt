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

import app.doorprints.crypto.CryptoProvider

/**
 * PKCE (RFC 7636) with the S256 method, and the other random values of one sign-in attempt. The verifier lives only in
 * memory for the length of one attempt; Google gets only its hash.
 */
class Pkce private constructor(val verifier: String, val challenge: String, val state: String) {
    override fun toString() = "Pkce(…)"

    companion object {
        /** 32 random bytes: a 43-character verifier (RFC 7636 asks for 43 to 128). */
        fun create(p: CryptoProvider): Pkce {
            val verifier = base64Url(p.randomBytes(32))
            return Pkce(verifier, challengeOf(p, verifier), base64Url(p.randomBytes(16)))
        }

        fun challengeOf(p: CryptoProvider, verifier: String): String {
            val sha = p.sha256()
            val bytes = verifier.encodeToByteArray()
            sha.update(bytes, 0, bytes.size)
            return base64Url(sha.digest())
        }

        /** Base64url without padding (RFC 4648 §5). */
        fun base64Url(bytes: ByteArray): String =
            app.doorprints.crypto.Bytes.b64(bytes).trimEnd('=').replace('+', '-').replace('/', '_')
    }
}
