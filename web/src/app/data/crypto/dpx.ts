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

import { b64, concat, constantTimeEquals, equalBytes, u32, unb64 } from './bytes';
import { CanonicalJson, exactObj, int, isObj, MAX_SAFE, parseJson, str } from './canonical-json';
import { CryptoError } from './crypto-provider';
import type { AesKey, CryptoProvider } from './crypto-provider';
import { contentWrapKey, KID_SIZE, WrapAad } from './folder-key';
import type { FolderKeys } from './folder-key';

export type DpxErrorKind =
  | 'NOT_DPX'
  | 'UNSUPPORTED_VERSION'
  | 'UNSUPPORTED_ALGORITHM'
  | 'HEADER_INVALID'
  | 'INNER_MISMATCH'
  | 'UNKNOWN_EPOCH'
  | 'KEY_UNWRAP_FAILED'
  | 'CHUNK_AUTH_FAILED'
  | 'TRUNCATED'
  | 'TRAILING_DATA'
  | 'NON_CANONICAL_CHUNKS'
  | 'TOO_LARGE'
  | 'CHECKSUM_MISMATCH'
  | 'CHECKSUM_REQUIRED';

/** Why a `dpx/1` file was refused (the kinds of Kotlin's `DpxException`). */
export class DpxError extends Error {
  constructor(
    readonly kind: DpxErrorKind,
    message: string,
    readonly chunkIndex = -1,
  ) {
    super(`dpx ${kind}: ${message}`);
    this.name = 'DpxError';
  }
}

/** Where bytes come from: up to `max` bytes (at least one), or null at the end. */
export interface ByteSource {
  read(max: number): Promise<Uint8Array | null>;
}

export type ByteSink = (bytes: Uint8Array) => void | Promise<void>;

export const DPX_VERSION = 1;
export const DPX_ALG = 'A256GCM-STREAM-64K';
export const CHUNK_SIZE = 65536;
const TAG = 16;
const NONCE_PREFIX = 7;
const MAX_HEADER = 4096;
const MAX_EPOCH = 2147483647;
const MAX_INDEX = 0xffffffff;
/** 4 GiB, as Kotlin. */
export const DEFAULT_MAX_PLAINTEXT = 4 * 1024 * 1024 * 1024;
export const MAGIC = new Uint8Array([0x44, 0x50, 0x58, 0x31]);
/** Decrypting a photo needs its row's SHA-256. */
export const PHOTO = 'photo/1';
const INNER = /^[a-z][a-z0-9-]{0,39}\/[1-9][0-9]{0,3}$/;

export interface DpxHeader {
  readonly epoch: number;
  readonly kid: Uint8Array;
  readonly wrapNonce: Uint8Array;
  readonly wrappedKey: Uint8Array;
  readonly noncePrefix: Uint8Array;
  readonly inner: string;
  /** `"DPX1" ‖ u16 length ‖ canonical JSON`, the prefix every chunk authenticates. */
  readonly bytes: Uint8Array;
}

export interface DpxResult {
  header: DpxHeader;
  plaintextSize: number;
  fileSize: number;
  plaintextSha256: Uint8Array;
  ciphertextSha256: Uint8Array;
}

function headerJson(h: Omit<DpxHeader, 'bytes'>): Uint8Array {
  return new CanonicalJson()
    .raw('{"v":')
    .number(DPX_VERSION)
    .raw(',"alg":')
    .string(DPX_ALG)
    .raw(',"epoch":')
    .number(h.epoch)
    .raw(',"kid":')
    .string(b64(h.kid))
    .raw(',"wrappedKey":{"nonce":')
    .string(b64(h.wrapNonce))
    .raw(',"ct":')
    .string(b64(h.wrappedKey))
    .raw('},"noncePrefix":')
    .string(b64(h.noncePrefix))
    .raw(',"chunkSize":')
    .number(CHUNK_SIZE)
    .raw(',"inner":')
    .string(h.inner)
    .raw('}')
    .bytes();
}

export function frame(json: Uint8Array): Uint8Array {
  if (json.length < 1 || json.length > MAX_HEADER) throw new RangeError('header length');
  return concat(MAGIC, new Uint8Array([json.length >>> 8, json.length & 0xff]), json);
}

function makeHeader(h: Omit<DpxHeader, 'bytes'>): DpxHeader {
  return { ...h, bytes: frame(headerJson(h)) };
}

export function chunkNonce(prefix: Uint8Array, index: number, last: boolean): Uint8Array {
  return concat(prefix, u32(index), new Uint8Array([last ? 1 : 0]));
}

