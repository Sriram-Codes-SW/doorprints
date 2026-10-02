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
import { b64, concat, hex, utf8 } from './bytes';
import { CanonicalJson } from './canonical-json';
import { WebCryptoProvider } from './crypto-provider';
import type { P256PrivateKey } from './crypto-provider';
import { Dpx } from './dpx';
import { HPKE_INFO, kidOf, macKey, WrapAad } from './folder-key';
import { Hpke } from './hpke';
import { bodyJson, KeysError, KeysFile } from './keys-file';
import type { KeysBody, KeysErrorKind, WrittenKeys } from './keys-file';
import { KeysGuard, revokedEpochRule, sameWatermark } from './keys-guard';
import type { KeysWatermark, KeysWatermarkStore } from './keys-guard';
import { isValidScalar, reduceToScalar } from './p256-scalar';
import { RecoveryKey, RecoveryKeyError } from './recovery-key';

/**
 * `keys.json`, the pin, the recovery anchor, the revoked-epoch rule and the recovery key on the website (TC-U-130,
 * TC-U-127), and the review's proofs of concept turned into tests (forge.ts, poison.ts; TC-U-132).
 */
const p = new WebCryptoProvider();
const files = new KeysFile(p);
const t0 = 1_790_000_000_000;

class MemoryStore implements KeysWatermarkStore {
  value: KeysWatermark | null = null;
  async load() {
    return this.value;
  }
  async compareAndSet(expected: KeysWatermark | null, next: KeysWatermark) {
    if (!sameWatermark(this.value, expected)) return false;
    this.value = next;
    return true;
  }
}
const fresh = () => new KeysGuard(p, new MemoryStore());
const pinnedTo = async (w: WrittenKeys) => {
  const g = fresh();
  await g.pinCreated(w);
  return g;
};
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

function fileOf(body: KeysBody, mac: Uint8Array): Uint8Array {
  return concat(new CanonicalJson().raw('{"format":"doorprints-keys/1","body":').bytes(), bodyJson(body), new CanonicalJson().raw(',"mac":').string(b64(mac)).raw('}').bytes());
}

/** forge.ts: the attacker's own folder key, re-wrapped to every listed key (the recovery anchor kept), MACed. */
async function forge(genuine: Uint8Array, nextEpoch: boolean): Promise<Uint8Array> {
  const { body } = files.parse(genuine);
  const evil = await p.p256Generate();
  const F = p.randomBytes(32);
  const epoch = nextEpoch ? body.epoch + 1 : body.epoch;
  const hpke = new Hpke(p);
  const wrap = async (pub: Uint8Array, k: Uint8Array) => {
    const s = await hpke.seal(pub, HPKE_INFO, WrapAad.folderKey(epoch, k), F);
    return { enc: s.enc, ct: s.ciphertext };
  };
  const devices = [];
  for (const d of body.devices) devices.push({ ...d, wrap: await wrap(d.publicKey, d.kid) });
  const ek = kidOf(p, evil.publicKey);
  devices.push({ kid: ek, name: 'Pixel 8', platform: 'android' as const, publicKey: evil.publicKey, enrolledAt: 3, enrolledBy: body.devices[0].kid, wrap: await wrap(evil.publicKey, ek) });
  const recovery = body.recovery ? { ...body.recovery, wrap: await wrap(body.recovery.publicKey, body.recovery.kid) } : null;
  const chain = nextEpoch ? [...body.chain, { epoch, nonce: p.randomBytes(12), ct: p.randomBytes(48) }] : body.chain;
  const forged: KeysBody = { revision: body.revision + 1, epoch, chain, devices, recovery, revoked: body.revoked };
  const mac = await p.hmacSha256(await macKey(p, F), concat(utf8('doorprints-keys/1'), new Uint8Array([0]), bodyJson(forged)));
  return fileOf(forged, mac);
}

