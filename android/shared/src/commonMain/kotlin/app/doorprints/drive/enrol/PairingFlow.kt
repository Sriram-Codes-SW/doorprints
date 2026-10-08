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

/**
 * One pairing message on the 8-digit channel (web twin: `PairingMessage` in `pairing-flow.ts`). Binary fields are
 * standard base64 text, as on the website, so a message can travel over any text channel. QR enrolment is the separate
 * `dp1.` code ([app.doorprints.crypto.qrOfferText]).
 */
data class PairingMessage(
    val phase: Phase,
    val createdAtMs: Long,
    val pkNew: String? = null,
    val commit: String? = null,
    val pkApprover: String? = null,
    val nApprover: String? = null,
    val nNew: String? = null,
    /** HPKE wrap of the current folder key, copied back after the codes match. Base64. */
    val wrapEnc: String? = null,
    val wrapCt: String? = null,
    val epoch: Int? = null,
) {
    /**
      * The three messages of the exchange, in order: the newcomer's commitment, the approver's answer, the newcomer's
      * reveal.
     */
    enum class Phase { COMMIT, APPROVER, REVEAL }
}

/** A step that worked ([code] is empty before the reveal; [message] is what to send on) or why it did not. */
sealed interface PairingOutcome {
    /**
      * The step worked. [code] is the 8-digit code once both nonces are known (empty before the reveal); [message] is
      * what to send on.
     */
    data class Ok(val code: String, val message: PairingMessage? = null) : PairingOutcome
    /** The step was refused; nothing is derived from the message. */
    data class Refused(val reason: Reason) : PairingOutcome
    /**
     * Why a message is refused: too old, a reveal that does not match its commitment, or a missing or malformed field.
     */
    enum class Reason { EXPIRED, COMMIT_MISMATCH, INCOMPLETE }
}

/** Attach the enrolled device's wrap so the newcomer can open it. The pairing fields stay as they were. */
fun withWrap(message: PairingMessage, wrapEnc: String, wrapCt: String, epoch: Int): PairingMessage =
    message.copy(wrapEnc = wrapEnc, wrapCt = wrapCt, epoch = epoch)

/**
 * Commit-then-reveal pairing (docs/15 §9.5 i, S4b-BL-126; web twin: `pairing-flow.ts`): the newcomer commits to its
 * nonce, the approver answers with its own, the newcomer reveals, and both compute the same 8-digit code, which the
 * two people compare. A request older than ten minutes, or a reveal that does not match its commit, is refused.
 */
class PairingFlow(p: CryptoProvider) {
    private val codes = PairingCode(p)

    /** Newcomer: post pk_new and SHA-256(n_new). */
    fun newcomerCommit(pkNew: ByteArray, nNew: ByteArray, nowMs: Long): PairingMessage =
        PairingMessage(
            PairingMessage.Phase.COMMIT, nowMs, pkNew = Bytes.b64(pkNew), commit = Bytes.b64(codes.commitNonce(nNew)),
        )

    /** Approver: post n_a and pk_approver. */
    fun approverReply(commit: PairingMessage, pkApprover: ByteArray, nApprover: ByteArray, nowMs: Long): PairingOutcome {
        if (commit.phase != PairingMessage.Phase.COMMIT || commit.pkNew == null || commit.commit == null) return incomplete
        if (PairingCode.pairingExpired(commit.createdAtMs, nowMs)) return expired
        return PairingOutcome.Ok(
            "",
            PairingMessage(
                PairingMessage.Phase.APPROVER, commit.createdAtMs, pkNew = commit.pkNew, commit = commit.commit,
                pkApprover = Bytes.b64(pkApprover), nApprover = Bytes.b64(nApprover),
            ),
        )
    }

    /** Newcomer: reveal n_new. Both sides then compute the 8-digit code. */
    fun revealAndCode(approver: PairingMessage, nNew: ByteArray, nowMs: Long): PairingOutcome {
        if (approver.phase != PairingMessage.Phase.APPROVER) return incomplete
        val commit = Bytes.unb64(approver.commit ?: return incomplete)
        val nA = Bytes.unb64(approver.nApprover ?: return incomplete)
        val pkNew = Bytes.unb64(approver.pkNew ?: return incomplete)
        val pkA = Bytes.unb64(approver.pkApprover ?: return incomplete)
        if (PairingCode.pairingExpired(approver.createdAtMs, nowMs)) return expired
        if (commit == null || nA == null || pkNew == null || pkA == null) return incomplete
        if (!codes.commitHolds(nNew, commit)) return mismatch
        return PairingOutcome.Ok(
            codes.pairingCode(nNew, nA, pkNew, pkA),
            approver.copy(phase = PairingMessage.Phase.REVEAL, nNew = Bytes.b64(nNew)),
        )
    }

    /** Approver (or newcomer after the reveal): the code from the revealed message. */
    fun codeOf(revealed: PairingMessage, nowMs: Long): PairingOutcome {
        if (revealed.phase != PairingMessage.Phase.REVEAL) return incomplete
        val nNew = Bytes.unb64(revealed.nNew ?: return incomplete)
        val commit = Bytes.unb64(revealed.commit ?: return incomplete)
        val nA = Bytes.unb64(revealed.nApprover ?: return incomplete)
        val pkNew = Bytes.unb64(revealed.pkNew ?: return incomplete)
        val pkA = Bytes.unb64(revealed.pkApprover ?: return incomplete)
        if (PairingCode.pairingExpired(revealed.createdAtMs, nowMs)) return expired
        if (nNew == null || commit == null || nA == null || pkNew == null || pkA == null) return incomplete
        if (!codes.commitHolds(nNew, commit)) return mismatch
        return PairingOutcome.Ok(codes.pairingCode(nNew, nA, pkNew, pkA))
    }

    private companion object {
        val incomplete = PairingOutcome.Refused(PairingOutcome.Reason.INCOMPLETE)
        val expired = PairingOutcome.Refused(PairingOutcome.Reason.EXPIRED)
        val mismatch = PairingOutcome.Refused(PairingOutcome.Reason.COMMIT_MISMATCH)
    }
}
