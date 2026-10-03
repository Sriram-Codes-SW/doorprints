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
import { b64, equalBytes } from '../../crypto/bytes';
import { WebCryptoProvider } from '../../crypto/crypto-provider';
import { Dpx } from '../../crypto/dpx';
import { kidOf } from '../../crypto/folder-key';
import { RecoveryKey } from '../../crypto/recovery-key';
import { DRIVE_LAYOUT } from '../drive-client';
import type { DriveOp } from '../fake-drive-faults';
import { DriveFaults } from '../fake-drive-faults';
import { FakeDriveServer } from '../in-memory-fake-drive';
import { BackupMeta } from './backup-meta';
import { BACKUP_PARTIAL_PREFIX } from './backup-names';
import { selectRetention } from './backup-retention';
import type { RetentionEntry } from './backup-retention';
import { ControlFile } from './control-file';
import { Payload, RecordingStaging, Rig } from './backup-test-rig';
import type { BackupOutcome, DriveBackup, DriveProblemKind, ReadyFolder } from './drive-backup-results';

/**
 * The Drive backup service on the fake Drive (S4b-BL-116, TC-U-134; Kotlin `DriveBackupServiceTest`, the same cases):
 * the round trip, interrupted and resumed uploads, checksum mismatch, partial files, retention, the shrink guard, files
 * that are not backups, refused imports, keys and control rollback, offline. Real crypto, the fake Drive.
 */
const p = new WebCryptoProvider();
const day = 86_400_000;
const writes: DriveOp[] = ['CREATE', 'UPLOAD', 'UPLOAD_START', 'UPLOAD_CHUNK', 'UPDATE', 'DELETE', 'TRASH'];

function world() {
  const server = new FakeDriveServer();
  return { server, clock: server.clock, writeCount: () => server.requests.filter((r) => writes.includes(r.op)).length };
}

async function done(rig: Rig, folder: ReadyFolder, payload: Payload): Promise<Extract<BackupOutcome, { kind: 'done' }>> {
  const o = await rig.service.backUp(folder, payload.source());
  if (o.kind !== 'done') throw new Error(`backup failed: ${o.problem.kind}`);
  return o;
}

async function failed(rig: Rig, folder: ReadyFolder, payload: Payload) {
  const o = await rig.service.backUp(folder, payload.source());
  if (o.kind !== 'failed') throw new Error('expected a failure');
  return o.problem;
}

const imp = (rig: Rig, folder: ReadyFolder, b: DriveBackup, staging = new RecordingStaging()) => rig.service.imports.download(folder, b, staging);

function plant(rig: Rig, folderId: string, name: string, props: Record<string, string>, content: Uint8Array, state = 'complete') {
  return rig.server.putByHand({ name, mimeType: 'application/octet-stream', parents: [folderId], appProperties: { ...props, [DRIVE_LAYOUT.state]: state } }, content);
}

function fakeMeta(createdAt: number, houses: number, epoch = 1): Record<string, string> {
  return {
    kind: 'backup', createdAt: String(createdAt), houses: String(houses), epoch: String(epoch),
    kid: b64(p.randomBytes(16)), bm: b64(p.randomBytes(32)),
  };
}

const flipAt = (b: Uint8Array, at: number, mask = 1) => b.slice().map((x, i) => (i === at ? x ^ mask : x));

