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
import vectorsJson from '../../../../../docs/schemas/dpx-vectors.json';
import { b64, hex, unb64, unhex } from './bytes';
import { WebCryptoProvider } from './crypto-provider';
import { Dpx, sourceOf } from './dpx';
import { FakeRandomProvider, patternBytes } from './fake-random';
import { kidOf } from './folder-key';
import { Hpke } from './hpke';
import { KeysFile } from './keys-file';
import type { DevicePlatform, OpenedKeys, WrittenKeys } from './keys-file';
import { KeysGuard, sameWatermark } from './keys-guard';
import type { KeysWatermark } from './keys-guard';
import { RecoveryKey, RecoveryKeyError } from './recovery-key';

/**
 * The cross-platform parity vectors (`docs/schemas/dpx-vectors.json`, TC-U-129), the same cases as Kotlin's
 * `CryptoVectorsTest`: what Kotlin wrote, the website writes byte for byte, and opens.
 */
interface Rec {
  bytes: string;
  symbols: string;
  display: string;
  scalar: string;
  publicKey: string;
  kid: string;
}
interface Env {
  name: string;
  folderKey: string;
  epoch: number;
  kid: string;
  inner: string;
  contentKey: string;
  wrapNonce: string;
  noncePrefix: string;
  plaintextSize: number;
  plaintextSha256: string;
  fileSize: number;
  fileSha256: string;
  file?: string;
}
interface Step {
  op: string;
  now: number;
  approver?: number;
  device?: number;
  revoke?: number;
  newRecoveryBytes?: string;
  file: string;
  openers: number[];
  folderKeys: Record<string, string>;
}
const vectors = vectorsJson as unknown as {
  format: string;
  recovery: Rec[];
  recoveryParse: { input: string; bytes?: string; error?: string }[];
  envelope: Env[];
  keys: { seed: string; recoveryBytes: string; devices: { ikm: string; name: string; platform: DevicePlatform }[]; steps: Step[] };
};
const p = new WebCryptoProvider();
const B = (s: string) => {
  const b = unb64(s);
  if (!b) throw new Error('bad base64 in the vectors');
  return b;
};
const memoryGuard = () => {
  let w: KeysWatermark | null = null;
  return new KeysGuard(p, {
    load: async () => w,
    compareAndSet: async (expected, next) => {
      if (!sameWatermark(w, expected)) return false;
      w = next;
      return true;
    },
  });
};

describe('dpx-vectors.json (Kotlin parity)', () => {
  it('has the format', () => expect(vectors.format).toBe('doorprints-dpx-vectors/1'));

  it('recovery keys: bytes, text, scalar, public key', async () => {
    for (const v of vectors.recovery) {
      const key = RecoveryKey.fromBytes(unhex(v.bytes));
      expect(key.symbols).toBe(v.symbols);
      expect(key.display).toBe(v.display);
      expect(hex(RecoveryKey.parse(v.display).bytes)).toBe(v.bytes);
      expect(hex(await key.scalar(p))).toBe(v.scalar);
      const pair = await key.keyPair(p);
      expect(hex(pair.publicKey)).toBe(v.publicKey);
      expect(hex(kidOf(p, pair.publicKey))).toBe(v.kid);
    }
  });

  it('typed recovery keys', () => {
    for (const v of vectors.recoveryParse) {
      if (v.bytes) expect(hex(RecoveryKey.parse(v.input).bytes)).toBe(v.bytes);
      else {
        let reason = '';
        try {
          RecoveryKey.parse(v.input);
        } catch (e) {
          reason = (e as RecoveryKeyError).reason;
        }
        expect(reason, v.input).toBe(v.error);
      }
    }
  });

  it('envelopes: the same file bytes, and they open', async () => {
    for (const v of vectors.envelope) {
      const parts: Uint8Array[] = [];
      const pt = patternBytes(v.plaintextSize);
      const r = await new Dpx(p).encryptWith(B(v.folderKey), v.epoch, B(v.kid), v.inner, sourceOf(pt), (b) => void parts.push(b), Number.MAX_SAFE_INTEGER, {
        contentKey: B(v.contentKey),
        wrapNonce: B(v.wrapNonce),
        noncePrefix: B(v.noncePrefix),
      });
      const file = new Uint8Array(parts.reduce((n, x) => n + x.length, 0));
      let at = 0;
      for (const x of parts) {
        file.set(x, at);
        at += x.length;
      }
      expect(file.length, v.name).toBe(v.fileSize);
      expect(hex(r.ciphertextSha256), v.name).toBe(v.fileSha256);
      expect(hex(r.plaintextSha256), v.name).toBe(v.plaintextSha256);
      if (v.file) expect(b64(file), v.name).toBe(v.file);
      const keys = { folderKey: async (e: number) => (e === v.epoch ? B(v.folderKey) : null) };
      const back = await new Dpx(p).decryptBytes(keys, v.inner, file, { expectedPlaintextSha256: unhex(v.plaintextSha256) });
      expect(hex(back.plaintext) === hex(pt), v.name).toBe(true);
    }
  });

  it('keys.json: the same bytes at every step, opened by every device and the recovery key', async () => {
    const k = vectors.keys;
    const rp = new FakeRandomProvider(p, k.seed);
    const files = new KeysFile(rp);
    const keys = await Promise.all(k.devices.map((d) => new Hpke(p).deriveKeyPair(unhex(d.ikm))));
    const devices = k.devices.map((d, i) => ({ publicKey: keys[i].publicKey, name: d.name, platform: d.platform }));
    let recovery = RecoveryKey.fromBytes(unhex(k.recoveryBytes));
    let opened: OpenedKeys | null = null;
    for (const step of k.steps) {
      let w: WrittenKeys;
      if (step.op === 'create') w = await files.createFirstDevice(devices[0], recovery, step.now);
      else if (step.op === 'addDevice') w = await files.addDevice(opened!, kidOf(p, devices[step.approver!].publicKey), devices[step.device!], step.now);
      else if (step.op === 'newEpoch') {
        recovery = RecoveryKey.fromBytes(unhex(step.newRecoveryBytes!));
        w = await files.newEpoch(opened!, step.now, { revokeKid: kidOf(p, devices[step.revoke!].publicKey), newRecovery: recovery });
      }
      else throw new Error(step.op);
      expect(b64(w.bytes), step.op).toBe(step.file);
      opened = w.opened;
      for (const d of step.openers) {
        const o = await new KeysFile(p).openFirstPin(w.bytes, keys[d], memoryGuard(), B(step.folderKeys[String(w.opened.epoch)]));
        for (const [epoch, key] of Object.entries(step.folderKeys)) expect(b64((await o.folderKey(Number(epoch)))!)).toBe(key);
      }
      const r = await new KeysFile(p).openWithRecovery(w.bytes, recovery, memoryGuard());
      for (const [epoch, key] of Object.entries(step.folderKeys)) expect(b64((await r.folderKey(Number(epoch)))!)).toBe(key);
    }
  });
});
