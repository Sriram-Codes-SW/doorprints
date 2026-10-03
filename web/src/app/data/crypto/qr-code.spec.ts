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
import { utf8 } from './bytes';
import { encodeQr, QR_GF_EXP8, qrSvg, qrVersionBits } from './qr-code';

describe('QR byte mode, ECC M, mask 0', () => {
  it('uses the GF(2^8) table and the published version-info words', () => {
    expect(QR_GF_EXP8).toBe(0x1d);
    expect(qrVersionBits(7)).toBe(0x07c94);
    expect(qrVersionBits(8)).toBe(0x085bc);
    expect(qrVersionBits(9)).toBe(0x09a99);
    expect(qrVersionBits(10)).toBe(0x0a4d3);
  });

  it('draws finders, the dark module and format bits for ECC M mask 0', () => {
    const m = encodeQr(utf8('A'));
    expect(m.length).toBe(21);
    expect(m[0][0]).toBe(true);
    expect(m[1][1]).toBe(false);
    expect(m[3][3]).toBe(true);
    expect(m[7][7]).toBe(false);
    expect(m[0][20]).toBe(true);
    expect(m[20][0]).toBe(true);
    expect(m[13][8]).toBe(true);
    expect(m[0][8]).toBe(false);
    expect(m[1][8]).toBe(true);
    expect(m[8][20]).toBe(false);
  });

  it('grows to version 8 for an enrolment-sized payload and emits an SVG', () => {
    const payload = new Uint8Array(134);
    payload[0] = 0x64;
    const m = encodeQr(payload);
    expect(m.length).toBe(21 + 4 * 7);
    expect(m[m.length - 8][8]).toBe(true);
    const svg = qrSvg(payload);
    expect(svg.startsWith('<svg ')).toBe(true);
    expect(svg).toContain('<rect ');
  });
});