describe('keys.json', () => {
  it('creates, adds, revokes with a chained epoch, and opens old files', async () => {
    const [phone, tablet, laptop] = [await p.p256Generate(), await p.p256Generate(), await p.p256Generate()];
    const recovery = RecoveryKey.generate(p);
    const w1 = await files.createFirstDevice(nd(phone, 'Pixel 8'), recovery, t0);
    const old = (await new Dpx(p).encryptBytes(w1.opened.currentFolderKey(), 1, kid(phone), 'doorprints-backup/2', utf8('old'))).file;
    const w2 = await files.addDevice(w1.opened, kid(phone), nd(tablet, 'Tab'), t0 + 1);
    expect(hex((await files.openFirstPin(w2.bytes, tablet, fresh(), w1.opened.currentFolderKey())).currentFolderKey())).toBe(hex(w1.opened.currentFolderKey()));
    await expectKind('ALREADY_ENROLLED', () => files.addDevice(w2.opened, kid(phone), nd(tablet, 'again'), t0 + 2));
    await expectKind('NOT_LISTED', () => files.addDevice(w2.opened, kid(laptop), nd(laptop, 'x'), t0 + 2));
    const w3 = await files.newEpoch(w2.opened, t0 + 100, { revokeKid: kid(tablet) });
    await expectKind('REVOKED', async () => files.open(w3.bytes, tablet, await pinnedTo(w1)));
    await expectKind('NOT_ENROLLED', () => files.open(w3.bytes, laptop, fresh()));
    const w4 = await files.addDevice(w3.opened, kid(phone), nd(laptop, 'Laptop'), t0 + 200);
    const byLaptop = await files.openFirstPin(w4.bytes, laptop, fresh(), w4.opened.currentFolderKey());
    expect(byLaptop.epoch).toBe(2);
    expect(new TextDecoder().decode((await new Dpx(p).decryptBytes(byLaptop, 'doorprints-backup/2', old)).plaintext)).toBe('old');
    await files.open(w4.bytes, phone, await pinnedTo(w1));
    const byRecovery = await files.openWithRecovery(w4.bytes, RecoveryKey.parse(recovery.display.toLowerCase()), fresh());
    expect(hex(byRecovery.currentFolderKey())).toBe(hex(byLaptop.currentFolderKey()));
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(w4.bytes, RecoveryKey.generate(p), fresh()));
    const body = byLaptop.body;
    expect(revokedEpochRule(body, 1, kid(tablet), t0 + 50)).toBe('ACCEPT');
    expect(revokedEpochRule(body, 1, kid(tablet), t0 + 150)).toBe('SKIP_REVOKED_WRITER');
    expect(revokedEpochRule(body, 1, kid(phone), t0 + 150)).toBe('SKIP_OLD_EPOCH_AFTER_REVOKE');
    expect(revokedEpochRule(body, 2, kid(phone), t0 + 150)).toBe('ACCEPT');
    expect(revokedEpochRule(body, 2, p.randomBytes(16), t0)).toBe('SKIP_UNKNOWN_WRITER');
    expect(revokedEpochRule(body, 2, body.recovery!.kid, t0)).toBe('SKIP_UNKNOWN_WRITER');
    expect(revokedEpochRule(body, 3, kid(phone), t0)).toBe('NEWER_EPOCH');
    // A new recovery key: a new anchor, the old kid revoked, the old key refused.
    const next = RecoveryKey.generate(p);
    const w5 = await files.newEpoch(w4.opened, t0 + 300, { newRecovery: next });
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(w5.bytes, recovery, fresh()));
    expect((await files.openWithRecovery(w5.bytes, next, fresh())).body.recovery!.anchorEpoch).toBe(3);
  });

  it('opens nothing without a pin but by the named paths', async () => {
    const phone = await p.p256Generate();
    const w = await files.createFirstDevice(nd(phone, 'Pixel 8'), RecoveryKey.generate(p), t0);
    await expectKind('NOT_PINNED', () => files.open(w.bytes, phone, fresh()));
    await expectKind('PIN_MISMATCH', () => files.openFirstPin(w.bytes, phone, fresh(), p.randomBytes(32)));
    const g = fresh();
    await files.openFirstPin(w.bytes, phone, g, w.opened.currentFolderKey());
    await files.open(w.bytes, phone, g);
  });

  it('refuses a re-wrapped list at the same epoch and the next, on the device and the recovery path (forge.ts)', async () => {
    const [phone, tablet] = [await p.p256Generate(), await p.p256Generate()];
    const recovery = RecoveryKey.generate(p);
    const w1 = await files.createFirstDevice(nd(phone, 'A'), recovery, t0);
    const w2 = await files.addDevice(w1.opened, kid(phone), nd(tablet, 'B'), t0 + 1);
    const tabletGuard = fresh();
    await files.openFirstPin(w2.bytes, tablet, tabletGuard, w1.opened.currentFolderKey());
    const same = await forge(w2.bytes, false);
    await expectKind('FORK_DETECTED', () => files.open(same, tablet, tabletGuard));
    await expectKind('RECOVERY_ANCHOR_INVALID', () => files.openWithRecovery(same, recovery, fresh()));
    const next = await forge(w2.bytes, true);
    await expectKind('PIN_MISMATCH', () => files.open(next, tablet, tabletGuard));
    await expectKind('RECOVERY_ANCHOR_INVALID', () => files.openWithRecovery(next, recovery, tabletGuard));
    await files.open(w2.bytes, tablet, tabletGuard);
  });

  it('lets the genuine revoke win over a huge revision under the old epoch (poison.ts)', async () => {
    const [A, B, T] = [await p.p256Generate(), await p.p256Generate(), await p.p256Generate()];
    const w1 = await files.createFirstDevice(nd(A, 'A'), null, 1);
    const w2 = await files.addDevice(w1.opened, kid(A), nd(B, 'B'), 2);
    const w3 = await files.addDevice(w2.opened, kid(A), nd(T, 'Thief'), 3);
    const gB = fresh();
    await files.openFirstPin(w3.bytes, B, gB, w1.opened.currentFolderKey());
    const thief = await files.openFirstPin(w3.bytes, T, fresh(), w1.opened.currentFolderKey());
    const revoke = await files.newEpoch(w3.opened, 4, { revokeKid: kid(T) });
    const poisoned: KeysBody = { ...thief.body, revision: Number.MAX_SAFE_INTEGER };
    const mac = await p.hmacSha256(await macKey(p, thief.currentFolderKey()), concat(utf8('doorprints-keys/1'), new Uint8Array([0]), bodyJson(poisoned)));
    const o = await files.open(fileOf(poisoned, mac), B, gB);
    await expectKind('REVISION_LIMIT', async () => files.addDevice(o, kid(B), nd(await p.p256Generate(), 'x'), 5));
    await files.open(revoke.bytes, B, gB);
    expect((await gB.watermark())!.epoch).toBe(2);
    await expectKind('ROLLED_BACK', () => files.open(fileOf(poisoned, mac), B, gB));
  });

  it('detects two writers at once, and the store compare-and-set', async () => {
    const [phone, a, b] = [await p.p256Generate(), await p.p256Generate(), await p.p256Generate()];
    const w1 = await files.createFirstDevice(nd(phone, 'P'), null, t0);
    const wa = await files.addDevice(w1.opened, kid(phone), nd(a, 'A'), t0 + 1);
    const wb = await files.addDevice(w1.opened, kid(phone), nd(b, 'B'), t0 + 1);
    const g = await pinnedTo(w1);
    await g.acceptWritten(wa);
    await expectKind('FORK_DETECTED', () => files.open(wb.bytes, phone, g));
    const e1 = await files.newEpoch(w1.opened, t0 + 2);
    const e2 = await files.newEpoch(w1.opened, t0 + 2);
    const h = await pinnedTo(w1);
    await files.open(e1.bytes, phone, h);
    await expectKind('FORK_DETECTED', () => files.open(e2.bytes, phone, h));
    const inner = new MemoryStore();
    await new KeysGuard(p, inner).pinCreated(w1);
    const never: KeysWatermarkStore = { load: () => inner.load(), compareAndSet: async () => false };
    await expectKind('CONCURRENT_UPDATE', () => files.open(wa.bytes, phone, new KeysGuard(p, never)));
  });

  it('refuses a changed list (MAC), a rolled-back list and non-canonical bytes', async () => {
    const phone = await p.p256Generate();
    const tablet = await p.p256Generate();
    const w1 = await files.createFirstDevice(nd(phone, 'Pixel 8'), null, t0);
    await expectKind('NO_RECOVERY', () => files.openWithRecovery(w1.bytes, RecoveryKey.generate(p), fresh()));
    const text = new TextDecoder().decode(w1.bytes);
    const g0 = await pinnedTo(w1);
    await expectKind('MAC_INVALID', () => files.open(utf8(text.replace('"Pixel 8"', '"Pixel 9"')), phone, g0));
    await expectKind('NOT_CANONICAL', () => files.open(utf8(' ' + text), phone, g0));
    await expectKind('NOT_CANONICAL', () => files.open(utf8(text.replace('"revision":1', '"revision":1.0')), phone, g0));
    await expectKind('MALFORMED', () => files.open(utf8(text.replace('"revoked":[]', '"revoked":[],"totp":null')), phone, g0));
    await expectKind('UNSUPPORTED_FORMAT', () => files.open(utf8(text.replace('doorprints-keys/1', 'doorprints-keys/2')), phone, g0));
    await expectKind('INVALID_ENTRY', () => files.open(utf8(text.replace(/"kid":"[^"]+"/, `"kid":"${b64(new Uint8Array(16))}"`)), phone, g0));
    const w2 = await files.addDevice(w1.opened, kid(phone), nd(tablet, 'Tab'), t0 + 1);
    const unknownBy = new TextDecoder().decode(w2.bytes).replace(`"enrolledBy":"${b64(kid(phone))}"`, `"enrolledBy":"${b64(new Uint8Array(16).fill(9))}"`);
    await expectKind('INVALID_ENTRY', () => files.open(utf8(unknownBy), phone, g0));
    const w3 = await files.newEpoch(w2.opened, t0 + 2, { revokeKid: kid(tablet) });
    const g = await pinnedTo(w1);
    await files.open(w3.bytes, phone, g);
    expect((await g.watermark())!.revision).toBe(3);
    await expectKind('ROLLED_BACK', () => files.open(w2.bytes, phone, g));
    await expectKind('ROLLED_BACK', () => files.open(w1.bytes, phone, g));
    await files.open(w3.bytes, phone, g);
    await expectKind('LAST_RECIPIENT', () => files.newEpoch(w3.opened, t0 + 3, { revokeKid: kid(phone) }));
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
