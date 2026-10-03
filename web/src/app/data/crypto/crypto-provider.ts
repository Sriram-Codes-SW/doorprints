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

import { Sha256 } from '../../export/sha256';
import { ab, concat, constantTimeEquals, unhex } from './bytes';
import { isValidScalar } from './p256-scalar';

/**
 * The cryptographic primitives the Drive encryption needs (docs/15 §9.2, S4b-BL-125), the twin of Kotlin's
 * `CryptoProvider`: HKDF, HPKE, the `dpx/1` envelope and `keys.json` are written over this interface, and
 * `WebCryptoProvider` implements it on WebCrypto (`crypto.subtle`). Every failure is a `CryptoError`; nothing
 * returns a partial or unauthenticated result.
 */
export interface CryptoProvider {
  randomBytes(size: number): Uint8Array;
  /** An incremental SHA-256 (for public values: file checksums and kids). */
  sha256(): Sha256;
  hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array>;
  /** 16 bytes (RFC 9180's AES-128 vectors only) or 32. */
  aesKey(raw: Uint8Array): Promise<AesKey>;
  /** An AES-GCM `CryptoKey` already derived non-extractable (S4b-BL-132). */
  adoptAes(key: CryptoKey): AesKey;
  /** AES-GCM, 96-bit nonce chosen by the caller (never reused under a key), 128-bit tag appended. */
  aesGcmSeal(key: AesKey, nonce: Uint8Array, aad: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array>;
  /** Fails closed: `CryptoError('AUTH_FAILED')` and no plaintext for a wrong key, nonce, AAD or byte. */
  aesGcmOpen(key: AesKey, nonce: Uint8Array, aad: Uint8Array, sealed: Uint8Array): Promise<Uint8Array>;
  p256Generate(): Promise<P256PrivateKey>;
  /**
   * The key pair of a 32-byte scalar in [1, n − 1]. Chromium reads the public key from a JWK export.
   * Firefox imports the same key but will not export that JWK, so the public point comes from two ECDH operations.
   */
  p256FromScalar(scalar: Uint8Array): Promise<P256PrivateKey>;
  /** An uncompressed SEC1 point (65 bytes), coordinates below p, on the curve; anything else `INVALID_KEY`. */
  p256ValidatePublic(encoded: Uint8Array): Uint8Array;
  /** ECDH: the 32-byte x-coordinate; the peer key is validated first. */
  p256Agree(privateKey: P256PrivateKey, peerPublic: Uint8Array): Promise<Uint8Array>;
  /** Wrap a stored P-256 private key (from IndexedDB), validating it before use. */
  p256FromStoredKey(privateKey: CryptoKey, publicKeyRaw: Uint8Array): Promise<P256PrivateKey>;
}

export interface AesKey {
  readonly sizeBytes: number;
}

export interface P256PrivateKey {
  /** The uncompressed SEC1 encoding (65 bytes); a copy. */
  readonly publicKey: Uint8Array;
}

export type CryptoErrorKind = 'UNAVAILABLE' | 'AUTH_FAILED' | 'INVALID_KEY' | 'INVALID_INPUT';

/** The message never holds key or plaintext bytes. */
export class CryptoError extends Error {
  constructor(
    readonly kind: CryptoErrorKind,
    message: string,
  ) {
    super(`crypto ${kind}: ${message}`);
    this.name = 'CryptoError';
  }
}

/** One-shot SHA-256. */
export function sha256Of(p: CryptoProvider, ...parts: Uint8Array[]): Uint8Array {
  const h = p.sha256();
  for (const part of parts) h.update(part);
  return h.digest();
}

const P = 0xffffffff00000001000000000000000000000000ffffffffffffffffffffffffn;
const B = 0x5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604bn;
const EC = { name: 'ECDH', namedCurve: 'P-256' } as const;

function toBig(bytes: Uint8Array): bigint {
  let v = 0n;
  for (const b of bytes) v = (v << 8n) | BigInt(b);
  return v;
}

function mod(a: bigint): bigint {
  const r = a % P;
  return r < 0n ? r + P : r;
}

function modPow(base: bigint, exp: bigint, m: bigint): bigint {
  let result = 1n;
  let b = mod(base);
  let e = exp;
  while (e > 0n) {
    if (e & 1n) result = (result * b) % m;
    b = (b * b) % m;
    e >>= 1n;
  }
  return result;
}

/** Uncompressed generator, SEC 2 / FIPS 186-5. Public. */
const G_POINT = unhex(
  '046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5',
);
const SCALAR_ONE = (() => {
  const s = new Uint8Array(32);
  s[31] = 1;
  return s;
})();
/** n − 1. Public. */
const SCALAR_NM1 = unhex('ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632550');

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a[i] ^ b[i];
  return d === 0;
}