describe('DriveBackupService', () => {
  it('create, back up, list and import: the round trip', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const out = await a.service.createFolder(true);
    expect(out.connection.kind).toBe('READY');
    expect(out.recoveryKey).not.toBeNull();
    const folder = await a.ready();
    expect(folder.keys.epoch).toBe(1);
    expect(folder.control.revision).toBe(1);
    const payload = Payload.of(200_000, 12);
    const d = await done(a, folder, payload);
    expect(d.backup.houses).toBe(12);
    expect(d.backup.createdAt).toBe(clock.now());
    expect(d.tidy.trashed).toEqual([]);
    expect(d.tidy.problem).toBeNull();
    expect(d.missingNewer).toBe(false);
    const file = a.live()[0];
    expect(a.live().length).toBe(1);
    expect(file.name.startsWith('Doorprints-backup-') && file.name.endsWith('.dpx')).toBe(true);
    expect(file.appProperties[DRIVE_LAYOUT.state]).toBe('complete');
    const stored = server.contentOf(file.id)!;
    expect(new TextDecoder().decode(stored.slice(0, 4))).toBe('DPX1');
    expect(equalBytes(stored.slice(0, 64), payload.bytes.slice(0, 64))).toBe(false);
    const listing = await a.service.listBackups(folder);
    expect(listing.backups.map((b) => b.fileId)).toEqual([d.backup.fileId]);
    expect(listing.unfinished.length + listing.junk.length + listing.ignored.length).toBe(0);
    expect(listing.missingNewer).toBe(false);
    const staging = new RecordingStaging();
    const r = await imp(a, folder, listing.backups[0], staging);
    expect(r.kind).toBe('verified');
    if (r.kind === 'verified') {
      expect(r.format).toBe('doorprints-backup/1');
      expect(r.plaintextSize).toBe(payload.bytes.length);
    }
    expect(equalBytes(staging.bytes(), payload.bytes)).toBe(true);
    expect(staging.discards).toBe(0);
    expect(await a.service.verifyNewest(folder)).toBeNull();
    expect(a.state.value.lastVerifyAt).toBe(clock.now());
  });

  it('writes the kinds and places the deletion rules expect', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    await done(a, folder, Payload.of(3_000, 3));
    const all = server.allFiles();
    const root = all.find((f) => f.appProperties[DRIVE_LAYOUT.role] === 'root')!;
    const keys = all.find((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'keys')!;
    const control = all.find((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'control')!;
    const backup = all.find((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'backup')!;
    expect(keys.parents).toEqual([root.id]);
    expect(control.parents).toEqual([root.id]);
    expect(backup.parents).toEqual([a.backupsFolder().id]);
    expect(a.backupsFolder().parents).toEqual([root.id]);
    // Every file we made carries a kind.
    expect(all.filter((f) => f.mimeType !== 'application/vnd.google-apps.folder').every((f) => f.appProperties[DRIVE_LAYOUT.kind] != null)).toBe(true);
  });

  it('reconnects without writing', async () => {
    const { server, writeCount } = world();
    const a = await Rig.make(server);
    await a.created();
    const before = writeCount();
    expect((await a.service.connect()).kind).toBe('READY');
    expect(writeCount()).toBe(before);
  });

  it('uploads a large backup resumable and resumes an interrupted chunk', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const big = Payload.of(5_600_000, 40);
    server.faults.on('UPLOAD_CHUNK', 2, DriveFaults.dropAfter(100_000));
    const d = await done(a, folder, big);
    const ops = server.requests.map((r) => r.op);
    expect(ops).toContain('UPLOAD_START');
    expect(ops).toContain('UPLOAD_STATUS');
    expect(clock.slept.length).toBeGreaterThan(0);
    const staging = new RecordingStaging();
    expect((await imp(a, folder, d.backup, staging)).kind).toBe('verified');
    expect(equalBytes(staging.bytes(), big.bytes)).toBe(true);
    expect(a.live().length).toBe(1);
  }, 60_000);

  it('lists nothing from an upload that never finishes', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    server.faults.stopAfter(server.requests.length + 3);
    const problem = await failed(a, folder, Payload.of(5_600_000, 5));
    expect(problem.kind).toBe('OFFLINE');
    server.faults.clear();
    expect(a.live().length).toBe(0);
    expect((await a.service.listBackups(folder)).backups.length).toBe(0);
    expect(a.state.value.lastFailure).toBe('RETRYABLE');
  }, 60_000);

  it('never lists a checksum mismatch and bins the file', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    server.faults.next(DriveFaults.corruptContent, 'UPLOAD');
    expect((await failed(a, folder, Payload.of(10_000, 3))).kind).toBe('CORRUPT');
    expect(a.live().length).toBe(0);
    expect(a.binned().length).toBe(1);
    expect((await a.service.listBackups(folder)).backups.length).toBe(0);
    clock.advance(60_000);
    await done(a, folder, Payload.of(10_000, 3));
    expect((await a.service.listBackups(folder)).backups.length).toBe(1);
  });

  it('never lists a partial file and completes an unfinished genuine one at the next run', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const payload = Payload.of(30_000, 7);
    server.faults.always(DriveFaults.offline, 'UPDATE');
    expect((await failed(a, folder, payload)).kind).toBe('OFFLINE');
    server.faults.clear();
    const partial = a.live()[0];
    expect(a.live().length).toBe(1);
    expect(partial.appProperties[DRIVE_LAYOUT.state]).toBe('partial');
    expect(partial.name.startsWith(BACKUP_PARTIAL_PREFIX)).toBe(true);
    const listing = await a.service.listBackups(folder);
    expect(listing.backups.length).toBe(0);
    expect(listing.unfinished.map((b) => b.fileId)).toEqual([partial.id]);
    clock.advance(day);
    const d = await done(a, folder, Payload.of(30_000, 7, 1));
    expect(d.tidy.completed).toEqual([partial.id]);
    expect(d.tidy.trashed).toEqual([]);
    const after = await a.service.listBackups(folder);
    expect(after.backups.length).toBe(2);
    expect(a.live().every((f) => !f.name.startsWith(BACKUP_PARTIAL_PREFIX))).toBe(true);
    const staging = new RecordingStaging();
    expect((await imp(a, folder, after.backups[after.backups.length - 1], staging)).kind).toBe('verified');
    expect(equalBytes(staging.bytes(), payload.bytes)).toBe(true);
  });

  it('bins a partial file that does not verify only after a day', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const planted = server.putByHand(
      { name: 'partial-Doorprints-backup-x.dpx', mimeType: 'application/octet-stream', parents: [a.backupsFolder().id], appProperties: { kind: 'backup', state: 'partial' } },
      new Uint8Array(10).fill(1),
    );
    const first = await done(a, folder, Payload.of(5_000, 2));
    expect(first.tidy.trashed).toEqual([]);
    expect((await a.service.listBackups(folder)).junk.map((f) => f.id)).toEqual([planted.id]);
    clock.advance(25 * 3_600_000);
    const second = await done(a, folder, Payload.of(5_000, 2, 3));
    expect(second.tidy.trashed).toEqual([planted.id]);
    expect(a.binned().some((f) => f.id === planted.id)).toBe(true);
    expect((await a.service.listBackups(folder)).backups.length).toBe(2);
  });

  // ---- retention ----

  it('keeps exactly the set the retention rule says', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const created: RetentionEntry[] = [];
    for (let i = 0; i < 40; i++) {
      const d = await done(a, folder, Payload.of(2_000, 20, i));
      created.push({ id: d.backup.fileId, createdAt: d.backup.createdAt, houses: 20 });
      const expected = selectRetention(created.filter((e) => server.fileOrNull(e.id)?.trashed !== true), 330);
      const live = a.live().map((f) => f.id).sort();
      expect(live, `run ${i}`).toEqual([...expected.keep].sort());
      expect(live.length).toBeLessThanOrEqual(17);
      clock.advance(day);
    }
    expect(a.binned().length).toBeGreaterThan(0);
    expect(a.live().length + a.binned().length).toBe(created.length);
    const liveIds = new Set(a.live().map((f) => f.id));
    expect(created.slice(-7).every((e) => liveIds.has(e.id))).toBe(true);
  }, 120_000);

  it('holds pruning at the shrink guard until the person confirms', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    for (let i = 0; i < 14; i++) {
      await done(a, folder, Payload.of(2_000, 40, i));
      clock.advance(day);
    }
    const steady = a.live().length;
    expect(a.binned().length).toBeGreaterThan(0);
    const small = await done(a, folder, Payload.of(2_000, 3, 99));
    expect(small.tidy.hold).toEqual({ backupId: small.backup.fileId, houses: 3, previousId: small.tidy.hold!.previousId, previousHouses: 40 });
    expect(small.tidy.trashed).toEqual([]);
    expect(a.live().length).toBe(steady + 1);
    clock.advance(day);
    const again = await done(a, folder, Payload.of(2_000, 3, 98));
    expect(again.tidy.hold).not.toBeNull();
    expect(again.tidy.trashed).toEqual([]);
    await a.service.confirmShrink(small.backup.fileId);
    await a.service.confirmShrink(again.backup.fileId);
    clock.advance(day);
    const after = await done(a, folder, Payload.of(2_000, 3, 97));
    expect(after.tidy.hold).toBeNull();
    expect(after.tidy.trashed.length).toBeGreaterThan(0);
  }, 60_000);

  // ---- files that are not backups ----

  it('does not count plain, planted or tiny files as backups', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const good = await done(a, folder, Payload.of(4_000, 10));
    const backups = a.backupsFolder().id;
    clock.advance(day);
    const zip = server.putByHand({ name: 'Doorprints-backup-2030-01-01-0000.dpx', mimeType: 'application/zip', parents: [backups] }, new Uint8Array([0x50, 0x4b, 3, 4]));
    const forged = plant(a, backups, 'Doorprints-backup-2030-01-01-0001.dpx', fakeMeta(clock.now() + 10 * day, 999), p.randomBytes(500));
    const partialForged = plant(a, backups, 'partial-x.dpx', fakeMeta(clock.now() + 11 * day, 999), p.randomBytes(500), 'partial');
    const noProps = plant(a, backups, 'y.dpx', { kind: 'backup' }, p.randomBytes(500));
    const tiny = plant(a, backups, 'tiny.dpx', fakeMeta(clock.now() + 12 * day, 0), p.randomBytes(500));
    const listing = await a.service.listBackups(folder);
    expect(listing.backups.map((b) => b.fileId)).toEqual([good.backup.fileId]);
    const reasons = new Map(listing.ignored);
    expect(reasons.get(forged.id)).toBe('MAC_INVALID');
    expect(reasons.get(tiny.id)).toBe('MAC_INVALID');
    expect(reasons.get(noProps.id)).toBe('NOT_A_BACKUP');
    expect(reasons.has(zip.id)).toBe(false);
    expect(listing.junk.map((f) => f.id)).toEqual([partialForged.id]);
    const next = await done(a, folder, Payload.of(4_000, 10, 5));
    expect(next.tidy.hold).toBeNull();
    expect((await a.service.listBackups(folder)).backups.length).toBe(2);
  });

  it('breaks the MAC when an old backup is made to look new or its numbers are moved', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const old = await done(a, folder, Payload.of(4_000, 10, 1));
    clock.advance(day);
    const newer = await done(a, folder, Payload.of(4_000, 2, 2));
    await a.drive.updateMetadata(old.backup.fileId, { appProperties: { createdAt: String(clock.now() + day) } });
    let listing = await a.service.listBackups(folder);
    expect(listing.backups.map((b) => b.fileId)).toEqual([newer.backup.fileId]);
    expect(new Map(listing.ignored).get(old.backup.fileId)).toBe('MAC_INVALID');
    const oldProps = server.fileOrNull(old.backup.fileId)!.appProperties;
    await a.drive.updateMetadata(old.backup.fileId, { appProperties: { createdAt: String(old.backup.createdAt) } });
    await a.drive.updateMetadata(newer.backup.fileId, {
      appProperties: { houses: oldProps['houses'], bm: oldProps['bm'], kid: oldProps['kid'], createdAt: String(old.backup.createdAt) },
    });
    listing = await a.service.listBackups(folder);
    expect(listing.backups.map((b) => b.fileId)).toEqual([old.backup.fileId]);
    expect(new Map(listing.ignored).get(newer.backup.fileId)).toBe('MAC_INVALID');
  });

  it('treats a copy of a genuine backup as a duplicate, not a new backup', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const b = await done(a, folder, Payload.of(4_000, 10));
    clock.advance(day);
    const src = server.fileOrNull(b.backup.fileId)!;
    const copy = server.putByHand({ name: 'copy.dpx', mimeType: 'application/octet-stream', parents: [a.backupsFolder().id], appProperties: src.appProperties }, server.contentOf(src.id));
    const listing = await a.service.listBackups(folder);
    expect(listing.backups.map((x) => x.fileId)).toEqual([b.backup.fileId]);
    expect(listing.duplicates).toEqual([copy.id]);
    expect(listing.backups[0].createdAt).toBe(b.backup.createdAt);
    const next = await done(a, folder, Payload.of(4_000, 10, 4));
    expect(next.tidy.trashed).toContain(copy.id);
    expect(a.live().some((f) => f.id === copy.id)).toBe(false);
  });

  it('ignores backups from before Delete all backups and reports a missing newest', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const b1 = await done(a, folder, Payload.of(3_000, 5, 1));
    clock.advance(day);
    const b2 = await done(a, folder, Payload.of(3_000, 5, 2));
    const next = await new ControlFile(p).next(folder.keys, folder.control, b1.backup.createdAt);
    server.editByHand(folder.controlId, next.bytes);
    const folder2 = await a.ready();
    const listing = await a.service.listBackups(folder2);
    expect(listing.backups.map((b) => b.fileId)).toEqual([b2.backup.fileId]);
    expect(new Map(listing.ignored).get(b1.backup.fileId)).toBe('DELETED_BEFORE');
    server.deleteByHand(b2.backup.fileId);
    const after = await a.service.listBackups(folder2);
    expect(after.backups.length).toBe(0);
    expect(after.missingNewer).toBe(true);
  });

  // ---- refused imports ----

  it('refuses a backup edited after the listing and discards the staging', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const b = await done(a, folder, Payload.of(40_000, 10));
    const listed = (await a.service.listBackups(folder)).backups[0];
    const bytes = server.contentOf(b.backup.fileId)!;
    server.editByHand(b.backup.fileId, flipAt(bytes, bytes.length - 5));
    const staging = new RecordingStaging();
    const r = await imp(a, folder, listed, staging);
    expect(r.kind === 'refused' && r.problem.kind).toBe('BACKUP_REFUSED');
    expect(staging.discards).toBe(1);
    expect(staging.bytes().length).toBe(0);
    expect((await a.service.listBackups(folder)).backups.length).toBe(0);
  });

  it('fails the checksum when bytes change while downloading, and discards', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const b = await done(a, folder, Payload.of(40_000, 10));
    const listed = (await a.service.listBackups(folder)).backups[0];
    server.faults.next(
      DriveFaults.interleave((s) => {
        const c = s.contentOf(b.backup.fileId)!;
        s.editByHand(b.backup.fileId, flipAt(c, Math.floor(c.length / 2), 4));
      }),
      'DOWNLOAD',
    );
    const staging = new RecordingStaging();
    const r = await imp(a, folder, listed, staging);
    expect(r.kind === 'refused' && r.problem.kind).toBe('CORRUPT');
    expect(staging.discards).toBe(1);
  });

  it('reports a backup in the bin or moved away as gone, and discards', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const b = await done(a, folder, Payload.of(4_000, 10));
    server.trashByHand(b.backup.fileId);
    const s1 = new RecordingStaging();
    const r1 = await imp(a, folder, b.backup, s1);
    expect(r1.kind === 'refused' && r1.problem.kind).toBe('BACKUP_GONE');
    expect(s1.discards).toBe(1);
    server.untrashByHand(b.backup.fileId);
    server.moveByHand(b.backup.fileId, folder.rootId);
    const s2 = new RecordingStaging();
    const r2 = await imp(a, folder, b.backup, s2);
    expect(r2.kind === 'refused' && r2.problem.kind).toBe('BACKUP_GONE');
    expect(s2.discards).toBe(1);
    server.deleteByHand(b.backup.fileId);
    const s3 = new RecordingStaging();
    expect((await imp(a, folder, b.backup, s3)).kind).toBe('refused');
    expect(s3.discards).toBe(1);
  });

  /** A backup made by a key holder by hand: `metaKid` in the metadata, `headerKid` in the file. */
  async function handMade(a: Rig, folder: ReadyFolder, plain: Uint8Array, headerKid: Uint8Array, metaKid: Uint8Array, createdAt: number): Promise<string> {
    const key = folder.keys.currentFolderKey();
    const { file, result } = await new Dpx(p).encryptBytes(key, folder.keys.epoch, headerKid, 'doorprints-backup/1', plain);
    const meta = new BackupMeta(createdAt, 4, folder.keys.epoch, metaKid, result.ciphertextSha256);
    const props = { ...meta.appProperties(await meta.mac(p, key), 'hand'), [DRIVE_LAYOUT.state]: 'complete' };
    return a.server.putByHand({ name: 'by-hand.dpx', mimeType: 'application/octet-stream', parents: [a.backupsFolder().id], appProperties: props }, file).id;
  }

  it('refuses a header that disagrees with the metadata and a writer the list does not know', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const myKid = kidOf(p, a.identity.key.publicKey);
    const okId = await handMade(a, folder, new Uint8Array(300).fill(9), myKid, myKid, clock.now());
    const ok = (await a.service.listBackups(folder)).backups.find((b) => b.fileId === okId)!;
    const s0 = new RecordingStaging();
    expect((await imp(a, folder, ok, s0)).kind).toBe('verified');
    expect(s0.bytes().length).toBe(300);
    clock.advance(day);
    const badId = await handMade(a, folder, new Uint8Array(300).fill(9), p.randomBytes(16), myKid, clock.now());
    const bad = (await a.service.listBackups(folder)).backups.find((b) => b.fileId === badId)!;
    const s1 = new RecordingStaging();
    expect((await imp(a, folder, bad, s1)).kind).toBe('refused');
    expect(s1.discards).toBe(1);
    expect(s1.bytes().length).toBe(0);
    clock.advance(day);
    const ghost = p.randomBytes(16);
    const ghostId = await handMade(a, folder, new Uint8Array(300).fill(9), ghost, ghost, clock.now());
    expect(new Map((await a.service.listBackups(folder)).ignored).get(ghostId)).toBe('WRITER_REFUSED');
  });

  it('refuses plain bytes with valid-looking metadata as not dpx', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const myKid = kidOf(p, a.identity.key.publicKey);
    const key = folder.keys.currentFolderKey();
    const zip = new Uint8Array([0x50, 0x4b, 3, 4, ...new Uint8Array(200)]);
    const sha = new Uint8Array(await crypto.subtle.digest('SHA-256', zip));
    const meta = new BackupMeta(clock.now(), 4, 1, myKid, sha);
    const props = { ...meta.appProperties(await meta.mac(p, key), 'hand'), [DRIVE_LAYOUT.state]: 'complete' };
    const id = server.putByHand({ name: 'zip.dpx', mimeType: 'application/octet-stream', parents: [a.backupsFolder().id], appProperties: props }, zip).id;
    const listed = (await a.service.listBackups(folder)).backups.find((b) => b.fileId === id)!;
    const staging = new RecordingStaging();
    const r = await imp(a, folder, listed, staging);
    expect(r.kind === 'refused' && r.problem.kind).toBe('BACKUP_REFUSED');
    expect(r.kind === 'refused' && r.problem.dpxKind).not.toBeNull();
    expect(staging.discards).toBe(1);
    expect(staging.bytes().length).toBe(0);
  });

  // ---- keys, recovery and enrolment ----

  it('never adopts a new device and writes nothing', async () => {
    const { server, writeCount } = world();
    const a = await Rig.make(server);
    await done(a, await a.created(), Payload.of(3_000, 3));
    const b = await Rig.make(server, 'Tablet');
    const before = writeCount();
    const c = await b.service.connect();
    expect(c.kind).toBe('NEEDS_ENROLMENT');
    expect(c.kind === 'NEEDS_ENROLMENT' && c.recoveryAvailable).toBe(true);
    const e = await b.service.createFolder(true);
    expect(e.connection.kind === 'ERROR' && e.connection.problem.kind).toBe('FOLDER_EXISTS');
    expect(e.recoveryKey).toBeNull();
    expect(writeCount()).toBe(before);
    expect(server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.role] === 'root').length).toBe(1);
  });

  it('opens nothing with a wrong recovery key, and shares backups after the right one', async () => {
    const { server, clock, writeCount } = world();
    const a = await Rig.make(server);
    const out = await a.service.createFolder(true);
    const first = (out.connection as { folder: ReadyFolder }).folder;
    const keysBefore = server.contentOf(first.keysId)!;
    const b = await Rig.make(server, 'Tablet');
    const before = writeCount();
    const wrong = await b.service.openWithRecoveryKey(RecoveryKey.generate(p));
    expect(wrong.kind === 'ERROR' && wrong.problem.kind).toBe('WRONG_RECOVERY_KEY');
    expect(writeCount()).toBe(before);
    expect(equalBytes(server.contentOf(first.keysId)!, keysBefore)).toBe(true);
    expect([...b.trust.keysStores.values()].every((s) => s.value === null)).toBe(true);
    await done(a, await a.ready(), Payload.of(3_000, 3, 1));
    clock.advance(day);
    const ok = await b.service.openWithRecoveryKey(out.recoveryKey!);
    expect(ok.kind).toBe('READY');
    const folderB = (ok as { folder: ReadyFolder }).folder;
    expect(folderB.keys.body.devices.length).toBe(2);
    expect((await b.service.listBackups(folderB)).backups.length).toBe(1);
    await done(b, folderB, Payload.of(3_000, 4, 2));
    const folderA = await a.ready();
    const listing = await a.service.listBackups(folderA);
    expect(listing.backups.length).toBe(2);
    const staging = new RecordingStaging();
    expect((await imp(a, folderA, listing.backups[0], staging)).kind).toBe('verified');
    expect(staging.bytes().length).toBeGreaterThan(0);
  });

  it('refuses a rolled back keys.json without any write', async () => {
    const { server, writeCount } = world();
    const a = await Rig.make(server);
    const out = await a.service.createFolder(true);
    const keysId = (out.connection as { folder: ReadyFolder }).folder.keysId;
    const firstRevision = (await a.drive.revisions(keysId))[0].id;
    const b = await Rig.make(server, 'Tablet');
    await b.service.openWithRecoveryKey(out.recoveryKey!);
    expect((await a.service.connect()).kind).toBe('READY');
    const folder = await a.ready();
    server.rollBack(keysId, firstRevision);
    const before = writeCount();
    const c = await a.service.connect();
    expect(c.kind === 'ERROR' && c.problem.kind).toBe('KEYS_ROLLED_BACK');
    expect(c.kind === 'ERROR' && c.problem.keysKind).toBe('ROLLED_BACK');
    expect(writeCount()).toBe(before);
    expect(folder.keys.epoch).toBe(1);
  });

  it('refuses a rolled back control file and accepts only a newer one', async () => {
    const { server, writeCount } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const old = server.contentOf(folder.controlId)!;
    const next = await new ControlFile(p).next(folder.keys, folder.control, 5);
    server.editByHand(folder.controlId, next.bytes);
    const c2 = await a.service.connect();
    expect(c2.kind === 'READY' && c2.folder.control.revision).toBe(2);
    server.editByHand(folder.controlId, old);
    const before = writeCount();
    const c = await a.service.connect();
    expect(c.kind === 'ERROR' && c.problem.kind).toBe('CONTROL_ROLLED_BACK');
    expect(writeCount()).toBe(before);
    const foreign = await Rig.make(new FakeDriveServer(), 'Other');
    const ff = await foreign.created();
    server.editByHand(folder.controlId, foreign.server.contentOf(ff.controlId)!);
    const c3 = await a.service.connect();
    expect(c3.kind === 'ERROR' && c3.problem.kind).toBe('CONTROL_INVALID');
  });

  it('writes a missing control file again, keeping the delete mark', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const next = await new ControlFile(p).next(folder.keys, folder.control, 77);
    server.editByHand(folder.controlId, next.bytes);
    await a.ready();
    server.deleteByHand(folder.controlId);
    const again = await a.ready();
    expect(again.control.revision).toBe(3);
    expect(again.control.backupsDeletedAt).toBe(77);
  });

  it('reports a folder without keys or in the bin, and does not re-create it', async () => {
    const { server, writeCount } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    server.deleteByHand(folder.keysId);
    const before = writeCount();
    const c = await a.service.connect();
    expect(c.kind === 'ERROR' && c.problem.kind).toBe('FOLDER_WITHOUT_KEYS');
    expect(writeCount()).toBe(before);
    server.trashByHand(folder.rootId);
    expect((await a.service.connect()).kind).toBe('FOLDER_GONE');
    expect(writeCount()).toBe(before);
  });

  it('starts an unfinished create again on the same device only', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    server.faults.always(DriveFaults.offline, 'UPLOAD');
    const out = await a.service.createFolder(true);
    expect(out.connection.kind === 'ERROR' && out.connection.problem.kind).toBe('OFFLINE');
    expect(out.recoveryKey).toBeNull();
    server.faults.clear();
    expect([...a.trust.keysStores.values()].every((s) => s.value === null)).toBe(true);
    expect((await a.service.connect()).kind).toBe('NO_FOLDER');
    const other = await Rig.make(server, 'Tablet');
    expect((await other.service.createFolder(true)).connection.kind).toBe('ERROR');
    const again = await a.service.createFolder(true);
    expect(again.connection.kind).toBe('READY');
    expect(server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.role] === 'root' && !f.trashed).length).toBe(1);
    expect(server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'keys' && !f.trashed).length).toBe(1);
  });

  // ---- offline and the schedule ----

  it('loses nothing offline, and the schedule waits and then retries', async () => {
    const { server, clock } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    await done(a, folder, Payload.of(3_000, 3));
    const liveBefore = a.live().map((f) => f.id);
    clock.advance(day);
    server.faults.always(DriveFaults.offline);
    expect((await failed(a, folder, Payload.of(3_000, 3, 1))).kind).toBe('OFFLINE');
    expect((await a.service.verifyNewest(folder))!.kind).toBe('OFFLINE');
    expect(a.state.value.lastFailure).toBe('RETRYABLE');
    server.faults.clear();
    expect(a.live().map((f) => f.id)).toEqual(liveBefore);
    const soon = await a.service.schedule(true, true);
    expect(soon.backup).toBe(false);
    expect(soon.reason).toBe('WAIT_RETRY');
    clock.advance(30 * 60 * 1000);
    expect((await a.service.schedule(true, true)).backup).toBe(true);
    await done(a, folder, Payload.of(3_000, 3, 2));
    expect(a.state.value.lastFailure).toBeNull();
    expect((await a.service.schedule(true, true)).reason).toBe('NOT_DUE');
    server.faults.always(DriveFaults.offline);
    const c = await a.service.connect();
    expect(c.kind === 'ERROR' && c.problem.kind).toBe('OFFLINE');
  });

  it('reports a full Drive and a refused grant as they are', async () => {
    const { server } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    server.faults.always(DriveFaults.quotaExceeded, 'UPLOAD');
    expect((await failed(a, folder, Payload.of(3_000, 3))).kind).toBe('QUOTA_EXCEEDED');
    expect(a.state.value.lastFailure).toBe('QUOTA');
    server.faults.clear();
    server.refusedTokens.add('token-1');
    server.refusedTokens.add('token-2');
    expect((await failed(a, folder, Payload.of(3_000, 3))).kind).toBe('UNAUTHORIZED');
    expect(a.state.value.lastFailure).toBe('UNAUTHORIZED');
  });

  it('writes nothing when the source fails or is not a backup format', async () => {
    const { server, writeCount } = world();
    const a = await Rig.make(server);
    const folder = await a.created();
    const before = writeCount();
    const o = await a.service.backUp(folder, async () => {
      throw new Error('disk full');
    });
    expect(o.kind === 'failed' && o.problem.kind).toBe('SOURCE_FAILED');
    const wrong = new Payload(new Uint8Array(10), 1, 'doorprints-sync/1');
    expect((await failed(a, folder, wrong)).kind).toBe('SOURCE_FAILED' satisfies DriveProblemKind);
    expect(writeCount()).toBe(before);
  });

  it('an 8-digit wrap enrols a second device, and revoke drops it', async () => {
    const { server } = world();
    const a = await Rig.make(server, 'Browser A');
    await a.created();
    const b = await Rig.make(server, 'Browser B');
    const approved = await a.service.approveDevice(b.identity.key.publicKey, b.identity.name, 'web');
    expect(approved.kind).toBe('approved');
    if (approved.kind !== 'approved') return;
    const joined = await b.service.joinFromWrap(approved.wrapEnc, approved.wrapCt, approved.epoch);
    expect(joined.kind).toBe('READY');
    if (joined.kind !== 'READY') return;
    expect(joined.folder.keys.body.devices).toHaveLength(2);
    const victim = kidOf(p, b.identity.key.publicKey);
    const revoked = await a.service.revokeDevice(victim);
    expect(revoked.connection.kind).toBe('READY');
    expect(revoked.recoveryKey).not.toBeNull();
    if (revoked.connection.kind === 'READY') {
      expect(revoked.connection.folder.keys.body.devices.some((d) => equalBytes(d.kid, victim))).toBe(false);
      expect(revoked.connection.folder.keys.epoch).toBe(2);
    }
    expect((await b.service.connect()).kind).not.toBe('READY');
  });

  it('a QR PSK wrap enrols a second device; a wrong PSK or another key does not', async () => {
    const { server } = world();
    const a = await Rig.make(server, 'Browser A');
    await a.created();
    const b = await Rig.make(server, 'Browser B');
    const psk = p.randomBytes(32);
    const approved = await a.service.approveDevicePsk(b.identity.key.publicKey, b.identity.name, 'web', psk);
    expect(approved.kind).toBe('approved');
    if (approved.kind !== 'approved') return;
    const wrong = psk.slice();
    wrong[0] ^= 1;
    expect((await b.service.joinFromPsk(approved.wrapEnc, approved.wrapCt, approved.epoch, wrong)).kind).toBe('ERROR');
    const other = await Rig.make(server, 'Browser C');
    expect((await other.service.joinFromPsk(approved.wrapEnc, approved.wrapCt, approved.epoch, psk)).kind).toBe('ERROR');
    const joined = await b.service.joinFromPsk(approved.wrapEnc, approved.wrapCt, approved.epoch, psk);
    expect(joined.kind).toBe('READY');
    if (joined.kind !== 'READY') return;
    expect(joined.folder.keys.body.devices).toHaveLength(2);
  });
});
