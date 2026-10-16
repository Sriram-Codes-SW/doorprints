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

package app.doorprints.drive.enrol

import app.doorprints.crypto.Bytes
import app.doorprints.crypto.CryptoProvider
import app.doorprints.crypto.constantTimeEquals
import app.doorprints.crypto.sha256Of

/**
 * The 8-digit pairing code and its commit (docs/15 §9.5 i, S4b-BL-126; web twin: `pairing-code.ts`). The newcomer
 * posts SHA-256(nonce) before it reveals the nonce, so the approver's own nonce cannot be chosen to steer the code.
 */
class PairingCode(private val p: CryptoProvider) {
    /** SHA-256(nonce): the commit the newcomer posts before revealing the nonce. */
    fun commitNonce(nonce: ByteArray): ByteArray = p.sha256Of(nonce)

    /**
     * The 8-digit comparison code both screens show: the first eight decimal digits of
     * SHA-256(n_new ‖ n_a ‖ pk_new ‖ pk_approver) read as a number, modulo 10^8, padded on the left with zeros.
     */
    fun pairingCode(nNew: ByteArray, nApprover: ByteArray, pkNew: ByteArray, pkApprover: ByteArray): String {
        val digest = p.sha256Of(Bytes.concat(nNew, nApprover, pkNew, pkApprover))
        var n = 0UL
        for (i in 0 until 8) n = (n shl 8) + (digest[i].toInt() and 0xff).toULong()
        return (n % 100_000_000UL).toString().padStart(8, '0')
    }

    /** True when [commit] is the commit of exactly [nonce]. */
    fun commitHolds(nonce: ByteArray, commit: ByteArray): Boolean = constantTimeEquals(commitNonce(nonce), commit)

    companion object {
        /** Ten minutes, docs/15 §9.5 i: a pairing request left alone expires. */
        const val PAIRING_TTL_MS = 10L * 60 * 1000

        /** A request from the future (a clock set back) is expired too. */
        fun pairingExpired(createdAtMs: Long, nowMs: Long): Boolean = nowMs < createdAtMs || nowMs - createdAtMs > PAIRING_TTL_MS
    }
}
