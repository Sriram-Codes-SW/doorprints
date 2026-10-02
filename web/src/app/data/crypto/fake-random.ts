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

import { concat, u32, utf8 } from './bytes';
import { sha256Of } from './crypto-provider';
import type { AesKey, CryptoProvider, P256PrivateKey } from './crypto-provider';
import { Hpke } from './hpke';

/**
 * Test hook, never used by the app: a real provider whose random generator is a deterministic stream (block i =
 * SHA-256("doorprints-fake-random" ‖ seed ‖ u32 i)), the twin of Kotlin's `FakeRandomProvider`, so a `keys.json`
 * or a `dpx/1` file can be a byte-exact vector on both stacks.
 */
export class FakeRandomProvider implements CryptoProvider {
  private block = 0;
  private buffer: Uint8Array = new Uint8Array(0);
  private at = 0;
  private readonly seed: Uint8Array;

  constructor(
    private readonly real: CryptoProvider,
    seed: string,
  ) {
    this.seed = utf8(seed);
  }

  randomBytes(size: number): Uint8Array {
    const out = new Uint8Array(size);
    for (let i = 0; i < size; i++) {
      if (this.at === this.buffer.length) {
        this.buffer = sha256Of(this.real, concat(utf8('doorprints-fake-random'), this.seed, u32(this.block++)));
        this.at = 0;
      }
      out[i] = this.buffer[this.at++];
    }
    return out;
  }

  sha256() {
    return this.real.sha256();
  }
  hmacSha256(key: Uint8Array, data: Uint8Array) {
    return this.real.hmacSha256(key, data);
  }
  aesKey(raw: Uint8Array) {
    return this.real.aesKey(raw);
  }
  aesGcmSeal(key: AesKey, nonce: Uint8Array, aad: Uint8Array, pt: Uint8Array) {
    return this.real.aesGcmSeal(key, nonce, aad, pt);
  }
  aesGcmOpen(key: AesKey, nonce: Uint8Array, aad: Uint8Array, ct: Uint8Array) {
    return this.real.aesGcmOpen(key, nonce, aad, ct);
  }
  p256Generate(): Promise<P256PrivateKey> {
    return new Hpke(this).deriveKeyPair(this.randomBytes(32));
  }
  p256FromScalar(scalar: Uint8Array) {
    return this.real.p256FromScalar(scalar);
  }
  p256ValidatePublic(encoded: Uint8Array) {
    return this.real.p256ValidatePublic(encoded);
  }
  p256Agree(k: P256PrivateKey, peer: Uint8Array) {
    return this.real.p256Agree(k, peer);
  }
  p256FromStoredKey(privateKey: CryptoKey, publicKeyRaw: Uint8Array) {
    return this.real.p256FromStoredKey(privateKey, publicKeyRaw);
  }
}

/** Byte i = (31 · i + 7) mod 256, the vectors' plaintext. */
export function patternBytes(size: number): Uint8Array {
  const out = new Uint8Array(size);
  for (let i = 0; i < size; i++) out[i] = (31 * i + 7) & 0xff;
  return out;
}
