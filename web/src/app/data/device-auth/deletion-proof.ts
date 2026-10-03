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

import { ab, concat, constantTimeEquals, hex, unhex, utf8 } from '../crypto/bytes';

/**
 * The website's deletion proof (S4b-BL-135, docs/15 §10.4). After a PRF open, the PRF output is an HKDF base and
 * the HMAC key derived from it is non-extractable. The proof is HMAC-SHA-256 over `utf8(operationId) ‖ 0x00 ‖
 * u64be(issuedAtMs)`. A 64-hex proof that was not signed with that key fails, even if `registerGrant` stored a grant.
 */
const INFO = utf8('doorprints/deletion-proof/1');

export async function importProofKey(prfOutput: Uint8Array): Promise<CryptoKey> {
  if (prfOutput.length !== 32) throw new RangeError('PRF output');
  const base = await crypto.subtle.importKey('raw', ab(prfOutput), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: ab(INFO) },
    base,
    { name: 'HMAC', hash: 'SHA-256', length: 256 },
    false,
    ['sign'],
  );
}

export function proofMessage(operationId: string, issuedAtMs: number): Uint8Array {
  if (!Number.isSafeInteger(issuedAtMs) || issuedAtMs < 0) throw new RangeError('time');
  const time = new Uint8Array(8);
  const view = new DataView(time.buffer);
  view.setUint32(0, Math.floor(issuedAtMs / 0x100000000));
  view.setUint32(4, issuedAtMs >>> 0);
  return concat(utf8(operationId), new Uint8Array([0]), time);
}

export async function signProof(key: CryptoKey, operationId: string, issuedAtMs: number): Promise<string> {
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, ab(proofMessage(operationId, issuedAtMs))));
  return hex(mac);
}

export async function checkProof(key: CryptoKey, operationId: string, issuedAtMs: number, proof: string): Promise<boolean> {
  if (!/^[0-9a-f]{64}$/.test(proof)) return false;
  const mac = new Uint8Array(await crypto.subtle.sign('HMAC', key, ab(proofMessage(operationId, issuedAtMs))));
  return constantTimeEquals(mac, unhex(proof));
}
