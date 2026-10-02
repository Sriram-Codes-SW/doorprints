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
import { b64, concat } from './bytes';
import { sha256Of } from './crypto-provider';
import { WebCryptoProvider } from './crypto-provider';
import { CHUNK_SIZE, chunkAad, chunkNonce, Dpx, DpxError, frame, MAGIC, sourceOf } from './dpx';
import type { DpxErrorKind } from './dpx';
import type { FolderKeys } from './folder-key';

/** The `dpx/1` envelope on the website: round trips, tamper, truncation, reordering, downgrade (TC-U-128). */
const p = new WebCryptoProvider();
const dpx = new Dpx(p);
const folderKey = p.randomBytes(32);
const epoch = 4;
const kid = p.randomBytes(16);
const keys: FolderKeys = { folderKey: async (e) => (e === epoch ? folderKey.slice() : null) };
const full = CHUNK_SIZE + 16;

const enc = async (pt: Uint8Array, inner = 'sync/1') => (await dpx.encryptBytes(folderKey, epoch, kid, inner, pt)).file;
const dec = async (file: Uint8Array, k: FolderKeys = keys, inner = 'sync/1') => (await dpx.decryptBytes(k, inner, file)).plaintext;
const headerLen = (file: Uint8Array) => 6 + ((file[4] << 8) | file[5]);

async function expectKind(kind: DpxErrorKind, f: () => Promise<unknown>): Promise<DpxError> {
  try {
    await f();
  } catch (e) {
    expect(e).toBeInstanceOf(DpxError);
    expect((e as DpxError).kind, (e as Error).message).toBe(kind);
    return e as DpxError;
  }
  throw new Error(`expected ${kind}`);
}

