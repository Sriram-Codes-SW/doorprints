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

import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.drive.delete.DeletionLevel
import app.doorprints.drive.wiring.ProverDeviceAuth
import app.doorprints.testing.blocking
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iPhone's deletion proof over a scripted [ProtectedSecret] (the Keychain item behind it is `KeychainProtectedSecret`). */
class SecretOperationProverTest {
    private val crypto = platformCryptoProvider()

    private class FakeSecret(var answer: () -> SecretOpen) : ProtectedSecret {
        val reasons = mutableListOf<String>()
        var lastHandedOut: ByteArray? = null
        override suspend fun open(reason: String): SecretOpen {
            reasons += reason
            return answer().also { (it as? SecretOpen.Opened)?.let { o -> lastHandedOut = o.secret } }
        }
    }

    private val key = ByteArray(32) { (it + 1).toByte() }
    private var t = 5_000L
    private val secret = FakeSecret { SecretOpen.Opened(key.copyOf()) }
    private val prover = SecretOperationProver(secret, crypto)

    private fun prove(op: String = "del-1", level: DeletionLevel = DeletionLevel.L2, reason: String = "Delete") =
        blocking { prover.prove(op, level, reason) { t } }

    @Test
    fun aPassBecomesTheHmacOfTheOperationAndTheTimeUnderTheSecret() {
        val proved = assertIs<ProofOutcome.Proved>(prove("del-1"))
        assertEquals(5_000L, proved.issuedAtMs)
        assertEquals(OperationProof.hex(crypto.hmacSha256(key, OperationProof.message("del-1", 5_000L))), proved.proof)
        assertTrue(OperationProof.isProof(proved.proof))
    }

    @Test
    fun theTimeIsReadAfterThePersonPassedNotBefore() {
        var clock = 100L
        val slow = FakeSecret { clock = 9_000L; SecretOpen.Opened(key.copyOf()) }
        val proved = assertIs<ProofOutcome.Proved>(blocking { SecretOperationProver(slow, crypto).prove("op", DeletionLevel.L2, "r") { clock } })
        assertEquals(9_000L, proved.issuedAtMs)
    }

    @Test
    fun theProofIsBoundToTheOperationTheTimeAndTheSecret() {
        val a = (prove("del-1") as ProofOutcome.Proved).proof
        assertNotEquals(a, (prove("del-2") as ProofOutcome.Proved).proof)
        assertEquals(a, (prove("del-1") as ProofOutcome.Proved).proof)
        t += 1
        assertNotEquals(a, (prove("del-1") as ProofOutcome.Proved).proof)
        t = 5_000L
        val other = FakeSecret { SecretOpen.Opened(ByteArray(32) { 9 }) }
        assertNotEquals(a, (blocking { SecretOperationProver(other, crypto).prove("del-1", DeletionLevel.L2, "r") { t } } as ProofOutcome.Proved).proof)
    }

    @Test
    fun theReasonReachesThePrompt() {
        prove(reason = "Delete all backups from Google Drive")
        assertEquals(listOf("Delete all backups from Google Drive"), secret.reasons)
    }

    @Test
    fun theSecretIsWipedAfterTheHmac() {
        prove()
        assertContentEquals(ByteArray(32), secret.lastHandedOut)
    }

    @Test
    fun aSecretOfTheWrongSizeIsNoProofAndIsWiped() {
        secret.answer = { SecretOpen.Opened(ByteArray(16) { 3 }) }
        assertEquals(ProofOutcome.Failed, prove())
        assertContentEquals(ByteArray(16), secret.lastHandedOut)
    }

    @Test
    fun everyAnswerOfThePlatformIsItsOwnOutcome() {
        val table = mapOf(
            SecretOpen.Cancelled to ProofOutcome.Cancelled,
            SecretOpen.Denied to ProofOutcome.Denied,
            SecretOpen.NoLock to ProofOutcome.NoLock,
            SecretOpen.Unavailable to ProofOutcome.Unavailable,
            SecretOpen.Failed to ProofOutcome.Failed,
        )
        for ((answer, outcome) in table) {
            secret.answer = { answer }
            assertEquals(outcome, prove(), "$answer")
        }
    }

    @Test
    fun anErrorFromThePlatformIsFailedButACancellationPropagates() {
        secret.answer = { throw IllegalStateException("keychain") }
        assertEquals(ProofOutcome.Failed, prove())
        secret.answer = { throw CancellationException("screen left") }
        assertFailsWith<CancellationException> { prove() }
    }

    @Test
    fun aSecretNeverShowsInItsToString() {
        assertEquals("Opened", SecretOpen.Opened(key).toString())
    }

    // ---- through the phone's adapter, as the controller uses it ----

    private fun auth(lock: Boolean = true) = ProverDeviceAuth(prover, { lock }) { t }

    @Test
    fun thePhoneAdapterCarriesTheProofOfThePassAndHandsItOutOnce() {
        val auth = auth()
        auth.bindNext("plan-7")
        assertEquals(AuthResult.SUCCESS, blocking { auth.authenticate("Delete", DeleteLevel.L2) })
        val proof = auth.takeProof()!!
        assertEquals(5_000L, proof.issuedAtMs)
        assertEquals(OperationProof.hex(crypto.hmacSha256(key, OperationProof.message("plan-7", 5_000L))), proof.proof)
        assertNull(auth.takeProof(), "a proof is single use")
    }

    @Test
    fun levelOneAsksNothingAndAPassWithNoLockOrALockoutIsNotAProof() {
        val auth = auth()
        assertEquals(AuthResult.FAILED, blocking { auth.authenticate("x", DeleteLevel.L1) })
        assertEquals(0, secret.reasons.size)
        secret.answer = { SecretOpen.NoLock }
        assertEquals(AuthResult.LOCK_NOT_SET, blocking { auth.authenticate("x", DeleteLevel.L3) })
        assertNull(auth.takeProof())
        secret.answer = { SecretOpen.Denied }
        assertEquals(AuthResult.LOCKED_OUT, blocking { auth.authenticate("x", DeleteLevel.L3) })
        secret.answer = { SecretOpen.Cancelled }
        assertEquals(AuthResult.CANCELLED, blocking { auth.authenticate("x", DeleteLevel.L2) })
        assertNull(auth.takeProof())
    }
}
