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
import { b64, hex } from './bytes';
import { WebCryptoProvider } from './crypto-provider';
import type { P256PrivateKey } from './crypto-provider';
import { Dpx } from './dpx';
import { kidOf } from './folder-key';
import { KeysError, KeysFile } from './keys-file';
import type { KeysErrorKind } from './keys-file';
import { KeysGuard, revokedEpochRule } from './keys-guard';
import type { KeysWatermark } from './keys-guard';
import { reduceToScalar, isValidScalar } from './p256-scalar';
import { RecoveryKey, RecoveryKeyError } from './recovery-key';

/** `keys.json`, the rollback guard, the revoked-epoch rule and the recovery key on the website (TC-U-130, TC-U-127). */
const p = new WebCryptoProvider();
const files = new KeysFile(p);
const t0 = 1_790_000_000_000;

function memoryGuard(): { guard: KeysGuard; get: () => KeysWatermark | null } {
  let w: KeysWatermark | null = null;
  return { guard: new KeysGuard({ load: async () => w, save: async (x) => void (w = x) }), get: () => w };
}
const guard = () => memoryGuard().guard;
const nd = (k: P256PrivateKey, name: string) => ({ publicKey: k.publicKey, name, platform: 'web' as const });
const kid = (k: P256PrivateKey) => kidOf(p, k.publicKey);

async function expectKind(kind: KeysErrorKind, f: () => Promise<unknown>): Promise<void> {
  try {
    await f();
  } catch (e) {
    expect(e).toBeInstanceOf(KeysError);
    expect((e as KeysError).kind, (e as Error).message).toBe(kind);
    return;
  }
  throw new Error(`expected ${kind}`);
}

