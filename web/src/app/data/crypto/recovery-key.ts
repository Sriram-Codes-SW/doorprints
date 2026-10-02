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

import { utf8 } from './bytes';
import type { AesKey, CryptoProvider, P256PrivateKey } from './crypto-provider';
import { CryptoError } from './crypto-provider';
import { Hkdf } from './hpke';
import { reduceToScalar } from './p256-scalar';

export type RecoveryKeyReason = 'WRONG_LENGTH' | 'INVALID_CHARACTER' | 'OUT_OF_RANGE' | 'CHECK_MISMATCH';

export class RecoveryKeyError extends CryptoError {
  constructor(
    readonly reason: RecoveryKeyReason,
    message: string,
  ) {
    super('INVALID_INPUT', message);
    this.name = 'RecoveryKeyError';
  }
}

const SIZE = 16;
const SYMBOLS = 27;
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const CHECK_ALPHABET = ALPHABET + '*~$=U';

/**
 * The recovery key (docs/15 §9.4), the twin of Kotlin's `RecoveryKey`: 128 bits as 26 Crockford base32 symbols (two
 * zero bits in front, so the first is 0..7) plus Crockford's mod-37 check symbol, shown in groups of four.
 */
export class RecoveryKey {
  readonly #raw: Uint8Array;

  private constructor(raw: Uint8Array) {
    this.#raw = raw;
  }

  static generate(p: CryptoProvider): RecoveryKey {
    return new RecoveryKey(p.randomBytes(SIZE));
  }

  static fromBytes(bytes: Uint8Array): RecoveryKey {
    if (bytes.length !== SIZE) throw new RangeError('a recovery key is 16 bytes');
    return new RecoveryKey(bytes.slice());
  }

  /** Forgiving of case, spaces, hyphens and Crockford's look-alikes (O as 0, I and L as 1); strict on the check. */
  static parse(text: string): RecoveryKey {
    let symbols = '';
    for (const c of text) if (!' -\t\n\r '.includes(c)) symbols += c;
    const chars = [...symbols];
    if (chars.length !== SYMBOLS) throw new RecoveryKeyError('WRONG_LENGTH', `expected ${SYMBOLS} symbols`);
    const values: number[] = [];
    chars.forEach((raw, i) => {
      let c = raw >= 'a' && raw <= 'z' ? raw.toUpperCase() : raw;
      if (c === 'O') c = '0';
      else if (c === 'I' || c === 'L') c = '1';
      const v = (i < SYMBOLS - 1 ? ALPHABET : CHECK_ALPHABET).indexOf(c);
      if (v < 0 || c.length !== 1) throw new RecoveryKeyError('INVALID_CHARACTER', `symbol ${i + 1}`);
      values.push(v);
    });
    if (values[0] > 7) throw new RecoveryKeyError('OUT_OF_RANGE', 'more than 128 bits');
    const data = values.slice(0, SYMBOLS - 1);
    if (checkOf(data) !== values[SYMBOLS - 1]) throw new RecoveryKeyError('CHECK_MISMATCH', 'check symbol');
    return new RecoveryKey(fromDigits(data));
  }

  get bytes(): Uint8Array {
    return this.#raw.slice();
  }

  get symbols(): string {
    const digits = digitsOf(this.#raw);
    return digits.map((d) => ALPHABET[d]).join('') + CHECK_ALPHABET[checkOf(digits)];
  }

  get display(): string {
    return this.symbols.match(/.{1,4}/g)!.join('-');
  }

  toString(): string {
    return 'RecoveryKey(…)';
  }

  /** @internal FIPS 186-5 A.2.1 over HKDF-SHA-256(ikm = key, salt "doorprints/dpx1/recovery", info "p256", 48). */
  async scalar(p: CryptoProvider): Promise<Uint8Array> {
    const seed = await new Hkdf(p).derive(utf8('doorprints/dpx1/recovery'), this.#raw, utf8('p256'), 48);
    const d = reduceToScalar(seed);
    seed.fill(0);
    return d;
  }

  async keyPair(p: CryptoProvider): Promise<P256PrivateKey> {
    const d = await this.scalar(p);
    try {
      return await p.p256FromScalar(d);
    } finally {
      d.fill(0);
    }
  }

  /** @internal The key of the recovery anchor: HKDF(ikm = the key bytes, salt "", info "doorprints/dpx1/recovery-anchor"). */
  async anchorKey(p: CryptoProvider): Promise<AesKey> {
    const k = await new Hkdf(p).derive(new Uint8Array(0), this.#raw, utf8('doorprints/dpx1/recovery-anchor'), 32);
    try {
      return await p.aesKey(k);
    } finally {
      k.fill(0);
    }
  }
}

function digitsOf(bytes: Uint8Array): number[] {
  const out: number[] = [];
  for (let k = 0; k < SYMBOLS - 1; k++) {
    let v = 0;
    for (let j = 0; j < 5; j++) {
      const bit = k * 5 + j - 2;
      const b = bit < 0 ? 0 : (bytes[bit >> 3] >>> (7 - (bit & 7))) & 1;
      v = (v << 1) | b;
    }
    out.push(v);
  }
  return out;
}

function fromDigits(digits: number[]): Uint8Array {
  const out = new Uint8Array(SIZE);
  digits.forEach((d, k) => {
    for (let j = 0; j < 5; j++) {
      const bit = k * 5 + j - 2;
      if (bit < 0) continue;
      if ((d >>> (4 - j)) & 1) out[bit >> 3] |= 1 << (7 - (bit & 7));
    }
  });
  return out;
}

function checkOf(digits: number[]): number {
  let c = 0;
  for (const d of digits) c = (c * 32 + d) % 37;
  return c;
}
