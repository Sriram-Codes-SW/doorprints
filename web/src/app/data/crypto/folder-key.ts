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

import { concat, label, u32, utf8 } from './bytes';
import { sha256Of } from './crypto-provider';
import type { AesKey, CryptoProvider } from './crypto-provider';
import { Hkdf } from './hpke';

/**
 * The uses of a folder key, each its own HKDF-SHA-256 key (empty salt): the twin of Kotlin's `FolderKey`.
 * `doorprints/dpx1/dir` MACs `keys.json`; `doorprints/dpx1/content-wrap` wraps content keys;
 * `doorprints/dpx1/chain-wrap` wraps the previous epoch's folder key.
 */
export const FOLDER_KEY_SIZE = 32;

async function derive(p: CryptoProvider, folderKey: Uint8Array, info: string): Promise<Uint8Array> {
  if (folderKey.length !== FOLDER_KEY_SIZE) throw new RangeError('a folder key is 32 bytes');
  return new Hkdf(p).derive(new Uint8Array(0), folderKey, utf8(info), 32);
}

export function macKey(p: CryptoProvider, folderKey: Uint8Array): Promise<Uint8Array> {
  return derive(p, folderKey, 'doorprints/dpx1/dir');
}
export async function contentWrapKey(p: CryptoProvider, folderKey: Uint8Array): Promise<AesKey> {
  return p.aesKey(await derive(p, folderKey, 'doorprints/dpx1/content-wrap'));
}
export async function chainWrapKey(p: CryptoProvider, folderKey: Uint8Array): Promise<AesKey> {
  return p.aesKey(await derive(p, folderKey, 'doorprints/dpx1/chain-wrap'));
}

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
  chain(epoch: number): Uint8Array {
    return concat(label('dpx1/epoch-chain'), u32(epoch), u32(epoch - 1));
  },
};

/** The first 16 bytes of the SHA-256 of an uncompressed public key. */
export function kidOf(p: CryptoProvider, publicKey: Uint8Array): Uint8Array {
  return sha256Of(p, publicKey).slice(0, KID_SIZE);
}

/** The folder keys a reader holds, by epoch. */
export interface FolderKeys {
  folderKey(epoch: number): Promise<Uint8Array | null>;
}
