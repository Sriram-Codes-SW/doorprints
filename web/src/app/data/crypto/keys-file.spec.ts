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
import { chainWrapKey, HPKE_INFO, kidOf, macKey, WrapAad } from './folder-key';
import { Hpke } from './hpke';
import { bodyJson, KeysError, KeysFile, OpenedKeys } from './keys-file';
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
    await expectKind('NEW_RECOVERY_REQUIRED', () => files.newEpoch(w2.opened, t0 + 100, { revokeKid: kid(tablet) }));
    await expectKind('NEW_RECOVERY_REQUIRED', () => files.newEpoch(w2.opened, t0 + 100, { revokeKid: kid(tablet), newRecovery: recovery }));
    const r2 = RecoveryKey.generate(p);
    const w3 = await files.newEpoch(w2.opened, t0 + 100, { revokeKid: kid(tablet), newRecovery: r2 });
    expect(w3.opened.revision).toBe(1);
    await expectKind('REVOKED', async () => files.open(w3.bytes, tablet, await pinnedTo(w1)));
    await expectKind('NOT_ENROLLED', () => files.open(w3.bytes, laptop, fresh()));
    const w4 = await files.addDevice(w3.opened, kid(phone), nd(laptop, 'Laptop'), t0 + 200);
    const byLaptop = await files.openFirstPin(w4.bytes, laptop, fresh(), w4.opened.currentFolderKey());
    expect(byLaptop.epoch).toBe(2);
    expect(new TextDecoder().decode((await new Dpx(p).decryptBytes(byLaptop, 'doorprints-backup/2', old)).plaintext)).toBe('old');
    await files.open(w4.bytes, phone, await pinnedTo(w1));
    const byRecovery = await files.openWithRecovery(w4.bytes, RecoveryKey.parse(r2.display.toLowerCase()), fresh());
    expect(hex(byRecovery.currentFolderKey())).toBe(hex(byLaptop.currentFolderKey()));
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(w4.bytes, recovery, fresh()));
    const body = byLaptop.body;
    expect(revokedEpochRule(body, 1, kid(tablet), t0 + 50)).toBe('ACCEPT');
    expect(revokedEpochRule(body, 1, kid(tablet), t0 + 150)).toBe('SKIP_REVOKED_WRITER');
    expect(revokedEpochRule(body, 1, kid(phone), t0 + 150)).toBe('SKIP_OLD_EPOCH_AFTER_REVOKE');
    expect(revokedEpochRule(body, 2, kid(phone), t0 + 150)).toBe('ACCEPT');
    expect(revokedEpochRule(body, 2, p.randomBytes(16), t0)).toBe('SKIP_UNKNOWN_WRITER');
    expect(revokedEpochRule(body, 2, body.recovery!.kid, t0)).toBe('SKIP_UNKNOWN_WRITER');
    expect(revokedEpochRule(body, 3, kid(phone), t0)).toBe('NEWER_EPOCH');
    expect(revokedEpochRule(body, 1, w1.opened.body.recovery!.kid, t0 + 50)).toBe('SKIP_UNKNOWN_WRITER');
    // A new recovery key: a new anchor, the old kid revoked, the old key refused.
    const next = RecoveryKey.generate(p);
    const w5 = await files.newEpoch(w4.opened, t0 + 300, { newRecovery: next });
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(w5.bytes, r2, fresh()));
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
    await expectKind('REVISION_JUMP', () => files.open(fileOf(poisoned, mac), B, gB));
    const o = await files.openFirstPin(fileOf(poisoned, mac), B, fresh(), w1.opened.currentFolderKey());
    await expectKind('REVISION_LIMIT', async () => files.addDevice(o, kid(B), nd(await p.p256Generate(), 'x'), 5));
    expect((await files.newEpoch(o, 5, { revokeKid: kid(T) })).opened.revision).toBe(1);
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
    expect((await g.watermark())!.revision).toBe(1);
    await expectKind('ROLLED_BACK', () => files.open(w2.bytes, phone, g));
    await expectKind('ROLLED_BACK', () => files.open(w1.bytes, phone, g));
    await files.open(w3.bytes, phone, g);
    await expectKind('LAST_RECIPIENT', () => files.newEpoch(w3.opened, t0 + 3, { revokeKid: kid(phone) }));
  });
});

async function link(epoch: number, key: Uint8Array, prev: Uint8Array) {
  const nonce = p.randomBytes(12);
  return { epoch, nonce, ct: await p.aesGcmSeal(await chainWrapKey(p, key), nonce, WrapAad.chain(epoch), prev) };
}
async function signed(body: KeysBody, key: Uint8Array): Promise<Uint8Array> {
  return fileOf(body, await p.hmacSha256(await macKey(p, key), concat(utf8('doorprints-keys/1'), new Uint8Array([0]), bodyJson(body))));
}

