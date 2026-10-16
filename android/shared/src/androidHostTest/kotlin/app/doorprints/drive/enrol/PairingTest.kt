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
import app.doorprints.crypto.JvmCryptoProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The commit-then-reveal pairing code and flow (S4b-BL-126; web twins: `pairing-code.spec.ts`, `pairing-flow.spec.ts`). */
class PairingTest {
    private val p = JvmCryptoProvider
    private val codes = PairingCode(p)
    private val flow = PairingFlow(p)

    private fun bytes(label: String, n: Int) = ByteArray(n) { ((label[it % label.length].code + it) and 0xff).toByte() }
    private fun fill(n: Int, v: Int) = ByteArray(n) { v.toByte() }

    private val t0 = 5_000_000L
    private val nNew = fill(16, 1)
    private val nA = fill(16, 2)
    private val pkNew = fill(65, 3)
    private val pkA = fill(65, 4)

    // ---- the code ----

    @Test
    fun theCommitHoldsOnlyForTheSameNonce() {
        val n = bytes("new", 16)
        val c = codes.commitNonce(n)
        assertEquals(32, c.size)
        assertTrue(codes.commitHolds(n, c))
        for (i in n.indices) {
            val flipped = n.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("flip at $i", codes.commitHolds(flipped, c))
        }
        assertFalse(codes.commitHolds(n, c.copyOf(31)))
    }

    @Test
    fun theCommitIsSha256OfTheNonce() {
        // SHA-256 of 16 bytes of 0x01, from the platform's own digest, not from the code under test.
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest(ByteArray(16) { 1 })
        assertEquals(Bytes.hex(expected), Bytes.hex(codes.commitNonce(ByteArray(16) { 1 })))
    }

    @Test
    fun theCodeIsTheFirstEightDigitsOfTheDigestAsANumberPaddedWithZeros() {
        val nn = bytes("n-new", 16)
        val na = bytes("n-a", 16)
        val pn = bytes("pk-new", 65)
        val pa = bytes("pk-a", 65)
        val code = codes.pairingCode(nn, na, pn, pa)
        assertTrue(code, Regex("^\\d{8}$").matches(code))
        assertEquals(code, codes.pairingCode(nn, na, pn, pa))
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(nn + na + pn + pa)
        val number = java.math.BigInteger(1, digest.copyOf(8)).mod(java.math.BigInteger.valueOf(100_000_000L))
        assertEquals(number.toString().padStart(8, '0'), code)
    }

    @Test
    fun theCodeIsLeftPaddedWithZeros() {
        // Search for an input whose number has leading zeros, so the padding is exercised on the real path.
        var found: String? = null
        for (i in 0 until 5000) {
            val c = codes.pairingCode(Bytes.u32(i.toLong()), nA, pkNew, pkA)
            if (c.startsWith("0")) {
                found = c
                break
            }
        }
        assertNotNull(found)
        assertEquals(8, found!!.length)
    }

    @Test
    fun aSwappedPublicKeyOrNonceGivesADifferentCode() {
        val nn = bytes("n-new", 16)
        val na = bytes("n-a", 16)
        val pn = bytes("pk-new", 65)
        val pa = bytes("pk-a", 65)
        val good = codes.pairingCode(nn, na, pn, pa)
        assertNotEquals(good, codes.pairingCode(na, nn, pn, pa))
        assertNotEquals(good, codes.pairingCode(nn, na, pa, pn))
    }

    // ---- the flow ----

    private fun ok(o: PairingOutcome): PairingOutcome.Ok = o as? PairingOutcome.Ok ?: fail("expected Ok, got $o").let { error("unreachable") }

    @Test
    fun bothSidesShowTheSameEightDigitCodeAfterTheReveal() {
        val commit = flow.newcomerCommit(pkNew, nNew, t0)
        val reply = ok(flow.approverReply(commit, pkA, nA, t0 + 1000))
        val revealed = ok(flow.revealAndCode(reply.message!!, nNew, t0 + 2000))
        assertEquals(PairingMessage.Phase.REVEAL, revealed.message!!.phase)
        val other = flow.codeOf(revealed.message!!, t0 + 2000)
        assertEquals(PairingOutcome.Ok(revealed.code), other)
        assertTrue(Regex("^\\d{8}$").matches(revealed.code))
        assertEquals(codes.pairingCode(nNew, nA, pkNew, pkA), revealed.code)
    }

