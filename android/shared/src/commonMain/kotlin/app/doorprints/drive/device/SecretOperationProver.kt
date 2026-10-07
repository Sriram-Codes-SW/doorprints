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

import app.doorprints.crypto.CryptoProvider
import app.doorprints.drive.delete.DeletionLevel
import kotlinx.coroutines.CancellationException

/** What opening a [ProtectedSecret] came to. The secret is never in a `toString`. */
sealed interface SecretOpen {
    /** The person authenticated and the platform released the 32-byte [secret] (the caller wipes it). */
    class Opened(val secret: ByteArray) : SecretOpen {
        override fun toString() = "Opened"
    }

    /** The platform refused the person (too many wrong tries; it locks them out for a while). */
    data object Denied : SecretOpen

    /** The person dismissed the prompt. */
    data object Cancelled : SecretOpen

    /** The platform found no passcode, so no secret can be protected by one. */
    data object NoLock : SecretOpen

    /** No way to ask now (no screen to show it on, the phone is locked). Fails closed. */
    data object Unavailable : SecretOpen

    /** Anything else that went wrong. */
    data object Failed : SecretOpen
}

/**
 * A secret the platform releases **only after the person authenticated for this read**: the iPhone's is a Keychain item
 * whose access control is `kSecAccessControlUserPresence` (Face ID, Touch ID or the passcode on every read), so the secret
 * cannot be used by an overlay, a tap or a patched check inside the app: the platform, not the app, gates it (docs/15
 * §10.2). Its Android twin is the Keystore HMAC key with per-use authentication, which never releases its bytes at all.
 */
interface ProtectedSecret {
    /** Asks the person (the system's prompt shows [reason]) and returns the secret; makes the secret first when there is none. */
    suspend fun open(reason: String): SecretOpen
}

/**
 * [OperationProver] over a [ProtectedSecret] (S4b-BL-135, the iPhone's half): the proof is the 64-hex
 * `HMAC-SHA-256(secret, utf8(operationId) 00 u64be(issuedAtMs))` of the website's message ([OperationProof.message]),
 * made **after** the person authenticated, with `issuedAtMs` the moment they passed. The secret is held in memory only for
 * the HMAC and wiped at once. Single use, the 60-second window, the plan and level binding and the lock-awareness are the
 * authorizer's and the gate's (`PhoneDeletionAuthorizer`, `ProverDeviceAuth`, `DriveGate`), the same code as Android's.
 */
class SecretOperationProver(
    private val secret: ProtectedSecret,
    private val crypto: CryptoProvider,
) : OperationProver {

    override suspend fun prove(operationId: String, level: DeletionLevel, reason: String, now: () -> Long): ProofOutcome {
        val opened = try {
            secret.open(reason)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ProofOutcome.Failed
        }
        return when (opened) {
            is SecretOpen.Opened -> {
                val key = opened.secret
                try {
                    if (key.size != KEY_BYTES) return ProofOutcome.Failed
                    val issued = now()
                    ProofOutcome.Proved(issued, OperationProof.hex(crypto.hmacSha256(key, OperationProof.message(operationId, issued))))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ProofOutcome.Failed
                } finally {
                    key.fill(0)
                }
            }
            SecretOpen.Cancelled -> ProofOutcome.Cancelled
            SecretOpen.Denied -> ProofOutcome.Denied
            SecretOpen.NoLock -> ProofOutcome.NoLock
            SecretOpen.Unavailable -> ProofOutcome.Unavailable
            SecretOpen.Failed -> ProofOutcome.Failed
        }
    }

    private companion object {
        const val KEY_BYTES = 32
    }
}