export function chunkAad(header: Uint8Array, index: number, last: boolean): Uint8Array {
  return concat(header, u32(index), new Uint8Array([last ? 1 : 0]));
}

/** Reads until `n` bytes or the end. */
async function readUpTo(source: ByteSource, n: number): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  let got = 0;
  while (got < n) {
    const b = await source.read(n - got);
    if (b === null) break;
    if (b.length === 0 || b.length > n - got) throw new Error('a ByteSource returned a wrong count');
    parts.push(b);
    got += b.length;
  }
  return parts.length === 1 ? parts[0] : concat(...parts);
}

/** A source over bytes in memory, `step` bytes at most per read (for tests). */
export function sourceOf(bytes: Uint8Array, step = Number.MAX_SAFE_INTEGER): ByteSource {
  let at = 0;
  return {
    async read(max: number) {
      if (at >= bytes.length) return null;
      const n = Math.min(max, step, bytes.length - at);
      const out = bytes.subarray(at, at + n);
      at += n;
      return out;
    },
  };
}

/**
 * The `dpx/1` envelope (docs/15 §9.6, §9.7), byte for byte the twin of Kotlin's `Dpx` (the layout is in its
 * comment). Streaming: two chunks in memory; `decrypt` gives each chunk to the sink once it authenticated, and the
 * whole file is proven only when it resolves, so a caller holds what it was given as unconfirmed until then.
 */
export class Dpx {
  constructor(private readonly p: CryptoProvider) {}

  /**
   * The length is not known in advance, so a `TOO_LARGE` (or any failure of the source or sink) can leave a partial
   * file on the sink: write to a temporary place and discard it on any rejection.
   */
  async encrypt(
    folderKey: Uint8Array,
    epoch: number,
    writerKid: Uint8Array,
    inner: string,
    source: ByteSource,
    sink: ByteSink,
    maxPlaintext = DEFAULT_MAX_PLAINTEXT,
  ): Promise<DpxResult> {
    const contentKey = this.p.randomBytes(32);
    try {
      return await this.encryptWith(folderKey, epoch, writerKid, inner, source, sink, maxPlaintext, {
        contentKey,
        wrapNonce: this.p.randomBytes(12),
        noncePrefix: this.p.randomBytes(NONCE_PREFIX),
      });
    } finally {
      contentKey.fill(0);
    }
  }

  /** @internal The deterministic mode of the vectors. Never call it with reused values. */
  async encryptWith(
    folderKey: Uint8Array,
    epoch: number,
    writerKid: Uint8Array,
    inner: string,
    source: ByteSource,
    sink: ByteSink,
    maxPlaintext: number,
    r: { contentKey: Uint8Array; wrapNonce: Uint8Array; noncePrefix: Uint8Array },
  ): Promise<DpxResult> {
    if (!Number.isInteger(epoch) || epoch < 1 || epoch > MAX_EPOCH) throw new RangeError('epoch');
    if (writerKid.length !== KID_SIZE || !INNER.test(inner)) throw new RangeError('kid or inner');
    if (r.contentKey.length !== 32 || r.wrapNonce.length !== 12 || r.noncePrefix.length !== NONCE_PREFIX) throw new RangeError('random values');
    const wrapped = await this.p.aesGcmSeal(await contentWrapKey(this.p, folderKey), r.wrapNonce, WrapAad.contentKey(epoch, writerKid, inner), r.contentKey);
    const header = makeHeader({ epoch, kid: writerKid.slice(), wrapNonce: r.wrapNonce.slice(), wrappedKey: wrapped, noncePrefix: r.noncePrefix.slice(), inner });
    const key = await this.p.aesKey(r.contentKey);
    const ptHash = this.p.sha256();
    const ctHash = this.p.sha256();
    let fileSize = 0;
    const out = async (b: Uint8Array) => {
      await sink(b);
      ctHash.update(b);
      fileSize += b.length;
    };
    await out(header.bytes);
    let total = 0;
    let index = 0;
    let cur = await readUpTo(source, CHUNK_SIZE);
    for (;;) {
      const next = cur.length === CHUNK_SIZE ? await readUpTo(source, CHUNK_SIZE) : new Uint8Array(0);
      const last = next.length === 0;
      total += cur.length;
      if (total > maxPlaintext) throw new DpxError('TOO_LARGE', 'plaintext over the limit');
      if (index > MAX_INDEX) throw new DpxError('TOO_LARGE', 'more chunks than the index holds');
      ptHash.update(cur);
      await out(await this.p.aesGcmSeal(key, chunkNonce(header.noncePrefix, index, last), chunkAad(header.bytes, index, last), cur));
      if (last) break;
      cur = next;
      index++;
    }
    return { header, plaintextSize: total, fileSize, plaintextSha256: ptHash.digest(), ciphertextSha256: ctHash.digest() };
  }

