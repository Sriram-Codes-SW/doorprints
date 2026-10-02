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
import vectorsJson from '../../../../../docs/schemas/hpke-vectors.json';
import { hex, unhex } from './bytes';
import { CryptoError, WebCryptoProvider } from './crypto-provider';
import type { CryptoErrorKind } from './crypto-provider';
import { AES_128_GCM, AES_256_GCM, Hkdf, Hpke, KDF_ID, KEM_ID, MODE_BASE, MODE_PSK } from './hpke';
import { P256_ORDER } from './p256-scalar';

/**
 * The primitives and HPKE against `docs/schemas/hpke-vectors.json` (TC-U-125, TC-U-126), the same cases as Kotlin's
 * `PrimitivesTest` and `HpkeVectorsTest`: RFC 4231, RFC 5869, the GCM spec, RFC 5903, P-256 multiples, RFC 9180
 * A.3.1 (official), and the AES-256-GCM regression vectors.
 */
type Case = Record<string, string | number>;
interface HpkeCase extends Case {
  mode: number;
  kem_id: number;
  kdf_id: number;
  aead_id: number;
}
const vectors = vectorsJson as unknown as {
  primitives: Record<string, Case[]>;
  hpke: { official: (HpkeCase & { encryptions: Case[] })[]; regression: (HpkeCase & { encryptions: Case[] })[] };
};
const p = new WebCryptoProvider();
const h = (c: Case, k: string) => unhex(String(c[k]));

async function expectKind(kind: CryptoErrorKind, f: () => Promise<unknown> | unknown): Promise<void> {
  try {
    await f();
  } catch (e) {
    expect(e).toBeInstanceOf(CryptoError);
    expect((e as CryptoError).kind).toBe(kind);
    return;
  }
  throw new Error(`expected ${kind}`);
}

describe('crypto primitives (WebCrypto) against published vectors', () => {
  it('HMAC-SHA-256 matches RFC 4231', async () => {
    for (const c of vectors.primitives['hmacSha256']) expect(hex(await p.hmacSha256(h(c, 'key'), h(c, 'data')))).toBe(c['mac']);
  });

  it('HKDF matches RFC 5869', async () => {
    const hkdf = new Hkdf(p);
    for (const c of vectors.primitives['hkdfSha256']) {
      const prk = await hkdf.extract(h(c, 'salt'), h(c, 'ikm'));
      expect(hex(prk)).toBe(c['prk']);
      expect(hex(await hkdf.expand(prk, h(c, 'info'), Number(c['length'])))).toBe(c['okm']);
    }
  });

  it('AES-GCM matches the GCM spec and fails closed', async () => {
    for (const c of vectors.primitives['aesGcm']) {
      const key = await p.aesKey(h(c, 'key'));
      const sealed = await p.aesGcmSeal(key, h(c, 'nonce'), h(c, 'aad'), h(c, 'pt'));
      expect(hex(sealed)).toBe(String(c['ct']) + String(c['tag']));
      expect(hex(await p.aesGcmOpen(key, h(c, 'nonce'), h(c, 'aad'), sealed))).toBe(c['pt']);
      for (let i = 0; i < sealed.length; i += 7) {
        const bad = sealed.slice();
        bad[i] ^= 1;
        await expectKind('AUTH_FAILED', () => p.aesGcmOpen(key, h(c, 'nonce'), h(c, 'aad'), bad));
      }
      await expectKind('AUTH_FAILED', () => p.aesGcmOpen(key, h(c, 'nonce'), new Uint8Array([1]), sealed));
      await expectKind('AUTH_FAILED', () => p.aesGcmOpen(key, h(c, 'nonce'), h(c, 'aad'), new Uint8Array(15)));
    }
    await expectKind('INVALID_INPUT', async () => p.aesGcmSeal(await p.aesKey(new Uint8Array(32)), new Uint8Array(16), new Uint8Array(0), new Uint8Array(0)));
    await expectKind('INVALID_KEY', () => p.aesKey(new Uint8Array(24)));
  });

  it('ECDH and the scalar-to-public-key path match RFC 5903 and the P-256 multiples', async () => {
    for (const c of vectors.primitives['ecdhP256']) {
      const i = await p.p256FromScalar(h(c, 'i'));
      const r = await p.p256FromScalar(h(c, 'r'));
      expect(hex(i.publicKey)).toBe(c['gi']);
      expect(hex(r.publicKey)).toBe(c['gr']);
      expect(hex(await p.p256Agree(i, r.publicKey))).toBe(c['girx']);
      expect(hex(await p.p256Agree(r, i.publicKey))).toBe(c['girx']);
    }
    for (const c of vectors.primitives['p256ScalarMult']) expect(hex((await p.p256FromScalar(h(c, 'k'))).publicKey)).toBe(c['point']);
  });

  it('refuses scalars out of range and invalid points', async () => {
    for (const bad of [new Uint8Array(32), P256_ORDER, new Uint8Array(32).fill(255), new Uint8Array(31)]) {
      await expectKind('INVALID_KEY', () => p.p256FromScalar(bad));
    }
    const good = (await p.p256Generate()).publicKey;
    const pHex = unhex('ffffffff00000001000000000000000000000000ffffffffffffffffffffffff');
    const offCurve = good.slice();
    offCurve[64] ^= 1;
    const compressed = good.slice(0, 33);
    compressed[0] = 2;
    const xp = good.slice();
    xp.set(pHex, 1);
    const yp = good.slice();
    yp.set(pHex, 33);
    for (const bad of [new Uint8Array(65), new Uint8Array([0]), good.slice(0, 64), compressed, offCurve, xp, yp]) {
      await expectKind('INVALID_KEY', () => p.p256ValidatePublic(bad));
      await expectKind('INVALID_KEY', async () => p.p256Agree(await p.p256Generate(), bad));
    }
  });

  it('a scalar key pair has the right sign of y (HPKE opens to it)', async () => {
    for (let n = 0; n < 5; n++) {
      const k = await p.p256FromScalar(p.randomBytes(32).map((b, i) => (i === 0 ? b & 0x7f : b)));
      const sealed = await new Hpke(p).seal(k.publicKey, new Uint8Array(0), new Uint8Array(0), new Uint8Array([1, 2, 3]));
      expect([...(await new Hpke(p).open(sealed.enc, k, new Uint8Array(0), new Uint8Array(0), sealed.ciphertext))]).toEqual([1, 2, 3]);
    }
  });
});

