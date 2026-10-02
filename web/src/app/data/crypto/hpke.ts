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

import { concat, i2osp, utf8, xor } from './bytes';
import { CryptoError } from './crypto-provider';
import type { AesKey, CryptoProvider, P256PrivateKey } from './crypto-provider';
import { isValidScalar } from './p256-scalar';

const HASH_LEN = 32;

/** HKDF with SHA-256 (RFC 5869) over the provider's HMAC; the twin of Kotlin's `Hkdf`. */
export class Hkdf {
  constructor(private readonly p: CryptoProvider) {}

  /** An empty salt is HashLen zero bytes (RFC 5869 §2.2). */
  extract(salt: Uint8Array, ikm: Uint8Array): Promise<Uint8Array> {
    return this.p.hmacSha256(salt.length === 0 ? new Uint8Array(HASH_LEN) : salt, ikm);
  }

  async expand(prk: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
    if (!Number.isInteger(length) || length < 0 || length > 255 * HASH_LEN) throw new CryptoError('INVALID_INPUT', 'HKDF length');
    if (prk.length < HASH_LEN) throw new CryptoError('INVALID_INPUT', 'HKDF PRK too short');
    const out = new Uint8Array(length);
    let t: Uint8Array = new Uint8Array(0);
    let at = 0;
    for (let counter = 1; at < length; counter++) {
      t = await this.p.hmacSha256(prk, concat(t, info, new Uint8Array([counter])));
      const n = Math.min(HASH_LEN, length - at);
      out.set(t.subarray(0, n), at);
      at += n;
    }
    return out;
  }

  async derive(salt: Uint8Array, ikm: Uint8Array, info: Uint8Array, length: number): Promise<Uint8Array> {
    return this.expand(await this.extract(salt, ikm), info, length);
  }
}

export const KEM_ID = 0x0010;
export const KDF_ID = 0x0001;
export const MODE_BASE = 0x00;
export const MODE_PSK = 0x01;
export const MODE_AUTH = 0x02;
export const MODE_AUTH_PSK = 0x03;
const N_SECRET = 32;
const N_ENC = 65;
const N_SK = 32;
const N_N = 12;
const N_H = 32;
const HPKE_V1 = utf8('HPKE-v1');
const EMPTY = new Uint8Array(0);

export interface HpkeAead {
  readonly id: number;
  readonly keySize: number;
}
/** @internal For RFC 9180's A.3.1 test vector only; nothing in the app may pick it. */
export const AES_128_GCM: HpkeAead = { id: 0x0001, keySize: 16 };
export const AES_256_GCM: HpkeAead = { id: 0x0002, keySize: 32 };

/** @internal What the key schedule makes (RFC 9180 §5.1). */
export interface HpkeSchedule {
  key: Uint8Array;
  baseNonce: Uint8Array;
  exporterSecret: Uint8Array;
}

/** An encryption context; one per setup. */
export class HpkeContext {
  private seq = 0;

  constructor(
    private readonly p: CryptoProvider,
    private readonly key: AesKey,
    private readonly baseNonce: Uint8Array,
  ) {}

  private nonce(): Uint8Array {
    return xor(this.baseNonce, concat(new Uint8Array(4), i2osp(this.seq, 8)));
  }

  private increment(): void {
    if (this.seq >= Number.MAX_SAFE_INTEGER) throw new CryptoError('INVALID_INPUT', 'HPKE message limit');
    this.seq++;
  }

  async seal(aad: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
    const ct = await this.p.aesGcmSeal(this.key, this.nonce(), aad, plaintext);
    this.increment();
    return ct;
  }

  async open(aad: Uint8Array, ciphertext: Uint8Array): Promise<Uint8Array> {
    const pt = await this.p.aesGcmOpen(this.key, this.nonce(), aad, ciphertext);
    this.increment();
    return pt;
  }
}

/**
 * HPKE (RFC 9180) with DHKEM(P-256, HKDF-SHA256) and HKDF-SHA256 over a `CryptoProvider`, byte for byte the twin of
 * Kotlin's `Hpke`: base mode public, the key schedule already taking the mode, `psk` and `psk_id` for the PSK mode of
 * S4b-BL-126.
 */
export class Hpke {
  private readonly hkdf: Hkdf;
  private readonly kemSuiteId: Uint8Array;
  private readonly suiteId: Uint8Array;

  constructor(
    private readonly p: CryptoProvider,
    readonly aead: HpkeAead = AES_256_GCM,
  ) {
    this.hkdf = new Hkdf(p);
    this.kemSuiteId = concat(utf8('KEM'), i2osp(KEM_ID, 2));
    this.suiteId = concat(utf8('HPKE'), i2osp(KEM_ID, 2), i2osp(KDF_ID, 2), i2osp(aead.id, 2));
  }

  private labeledExtract(suite: Uint8Array, salt: Uint8Array, label: string, ikm: Uint8Array): Promise<Uint8Array> {
    return this.hkdf.extract(salt, concat(HPKE_V1, suite, utf8(label), ikm));
  }

  private labeledExpand(suite: Uint8Array, prk: Uint8Array, label: string, info: Uint8Array, length: number): Promise<Uint8Array> {
    return this.hkdf.expand(prk, concat(i2osp(length, 2), HPKE_V1, suite, utf8(label), info), length);
  }

