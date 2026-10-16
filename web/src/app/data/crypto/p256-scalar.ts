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

import { unhex } from './bytes';

/**
 * The scalar arithmetic on P-256's order n that the recovery key and HPKE's DeriveKeyPair need; the twin of Kotlin's
 * `P256Scalar`: 16-bit limbs in plain numbers (exact in doubles), no branch or index that depends on a secret value.
 * No point arithmetic here: the public key always comes from WebCrypto.
 */
const LIMBS = 16;
/** The order n of the P-256 group, big-endian. */
export const P256_ORDER = unhex('ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551');
const ORDER_MINUS_1 = toLimbs(unhex('ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550'));

/** 32 big-endian bytes to 16 little-endian 16-bit limbs. */
function toLimbs(b: Uint8Array): number[] {
  const l: number[] = new Array(LIMBS);
  for (let i = 0; i < LIMBS; i++) l[i] = (b[30 - 2 * i] << 8) | b[31 - 2 * i];
  return l;
}

function fromLimbs(l: number[]): Uint8Array {
  const out = new Uint8Array(32);
  for (let i = 0; i < LIMBS; i++) {
    out[30 - 2 * i] = (l[i] >>> 8) & 0xff;
    out[31 - 2 * i] = l[i] & 0xff;
  }
  return out;
}

/** True when the 32-byte big-endian scalar is in [1, n − 1]. */
export function isValidScalar(scalar: Uint8Array): boolean {
  if (scalar.length !== 32) return false;
  const v = toLimbs(scalar);
  const n = toLimbs(P256_ORDER);
  let borrow = 0;
  let nonZero = 0;
  for (let i = 0; i < LIMBS; i++) {
    const d = v[i] - n[i] - borrow;
    borrow = (d >>> 31) & 1; // d is negative exactly when a borrow is due
    nonZero |= v[i];
  }
  const isNonZero = ((nonZero | -nonZero) >>> 31) & 1;
  return (borrow & isNonZero) === 1;
}

/**
 * FIPS 186-5 A.2.1: `d = (c mod (n − 1)) + 1` for a big-endian seed c of any length (docs/15 §9.4 uses 48 bytes).
 * Horner's rule over the bits with one constant-time conditional subtraction per bit.
 */
export function reduceToScalar(seed: Uint8Array): Uint8Array {
  const m = ORDER_MINUS_1;
  const r: number[] = new Array(LIMBS).fill(0);
  const t: number[] = new Array(LIMBS).fill(0);
  for (const byte of seed) {
    for (let bit = 7; bit >= 0; bit--) {
      let carryIn = (byte >>> bit) & 1;
      for (let i = 0; i < LIMBS; i++) {
        const limb = r[i];
        r[i] = ((limb << 1) | carryIn) & 0xffff;
        carryIn = limb >>> 15;
      }
      const carry = carryIn;
      let borrow = 0;
      for (let i = 0; i < LIMBS; i++) {
        const d = r[i] - m[i] - borrow;
        t[i] = d & 0xffff;
        borrow = (d >>> 31) & 1;
      }
      const take = carry | (1 - borrow);
      const mask = -take & 0xffff;
      for (let i = 0; i < LIMBS; i++) r[i] = (t[i] & mask) | (r[i] & ~mask & 0xffff);
    }
  }
  let carry = 1;
  for (let i = 0; i < LIMBS; i++) {
    const s = r[i] + carry;
    r[i] = s & 0xffff;
    carry = s >>> 16;
  }
  return fromLimbs(r);
}
