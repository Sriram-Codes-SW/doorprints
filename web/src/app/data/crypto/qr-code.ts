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

/**
 * A small QR encoder for enrolment (S4b-BL-134): byte mode, error correction M, versions 1–10, mask 0.
 * No library. A browser without `BarcodeDetector` pastes the same text the code carries.
 */

const EXP = new Uint8Array(512);
const LOG = new Uint8Array(256);
{
  let x = 1;
  for (let i = 0; i < 255; i++) {
    EXP[i] = x;
    LOG[x] = i;
    x <<= 1;
    if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
}

/** EXP[8] of GF(2^8) with polynomial 0x11d. Tests pin this so the table cannot drift. */
export const QR_GF_EXP8 = EXP[8];

function mul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  return EXP[LOG[a] + LOG[b]];
}

/** Version information bits for versions 7–40 (ISO/IEC 18004). */
export function qrVersionBits(version: number): number {
  let rem = version;
  for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
  return ((version << 12) | (rem & 0xfff)) >>> 0;
}

interface EccSpec {
  ecc: number;
  groups: ReadonlyArray<readonly [number, number]>;
}

/** Error correction M, versions 1–10 (blocks: count, data codewords). */
const ECC: readonly EccSpec[] = [
  { ecc: 10, groups: [[1, 16]] },
  { ecc: 16, groups: [[1, 28]] },
  { ecc: 26, groups: [[1, 44]] },
  { ecc: 18, groups: [[2, 32]] },
  { ecc: 24, groups: [[2, 43]] },
  { ecc: 16, groups: [[4, 27]] },
  { ecc: 18, groups: [[4, 31]] },
  { ecc: 22, groups: [[2, 38], [2, 39]] },
  { ecc: 22, groups: [[3, 36], [2, 37]] },
  { ecc: 26, groups: [[4, 43], [1, 44]] },
];

const REMAINDER = [0, 7, 7, 7, 7, 7, 0, 0, 0, 0];

const ALIGN: readonly (readonly number[])[] = [
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50],
];

const divisors = new Map<number, Uint8Array>();

function rsDivisor(degree: number): Uint8Array {
  const cached = divisors.get(degree);
  if (cached) return cached;
  const result = new Uint8Array(degree);
  result[degree - 1] = 1;
  let root = 1;
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < result.length; j++) {
      result[j] = mul(result[j], root);
      if (j + 1 < result.length) result[j] ^= result[j + 1];
    }
    root = mul(root, 2);
  }
  divisors.set(degree, result);
  return result;
}

function rsRemainder(data: Uint8Array, divisor: Uint8Array): Uint8Array {
  const result = new Uint8Array(divisor.length);
  for (const b of data) {
    const factor = b ^ result[0];
    result.copyWithin(0, 1);
    result[result.length - 1] = 0;
    for (let i = 0; i < result.length; i++) result[i] ^= mul(divisor[i], factor);
  }
  return result;
}

function dataCodewords(version: number): number {
  let n = 0;
  for (const [count, len] of ECC[version - 1].groups) n += count * len;
  return n;
}

function byteCapacity(version: number): number {
  const countBits = version <= 9 ? 8 : 16;
  return Math.floor((dataCodewords(version) * 8 - 4 - countBits) / 8);
}

function chooseVersion(length: number): number {
  for (let v = 1; v <= 10; v++) if (byteCapacity(v) >= length) return v;
  throw new RangeError('qr payload is longer than version 10');
}

function packData(version: number, bytes: Uint8Array): Uint8Array {
  const countBits = version <= 9 ? 8 : 16;
  const capacity = dataCodewords(version);
  const bits: number[] = [];
  const push = (value: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) bits.push((value >>> i) & 1);
  };
  push(0b0100, 4);
  push(bytes.length, countBits);
  for (const b of bytes) push(b, 8);
  const limit = capacity * 8;
  const term = Math.min(4, limit - bits.length);
  for (let i = 0; i < term; i++) bits.push(0);
  while (bits.length % 8 !== 0) bits.push(0);
  const out = new Uint8Array(capacity);
  for (let i = 0; i < bits.length / 8; i++) {
    let v = 0;
    for (let b = 0; b < 8; b++) v = (v << 1) | bits[i * 8 + b];
    out[i] = v;
  }
  for (let i = bits.length / 8, pad = 0; i < capacity; i++, pad++) out[i] = pad % 2 === 0 ? 0xec : 0x11;
  return out;
}

function interleave(version: number, data: Uint8Array): Uint8Array {
  const spec = ECC[version - 1];
  const divisor = rsDivisor(spec.ecc);
  const blocks: Uint8Array[] = [];
  const eccs: Uint8Array[] = [];
  let offset = 0;
  for (const [count, len] of spec.groups) {
    for (let i = 0; i < count; i++) {
      const block = data.subarray(offset, offset + len);
      offset += len;
      const ecc = rsRemainder(block, divisor);
      if (!syndromeClear(block, ecc)) throw new Error('qr ecc');
      blocks.push(block);
      eccs.push(ecc);
    }
  }
  const out: number[] = [];
  const max = Math.max(...blocks.map((b) => b.length));
  for (let i = 0; i < max; i++) for (const b of blocks) if (i < b.length) out.push(b[i]);
  for (let i = 0; i < spec.ecc; i++) for (const e of eccs) out.push(e[i]);
  return Uint8Array.from(out);
}

