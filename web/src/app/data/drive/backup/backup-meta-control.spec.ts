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
import { b64, hex } from '../../crypto/bytes';
import { WebCryptoProvider } from '../../crypto/crypto-provider';
import { kidOf } from '../../crypto/folder-key';
import { KeysFile } from '../../crypto/keys-file';
import { KeysGuard } from '../../crypto/keys-guard';
import { RecoveryKey } from '../../crypto/recovery-key';
import { DRIVE_LAYOUT } from '../drive-client';
import type { DriveFile } from '../drive-client';
import { backupName } from './backup-names';
import { BACKUP_META, BackupMeta, backupMetaKey, controlKey } from './backup-meta';
import { CONTROL_FORMAT, ControlError, ControlFile, controlBodyJson } from './control-file';
import type { ControlErrorKind } from './control-file';
import { MemoryControlStore, MemoryKeysStore } from './backup-test-rig';

/** The backup metadata MAC and `doorprints.json` (MAC, canonical form, rollback watermark): S4b-BL-116, TC-U-133. */
const p = new WebCryptoProvider();
const keysFile = new KeysFile(p);
const control = new ControlFile(p);
const t0 = 1_790_000_000_000;

async function setup() {
  const phone = await p.p256Generate();
  const created = await keysFile.createFirstDevice({ publicKey: phone.publicKey, name: 'Pixel 8', platform: 'web' }, RecoveryKey.generate(p), t0);
  return { phone, created, kid: kidOf(p, phone.publicKey), sha: p.randomBytes(32) };
}

async function macOf(m: BackupMeta, created: Awaited<ReturnType<typeof setup>>['created']) {
  return m.mac(p, created.opened.currentFolderKey());
}

function fileOf(m: BackupMeta, mac: Uint8Array, extra: Record<string, string> = {}, sum: string | null = hex(m.ciphertextSha256)): DriveFile {
  return {
    id: 'f1', name: 'x', mimeType: 'application/octet-stream', parents: ['b'], appProperties: { ...m.appProperties(mac, 'dev0123456789abc'), ...extra },
    size: 10, sha256Checksum: sum, createdTime: 0, modifiedTime: 0, trashed: false, headRevisionId: null,
  };
}

async function controlKind(kind: ControlErrorKind, f: () => Promise<unknown>) {
  try {
    await f();
  } catch (e) {
    expect(e).toBeInstanceOf(ControlError);
    expect((e as ControlError).kind, (e as Error).message).toBe(kind);
    return;
  }
  throw new Error(`expected ${kind}`);
}

