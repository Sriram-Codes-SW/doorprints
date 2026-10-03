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

import { concat, constantTimeEquals } from '../../crypto/bytes';
import { Sha256 } from '../../../export/sha256';

/** Ten minutes, docs/15 §9.5 i: a pairing request left alone expires. */
export const PAIRING_TTL_MS = 10 * 60 * 1000;

/** SHA-256(nonce): the commit the newcomer posts before revealing the nonce. */
export function commitNonce(nonce: Uint8Array): Uint8Array {
  return new Sha256().update(nonce).digest();
}

/**
 * The 8-digit comparison code both screens show (docs/15 §9.5 i): the first eight decimal digits of
 * SHA-256(n_new ‖ n_a ‖ pk_new ‖ pk_approver), padded on the left with zeros.
 */
export function pairingCode(
  nNew: Uint8Array,
  nApprover: Uint8Array,
  pkNew: Uint8Array,
  pkApprover: Uint8Array,
): string {
  const digest = new Sha256().update(concat(nNew, nApprover, pkNew, pkApprover)).digest();
  let n = 0n;
  for (let i = 0; i < 8; i++) n = (n << 8n) + BigInt(digest[i]);
  return (n % 100_000_000n).toString().padStart(8, '0');
}

export function commitHolds(nonce: Uint8Array, commit: Uint8Array): boolean {
  return constantTimeEquals(commitNonce(nonce), commit);
}

export function pairingExpired(createdAtMs: number, nowMs: number): boolean {
  return nowMs < createdAtMs || nowMs - createdAtMs > PAIRING_TTL_MS;
}