describe('dpx/1', () => {
  it('round-trips every size, also with small reads', async () => {
    for (const size of [0, 1, 100, CHUNK_SIZE - 1, CHUNK_SIZE, CHUNK_SIZE + 1, 3 * CHUNK_SIZE + 5]) {
      const pt = p.randomBytes(size);
      const file = await enc(pt);
      const chunks = size === 0 ? 1 : Math.ceil(size / CHUNK_SIZE);
      expect(file.length).toBe(size + headerLen(file) + 16 * chunks);
      expect(b64(await dec(file))).toBe(b64(pt));
      const parts: Uint8Array[] = [];
      const r = await dpx.decrypt(keys, 'sync/1', sourceOf(file, 7000), (b) => void parts.push(b));
      expect(b64(concat(...parts))).toBe(b64(pt));
      expect(r.fileSize).toBe(file.length);
    }
  });

  it('never reuses a content key or nonce prefix', async () => {
    const a = await dpx.readHeader(sourceOf(await enc(new Uint8Array(3))));
    const b = await dpx.readHeader(sourceOf(await enc(new Uint8Array(3))));
    expect(b64(a.wrappedKey)).not.toBe(b64(b.wrappedKey));
    expect(b64(a.noncePrefix)).not.toBe(b64(b.noncePrefix));
  });

  it('authenticates every header byte', async () => {
    const file = await enc(p.randomBytes(100));
    for (let i = 0; i < headerLen(file); i++) {
      const bad = file.slice();
      bad[i] ^= 0x01;
      await expect(dec(bad)).rejects.toBeInstanceOf(DpxError);
    }
  });

  it('gives each header change its own kind', async () => {
    const file = await enc(new Uint8Array(5));
    const h = headerLen(file);
    const json = new TextDecoder().decode(file.subarray(6, h));
    const withJson = (j: string) => concat(frame(new TextEncoder().encode(j)), file.subarray(h));
    await expectKind('KEY_UNWRAP_FAILED', () => dec(withJson(json.replace(/"kid":"[^"]+"/, `"kid":"${b64(p.randomBytes(16))}"`))));
    const both: FolderKeys = { folderKey: async (e) => (e === 4 || e === 5 ? folderKey.slice() : null) };
    await expectKind('KEY_UNWRAP_FAILED', () => dec(withJson(json.replace('"epoch":4', '"epoch":5')), both));
    await expectKind('UNKNOWN_EPOCH', () => dec(withJson(json.replace('"epoch":4', '"epoch":6'))));
    await expectKind('INNER_MISMATCH', () => dec(file, keys, 'photo/1'));
    await expectKind('UNSUPPORTED_VERSION', () => dec(withJson(json.replace('"v":1', '"v":2'))));
    await expectKind('UNSUPPORTED_ALGORITHM', () => dec(withJson(json.replace('A256GCM', 'A128GCM'))));
    await expectKind('HEADER_INVALID', () => dec(withJson(json.replace('65536', '4096'))));
    await expectKind('HEADER_INVALID', () => dec(withJson(json.slice(0, -1) + ',"flags":0}')));
    await expectKind('HEADER_INVALID', () => dec(withJson(json.replace('{"v":1,', '{ "v":1,'))));
    await expectKind('HEADER_INVALID', () => dec(withJson(json.replace('"epoch":4', '"epoch":4.0'))));
    await expectKind('HEADER_INVALID', () => dec(withJson(json.replace('"v":1,', '"v":1,"v":1,'))));
    const other = await enc(new Uint8Array(5));
    const swapped = concat(other.subarray(0, headerLen(other)), file.subarray(h));
    expect((await expectKind('CHUNK_AUTH_FAILED', () => dec(swapped))).chunkIndex).toBe(0);
  });

  it('authenticates every chunk, refuses truncation, reordering, duplicates and extra bytes', async () => {
    const file = await enc(p.randomBytes(3 * CHUNK_SIZE + 50));
    const h = headerLen(file);
    for (let c = 0; c < 4; c++) {
      for (const pos of [h + c * full, Math.min(h + (c + 1) * full, file.length) - 1]) {
        const bad = file.slice();
        bad[pos] ^= 4;
        expect((await expectKind('CHUNK_AUTH_FAILED', () => dec(bad))).chunkIndex).toBe(c);
      }
    }
    await expectKind('NOT_DPX', () => dec(new Uint8Array(0)));
    await expectKind('TRUNCATED', () => dec(file.slice(0, h)));
    for (let k = 1; k <= 3; k++) await expectKind('TRUNCATED', () => dec(file.slice(0, h + k * full)));
    await expect(dec(file.slice(0, h + full / 2))).rejects.toBeInstanceOf(DpxError);
    await expect(dec(file.slice(0, file.length - 1))).rejects.toBeInstanceOf(DpxError);
    const head = file.subarray(0, h);
    const ch = [0, 1, 2, 3].map((i) => file.subarray(h + i * full, Math.min(h + (i + 1) * full, file.length)));
    expect((await expectKind('CHUNK_AUTH_FAILED', () => dec(concat(head, ch[1], ch[0], ch[2], ch[3])))).chunkIndex).toBe(0);
    expect((await expectKind('CHUNK_AUTH_FAILED', () => dec(concat(head, ch[0], ch[0], ch[2], ch[3])))).chunkIndex).toBe(1);
    const exact = await enc(p.randomBytes(2 * CHUNK_SIZE));
    await expectKind('TRAILING_DATA', () => dec(concat(exact, new Uint8Array(1))));
    await expectKind('TRUNCATED', () => dec(exact.slice(0, headerLen(exact) + full)));
  });

  it('refuses plain files, other versions, wrong keys, limits and checksums', async () => {
    await expectKind('NOT_DPX', () => dec(new Uint8Array([0x50, 0x4b, 3, 4, 0, 0, 0])));
    const file = await enc(new Uint8Array(3));
    const v2 = file.slice();
    v2[3] = 0x32;
    await expectKind('UNSUPPORTED_VERSION', () => dec(v2));
    await expectKind('KEY_UNWRAP_FAILED', () => dec(file, { folderKey: async () => p.randomBytes(32) }));
    await expectKind('UNKNOWN_EPOCH', () => dec(file, { folderKey: async () => null }));
    const big = await enc(p.randomBytes(CHUNK_SIZE + 1));
    await expectKind('TOO_LARGE', () => dpx.decryptBytes(keys, 'sync/1', big, { maxPlaintext: CHUNK_SIZE }));
    await expectKind('CHECKSUM_MISMATCH', () => dpx.decrypt(keys, 'sync/1', sourceOf(file), () => undefined, { expectedPlaintextSha256: new Uint8Array(32) }));
    await expectKind('HEADER_INVALID', () => dec(concat(MAGIC, new Uint8Array([0, 0]))));
  });

  it('refuses an empty last chunk after data', async () => {
    const r = { contentKey: p.randomBytes(32), wrapNonce: p.randomBytes(12), noncePrefix: p.randomBytes(7) };
    const parts: Uint8Array[] = [];
    await dpx.encryptWith(folderKey, epoch, kid, 'sync/1', sourceOf(new Uint8Array(0)), (b) => void parts.push(b), 1e9, r);
    const header = await dpx.readHeader(sourceOf(concat(...parts)));
    const k = await p.aesKey(r.contentKey);
    const first = await p.aesGcmSeal(k, chunkNonce(r.noncePrefix, 0, false), chunkAad(header.bytes, 0, false), new Uint8Array(CHUNK_SIZE));
    const last = await p.aesGcmSeal(k, chunkNonce(r.noncePrefix, 1, true), chunkAad(header.bytes, 1, true), new Uint8Array(0));
    await expectKind('NON_CANONICAL_CHUNKS', () => dec(concat(header.bytes, first, last)));
  });

  it('opens a photo only against its row\'s hash, and wipes the folder key it was given', async () => {
    const a = p.randomBytes(3000);
    const b = p.randomBytes(3000);
    const fileA = await enc(a, 'photo/1');
    const fileB = await enc(b, 'photo/1');
    await expectKind('CHECKSUM_REQUIRED', () => dec(fileA, keys, 'photo/1'));
    const ok = await dpx.decryptBytes(keys, 'photo/1', fileA, { expectedPlaintextSha256: sha256Of(p, a) });
    expect(b64(ok.plaintext)).toBe(b64(a));
    await expectKind('CHECKSUM_MISMATCH', () => dpx.decryptBytes(keys, 'photo/1', fileB, { expectedPlaintextSha256: sha256Of(p, a) }));
    const handed: Uint8Array[] = [];
    await dec(await enc(new Uint8Array(4)), { folderKey: async () => { const k = folderKey.slice(); handed.push(k); return k; } });
    expect(handed[0].every((x) => x === 0)).toBe(true);
  });
});