describe('keys.json after the second review (poc2)', () => {
  it('a revoked device cannot forge for the recovery key (t2, t4)', async () => {
    const [A, T] = [await p.p256Generate(), await p.p256Generate()];
    const r1 = RecoveryKey.generate(p);
    let w = await files.createFirstDevice(nd(A, 'A'), r1, 1);
    const oldBody = files.parse(w.bytes).body;
    w = await files.newEpoch(w.opened, 2);
    w = await files.addDevice(w.opened, kid(A), nd(T, 'T'), 3);
    const k1 = (await (await files.openFirstPin(w.bytes, T, fresh(), w.opened.currentFolderKey())).folderKey(1))!;
    const r2 = RecoveryKey.generate(p);
    w = await files.newEpoch(w.opened, 4, { revokeKid: kid(T), newRecovery: r2 });
    w = await files.newEpoch(w.opened, 5);
    const genuine = files.parse(w.bytes).body;
    const F = [k1];
    for (let i = 0; i < 9; i++) F.push(p.randomBytes(32));
    const chain = [];
    for (let e = 2; e <= 10; e++) chain.push(await link(e, F[e - 1], F[e - 2]));
    const hpke = new Hpke(p);
    const rewrap = async (r: NonNullable<KeysBody['recovery']>) => {
      const s2 = await hpke.seal(r.publicKey, HPKE_INFO, WrapAad.folderKey(10, r.kid), F[9]);
      return { ...r, wrap: { enc: s2.enc, ct: s2.ciphertext } };
    };
    const withOld = await signed({ revision: 1, epoch: 10, chain, devices: [], recovery: await rewrap(oldBody.recovery!), revoked: [] }, F[9]);
    const withNew = await signed({ revision: 1, epoch: 10, chain, devices: [], recovery: await rewrap(genuine.recovery!), revoked: [] }, F[9]);
    await expectKind('RECOVERY_ANCHOR_INVALID', () => files.openWithRecovery(withNew, r2, fresh()));
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(withOld, r2, fresh()));
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(w.bytes, r1, fresh()));
    await files.openWithRecovery(w.bytes, r2, fresh());
    await expectKind('NEW_RECOVERY_REQUIRED', () => files.newEpoch(w.opened, 6, { newRecovery: r2 }));
    const r3 = RecoveryKey.generate(p);
    await files.newEpoch(w.opened, 6, { newRecovery: r3 });
    await expectKind('RECOVERY_MISMATCH', () => files.openWithRecovery(withNew, r3, fresh()));
  });

  it('a poisoned revision at the current epoch never blocks the revoke (t3)', async () => {
    const [A, T] = [await p.p256Generate(), await p.p256Generate()];
    const w1 = await files.createFirstDevice(nd(A, 'A'), RecoveryKey.generate(p), 1);
    const gA = await pinnedTo(w1);
    const w2 = await files.addDevice(w1.opened, kid(A), nd(T, 'Thief'), 2);
    await gA.acceptWritten(w2);
    const t = await files.openFirstPin(w2.bytes, T, fresh(), w1.opened.currentFolderKey());
    const poisoned = await signed({ ...t.body, revision: Number.MAX_SAFE_INTEGER }, t.currentFolderKey());
    await expectKind('REVISION_JUMP', () => files.open(poisoned, A, gA));
    const o = await files.openFirstPin(poisoned, A, fresh(), w1.opened.currentFolderKey());
    const revoke = await files.newEpoch(o, 3, { revokeKid: kid(T), newRecovery: RecoveryKey.generate(p) });
    await gA.acceptWritten(revoke);
    expect((await gA.watermark())!.epoch).toBe(2);
  });

  it('repins only on the enrolment key or the recovery anchor', async () => {
    const [phone, a, b] = [await p.p256Generate(), await p.p256Generate(), await p.p256Generate()];
    const recovery = RecoveryKey.generate(p);
    const w1 = await files.createFirstDevice(nd(phone, 'P'), recovery, t0);
    const wa = await files.addDevice(w1.opened, kid(phone), nd(a, 'A'), t0 + 1);
    const wb = await files.addDevice(w1.opened, kid(phone), nd(b, 'B'), t0 + 1);
    const g = await pinnedTo(w1);
    await files.open(wa.bytes, phone, g);
    await expectKind('FORK_DETECTED', () => files.open(wb.bytes, phone, g));
    await expectKind('PIN_MISMATCH', () => files.repinFirstPin(wb.bytes, phone, g, p.randomBytes(32)));
    await files.repinFirstPin(wb.bytes, phone, g, w1.opened.currentFolderKey());
    await files.open(wb.bytes, phone, g);
    const h = await pinnedTo(w1);
    await files.open((await files.newEpoch(w1.opened, t0 + 2)).bytes, phone, h);
    await expectKind('ROLLED_BACK', () => files.open(wb.bytes, phone, h));
    await files.repinWithRecovery(wb.bytes, recovery, h);
    expect((await h.watermark())!.epoch).toBe(1);
  });

  it('keeps the pin out of reach of app code', async () => {
    const g = fresh();
    const w = await files.createFirstDevice(nd(await p.p256Generate(), 'P'), null, t0);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    await expectKind('INVALID_ENTRY', () => (g as any).accept(Symbol('keys-file'), w.opened, 'CREATED'));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(() => new (OpenedKeys as any)(Symbol('keys-file'), p, w.opened.body, new Uint8Array(32))).toThrow(KeysError);
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