describe('BackupMeta', () => {
  it('round trips through appProperties', async () => {
    const s = await setup();
    const m = new BackupMeta(t0, 12, 1, s.kid, s.sha);
    const mac = await macOf(m, s.created);
    const read = BackupMeta.read(fileOf(m, mac))!;
    expect(read.meta.createdAt).toBe(t0);
    expect(read.meta.houses).toBe(12);
    expect(read.meta.epoch).toBe(1);
    expect(hex(read.meta.writerKid)).toBe(hex(s.kid));
    expect(hex(read.meta.ciphertextSha256)).toBe(hex(s.sha));
    expect(await BackupMeta.verify(p, s.created.opened, read.meta, read.mac)).toBe(true);
    for (const [k, v] of Object.entries(m.appProperties(mac, 'dev0123456789abc'))) expect(k.length + v.length).toBeLessThanOrEqual(DRIVE_LAYOUT.maxPropertyBytes);
  });

  it('covers every field and the file hash', async () => {
    const s = await setup();
    const m = new BackupMeta(t0, 12, 1, s.kid, s.sha);
    const mac = await macOf(m, s.created);
    const flip = (b: Uint8Array) => b.map((x, i) => (i === 0 ? x ^ 1 : x));
    const others = [
      new BackupMeta(t0 + 1, 12, 1, s.kid, s.sha), new BackupMeta(t0, 13, 1, s.kid, s.sha), new BackupMeta(t0, 12, 2, s.kid, s.sha),
      new BackupMeta(t0, 12, 1, flip(s.kid), s.sha), new BackupMeta(t0, 12, 1, s.kid, flip(s.sha)),
    ];
    for (const o of others) expect(hex(await macOf(o, s.created))).not.toBe(hex(mac));
    // Another folder's key set, and an epoch this device has no key for: false, not an exception.
    const other = await keysFile.createFirstDevice({ publicKey: s.phone.publicKey, name: 'Pixel 8', platform: 'web' }, null, t0);
    expect(await BackupMeta.verify(p, other.opened, m, mac)).toBe(false);
    expect(await BackupMeta.verify(p, s.created.opened, new BackupMeta(t0, 12, 9, s.kid, s.sha), mac)).toBe(false);
  });

  it('uses keys that are not the folder key nor each other', async () => {
    const s = await setup();
    const f = s.created.opened.currentFolderKey();
    expect(hex(await backupMetaKey(p, f))).not.toBe(hex(f));
    expect(hex(await backupMetaKey(p, f))).not.toBe(hex(await controlKey(p, f)));
  });

  it('treats non-canonical or missing metadata as not a backup', async () => {
    const s = await setup();
    const m = new BackupMeta(t0, 12, 1, s.kid, s.sha);
    const mac = await macOf(m, s.created);
    const bad = (extra: Record<string, string>) => expect(BackupMeta.read(fileOf(m, mac, extra)), JSON.stringify(extra)).toBeNull();
    bad({ createdAt: '0123' });
    bad({ createdAt: '-5' });
    bad({ createdAt: '1e3' });
    bad({ createdAt: '9007199254740992' });
    bad({ houses: '+3' });
    bad({ houses: '10000001' });
    bad({ epoch: '0' });
    bad({ kid: b64(new Uint8Array(15)) });
    bad({ kid: b64(s.kid).replace(/=+$/, '') });
    bad({ bm: b64(new Uint8Array(31)) });
    bad({ kind: 'sync' });
    expect(BackupMeta.read(fileOf(m, mac, {}, null))).toBeNull();
    expect(BackupMeta.read(fileOf(m, mac, {}, 'zz'))).toBeNull();
    expect(BackupMeta.read({ ...fileOf(m, mac), appProperties: {} })).toBeNull();
    expect(BackupMeta.read(fileOf(m, mac, {}, hex(s.sha).toUpperCase()))).not.toBeNull();
    expect(BACKUP_META.mac).toBe('bm');
  });

  it('names files in local time', () => {
    expect(backupName(1_790_913_600_000, 330)).toBe('Doorprints-backup-2026-10-02-0930.dpx');
    expect(backupName(1_790_913_600_000, 0)).toBe('Doorprints-backup-2026-10-02-0400.dpx');
    expect(backupName(1_790_913_600_000, -300)).toBe('Doorprints-backup-2026-10-01-2300.dpx');
  });
});

