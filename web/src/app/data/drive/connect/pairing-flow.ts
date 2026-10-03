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

import { b64, unb64 } from '../../crypto/bytes';
import { commitHolds, commitNonce, pairingCode, pairingExpired } from './pairing-code';

/** One pairing message on the 8-digit channel. QR enrolment is the separate `dp1.` code. */
export interface PairingMessage {
  readonly phase: 'commit' | 'approver' | 'reveal';
  readonly createdAtMs: number;
  readonly pkNew?: string;
  readonly commit?: string;
  readonly pkApprover?: string;
  readonly nApprover?: string;
  readonly nNew?: string;
  /** HPKE wrap of the current folder key, copied back after the codes match. Base64. */
  readonly wrapEnc?: string;
  readonly wrapCt?: string;
  readonly epoch?: number;
}

/** Attach the enrolled browser's wrap so the newcomer can open it. The pairing fields stay as they were. */
export function withWrap(message: PairingMessage, wrapEnc: string, wrapCt: string, epoch: number): PairingMessage {
  return { ...message, wrapEnc, wrapCt, epoch };
}

export type PairingOutcome =
  | { readonly ok: true; readonly code: string }
  | { readonly ok: false; readonly reason: 'EXPIRED' | 'COMMIT_MISMATCH' | 'INCOMPLETE' };

/** Newcomer: post pk_new and SHA-256(n_new). */
export function newcomerCommit(pkNew: Uint8Array, nNew: Uint8Array, nowMs: number): PairingMessage {
  return {
    phase: 'commit',
    createdAtMs: nowMs,
    pkNew: b64(pkNew),
    commit: b64(commitNonce(nNew)),
  };
}

/** Approver: post n_a and pk_approver. */
export function approverReply(
  commit: PairingMessage,
  pkApprover: Uint8Array,
  nApprover: Uint8Array,
  nowMs: number,
): PairingOutcome & { readonly message?: PairingMessage } {
  if (commit.phase !== 'commit' || !commit.pkNew || !commit.commit) return { ok: false, reason: 'INCOMPLETE' };
  if (pairingExpired(commit.createdAtMs, nowMs)) return { ok: false, reason: 'EXPIRED' };
  return {
    ok: true,
    code: '',
    message: {
      phase: 'approver',
      createdAtMs: commit.createdAtMs,
      pkNew: commit.pkNew,
      commit: commit.commit,
      pkApprover: b64(pkApprover),
      nApprover: b64(nApprover),
    },
  };
}

/** Newcomer: reveal n_new. Both sides then compute the 8-digit code. */
export function revealAndCode(
  approver: PairingMessage,
  nNew: Uint8Array,
  nowMs: number,
): PairingOutcome & { readonly message?: PairingMessage } {
  if (approver.phase !== 'approver' || !approver.pkNew || !approver.commit || !approver.pkApprover || !approver.nApprover) {
    return { ok: false, reason: 'INCOMPLETE' };
  }
  if (pairingExpired(approver.createdAtMs, nowMs)) return { ok: false, reason: 'EXPIRED' };
  const commit = unb64(approver.commit);
  const nA = unb64(approver.nApprover);
  const pkNew = unb64(approver.pkNew);
  const pkA = unb64(approver.pkApprover);
  if (!commit || !nA || !pkNew || !pkA) return { ok: false, reason: 'INCOMPLETE' };
  if (!commitHolds(nNew, commit)) return { ok: false, reason: 'COMMIT_MISMATCH' };
  const code = pairingCode(nNew, nA, pkNew, pkA);
  return {
    ok: true,
    code,
    message: {
      ...approver,
      phase: 'reveal',
      nNew: b64(nNew),
    },
  };
}

/** Approver (or newcomer after reveal): code from the revealed message. */
export function codeOf(revealed: PairingMessage, nowMs: number): PairingOutcome {
  if (revealed.phase !== 'reveal' || !revealed.nNew || !revealed.nApprover || !revealed.pkNew || !revealed.pkApprover || !revealed.commit) {
    return { ok: false, reason: 'INCOMPLETE' };
  }
  if (pairingExpired(revealed.createdAtMs, nowMs)) return { ok: false, reason: 'EXPIRED' };
  const nNew = unb64(revealed.nNew);
  const commit = unb64(revealed.commit);
  const nA = unb64(revealed.nApprover);
  const pkNew = unb64(revealed.pkNew);
  const pkA = unb64(revealed.pkApprover);
  if (!nNew || !commit || !nA || !pkNew || !pkA) return { ok: false, reason: 'INCOMPLETE' };
  if (!commitHolds(nNew, commit)) return { ok: false, reason: 'COMMIT_MISMATCH' };
  return { ok: true, code: pairingCode(nNew, nA, pkNew, pkA) };
}
