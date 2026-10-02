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
import { PAIRING_TTL_MS } from './pairing-code';
import { approverReply, codeOf, newcomerCommit, revealAndCode } from './pairing-flow';

function fill(n: number, v: number): Uint8Array {
  return new Uint8Array(n).fill(v);
}

describe('pairing-flow (commit-then-reveal, S4b-BL-126)', () => {
  const t0 = 5_000_000;
  const nNew = fill(16, 1);
  const nA = fill(16, 2);
  const pkNew = fill(65, 3);
  const pkA = fill(65, 4);

  it('both sides show the same 8-digit code after reveal', () => {
    const commit = newcomerCommit(pkNew, nNew, t0);
    const reply = approverReply(commit, pkA, nA, t0 + 1000);
    expect(reply.ok).toBe(true);
    if (!reply.ok || !reply.message) throw new Error('reply');
    const revealed = revealAndCode(reply.message, nNew, t0 + 2000);
    expect(revealed.ok).toBe(true);
    if (!revealed.ok || !revealed.message) throw new Error('reveal');
    const other = codeOf(revealed.message, t0 + 2000);
    expect(other).toEqual({ ok: true, code: revealed.code });
    expect(revealed.code).toMatch(/^\d{8}$/);
  });

  it('refuses a reveal that does not match the commit', () => {
    const commit = newcomerCommit(pkNew, nNew, t0);
    const reply = approverReply(commit, pkA, nA, t0);
    if (!reply.ok || !reply.message) throw new Error('reply');
    const otherNonce = fill(16, 9);
    expect(revealAndCode(reply.message, otherNonce, t0).ok).toBe(false);
  });

  it('refuses a request older than ten minutes', () => {
    const commit = newcomerCommit(pkNew, nNew, t0);
    expect(approverReply(commit, pkA, nA, t0 + PAIRING_TTL_MS + 1).ok).toBe(false);
  });
});