async function runHpke(v: HpkeCase & { encryptions: Case[] }): Promise<void> {
  expect(v.mode).toBe(0);
  expect(v.kem_id).toBe(KEM_ID);
  expect(v.kdf_id).toBe(KDF_ID);
  const hpke = new Hpke(p, v.aead_id === 1 ? AES_128_GCM : AES_256_GCM);
  const skE = await hpke.deriveKeyPair(h(v, 'ikmE'));
  const skR = await hpke.deriveKeyPair(h(v, 'ikmR'));
  expect(hex(skE.publicKey)).toBe(v['pkEm']);
  expect(hex(skR.publicKey)).toBe(v['pkRm']);
  if (v['skEm']) expect(hex((await p.p256FromScalar(h(v, 'skEm'))).publicKey)).toBe(v['pkEm']);
  if (v['skRm']) expect(hex((await p.p256FromScalar(h(v, 'skRm'))).publicKey)).toBe(v['pkRm']);
  const { sharedSecret, enc } = await hpke.encap(skR.publicKey, skE);
  expect(hex(enc)).toBe(v['enc']);
  expect(hex(sharedSecret)).toBe(v['shared_secret']);
  expect(hex(await hpke.decap(enc, skR))).toBe(v['shared_secret']);
  const s = await hpke.keySchedule(MODE_BASE, sharedSecret, h(v, 'info'), new Uint8Array(0), new Uint8Array(0));
  expect(hex(s.key)).toBe(v['key']);
  expect(hex(s.baseNonce)).toBe(v['base_nonce']);
  expect(hex(s.exporterSecret)).toBe(v['exporter_secret']);
  const sender = await hpke.setupBaseS(skR.publicKey, h(v, 'info'), skE);
  const receiver = await hpke.setupBaseR(enc, skR, h(v, 'info'));
  let seq = 0;
  for (const e of v.encryptions) {
    while (seq < Number(e['seq'])) {
      await receiver.open(new Uint8Array(0), await sender.context.seal(new Uint8Array(0), new Uint8Array(0)));
      seq++;
    }
    const ct = await sender.context.seal(h(e, 'aad'), h(e, 'pt'));
    expect(hex(ct)).toBe(e['ct']);
    expect(hex(await receiver.open(h(e, 'aad'), ct))).toBe(e['pt']);
    seq++;
  }
}

describe('HPKE (RFC 9180)', () => {
  it('RFC 9180 Appendix A.3.1 (official)', async () => {
    expect(vectors.hpke.official.length).toBeGreaterThan(0);
    for (const v of vectors.hpke.official) await runHpke(v);
  });

  it('the AES-256-GCM regression vectors', async () => {
    expect(vectors.hpke.regression.length).toBeGreaterThan(0);
    for (const v of vectors.hpke.regression) await runHpke(v);
  });

  it('open fails closed', async () => {
    const hpke = new Hpke(p);
    const r = await p.p256Generate();
    const other = await p.p256Generate();
    const info = new TextEncoder().encode('doorprints/dpx1/wrap');
    const aad = new Uint8Array([9]);
    const s = await hpke.seal(r.publicKey, info, aad, new Uint8Array(32).fill(3));
    expect(hex(await hpke.open(s.enc, r, info, aad, s.ciphertext))).toBe('03'.repeat(32));
    await expectKind('AUTH_FAILED', () => hpke.open(s.enc, other, info, aad, s.ciphertext));
    await expectKind('AUTH_FAILED', () => hpke.open(s.enc, r, new Uint8Array([1]), aad, s.ciphertext));
    await expectKind('AUTH_FAILED', () => hpke.open(s.enc, r, info, new Uint8Array([8]), s.ciphertext));
    await expectKind('AUTH_FAILED', () => hpke.open(other.publicKey, r, info, aad, s.ciphertext));
    const bad = s.ciphertext.slice();
    bad[5] ^= 0x80;
    await expectKind('AUTH_FAILED', () => hpke.open(s.enc, r, info, aad, bad));
    const offCurve = s.enc.slice();
    offCurve[64] ^= 1;
    await expectKind('INVALID_KEY', () => hpke.open(offCurve, r, info, aad, s.ciphertext));
    await expectKind('INVALID_KEY', () => hpke.open(s.enc.slice(0, 33), r, info, aad, s.ciphertext));
    const again = await hpke.seal(r.publicKey, info, aad, new Uint8Array(32).fill(3));
    expect(hex(again.enc)).not.toBe(hex(s.enc));
  });

  it('the key schedule checks the PSK inputs', async () => {
    const hpke = new Hpke(p);
    const ss = new Uint8Array(32);
    const e = new Uint8Array(0);
    await expectKind('INVALID_INPUT', () => hpke.keySchedule(MODE_BASE, ss, e, new Uint8Array(32), new Uint8Array(1)));
    await expectKind('INVALID_INPUT', () => hpke.keySchedule(MODE_PSK, ss, e, e, e));
    await expectKind('INVALID_INPUT', () => hpke.keySchedule(MODE_PSK, ss, e, new Uint8Array(16), new Uint8Array(1)));
  });
});
