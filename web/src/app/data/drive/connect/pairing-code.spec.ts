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

import { describe, expect, it } from 'vitest';
import {
  PAIRING_TTL_MS,
  commitHolds,
  commitNonce,
  pairingCode,
  pairingExpired,
} from './pairing-code';

function bytes(label: string, n: number): Uint8Array {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = (label.charCodeAt(i % label.length) + i) & 0xff;
  return out;
}

describe('pairing-code (S4b-BL-126, docs/15 §9.5 i)', () => {
  it('commit holds only for the same nonce', () => {
    const n = bytes('new', 16);
    const c = commitNonce(n);
    expect(commitHolds(n, c)).toBe(true);
    const flipped = n.slice();
    flipped[0] ^= 1;
    expect(commitHolds(flipped, c)).toBe(false);
  });

  it('both sides compute the same 8-digit code, padded', () => {
    const nNew = bytes('n-new', 16);
    const nA = bytes('n-a', 16);
    const pkNew = bytes('pk-new', 65);
    const pkA = bytes('pk-a', 65);
    const a = pairingCode(nNew, nA, pkNew, pkA);
    const b = pairingCode(nNew, nA, pkNew, pkA);
    expect(a).toMatch(/^\d{8}$/);
    expect(a).toBe(b);
  });

  it('a swapped public key or nonce yields a different code', () => {
    const nNew = bytes('n-new', 16);
    const nA = bytes('n-a', 16);
    const pkNew = bytes('pk-new', 65);
    const pkA = bytes('pk-a', 65);
    const good = pairingCode(nNew, nA, pkNew, pkA);
    expect(pairingCode(nA, nNew, pkNew, pkA)).not.toBe(good);
    expect(pairingCode(nNew, nA, pkA, pkNew)).not.toBe(good);
  });

  it('expires after ten minutes, not before', () => {
    const t0 = 1_000_000;
    expect(pairingExpired(t0, t0 + PAIRING_TTL_MS)).toBe(false);
    expect(pairingExpired(t0, t0 + PAIRING_TTL_MS + 1)).toBe(true);
    expect(pairingExpired(t0, t0 - 1)).toBe(true);
  });
});
