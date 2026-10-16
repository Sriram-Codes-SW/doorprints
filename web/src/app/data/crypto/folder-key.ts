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

import { ab, concat, label, u32, utf8 } from './bytes';
import { sha256Of } from './crypto-provider';
import type { AesKey, CryptoProvider } from './crypto-provider';
import { Hkdf } from './hpke';

/**
 * The uses of a folder key, each its own HKDF-SHA-256 key (empty salt): the twin of Kotlin's `FolderKey`.
 * `doorprints/dpx1/dir` MACs `keys.json`; `doorprints/dpx1/content-wrap` wraps content keys;
 * `doorprints/dpx1/chain-wrap` wraps the previous epoch's folder key; `doorprints/dpx1/key-id` names a key in a
 * device's pin (not a key).
 */
export const FOLDER_KEY_SIZE = 32;

async function derive(p: CryptoProvider, folderKey: Uint8Array, info: string): Promise<Uint8Array> {
  if (folderKey.length !== FOLDER_KEY_SIZE) throw new RangeError('a folder key is 32 bytes');
  return new Hkdf(p).derive(new Uint8Array(0), folderKey, utf8(info), 32);
}

/** The key that MACs `keys.json`, derived from the folder key so the folder key itself is never used as a MAC key. */
export function macKey(p: CryptoProvider, folderKey: Uint8Array): Promise<Uint8Array> {
  return derive(p, folderKey, 'doorprints/dpx1/dir');
}
async function aes(p: CryptoProvider, raw: Uint8Array): Promise<AesKey> {
  try {
    return await p.aesKey(raw);
  } finally {
    raw.fill(0);
  }
}
/** The AES key that wraps each file's content key; the derived bytes are wiped once the key is imported. */
export async function contentWrapKey(p: CryptoProvider, folderKey: Uint8Array): Promise<AesKey> {
  return aes(p, await derive(p, folderKey, 'doorprints/dpx1/content-wrap'));
}
/**
 * The AES key that wraps the previous epoch's folder key in the key chain, so an old epoch stays readable after a re-key.
 */
export async function chainWrapKey(p: CryptoProvider, folderKey: Uint8Array): Promise<AesKey> {
  return aes(p, await derive(p, folderKey, 'doorprints/dpx1/chain-wrap'));
}
/** The 16-byte name of a folder key (its kid), derived so the key cannot be recovered from it. */
export function keyId(p: CryptoProvider, folderKey: Uint8Array): Promise<Uint8Array> {
  return derive(p, folderKey, 'doorprints/dpx1/key-id');
}

/**
 * One folder key held as a non-extractable HKDF base (S4b-BL-132). The raw bytes are copied into WebCrypto and the
 * copy is overwritten; the caller's buffer is left for the one wrap that still needs it. MAC, wrap and key-id bytes
 * are derived from this key, so they are not a second long-lived copy of the folder key.
 */
export async function importFolderBase(raw: Uint8Array): Promise<CryptoKey> {
  if (raw.length !== FOLDER_KEY_SIZE) throw new RangeError('a folder key is 32 bytes');
  const copy = ab(raw);
  try {
    return await crypto.subtle.importKey('raw', copy, { name: 'HKDF' }, false, ['deriveBits', 'deriveKey']);
  } finally {
    copy.fill(0);
  }
}

function hkdfParams(info: string): HkdfParams {
  return { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: ab(utf8(info)) };
}

/** 32 bytes of HKDF-SHA-256 from a non-extractable folder key. The caller overwrites them when they are key bytes. */
export async function deriveFolderBits(base: CryptoKey, info: string): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.deriveBits(hkdfParams(info), base, 256));
}

/** HMAC-SHA-256 under HKDF(folder key, info), without a JavaScript copy of the folder key or of the HMAC key. */
export async function hmacFromBase(base: CryptoKey, info: string, data: Uint8Array): Promise<Uint8Array> {
  const key = await crypto.subtle.deriveKey(hkdfParams(info), base, { name: 'HMAC', hash: 'SHA-256', length: 256 }, false, ['sign']);
  return new Uint8Array(await crypto.subtle.sign('HMAC', key, ab(data)));
}

/** AES-256-GCM under HKDF(folder key, info), non-extractable. */
export async function aesFromBase(p: CryptoProvider, base: CryptoKey, info: string): Promise<AesKey> {
  const key = await crypto.subtle.deriveKey(hkdfParams(info), base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  return p.adoptAes(key);
}

/** Bytes in a key id. */
export const KID_SIZE = 16;
/** HPKE's `info` for every folder-key wrap. */
export const HPKE_INFO = utf8('doorprints/dpx1/wrap');

/** The additional data of the wraps (Kotlin `WrapAad`). */
export const WrapAad = {
  contentKey(epoch: number, writerKid: Uint8Array, inner: string): Uint8Array {
    if (writerKid.length !== KID_SIZE) throw new RangeError('kid');
    return concat(label('dpx1/content-key'), u32(epoch), writerKid, label(inner));
  },
  folderKey(epoch: number, recipientKid: Uint8Array): Uint8Array {
    if (recipientKid.length !== KID_SIZE) throw new RangeError('kid');
    return concat(label('dpx1/folder-key'), u32(epoch), recipientKid);
  },
  /** The recovery anchor: the folder key of the epoch the recovery key was made in, under the recovery key. */
  recoveryAnchor(epoch: number, recoveryKid: Uint8Array): Uint8Array {
    if (recoveryKid.length !== KID_SIZE) throw new RangeError('kid');
    return concat(label('dpx1/recovery-anchor'), u32(epoch), recoveryKid);
  },
  chain(epoch: number): Uint8Array {
    return concat(label('dpx1/epoch-chain'), u32(epoch), u32(epoch - 1));
  },
};

/** The first 16 bytes of the SHA-256 of an uncompressed public key. */
export function kidOf(p: CryptoProvider, publicKey: Uint8Array): Uint8Array {
  return sha256Of(p, publicKey).slice(0, KID_SIZE);
}

/** The folder keys a reader holds, by epoch; each call returns a new array, which the reader overwrites after use. */
export interface FolderKeys {
  folderKey(epoch: number): Promise<Uint8Array | null>;
  /**
   * The content-wrap AES key of an epoch, when the reader holds the folder key as a non-extractable HKDF base
   * (S4b-BL-132). Absent on a test double that only has the raw bytes.
   */
  contentWrapAes?(epoch: number): Promise<AesKey | null>;
}
