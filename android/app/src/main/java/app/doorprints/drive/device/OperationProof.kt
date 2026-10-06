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

package app.doorprints.drive.device

import app.doorprints.drive.delete.DeletionLevel

/*
 * The proof of a deletion on Android (S4b-BL-127, S4b-BL-135, docs/15 §10.2). What the platform signs and in what shape
 * (this file) runs on the JVM over a fake [OperationProver]; the platform prompt is in `AndroidOperationProvers.kt`; the
 * decisions (which level asks, single use, 60 seconds, the lock) are `DriveGate` and `PhoneDeletionAuthorizer`, which
 * carry the proof in the token. Web twin: `deletion-proof.ts`.
 */

/** What the platform said when asked to prove [operationId] for a person at the device. */
sealed interface ProofOutcome {
    /** The person authenticated at [issuedAtMs]; [proof] is 64 lower-case hex digits bound to the operation. */
    data class Proved(val issuedAtMs: Long, val proof: String) : ProofOutcome

    /** The platform refused the person (too many wrong tries; it locks them out for a while). */
    data object Denied : ProofOutcome

    /** The person dismissed the prompt. */
    data object Cancelled : ProofOutcome

    /** The prompt ended by itself without an answer. */
    data object TimedOut : ProofOutcome

    /** The platform found no screen lock. */
    data object NoLock : ProofOutcome

    /** No way to ask on this phone (no activity to show it on, no credential screen wired). Fails closed. */
    data object Unavailable : ProofOutcome

    /** Anything else that went wrong. */
    data object Failed : ProofOutcome
}

/**
 * Asks the person and, when they pass, signs the operation (docs/15 §10.2: a Keystore HMAC key through a `CryptoObject`
 * on Android 11+, a process-local key below). [now] is read **after** the person passed, for the proof's time.
 */
interface OperationProver {
    suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome
}

/** The proof's message, as on the website (`proofMessage`): `utf8(operationId) ‖ 0x00 ‖ u64be(issuedAtMs)`. */
object OperationProof {
    fun message(operationId: String, issuedAtMs: Long): ByteArray {
        require(issuedAtMs >= 0) { "time" }
        val time = ByteArray(8) { i -> (issuedAtMs ushr (56 - 8 * i)).toByte() }
        return operationId.encodeToByteArray() + byteArrayOf(0) + time
    }

    fun isProof(text: String): Boolean = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(digits[(b.toInt() shr 4) and 15]).append(digits[b.toInt() and 15])
        return out.toString()
    }
}