describe('keys.json', () => {
  it('creates, adds, revokes with a chained epoch, and opens old files', async () => {
    const [phone, tablet, laptop] = [await p.p256Generate(), await p.p256Generate(), await p.p256Generate()];
    const recovery = RecoveryKey.generate(p);
    const w1 = await files.createFirstDevice(nd(phone, 'Pixel 8'), (await recovery.keyPair(p)).publicKey, t0);
    const old = (await new Dpx(p).encryptBytes(w1.opened.currentFolderKey(), 1, kid(phone), 'doorprints-backup/2', new TextEncoder().encode('old'))).file;
    const w2 = await files.addDevice(w1.opened, kid(phone), nd(tablet, 'Tab'), t0 + 1);
    expect(hex((await files.open(w2.bytes, tablet, guard())).currentFolderKey())).toBe(hex(w1.opened.currentFolderKey()));
    await expectKind('ALREADY_ENROLLED', () => files.addDevice(w2.opened, kid(phone), nd(tablet, 'again'), t0 + 2));
    await expectKind('NOT_LISTED', () => files.addDevice(w2.opened, kid(laptop), nd(laptop, 'x'), t0 + 2));
    const w3 = await files.newEpoch(w2.opened, t0 + 100, { revokeKid: kid(tablet) });
    await expectKind('REVOKED', () => files.open(w3.bytes, tablet, guard()));
    await expectKind('NOT_ENROLLED', () => files.open(w3.bytes, laptop, guard()));
    const w4 = await files.addDevice(w3.opened, kid(phone), nd(laptop, 'Laptop'), t0 + 200);
    const byLaptop = await files.open(w4.bytes, laptop, guard());
    expect(byLaptop.epoch).toBe(2);
    expect(new TextDecoder().decode((await new Dpx(p).decryptBytes(byLaptop, 'doorprints-backup/2', old)).plaintext)).toBe('old');
    const byRecovery = await files.openWithRecovery(w4.bytes, RecoveryKey.parse(recovery.display.toLowerCase()), guard());
    expect(hex(byRecovery.currentFolderKey())).toBe(hex(byLaptop.currentFolderKey()));
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(w4.bytes, RecoveryKey.generate(p), guard()));
    const body = byLaptop.body;
    expect(revokedEpochRule(body, 1, kid(tablet), t0 + 50)).toBe('ACCEPT');
    expect(revokedEpochRule(body, 1, kid(tablet), t0 + 150)).toBe('SKIP_REVOKED_WRITER');
    expect(revokedEpochRule(body, 1, kid(phone), t0 + 150)).toBe('SKIP_OLD_EPOCH_AFTER_REVOKE');
    expect(revokedEpochRule(body, 2, kid(phone), t0 + 150)).toBe('ACCEPT');
    expect(revokedEpochRule(body, 2, p.randomBytes(16), t0)).toBe('SKIP_UNKNOWN_WRITER');
    expect(revokedEpochRule(body, 3, kid(phone), t0)).toBe('NEWER_EPOCH');
  });

  it('refuses a changed list (MAC), a rolled-back list and non-canonical bytes', async () => {
    const phone = await p.p256Generate();
    const tablet = await p.p256Generate();
    const w1 = await files.createFirstDevice(nd(phone, 'Pixel 8'), null, t0);
    await expectKind('NO_RECOVERY', () => files.openWithRecovery(w1.bytes, RecoveryKey.generate(p), guard()));
    const text = new TextDecoder().decode(w1.bytes);
    await expectKind('MAC_INVALID', () => files.open(new TextEncoder().encode(text.replace('"Pixel 8"', '"Pixel 9"')), phone, guard()));
    await expectKind('NOT_CANONICAL', () => files.open(new TextEncoder().encode(' ' + text), phone, guard()));
    await expectKind('NOT_CANONICAL', () => files.open(new TextEncoder().encode(text.replace('"revision":1', '"revision":1.0')), phone, guard()));
    await expectKind('MALFORMED', () => files.open(new TextEncoder().encode(text.replace('"revoked":[]', '"revoked":[],"totp":null')), phone, guard()));
    await expectKind('UNSUPPORTED_FORMAT', () => files.open(new TextEncoder().encode(text.replace('doorprints-keys/1', 'doorprints-keys/2')), phone, guard()));
    await expectKind('INVALID_ENTRY', () => files.open(new TextEncoder().encode(text.replace(/"kid":"[^"]+"/, `"kid":"${b64(new Uint8Array(16))}"`)), phone, guard()));
    const w2 = await files.addDevice(w1.opened, kid(phone), nd(tablet, 'Tab'), t0 + 1);
    const w3 = await files.newEpoch(w2.opened, t0 + 2, { revokeKid: kid(tablet) });
    const g = memoryGuard();
    await files.open(w3.bytes, phone, g.guard);
    expect(g.get()).toEqual({ epoch: 2, revision: 3 });
    await expectKind('ROLLED_BACK', () => files.open(w2.bytes, phone, g.guard));
    await expectKind('ROLLED_BACK', () => files.open(w1.bytes, phone, g.guard));
    await files.open(w3.bytes, phone, g.guard);
    await expectKind('LAST_RECIPIENT', () => files.newEpoch(w3.opened, t0 + 3, { revokeKid: kid(phone) }));
  });

  it('the guard only moves up', async () => {
    const g = memoryGuard();
    await g.guard.accept(1, 1);
    await g.guard.accept(2, 4);
    await expectKind('ROLLED_BACK', () => g.guard.accept(2, 3));
    await expectKind('ROLLED_BACK', () => g.guard.accept(1, 9));
    await expectKind('WATERMARK_CONFLICT', () => g.guard.accept(3, 4));
    expect(g.get()).toEqual({ epoch: 2, revision: 4 });
  });
});

describe('the recovery key and its scalar', () => {
  const N = 0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551n;
  const big = (b: Uint8Array) => b.reduce((v, x) => (v << 8n) | BigInt(x), 0n);

  it('reduces like (c mod (n − 1)) + 1', () => {
    const seeds: Uint8Array[] = [new Uint8Array(48), new Uint8Array(48).fill(255), new Uint8Array(0), new Uint8Array([1])];
    for (let i = 0; i < 300; i++) seeds.push(p.randomBytes(48));
    for (const s of seeds) {
      const d = reduceToScalar(s);
      expect(big(d)).toBe((big(s) % (N - 1n)) + 1n);
      expect(isValidScalar(d)).toBe(true);
    }
  });

  it('catches every single substitution and adjacent swap', () => {
    const alphabet = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
    for (let n = 0; n < 5; n++) {
      const s = RecoveryKey.generate(p).symbols;
      expect(hex(RecoveryKey.parse(s).bytes)).toBe(hex(RecoveryKey.parse(s.toLowerCase()).bytes));
      for (let i = 0; i < 26; i++) {
        for (const c of alphabet) {
          if (c === s[i]) continue;
          expect(() => RecoveryKey.parse(s.slice(0, i) + c + s.slice(i + 1))).toThrow(RecoveryKeyError);
        }
      }
      for (let i = 0; i < 26; i++) {
        if (s[i] === s[i + 1]) continue;
        expect(() => RecoveryKey.parse(s.slice(0, i) + s[i + 1] + s[i] + s.slice(i + 2))).toThrow(RecoveryKeyError);
      }
    }
  });
});