  async decrypt(
    keys: FolderKeys,
    expectedInner: string,
    source: ByteSource,
    sink: ByteSink,
    opts: {
      maxPlaintext?: number;
      expectedPlaintextSha256?: Uint8Array;
      expectedCiphertextSha256?: Uint8Array;
      headerCheck?: (h: DpxHeader) => void;
    } = {},
  ): Promise<DpxResult> {
    const maxPlaintext = opts.maxPlaintext ?? DEFAULT_MAX_PLAINTEXT;
    const ctHash = this.p.sha256();
    let fileSize = 0;
    const counted: ByteSource = {
      read: async (max) => {
        const b = await source.read(max);
        if (b) {
          ctHash.update(b);
          fileSize += b.length;
        }
        return b;
      },
    };
    const header = await this.readHeader(counted);
    if (header.inner !== expectedInner) throw new DpxError('INNER_MISMATCH', 'inner format');
    // Every photo is a valid file under the same folder key: only the row's hash binds the file to the row.
    if (header.inner === PHOTO && !opts.expectedPlaintextSha256) throw new DpxError('CHECKSUM_REQUIRED', "a photo needs its row's SHA-256");
    opts.headerCheck?.(header);
    const folderKey = await keys.folderKey(header.epoch);
    if (!folderKey) throw new DpxError('UNKNOWN_EPOCH', `epoch ${header.epoch}`);
    let contentKey: Uint8Array;
    try {
      contentKey = await this.p.aesGcmOpen(
        await contentWrapKey(this.p, folderKey),
        header.wrapNonce,
        WrapAad.contentKey(header.epoch, header.kid, header.inner),
        header.wrappedKey,
      );
    } catch (e) {
      if (e instanceof CryptoError) throw new DpxError('KEY_UNWRAP_FAILED', 'content key');
      throw e;
    } finally {
      folderKey.fill(0);
    }
    const key = await this.p.aesKey(contentKey);
    contentKey.fill(0);
    const ptHash = this.p.sha256();
    const full = CHUNK_SIZE + TAG;
    let total = 0;
    let index = 0;
    let cur = await readUpTo(counted, full);
    if (cur.length === 0) throw new DpxError('TRUNCATED', 'no chunk');
    for (;;) {
      if (index > MAX_INDEX) throw new DpxError('TOO_LARGE', 'more chunks than the index holds');
      const next = cur.length === full ? await readUpTo(counted, full) : new Uint8Array(0);
      const last = next.length === 0;
      if (cur.length < TAG) throw new DpxError('TRUNCATED', 'chunk shorter than a tag', index);
      const pt = (await this.openChunk(key, header, index, last, cur)) ?? (await this.diagnose(key, header, index, last, cur));
      if (last && pt.length === 0 && index > 0) throw new DpxError('NON_CANONICAL_CHUNKS', 'empty last chunk', index);
      total += pt.length;
      if (total > maxPlaintext) throw new DpxError('TOO_LARGE', 'plaintext over the limit', index);
      ptHash.update(pt);
      await sink(pt);
      if (last) break;
      cur = next;
      index++;
    }
    const result: DpxResult = { header, plaintextSize: total, fileSize, plaintextSha256: ptHash.digest(), ciphertextSha256: ctHash.digest() };
    if (opts.expectedPlaintextSha256 && !constantTimeEquals(opts.expectedPlaintextSha256, result.plaintextSha256)) {
      throw new DpxError('CHECKSUM_MISMATCH', 'plaintext SHA-256');
    }
    if (opts.expectedCiphertextSha256 && !constantTimeEquals(opts.expectedCiphertextSha256, result.ciphertextSha256)) {
      throw new DpxError('CHECKSUM_MISMATCH', 'file SHA-256');
    }
    return result;
  }

  private async openChunk(key: AesKey, h: DpxHeader, index: number, last: boolean, block: Uint8Array): Promise<Uint8Array | null> {
    try {
      return await this.p.aesGcmOpen(key, chunkNonce(h.noncePrefix, index, last), chunkAad(h.bytes, index, last), block);
    } catch (e) {
      if (e instanceof CryptoError) return null;
      throw e;
    }
  }