function be32(n: bigint): Uint8Array {
  const out = new Uint8Array(32);
  let v = n;
  for (let i = 31; i >= 0; i--) {
    out[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return out;
}

function uncompressed(x: bigint, y: bigint): Uint8Array {
  const out = new Uint8Array(65);
  out[0] = 4;
  out.set(be32(x), 1);
  out.set(be32(y), 33);
  return out;
}

function toB64url(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function scalarPlusOne(scalar: Uint8Array): Uint8Array {
  const out = new Uint8Array(scalar);
  for (let i = 31; i >= 0; i--) {
    const s = out[i] + 1;
    out[i] = s & 0xff;
    if (s < 256) break;
  }
  return out;
}

/** x(P + Q) for two distinct affine points with different x. Public values only. */
function addX(x1: bigint, y1: bigint, x2: bigint, y2: bigint): bigint {
  const dx = mod(x2 - x1);
  const dy = mod(y2 - y1);
  const lambda = mod(dy * modPow(dx, P - 2n, P));
  return mod(lambda * lambda - x1 - x2);
}

/** RFC 5915 ECPrivateKey inside a PKCS #8 PrivateKeyInfo for P-256, without the optional public key. */
const PKCS8_PREFIX = new Uint8Array([
  0x30, 0x41, 0x02, 0x01, 0x00, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
  0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x04, 0x27, 0x30, 0x25, 0x02, 0x01, 0x01, 0x04, 0x20,
]);

function fromB64url(s: string): Uint8Array {
  const pad = s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4);
  const bin = atob(pad);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

class WebAesKey implements AesKey {
  constructor(
    readonly key: CryptoKey,
    readonly sizeBytes: number,
  ) {}
}

class WebP256Key implements P256PrivateKey {
  constructor(
    readonly key: CryptoKey,
    private readonly pub: Uint8Array,
  ) {}
  get publicKey(): Uint8Array {
    return this.pub.slice();
  }
  toString(): string {
    return 'P256PrivateKey';
  }
}

/**
 * The website's provider, on WebCrypto only. Keys are non-extractable `CryptoKey`s wherever the API allows:
 * AES and HMAC keys always; a generated P-256 private key always (its public half is exported raw, as WebCrypto
 * always allows). The one exception is `p256FromScalar`: WebCrypto has no call that gives the public key of a raw
 * scalar. Chromium and Node import an extractable PKCS #8 key (RFC 5915 without the public key; the platform
 * computes it) only to export its JWK `x` and `y`, then import again, non-extractable. Firefox 132 and later
 * imports that PKCS #8 key and can run ECDH with it, but `exportKey('jwk')` fails (Mozilla bug 2000795). The
 * public point is then x(d·G) and x((d+1)·G) from the platform's own ECDH, and the sign of y from one addition
 * of those public points — the same method as Android — and the scalar is imported again as a non-extractable
 * JWK that already carries `x` and `y`. A browser that cannot import the PKCS #8 key at all still fails closed.
 * The scalar itself was already in memory (the recovery key or HPKE's DeriveKeyPair made it), so this exposes
 * nothing new.
 */
export class WebCryptoProvider implements CryptoProvider {
  private readonly subtle: SubtleCrypto;

  constructor(private readonly c: Crypto = globalThis.crypto) {
    if (!c?.subtle) throw new CryptoError('UNAVAILABLE', 'WebCrypto needs a secure context');
    this.subtle = c.subtle;
  }

  randomBytes(size: number): Uint8Array {
    if (!Number.isInteger(size) || size < 0) throw new CryptoError('INVALID_INPUT', 'size');
    const out = new Uint8Array(size);
    for (let i = 0; i < size; i += 65536) this.c.getRandomValues(out.subarray(i, Math.min(size, i + 65536)));
    return out;
  }

  sha256(): Sha256 {
    return new Sha256();
  }

  async hmacSha256(key: Uint8Array, data: Uint8Array): Promise<Uint8Array> {
    if (key.length === 0) throw new CryptoError('INVALID_INPUT', 'empty HMAC key');
    const k = await this.subtle.importKey('raw', ab(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return new Uint8Array(await this.subtle.sign('HMAC', k, ab(data)));
  }

  async aesKey(raw: Uint8Array): Promise<AesKey> {
    if (raw.length !== 16 && raw.length !== 32) throw new CryptoError('INVALID_KEY', 'AES key length');
    const k = await this.subtle.importKey('raw', ab(raw), { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
    return new WebAesKey(k, raw.length);
  }

  adoptAes(key: CryptoKey): AesKey {
    const algo = key.algorithm as AesKeyAlgorithm;
    if (key.type !== 'secret' || key.extractable || algo.name !== 'AES-GCM' || (algo.length !== 128 && algo.length !== 256)) {
      throw new CryptoError('INVALID_KEY', 'derived AES key');
    }
    if (!key.usages.includes('encrypt') || !key.usages.includes('decrypt')) throw new CryptoError('INVALID_KEY', 'derived AES key');
    return new WebAesKey(key, algo.length / 8);
  }

  private gcm(key: AesKey, nonce: Uint8Array, aad: Uint8Array): { k: CryptoKey; params: AesGcmParams } {
    if (nonce.length !== 12) throw new CryptoError('INVALID_INPUT', 'nonce length');
    if (!(key instanceof WebAesKey)) throw new CryptoError('INVALID_KEY', 'foreign key');
    return { k: key.key, params: { name: 'AES-GCM', iv: ab(nonce), additionalData: ab(aad), tagLength: 128 } };
  }

  async aesGcmSeal(key: AesKey, nonce: Uint8Array, aad: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
    const { k, params } = this.gcm(key, nonce, aad);
    return new Uint8Array(await this.subtle.encrypt(params, k, ab(plaintext)));
  }

  async aesGcmOpen(key: AesKey, nonce: Uint8Array, aad: Uint8Array, sealed: Uint8Array): Promise<Uint8Array> {
    if (sealed.length < 16) throw new CryptoError('AUTH_FAILED', 'shorter than a tag');
    const { k, params } = this.gcm(key, nonce, aad);
    try {
      return new Uint8Array(await this.subtle.decrypt(params, k, ab(sealed)));
    } catch {
      throw new CryptoError('AUTH_FAILED', 'tag mismatch');
    }
  }

  async p256Generate(): Promise<P256PrivateKey> {
    const pair = (await this.subtle.generateKey(EC, false, ['deriveBits'])) as CryptoKeyPair;
    const pub = new Uint8Array(await this.subtle.exportKey('raw', pair.publicKey));
    return new WebP256Key(pair.privateKey, this.p256ValidatePublic(pub));
  }

  async p256FromScalar(scalar: Uint8Array): Promise<P256PrivateKey> {
    if (!isValidScalar(scalar)) throw new CryptoError('INVALID_KEY', 'scalar out of range');
    let imported: CryptoKey;
    try {
      imported = await this.subtle.importKey('pkcs8', ab(concat(PKCS8_PREFIX, scalar)), EC, true, ['deriveBits']);
    } catch {
      throw new CryptoError('UNAVAILABLE', 'this browser cannot import a P-256 key without its public key');
    }
    try {
      const jwk = await this.subtle.exportKey('jwk', imported);
      if (jwk.x && jwk.y && jwk.d) return await this.importNonExtractable(jwk.d, jwk.x, jwk.y);
    } catch (e) {
      if (e instanceof CryptoError) throw e;
      // Firefox imports the PKCS #8 key and can derive with it, but will not export x and y.
    }
    const pub = await this.publicFromAgreement(imported, scalar);
    return this.importNonExtractable(toB64url(scalar), toB64url(pub.subarray(1, 33)), toB64url(pub.subarray(33, 65)));
  }

  /** A non-extractable ECDH key whose JWK already carries d, x and y. The public point is checked first. */
  private async importNonExtractable(d: string, x: string, y: string): Promise<P256PrivateKey> {
    const pub = this.p256ValidatePublic(concat(new Uint8Array([4]), fromB64url(x), fromB64url(y)));
    try {
      const key = await this.subtle.importKey('jwk', { kty: 'EC', crv: 'P-256', d, x, y, ext: false }, EC, false, ['deriveBits']);
      return new WebP256Key(key, pub);
    } catch (e) {
      if (e instanceof CryptoError) throw e;
      throw new CryptoError('INVALID_KEY', 'public key not determined');
    }
  }

  /**
   * x(d·G) from ECDH against the generator, and the sign of y from x((d+1)·G). Only public points enter the
   * `bigint` arithmetic; both scalars are imported into WebCrypto and are not multiplied here.
   */
  private async publicFromAgreement(priv: CryptoKey, scalar: Uint8Array): Promise<Uint8Array> {
    const x1 = toBig(await this.ecdhX(priv, G_POINT));
    const rhs = mod(x1 * x1 * x1 - 3n * x1 + B);
    const y0 = modPow(rhs, (P + 1n) >> 2n, P);
    if (mod(y0 * y0) !== rhs) throw new CryptoError('INVALID_KEY', 'no square root');
    const gx = toBig(G_POINT.subarray(1, 33));
    const gy = toBig(G_POINT.subarray(33, 65));
    let y: bigint;
    if (x1 === gx) {
      if (sameBytes(scalar, SCALAR_ONE)) y = gy;
      else if (sameBytes(scalar, SCALAR_NM1)) y = mod(-gy);
      else throw new CryptoError('INVALID_KEY', 'public key not determined');
    } else {
      const plus = scalarPlusOne(scalar);
      try {
        if (!isValidScalar(plus)) throw new CryptoError('INVALID_KEY', 'public key not determined');
        let priv2: CryptoKey;
        try {
          priv2 = await this.subtle.importKey('pkcs8', ab(concat(PKCS8_PREFIX, plus)), EC, false, ['deriveBits']);
        } catch {
          throw new CryptoError('UNAVAILABLE', 'this browser cannot import a P-256 key without its public key');
        }
        const x2 = toBig(await this.ecdhX(priv2, G_POINT));
        const withPlus = addX(x1, y0, gx, gy);
        const withMinus = addX(x1, mod(-y0), gx, gy);
        if (withPlus === x2 && withMinus !== x2) y = y0;
        else if (withMinus === x2 && withPlus !== x2) y = mod(-y0);
        else throw new CryptoError('INVALID_KEY', 'public key not determined');
      } finally {
        plus.fill(0);
      }
    }
    return this.p256ValidatePublic(uncompressed(x1, y));
  }

  p256ValidatePublic(encoded: Uint8Array): Uint8Array {
    if (encoded.length !== 65 || encoded[0] !== 4) throw new CryptoError('INVALID_KEY', 'not an uncompressed P-256 point');
    const x = toBig(encoded.subarray(1, 33));
    const y = toBig(encoded.subarray(33, 65));
    if (x >= P || y >= P) throw new CryptoError('INVALID_KEY', 'coordinate out of range');
    // Public values only: BigInt's timing does not matter here.
    if (mod(y * y) !== mod(x * x * x - 3n * x + B)) throw new CryptoError('INVALID_KEY', 'point not on P-256');
    return encoded.slice();
  }

  async p256Agree(privateKey: P256PrivateKey, peerPublic: Uint8Array): Promise<Uint8Array> {
    if (!(privateKey instanceof WebP256Key)) throw new CryptoError('INVALID_KEY', 'foreign key');
    return this.ecdhX(privateKey.key, peerPublic);
  }

  /** The 32-byte x-coordinate of ECDH. The peer point is validated first. */
  private async ecdhX(privateKey: CryptoKey, peerPublic: Uint8Array): Promise<Uint8Array> {
    const peer = this.p256ValidatePublic(peerPublic);
    try {
      const pk = await this.subtle.importKey('raw', ab(peer), EC, true, []);
      const secret = new Uint8Array(await this.subtle.deriveBits({ name: 'ECDH', public: pk }, privateKey, 256));
      if (secret.length !== 32) throw new CryptoError('INVALID_KEY', 'ECDH output length');
      return secret;
    } catch (e) {
      if (e instanceof CryptoError) throw e;
      throw new CryptoError('INVALID_KEY', 'ECDH');
    }
  }

  /**
   * Wrap a stored P-256 private key (from IndexedDB or similar persistent storage).
   * Validates the CryptoKey (type 'private', ECDH/P-256, deriveBits usage, non-extractable),
   * the public key bytes, and that the public key matches the private key.
   */
  async p256FromStoredKey(privateKey: CryptoKey, publicKeyRaw: Uint8Array): Promise<P256PrivateKey> {
    try {
      // Validate CryptoKey properties
      if (!privateKey || typeof privateKey !== 'object') {
        throw new CryptoError('INVALID_KEY', 'invalid CryptoKey object');
      }

      // Check extractable flag
      if (privateKey.extractable) {
        throw new CryptoError('INVALID_KEY', 'private key must be non-extractable');
      }

      // Check algorithm
      const algo = privateKey.algorithm as any;
      if (!algo || algo.name !== 'ECDH' || algo.namedCurve !== 'P-256') {
        throw new CryptoError('INVALID_KEY', 'not a P-256 ECDH private key');
      }

      // Check usages
      if (!privateKey.usages.includes('deriveBits')) {
        throw new CryptoError('INVALID_KEY', 'private key missing deriveBits usage');
      }

      // Validate public key bytes
      const pub = this.p256ValidatePublic(publicKeyRaw);

      // Verify the public key belongs to the private key: both sides of ECDH should produce the same secret.
      // Generate a temporary throwaway key pair for the test
      const testPair = (await this.subtle.generateKey(EC, false, ['deriveBits'])) as CryptoKeyPair;
      const testPublicRaw = new Uint8Array(await this.subtle.exportKey('raw', testPair.publicKey));

      // Compute s1 = deriveBits(ECDH, storedPrivate, testPublic)
      const s1 = new Uint8Array(await this.subtle.deriveBits({ name: 'ECDH', public: testPair.publicKey }, privateKey, 256));

      // Compute s2 = deriveBits(ECDH, testPrivate, storedPublic)
      const storedPublicCryptoKey = await this.subtle.importKey('raw', ab(pub), EC, false, []);
      const s2 = new Uint8Array(await this.subtle.deriveBits({ name: 'ECDH', public: storedPublicCryptoKey }, testPair.privateKey, 256));

      // Verify the secrets match
      if (!constantTimeEquals(s1, s2)) {
        throw new CryptoError('INVALID_KEY', 'public key does not match the private key');
      }

      return new WebP256Key(privateKey, pub);
    } catch (err) {
      if (err instanceof CryptoError) throw err;
      throw new CryptoError('INVALID_KEY', `failed to wrap stored key: ${err}`);
    }
  }
}