function syndromeClear(data: Uint8Array, ecc: Uint8Array): boolean {
  const cw = new Uint8Array(data.length + ecc.length);
  cw.set(data);
  cw.set(ecc, data.length);
  for (let i = 0; i < ecc.length; i++) {
    let s = 0;
    const a = EXP[i];
    for (const c of cw) s = mul(s, a) ^ c;
    if (s !== 0) return false;
  }
  return true;
}

function draw(version: number, codewords: Uint8Array): boolean[][] {
  const size = 21 + 4 * (version - 1);
  const dark: boolean[][] = Array.from({ length: size }, () => Array<boolean>(size).fill(false));
  const fn: boolean[][] = Array.from({ length: size }, () => Array<boolean>(size).fill(false));
  const set = (x: number, y: number, on: boolean) => {
    dark[y][x] = on;
    fn[y][x] = true;
  };
  const finder = (ox: number, oy: number) => {
    for (let dy = -1; dy <= 7; dy++) {
      for (let dx = -1; dx <= 7; dx++) {
        const x = ox + dx;
        const y = oy + dy;
        if (x < 0 || y < 0 || x >= size || y >= size) continue;
        const inBox = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6;
        const border = dx === 0 || dx === 6 || dy === 0 || dy === 6;
        const core = dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4;
        set(x, y, inBox && (border || core));
      }
    }
  };
  finder(0, 0);
  finder(size - 7, 0);
  finder(0, size - 7);
  for (let i = 0; i < size; i++) {
    if (!fn[6][i]) set(i, 6, i % 2 === 0);
    if (!fn[i][6]) set(6, i, i % 2 === 0);
  }
  const positions = ALIGN[version - 1];
  const last = positions[positions.length - 1];
  for (const cy of positions) {
    for (const cx of positions) {
      if ((cx === 6 && cy === 6) || (cx === 6 && cy === last) || (cy === 6 && cx === last)) continue;
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
      }
    }
  }
  const reserve = (x: number, y: number) => {
    fn[y][x] = true;
  };
  for (let i = 0; i <= 5; i++) reserve(8, i);
  reserve(8, 7);
  reserve(8, 8);
  reserve(7, 8);
  for (let i = 9; i < 15; i++) reserve(14 - i, 8);
  for (let i = 0; i < 8; i++) reserve(size - 1 - i, 8);
  for (let i = 8; i < 15; i++) reserve(8, size - 15 + i);
  reserve(8, size - 8);
  if (version >= 7) {
    for (let i = 0; i < 18; i++) {
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      reserve(a, b);
      reserve(b, a);
    }
  }
  const totalBits = codewords.length * 8 + REMAINDER[version - 1];
  let bit = 0;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        const upward = ((right + 1) & 2) === 0;
        const y = upward ? size - 1 - vert : vert;
        if (fn[y][x] || bit >= totalBits) continue;
        const byte = bit >>> 3;
        const on = byte < codewords.length ? ((codewords[byte] >>> (7 - (bit & 7))) & 1) === 1 : false;
        dark[y][x] = (x + y) % 2 === 0 ? !on : on;
        bit++;
      }
    }
  }
  if (bit !== totalBits) throw new Error('qr placement');
  const format = 0x5412;
  const bitAt = (i: number) => ((format >>> i) & 1) === 1;
  for (let i = 0; i <= 5; i++) set(8, i, bitAt(i));
  set(8, 7, bitAt(6));
  set(8, 8, bitAt(7));
  set(7, 8, bitAt(8));
  for (let i = 9; i < 15; i++) set(14 - i, 8, bitAt(i));
  for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bitAt(i));
  for (let i = 8; i < 15; i++) set(8, size - 15 + i, bitAt(i));
  set(8, size - 8, true);
  if (version >= 7) {
    const info = qrVersionBits(version);
    for (let i = 0; i < 18; i++) {
      const on = ((info >>> i) & 1) === 1;
      const a = size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      set(a, b, on);
      set(b, a, on);
    }
  }
  return dark;
}

/** Dark modules of a QR symbol for `bytes` (byte mode, ECC M, mask 0). */
export function encodeQr(bytes: Uint8Array): boolean[][] {
  const version = chooseVersion(bytes.length);
  return draw(version, interleave(version, packData(version, bytes)));
}

/** The same symbol as an SVG, with a four-module quiet zone. */
export function qrSvg(bytes: Uint8Array): string {
  const modules = encodeQr(bytes);
  const n = modules.length;
  const quiet = 4;
  const size = n + quiet * 2;
  let rects = '';
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (modules[y][x]) rects += `<rect x="${x + quiet}" y="${y + quiet}" width="1" height="1"/>`;
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges">${rects}</svg>`;
}