  /** As Kotlin: a block that opens with the other last flag tells TRAILING_DATA or TRUNCATED; else CHUNK_AUTH_FAILED. */
  private async diagnose(key: AesKey, h: DpxHeader, index: number, last: boolean, block: Uint8Array): Promise<never> {
    const asOther = await this.openChunk(key, h, index, !last, block);
    if (asOther && !last) throw new DpxError('TRAILING_DATA', 'bytes after the last chunk', index);
    if (asOther && last) throw new DpxError('TRUNCATED', 'file ends after a middle chunk', index);
    throw new DpxError('CHUNK_AUTH_FAILED', `chunk ${index}`, index);
  }

  async encryptBytes(folderKey: Uint8Array, epoch: number, writerKid: Uint8Array, inner: string, plaintext: Uint8Array): Promise<{ file: Uint8Array; result: DpxResult }> {
    const parts: Uint8Array[] = [];
    const result = await this.encrypt(folderKey, epoch, writerKid, inner, sourceOf(plaintext), (b) => void parts.push(b), Number.MAX_SAFE_INTEGER);
    return { file: concat(...parts), result };
  }

  /** The plaintext, only when the whole file checked out. */
  async decryptBytes(
    keys: FolderKeys,
    expectedInner: string,
    file: Uint8Array,
    opts: { maxPlaintext?: number; expectedPlaintextSha256?: Uint8Array; headerCheck?: (h: DpxHeader) => void } = {},
  ): Promise<{ plaintext: Uint8Array; result: DpxResult }> {
    const parts: Uint8Array[] = [];
    const result = await this.decrypt(keys, expectedInner, sourceOf(file), (b) => void parts.push(b), opts);
    return { plaintext: concat(...parts), result };
  }

  async readHeader(source: ByteSource): Promise<DpxHeader> {
    const magic = await readUpTo(source, 4);
    if (magic.length < 4) {
      if (magic.length > 0 && equalBytes(MAGIC.subarray(0, magic.length), magic)) throw new DpxError('TRUNCATED', 'inside the magic');
      throw new DpxError('NOT_DPX', 'no DPX1 magic');
    }
    if (!equalBytes(magic, MAGIC)) {
      if (equalBytes(magic.subarray(0, 3), MAGIC.subarray(0, 3))) throw new DpxError('UNSUPPORTED_VERSION', 'DPX version');
      throw new DpxError('NOT_DPX', 'no DPX1 magic');
    }
    const len = await readUpTo(source, 2);
    if (len.length < 2) throw new DpxError('TRUNCATED', 'inside the header length');
    const n = (len[0] << 8) | len[1];
    if (n < 1 || n > MAX_HEADER) throw new DpxError('HEADER_INVALID', `header length ${n}`);
    const json = await readUpTo(source, n);
    if (json.length < n) throw new DpxError('TRUNCATED', 'inside the header');
    return parseHeader(json);
  }
}

function parseHeader(json: Uint8Array): DpxHeader {
  const bad = (why: string): never => {
    throw new DpxError('HEADER_INVALID', why);
  };
  const root = parseJson(json);
  if (!isObj(root)) return bad('not a JSON object');
  const v = int(root['v'], 0, MAX_SAFE) ?? bad('v');
  if (v !== DPX_VERSION) throw new DpxError('UNSUPPORTED_VERSION', `v=${v}`);
  const alg = str(root['alg']) ?? bad('alg');
  if (alg !== DPX_ALG) throw new DpxError('UNSUPPORTED_ALGORITHM', 'alg');
  exactObj(root, 'v', 'alg', 'epoch', 'kid', 'wrappedKey', 'noncePrefix', 'chunkSize', 'inner') ?? bad('fields');
  const epoch = int(root['epoch'], 1, MAX_EPOCH) ?? bad('epoch');
  const b = (x: unknown, size: number, what: string): Uint8Array => {
    const s = str(x);
    return (s === null ? null : unb64(s, size)) ?? bad(what);
  };
  const kid = b(root['kid'], KID_SIZE, 'kid');
  const wk = exactObj(root['wrappedKey'], 'nonce', 'ct') ?? bad('wrappedKey');
  const wrapNonce = b(wk['nonce'], 12, 'wrappedKey.nonce');
  const wrappedKey = b(wk['ct'], 48, 'wrappedKey.ct');
  const noncePrefix = b(root['noncePrefix'], NONCE_PREFIX, 'noncePrefix');
  if (int(root['chunkSize'], 0, MAX_SAFE) !== CHUNK_SIZE) bad('chunkSize');
  const inner = str(root['inner']);
  if (inner === null || !INNER.test(inner)) return bad('inner');
  const header = makeHeader({ epoch, kid, wrapNonce, wrappedKey, noncePrefix, inner });
  if (!equalBytes(headerJson(header), json)) bad('not canonical');
  return header;
}