describe('ControlFile', () => {
  it('round trips and moves the watermark', async () => {
    const { created } = await setup();
    const store = new MemoryControlStore();
    const w1 = await control.create(created.opened, t0);
    const b1 = await control.open(w1.bytes, created.opened, store);
    expect(b1.revision).toBe(1);
    expect(b1.encryption).toBe('dpx/1');
    expect(b1.backupsDeletedAt).toBeNull();
    expect(store.value!.revision).toBe(1);
    const w2 = await control.next(created.opened, b1, t0 + 5);
    const b2 = await control.open(w2.bytes, created.opened, store);
    expect(b2.revision).toBe(2);
    expect(b2.backupsDeletedAt).toBe(t0 + 5);
    expect(store.value!.backupsDeletedAt).toBe(t0 + 5);
    await control.open(w2.bytes, created.opened, store);
    expect(b2.createdAt).toBe(t0);
    // next() keeps the mark when none is given.
    const w3 = await control.next(created.opened, b2);
    expect(w3.body.backupsDeletedAt).toBe(t0 + 5);
  });

  it('refuses a rolled back file', async () => {
    const { created } = await setup();
    const store = new MemoryControlStore();
    const w1 = await control.create(created.opened, t0);
    const w2 = await control.next(created.opened, w1.body, t0 + 5);
    await control.open(w2.bytes, created.opened, store);
    await controlKind('ROLLED_BACK', () => control.open(w1.bytes, created.opened, store));
    expect(store.value!.revision).toBe(2);
  });

  it('calls the same revision with another body a fork', async () => {
    const { created } = await setup();
    const store = new MemoryControlStore();
    const w1 = await control.create(created.opened, t0);
    const a = await control.next(created.opened, w1.body, t0 + 5);
    const b = await control.next(created.opened, w1.body, t0 + 6);
    await control.open(a.bytes, created.opened, store);
    await controlKind('FORK_DETECTED', () => control.open(b.bytes, created.opened, store));
  });

  it('refuses a forged or edited file', async () => {
    const { created, phone } = await setup();
    const store = new MemoryControlStore();
    const w1 = await control.create(created.opened, t0);
    const foreign = await keysFile.createFirstDevice({ publicKey: phone.publicKey, name: 'Pixel 8', platform: 'web' }, null, t0);
    await controlKind('MAC_INVALID', async () => control.open((await control.create(foreign.opened, t0)).bytes, created.opened, store));
    const text = new TextDecoder().decode(w1.bytes);
    const enc = (s: string) => new TextEncoder().encode(s);
    await controlKind('MAC_INVALID', () => control.open(enc(text.replace('"backupsDeletedAt":null', '"backupsDeletedAt":99')), created.opened, store));
    await controlKind('MAC_INVALID', () => control.open(enc(text.replace('dpx/1', 'none/')), created.opened, store));
    await controlKind('UNKNOWN_EPOCH', () => control.open(enc(text.replace('"epoch":1', '"epoch":4')), created.opened, store));
    await controlKind('NOT_CANONICAL', () => control.open(enc(text.replace('{"format"', '{ "format"')), created.opened, store));
    await controlKind('MALFORMED', () => control.open(enc('nope'), created.opened, store));
    await controlKind('UNSUPPORTED_FORMAT', () => control.open(enc(text.replace('doorprints-control/1', 'doorprints-control/2')), created.opened, store));
    expect(store.value).toBeNull();
  });

  it('refuses an unencrypted control file even when a key holder MACed it', async () => {
    const { created } = await setup();
    const key = created.opened.currentFolderKey();
    const body = { revision: 1, epoch: 1, createdAt: t0, encryption: 'none', backupsDeletedAt: null };
    const k = await controlKey(p, key);
    const input = new Uint8Array([...new TextEncoder().encode(CONTROL_FORMAT), 0, ...controlBodyJson(body)]);
    const bytes = control.encode(body, await p.hmacSha256(k, input));
    await controlKind('UNSUPPORTED_ENCRYPTION', () => control.open(bytes, created.opened, new MemoryControlStore()));
  });

  it('gives up when the watermark keeps changing', async () => {
    const { created } = await setup();
    const w1 = await control.create(created.opened, t0);
    let tries = 0;
    const racing = {
      load: async () => null,
      compareAndSet: async () => {
        tries++;
        return false;
      },
    };
    await controlKind('CONCURRENT_UPDATE', () => control.open(w1.bytes, created.opened, racing));
    expect(tries).toBe(4);
  });

  it('keeps the key list pin and the control watermark apart', async () => {
    const { created } = await setup();
    const keyStore = new MemoryKeysStore();
    await new KeysGuard(p, keyStore).pinCreated(created);
    const c = new MemoryControlStore();
    expect(c.value).toBeNull();
    await control.open((await control.create(created.opened, t0)).bytes, created.opened, c);
    expect(keyStore.value).not.toBeNull();
    expect(c.value).not.toBeNull();
  });
});