  /** DeriveKeyPair (RFC 9180 §7.1.3). */
  async deriveKeyPair(ikm: Uint8Array): Promise<P256PrivateKey> {
    if (ikm.length < N_SK) throw new CryptoError('INVALID_INPUT', 'DeriveKeyPair ikm shorter than Nsk');
    const dkpPrk = await this.labeledExtract(this.kemSuiteId, EMPTY, 'dkp_prk', ikm);
    for (let counter = 0; counter < 256; counter++) {
      const candidate = await this.labeledExpand(this.kemSuiteId, dkpPrk, 'candidate', i2osp(counter, 1), N_SK);
      if (isValidScalar(candidate)) return this.p.p256FromScalar(candidate);
    }
    throw new CryptoError('INVALID_KEY', 'DeriveKeyPair found no scalar');
  }

  /**
   * GenerateKeyPair: the platform's own key generation (`p256Generate`), so a wrap never depends on importing a raw
   * scalar. The vectors inject their ephemeral key; `FakeRandomProvider` makes `p256Generate` a DeriveKeyPair.
   */
  generateKeyPair(): Promise<P256PrivateKey> {
    return this.p.p256Generate();
  }

  private async extractAndExpand(dh: Uint8Array, kemContext: Uint8Array): Promise<Uint8Array> {
    const eaePrk = await this.labeledExtract(this.kemSuiteId, EMPTY, 'eae_prk', dh);
    return this.labeledExpand(this.kemSuiteId, eaePrk, 'shared_secret', kemContext, N_SECRET);
  }

  /** @internal */
  async encap(pkR: Uint8Array, ephemeral: P256PrivateKey): Promise<{ sharedSecret: Uint8Array; enc: Uint8Array }> {
    const recipient = this.p.p256ValidatePublic(pkR);
    const dh = await this.p.p256Agree(ephemeral, recipient);
    const enc = ephemeral.publicKey;
    return { sharedSecret: await this.extractAndExpand(dh, concat(enc, recipient)), enc };
  }

  /** @internal */
  async decap(enc: Uint8Array, skR: P256PrivateKey): Promise<Uint8Array> {
    if (enc.length !== N_ENC) throw new CryptoError('INVALID_KEY', 'enc length');
    const pkE = this.p.p256ValidatePublic(enc);
    const dh = await this.p.p256Agree(skR, pkE);
    return this.extractAndExpand(dh, concat(pkE, skR.publicKey));
  }

  /** @internal KeySchedule (RFC 9180 §5.1) for any mode, VerifyPSKInputs included. */
  async keySchedule(mode: number, sharedSecret: Uint8Array, info: Uint8Array, psk: Uint8Array, pskId: Uint8Array): Promise<HpkeSchedule> {
    const gotPsk = psk.length > 0;
    const gotPskId = pskId.length > 0;
    if (gotPsk !== gotPskId) throw new CryptoError('INVALID_INPUT', 'inconsistent PSK inputs');
    if (gotPsk && (mode === MODE_BASE || mode === MODE_AUTH)) throw new CryptoError('INVALID_INPUT', 'PSK input provided when not needed');
    if (!gotPsk && (mode === MODE_PSK || mode === MODE_AUTH_PSK)) throw new CryptoError('INVALID_INPUT', 'missing required PSK input');
    if (gotPsk && psk.length < 32) throw new CryptoError('INVALID_INPUT', 'PSK shorter than 32 bytes');
    const pskIdHash = await this.labeledExtract(this.suiteId, EMPTY, 'psk_id_hash', pskId);
    const infoHash = await this.labeledExtract(this.suiteId, EMPTY, 'info_hash', info);
    const context = concat(new Uint8Array([mode]), pskIdHash, infoHash);
    const secret = await this.labeledExtract(this.suiteId, sharedSecret, 'secret', psk);
    return {
      key: await this.labeledExpand(this.suiteId, secret, 'key', context, this.aead.keySize),
      baseNonce: await this.labeledExpand(this.suiteId, secret, 'base_nonce', context, N_N),
      exporterSecret: await this.labeledExpand(this.suiteId, secret, 'exp', context, N_H),
    };
  }

  private async context(s: HpkeSchedule): Promise<HpkeContext> {
    return new HpkeContext(this.p, await this.p.aesKey(s.key), s.baseNonce);
  }

  /** SetupBaseS; `ephemeral` only for the vectors (default: a fresh one). */
  async setupBaseS(pkR: Uint8Array, info: Uint8Array, ephemeral?: P256PrivateKey): Promise<{ enc: Uint8Array; context: HpkeContext }> {
    const { sharedSecret, enc } = await this.encap(pkR, ephemeral ?? (await this.generateKeyPair()));
    return { enc, context: await this.context(await this.keySchedule(MODE_BASE, sharedSecret, info, EMPTY, EMPTY)) };
  }

  async setupBaseR(enc: Uint8Array, skR: P256PrivateKey, info: Uint8Array): Promise<HpkeContext> {
    return this.context(await this.keySchedule(MODE_BASE, await this.decap(enc, skR), info, EMPTY, EMPTY));
  }

  async seal(pkR: Uint8Array, info: Uint8Array, aad: Uint8Array, plaintext: Uint8Array): Promise<{ enc: Uint8Array; ciphertext: Uint8Array }> {
    const s = await this.setupBaseS(pkR, info);
    return { enc: s.enc, ciphertext: await s.context.seal(aad, plaintext) };
  }

  async open(enc: Uint8Array, skR: P256PrivateKey, info: Uint8Array, aad: Uint8Array, ciphertext: Uint8Array): Promise<Uint8Array> {
    return (await this.setupBaseR(enc, skR, info)).open(aad, ciphertext);
  }
}
