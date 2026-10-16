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

import android.app.Application
import android.hardware.biometrics.BiometricPrompt
import app.doorprints.crypto.platformCryptoProvider
import app.doorprints.deviceauth.AuthResult
import app.doorprints.deviceauth.DeleteLevel
import app.doorprints.deviceauth.DeviceAuth
import app.doorprints.drive.delete.DeletionLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class FakeDeviceAuth(var result: AuthResult = AuthResult.SUCCESS) : DeviceAuth {
    var calls = 0
    var lastLevel: DeleteLevel? = null
    var lastReason: String? = null
    var failWith: Exception? = null
    override fun isDeviceLockEnabled() = true
    override suspend fun authenticate(reason: String, level: DeleteLevel): AuthResult {
        calls++
        lastLevel = level
        lastReason = reason
        failWith?.let { throw it }
        return result
    }
}

class OperationProofTest {
    @Test fun `the message is the operation id, a zero byte and the time as 8 big-endian bytes`() {
        val m = OperationProof.message("del-ab", 0x0102030405060708L)
        assertEquals("64656c2d6162" + "00" + "0102030405060708", OperationProof.hex(m))
    }

    @Test fun `a negative time is refused`() {
        try {
            OperationProof.message("x", -1)
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `a proof is 64 lower-case hex digits`() {
        assertTrue(OperationProof.isProof("0123456789abcdef".repeat(4)))
        assertFalse(OperationProof.isProof("0123456789ABCDEF".repeat(4)))
        assertFalse(OperationProof.isProof("a".repeat(65)))
    }
}

class SoftwareOperationProverTest {
    private val auth = FakeDeviceAuth()
    private val prover = SoftwareOperationProver(auth, platformCryptoProvider())
    private var t = 5_000L

    private fun prove(op: String = "del-1", level: DeletionLevel = DeletionLevel.L2, reason: String = "Delete") =
        runBlocking { prover.prove(op, level, reason) { t } }

    @Test fun `a pass becomes a proof stamped after the pass`() {
        val r = prove() as ProofOutcome.Proved
        assertEquals(5_000L, r.issuedAtMs)
        assertTrue(OperationProof.isProof(r.proof))
    }

    @Test fun `the proof is bound to the operation and the time`() {
        val a = (prove("del-1") as ProofOutcome.Proved).proof
        val b = (prove("del-2") as ProofOutcome.Proved).proof
        val again = (prove("del-1") as ProofOutcome.Proved).proof
        assertNotEquals(a, b)
        assertEquals(a, again)
        t += 1
        assertNotEquals(a, (prove("del-1") as ProofOutcome.Proved).proof)
    }

    @Test fun `another prover's proof differs, so a proof cannot be made without the process key`() {
        val other = SoftwareOperationProver(auth, platformCryptoProvider())
        val a = (prove() as ProofOutcome.Proved).proof
        val b = (runBlocking { other.prove("del-1", DeletionLevel.L2, "x") { t } } as ProofOutcome.Proved).proof
        assertNotEquals(a, b)
    }

    @Test fun `the reason and the level reach the platform prompt`() {
        prove(level = DeletionLevel.L3, reason = "Delete everything")
        assertEquals(DeleteLevel.L3, auth.lastLevel)
        assertEquals("Delete everything", auth.lastReason)
        prove(level = DeletionLevel.L2)
        assertEquals(DeleteLevel.L2, auth.lastLevel)
    }

    @Test fun `each platform answer is its own outcome`() {
        val expected = mapOf(
            AuthResult.CANCELLED to ProofOutcome.Cancelled,
            AuthResult.TIMED_OUT to ProofOutcome.TimedOut,
            AuthResult.LOCKED_OUT to ProofOutcome.Denied,
            AuthResult.LOCK_NOT_SET to ProofOutcome.NoLock,
            AuthResult.NOT_AVAILABLE to ProofOutcome.Unavailable,
            AuthResult.FAILED to ProofOutcome.Failed,
        )
        for ((result, outcome) in expected) {
            auth.result = result
            assertEquals(result.name, outcome, prove())
        }
    }

    @Test fun `a platform exception is a failure`() {
        auth.failWith = IllegalStateException("x")
        assertEquals(ProofOutcome.Failed, prove())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PromptErrorsTest {
    @Test fun `cancel, system cancel and the negative button are a cancel`() {
        assertEquals(ProofOutcome.Cancelled, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
        assertEquals(ProofOutcome.Cancelled, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_CANCELED))
        assertEquals(ProofOutcome.Cancelled, PromptErrors.map(PromptErrors.ERROR_NEGATIVE_BUTTON))
    }

    @Test fun `the prompt's own timeout is a timeout, not a cancel`() {
        assertEquals(ProofOutcome.TimedOut, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT))
    }

    @Test fun `too many wrong tries is a denial`() {
        assertEquals(ProofOutcome.Denied, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT))
        assertEquals(ProofOutcome.Denied, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT))
    }

    @Test fun `no credential is no lock`() {
        assertEquals(ProofOutcome.NoLock, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL))
    }

    @Test fun `missing hardware is unavailable and the rest fails closed`() {
        assertEquals(ProofOutcome.Unavailable, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT))
        assertEquals(ProofOutcome.Unavailable, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(ProofOutcome.Unavailable, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS))
        assertEquals(ProofOutcome.Failed, PromptErrors.map(BiometricPrompt.BIOMETRIC_ERROR_UNABLE_TO_PROCESS))
        assertEquals(ProofOutcome.Failed, PromptErrors.map(-12345))
    }
}