    @Test
    fun aRevealThatDoesNotMatchTheCommitIsRefusedByEitherSide() {
        val commit = flow.newcomerCommit(pkNew, nNew, t0)
        val reply = ok(flow.approverReply(commit, pkA, nA, t0))
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.COMMIT_MISMATCH), flow.revealAndCode(reply.message!!, fill(16, 9), t0))
        // A flipped bit of the real nonce fails too.
        val flipped = nNew.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.COMMIT_MISMATCH), flow.revealAndCode(reply.message!!, flipped, t0))
        // The approver refuses a revealed message whose nonce was swapped after the commit.
        val good = ok(flow.revealAndCode(reply.message!!, nNew, t0)).message!!
        val swapped = good.copy(nNew = Bytes.b64(fill(16, 9)))
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.COMMIT_MISMATCH), flow.codeOf(swapped, t0))
    }

    @Test
    fun aRequestOlderThanTenMinutesIsRefusedAtEveryStep() {
        val commit = flow.newcomerCommit(pkNew, nNew, t0)
        val late = t0 + PairingCode.PAIRING_TTL_MS + 1
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.EXPIRED), flow.approverReply(commit, pkA, nA, late))
        val reply = ok(flow.approverReply(commit, pkA, nA, t0 + 1)).message!!
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.EXPIRED), flow.revealAndCode(reply, nNew, late))
        val revealed = ok(flow.revealAndCode(reply, nNew, t0 + 2)).message!!
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.EXPIRED), flow.codeOf(revealed, late))
        // The last good moment is still good; a clock set back before the request is not.
        assertTrue(flow.approverReply(commit, pkA, nA, t0 + PairingCode.PAIRING_TTL_MS) is PairingOutcome.Ok)
        assertEquals(PairingOutcome.Refused(PairingOutcome.Reason.EXPIRED), flow.approverReply(commit, pkA, nA, t0 - 1))
    }

    @Test
    fun amessageOfTheWrongPhaseOrWithAMissingOrBrokenFieldIsIncomplete() {
        val incomplete = PairingOutcome.Refused(PairingOutcome.Reason.INCOMPLETE)
        val commit = flow.newcomerCommit(pkNew, nNew, t0)
        val reply = ok(flow.approverReply(commit, pkA, nA, t0)).message!!
        val revealed = ok(flow.revealAndCode(reply, nNew, t0)).message!!
        assertEquals(incomplete, flow.approverReply(reply, pkA, nA, t0))
        assertEquals(incomplete, flow.approverReply(commit.copy(commit = null), pkA, nA, t0))
        assertEquals(incomplete, flow.approverReply(commit.copy(pkNew = null), pkA, nA, t0))
        assertEquals(incomplete, flow.revealAndCode(commit, nNew, t0))
        assertEquals(incomplete, flow.revealAndCode(reply.copy(nApprover = null), nNew, t0))
        assertEquals(incomplete, flow.revealAndCode(reply.copy(pkApprover = "not base64!"), nNew, t0))
        assertEquals(incomplete, flow.codeOf(reply, t0))
        assertEquals(incomplete, flow.codeOf(revealed.copy(nNew = null), t0))
        assertEquals(incomplete, flow.codeOf(revealed.copy(commit = "%%%"), t0))
    }

    @Test
    fun theApproverCarriesTheCommitAndKeysThroughUnchangedAndTheWrapIsAttachedLater() {
        val commit = flow.newcomerCommit(pkNew, nNew, t0)
        assertNull(commit.nApprover)
        val reply = ok(flow.approverReply(commit, pkA, nA, t0)).message!!
        assertEquals(commit.createdAtMs, reply.createdAtMs)
        assertEquals(commit.pkNew, reply.pkNew)
        assertEquals(commit.commit, reply.commit)
        assertEquals(Bytes.b64(pkA), reply.pkApprover)
        val wrapped = withWrap(reply, "enc", "ct", 3)
        assertEquals(reply.copy(wrapEnc = "enc", wrapCt = "ct", epoch = 3), wrapped)
    }
}
