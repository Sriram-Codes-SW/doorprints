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
import { sha256Hex } from '../../export/sha256';
import { CHUNK_UNIT, DRIVE_LAYOUT, DriveError, DriveRetry, FOLDER_MIME } from './drive-client';
import type { DriveClient, DriveFile, DriveQuery, NewFile, UploadSession } from './drive-client';
import {
  confirmChecksum, deleteAll, downloadTo, downloadVerified, ensureFolder, listAll, markComplete, uploadBytes, uploadResumable,
} from './drive-ops';
import { FakeDriveHttp } from './fake-drive-http';
import { DriveFaults, FakeTokenProvider } from './fake-drive-faults';
import type { DriveOp } from './fake-drive-faults';
import { FetchDriveClient } from './fetch-drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from './in-memory-fake-drive';

/**
 * The DriveClient contract (S4b-BL-115, docs/06 TC-U-124; Kotlin `DriveClientContract`, the same cases): every case runs
 * on `InMemoryFakeDrive` and on `FetchDriveClient` over the same fake behind Drive's HTTP surface (`FakeDriveHttp`), and
 * must give the same answer. Both wait on the server's clock with random 0.5.
 */

interface Subject {
  name: string;
  server: FakeDriveServer;
  tokens: FakeTokenProvider;
  drive: DriveClient;
}

function subjects(setup: (server: FakeDriveServer) => void): Subject[] {
  return (['fake', 'fetch'] as const).map((name) => {
    const server = new FakeDriveServer();
    setup(server);
    const tokens = new FakeTokenProvider();
    const retry = new DriveRetry({ random: () => 0.5, sleep: async (ms) => server.clock.sleep(ms) });
    const drive = name === 'fake'
      ? new InMemoryFakeDrive(server, tokens, retry)
      : new FetchDriveClient(tokens, { fetch: new FakeDriveHttp(server).fetch, retry, now: () => server.clock.now() });
    return { name, server, tokens, drive };
  });
}

function both(title: string, setup: (server: FakeDriveServer) => void, body: (s: Subject) => Promise<void>): void {
  describe(title, () => {
    for (const name of ['fake', 'fetch']) {
      it(`on the ${name} client`, async () => {
        const s = subjects(setup).find((x) => x.name === name)!;
        await body(s);
      });
    }
  });
}

const none = () => undefined;
const ops = (s: Subject): DriveOp[] => s.server.requests.map((r) => r.op);
const count = (s: Subject, op: DriveOp) => ops(s).filter((o) => o === op).length;
/** Byte equality without the deep diff `toEqual` makes of a large array (slow for an upload). */
const same = (a: Uint8Array | null, b: Uint8Array) => a != null && a.length === b.length && a.every((v, i) => v === b[i]);
const bytes = (n: number, seed = 7) => Uint8Array.from({ length: n }, (_, i) => (i * 31 + seed) % 251);
const root = async (s: Subject) => (await ensureFolder(s.drive, DRIVE_LAYOUT.root, null, true))!;

async function kindOf(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof DriveError) return e.kind;
    throw e;
  }
  throw new Error('expected a DriveError');
}

function backup(parent: string, n: number, state: string = DRIVE_LAYOUT.stateComplete, device = 'd1'): NewFile {
  return {
    name: `Doorprints-backup-${n}.dpx`, mimeType: 'application/octet-stream', parents: [parent],
    appProperties: { kind: 'backup', device, state, createdAt: String(n) },
  };
}

describe('DriveClient contract (fake and fetch)', () => {
  both('the folder is found by its properties and never made again without create', none, async (s) => {
    expect(await ensureFolder(s.drive, DRIVE_LAYOUT.root, null, false)).toBeNull();
    expect(ops(s)).not.toContain('CREATE');
    const r = await root(s);
    expect(r.name).toBe('Doorprints');
    expect(r.mimeType).toBe(FOLDER_MIME);
    expect(r.appProperties).toEqual({ doorprints: 'root' });
    expect((await root(s)).id).toBe(r.id);
    expect((await ensureFolder(s.drive, DRIVE_LAYOUT.root, null, false, r.id))?.id).toBe(r.id);
    const backups = (await ensureFolder(s.drive, DRIVE_LAYOUT.backups, r.id, true))!;
    expect(backups.parents).toEqual([r.id]);
    s.server.deleteByHand(r.id);
    const creates = count(s, 'CREATE');
    expect(await ensureFolder(s.drive, DRIVE_LAYOUT.root, null, false, r.id)).toBeNull();
    expect(count(s, 'CREATE')).toBe(creates);
    const again = await root(s);
    s.server.trashByHand(again.id);
    expect(await ensureFolder(s.drive, DRIVE_LAYOUT.root, null, false, again.id)).toBeNull();
  });

  both('a lost create answer leaves two folders and the oldest is used', (server) => server.faults.on('CREATE', 1, DriveFaults.responseLost), async (s) => {
    const made = await root(s);
    const all = s.server.allFiles().filter((f) => f.appProperties['doorprints'] === 'root');
    expect(all.length).toBe(2);
    s.server.clock.advance(1);
    const oldest = [...all].sort((a, b) => a.createdTime - b.createdTime || (a.id < b.id ? -1 : 1))[0];
    expect((await root(s)).id).toBe(oldest.id);
    expect(all.map((f) => f.id)).toContain(made.id);
    expect(s.server.clock.slept.length).toBe(1);
  });

  both('listing by appProperties pages in creation order', none, async (s) => {
    const r = await root(s);
    const ids: string[] = [];
    for (let n = 1; n <= 5; n++) {
      s.server.clock.advance(10);
      ids.push((await s.drive.upload({ kind: 'new', file: backup(r.id, n, DRIVE_LAYOUT.stateComplete, n % 2 === 0 ? 'd2' : 'd1') }, bytes(10, n))).id);
    }
    s.server.clock.advance(10);
    await s.drive.upload({ kind: 'new', file: backup(r.id, 6, DRIVE_LAYOUT.statePartial) }, bytes(3));
    const query: DriveQuery = { parentId: r.id, appProperties: { kind: 'backup', state: 'complete' } };
    const first = await s.drive.list(query, null, 2);
    expect(first.files.map((f) => f.id)).toEqual(ids.slice(0, 2));
    expect(first.nextPageToken).not.toBeNull();
    const before = count(s, 'LIST');
    expect((await listAll(s.drive, query, 2)).map((f) => f.id)).toEqual(ids);
    expect(count(s, 'LIST') - before).toBe(3);
    expect((await listAll(s.drive, { ...query, appProperties: { ...query.appProperties, device: 'd2' } })).map((f) => f.id)).toEqual([ids[1], ids[3]]);
    await s.drive.trash(ids[0]);
    expect((await listAll(s.drive, query)).map((f) => f.id)).toEqual(ids.slice(1));
    expect((await listAll(s.drive, { ...query, trashed: null })).map((f) => f.id)).toEqual(ids);
    expect((await listAll(s.drive, { ...query, trashed: true })).map((f) => f.id)).toEqual([ids[0]]);
  });

  both('a new file is missing from listings for the lag but getFile sees it', (server) => (server.listingLagMs = 1000), async (s) => {
    const r = await root(s);
    s.server.clock.advance(1000);
    const file = await s.drive.upload({ kind: 'new', file: backup(r.id, 1) }, bytes(5));
    const query: DriveQuery = { parentId: r.id, appProperties: { kind: 'backup' } };
    expect(await listAll(s.drive, query)).toEqual([]);
    expect((await s.drive.getFile(file.id)).id).toBe(file.id);
    s.server.clock.advance(1000);
    expect((await listAll(s.drive, query)).map((f) => f.id)).toEqual([file.id]);
  });

  both('a small upload keeps its bytes, properties and checksum', none, async (s) => {
    const r = await root(s);
    const content = bytes(1000);
    const file = await s.drive.upload({ kind: 'new', file: backup(r.id, 1, DRIVE_LAYOUT.statePartial) }, content);
    expect(file.size).toBe(1000);
    expect(file.sha256Checksum).toBe(sha256Hex(content));
    expect(file.appProperties['state']).toBe('partial');
    expect(await s.drive.download(file.id)).toEqual(content);
    expect(await s.drive.download(file.id, { first: 10, last: 19 })).toEqual(content.slice(10, 20));
    expect(await downloadVerified(s.drive, file.id, sha256Hex(content))).toEqual(content);
    const parts: number[] = [];
    await downloadTo(s.drive, file.id, 1000, (b) => void parts.push(...b), CHUNK_UNIT);
    expect(Uint8Array.from(parts)).toEqual(content);
  });

  both('a resumable upload sends chunks and resumes after a dropped connection', (server) => server.faults.on('UPLOAD_CHUNK', 2, DriveFaults.dropAfter(100_000)), async (s) => {
    const r = await root(s);
    const content = bytes(3 * CHUNK_UNIT + 1000);
    const sessions: UploadSession[] = [];
    const file = await uploadResumable(s.drive, { kind: 'new', file: backup(r.id, 1, DRIVE_LAYOUT.statePartial) }, content.length,
      (offset, length) => content.slice(offset, offset + length), { chunkSize: CHUNK_UNIT, onSession: (x) => sessions.push(x) });
    expect(sessions.length).toBe(1);
    expect(file.sha256Checksum).toBe(sha256Hex(content));
    expect(same(s.server.contentOf(file.id), content)).toBe(true);
    const upload = ops(s).slice(ops(s).indexOf('UPLOAD_START'));
    expect(upload).toEqual(['UPLOAD_START', 'UPLOAD_CHUNK', 'UPLOAD_CHUNK', 'UPLOAD_STATUS', 'UPLOAD_CHUNK', 'UPLOAD_CHUNK']);
    expect(s.server.clock.slept).toEqual([500]);
  });

  both('a forgotten session starts once again', none, async (s) => {
    const r = await root(s);
    const content = bytes(2 * CHUNK_UNIT);
    let first = true;
    const file = await uploadResumable(s.drive, { kind: 'new', file: backup(r.id, 1) }, content.length, (offset, length) => {
      if (offset > 0 && first) {
        first = false;
        s.server.expireSessions();
      }
      return content.slice(offset, offset + length);
    }, { chunkSize: CHUNK_UNIT });
    expect(same(s.server.contentOf(file.id), content)).toBe(true);
    expect(count(s, 'UPLOAD_START')).toBe(2);
  });

  both('the complete marker comes only after the checksum matches', (server) => server.faults.on('UPLOAD', 2, DriveFaults.corruptContent), async (s) => {
    const r = await root(s);
    const content = bytes(500);
    const good = await s.drive.upload({ kind: 'new', file: { ...backup(r.id, 1, DRIVE_LAYOUT.statePartial), name: 'partial-a' } }, content);
    const done = await markComplete(s.drive, good.id, sha256Hex(content), 'Doorprints-backup-a.dpx', good);
    expect(done.appProperties['state']).toBe('complete');
    expect(done.name).toBe('Doorprints-backup-a.dpx');
    expect(done.appProperties['kind']).toBe('backup');
    const bad = await s.drive.upload({ kind: 'new', file: backup(r.id, 2, DRIVE_LAYOUT.statePartial) }, content);
    expect(await kindOf(markComplete(s.drive, bad.id, sha256Hex(content), 'x'))).toBe('CORRUPT');
    expect(s.server.fileOrNull(bad.id)!.appProperties['state']).toBe('partial');
    expect(await kindOf(downloadVerified(s.drive, bad.id, sha256Hex(content)))).toBe('CORRUPT');
  });

  both('a checksum Drive has not computed is asked again', (server) => server.faults.on('GET', 1, DriveFaults.lateChecksum), async (s) => {
    const r = await root(s);
    const content = bytes(64);
    const file = await s.drive.upload({ kind: 'new', file: backup(r.id, 1) }, content);
    expect((await confirmChecksum(s.drive, file.id, sha256Hex(content))).id).toBe(file.id);
    expect(count(s, 'GET')).toBe(2);
    expect(s.server.clock.slept).toEqual([500]);
  });

  both('metadata changes touch only what they name', none, async (s) => {
    const r = await root(s);
    const other = (await ensureFolder(s.drive, DRIVE_LAYOUT.sync, r.id, true))!;
    const file = await s.drive.upload({ kind: 'new', file: backup(r.id, 1) }, bytes(4));
    const changed = await s.drive.updateMetadata(file.id, {
      appProperties: { device: null, x: '1' }, addParents: [other.id], removeParents: [r.id],
    });
    expect(changed.name).toBe(file.name);
    expect(changed.appProperties['device']).toBeUndefined();
    expect(changed.appProperties['x']).toBe('1');
    expect(changed.appProperties['kind']).toBe('backup');
    expect(changed.parents).toEqual([other.id]);
    expect(await kindOf(s.drive.updateMetadata(file.id, { appProperties: { k: 'v'.repeat(124) } }))).toBe('BAD_REQUEST');
  });

  both('new content makes a revision and a roll-back shows in them', none, async (s) => {
    const r = await root(s);
    const file = await s.drive.upload({ kind: 'new', file: backup(r.id, 1) }, bytes(10, 1));
    s.server.clock.advance(5);
    const second = bytes(2 * CHUNK_UNIT, 2);
    const updated = await uploadBytes(s.drive, { kind: 'existing', fileId: file.id, mimeType: 'application/octet-stream', change: { name: 'n2' } }, second);
    expect(updated.name).toBe('n2');
    expect(updated.sha256Checksum).toBe(sha256Hex(second));
    const revisions = await s.drive.revisions(file.id);
    expect(revisions.length).toBe(2);
    expect(revisions[0].modifiedTime).toBeLessThan(revisions[1].modifiedTime);
    s.server.rollBack(file.id, revisions[0].id);
    expect((await s.drive.revisions(file.id)).length).toBe(3);
    expect(await s.drive.download(file.id)).toEqual(bytes(10, 1));
  });

  both('deleting is for good and trash is the bin', none, async (s) => {
    const r = await root(s);
    const photos = (await ensureFolder(s.drive, DRIVE_LAYOUT.photos, r.id, true))!;
    const photo = await s.drive.upload({ kind: 'new', file: { name: 'p-1.dpx', mimeType: 'application/octet-stream', parents: [photos.id] } }, bytes(100));
    const old = await s.drive.upload({ kind: 'new', file: backup(r.id, 1) }, bytes(100));
    const usage = (await s.drive.about()).quotaUsage;
    expect((await s.drive.trash(old.id)).trashed).toBe(true);
    expect((await s.drive.about()).quotaUsage).toBe(usage);
    await s.drive.delete(old.id);
    expect(s.server.fileOrNull(old.id)).toBeNull();
    await s.drive.delete(old.id);
    await s.drive.delete(r.id);
    expect(s.server.fileOrNull(photo.id)).toBeNull();
    expect((await s.drive.about()).quotaUsage).toBe(0);
  });

  both('deleteAll stops at the first failure and says what is left', none, async (s) => {
    const r = await root(s);
    const ids: string[] = [];
    for (let n = 1; n <= 4; n++) ids.push((await s.drive.upload({ kind: 'new', file: backup(r.id, n) }, bytes(8))).id);
    s.server.faults.stopAfter(s.server.requests.length + 2);
    const report = await deleteAll(s.drive, ids);
    expect(report.deleted).toEqual(ids.slice(0, 2));
    expect(report.left).toEqual(ids.slice(2));
    expect(report.error?.kind).toBe('OFFLINE');
    s.server.faults.clear();
    expect((await deleteAll(s.drive, report.left)).deleted).toEqual(ids.slice(2));
  });

  both('about gives the account and the quota', (server) => (server.quotaBytes = 1000), async (s) => {
    const about = await s.drive.about();
    expect(about.email).toBe('person@example.com');
    expect(about.quotaLimit).toBe(1000);
    const r = await root(s);
    await s.drive.upload({ kind: 'new', file: backup(r.id, 1) }, bytes(600));
    expect((await s.drive.about()).quotaUsage).toBe(600);
    expect(await kindOf(s.drive.upload({ kind: 'new', file: backup(r.id, 2) }, bytes(600)))).toBe('QUOTA_EXCEEDED');
    expect(count(s, 'UPLOAD')).toBe(2);
    expect(s.server.clock.slept).toEqual([]);
  });

  both('an expired token is asked for once more', (server) => server.faults.onCall(1, DriveFaults.tokenExpired), async (s) => {
    expect((await s.drive.about()).email).toBe('person@example.com');
    expect(s.tokens.rejected).toEqual(['token-1']);
    expect(s.server.tokensSeen).toEqual(['token-1', 'token-2']);
    s.server.refusedTokens.add('token-2');
    s.server.refusedTokens.add('token-3');
    expect(await kindOf(s.drive.about())).toBe('UNAUTHORIZED');
    expect(s.tokens.rejected).toEqual(['token-1', 'token-2']);
    expect(s.server.clock.slept).toEqual([]);
  });

  both('rate limits wait for Retry-After or the backoff', (server) => server.faults
    .onCall(1, DriveFaults.rateLimited(2000)).onCall(2, DriveFaults.rateLimited(null, true)).onCall(4, DriveFaults.rateLimited(120_000)), async (s) => {
    await s.drive.about();
    expect(s.server.clock.slept).toEqual([2000, 1000]);
    try {
      await s.drive.about();
      throw new Error('expected a rate limit');
    } catch (e) {
      expect((e as DriveError).kind).toBe('RATE_LIMITED');
      expect((e as DriveError).retryAfterMs).toBe(120_000);
    }
  });

  both('server errors are retried five times with backoff', (server) => server.faults.onCall(3, DriveFaults.server(503)), async (s) => {
    await s.drive.about();
    await s.drive.about();
    await s.drive.about();
    expect(s.server.clock.slept).toEqual([500]);
    s.server.faults.always(DriveFaults.server(500));
    try {
      await s.drive.about();
      throw new Error('expected a server error');
    } catch (e) {
      expect((e as DriveError).kind).toBe('SERVER');
      expect((e as DriveError).httpStatus).toBe(500);
    }
    expect(s.server.clock.slept).toEqual([500, 500, 1000, 2000, 4000]);
    expect(s.server.requests.length).toBe(4 + 5);
    s.server.faults.clear().always(DriveFaults.offline);
    expect(await kindOf(s.drive.about())).toBe('OFFLINE');
  });

  both('refusals are not retried', none, async (s) => {
    for (const [fault, kind] of [
      [DriveFaults.forbidden, 'FORBIDDEN'], [DriveFaults.notFound, 'NOT_FOUND'], [DriveFaults.conflict, 'CONFLICT'], [DriveFaults.cancelled, 'CANCELLED'],
    ] as const) {
      s.server.faults.next(fault);
      expect(await kindOf(s.drive.about())).toBe(kind);
    }
    expect(s.server.requests.length).toBe(4);
    expect(s.server.clock.slept).toEqual([]);
    expect(await kindOf(s.drive.getFile('nope'))).toBe('NOT_FOUND');
    expect(await kindOf(s.drive.getFile('../x'))).toBe('BAD_REQUEST');
    expect(s.server.requests.length).toBe(5);
  });

  both('another writer between list and update is not undone', none, async (s) => {
    const r = await root(s);
    const file = await s.drive.upload({ kind: 'new', file: backup(r.id, 1, DRIVE_LAYOUT.statePartial) }, bytes(4));
    s.server.faults.next(DriveFaults.interleave((server) => server.renameByHand(file.id, 'renamed by hand')), 'UPDATE');
    const done = await s.drive.updateMetadata(file.id, { appProperties: { state: 'complete' } });
    expect(done.name).toBe('renamed by hand');
    expect(done.appProperties['state']).toBe('complete');
  });

  it('two devices writing at once keep both files', async () => {
    const server = new FakeDriveServer();
    const a = new InMemoryFakeDrive(server);
    const b = new FetchDriveClient(new FakeTokenProvider(), { fetch: new FakeDriveHttp(server).fetch, now: () => server.clock.now() });
    const r = (await ensureFolder(a, DRIVE_LAYOUT.root, null, true))!;
    const sync = (await ensureFolder(b, DRIVE_LAYOUT.sync, r.id, true))!;
    const made: DriveFile[] = await Promise.all([a, b].map((d, i) => d.upload({
      kind: 'new', file: { name: `device-${i}.dpx`, mimeType: 'application/octet-stream', parents: [sync.id], appProperties: { kind: 'sync', device: `d${i}` } },
    }, bytes(50, i))));
    const listed = await listAll(a, { parentId: sync.id, appProperties: { kind: 'sync' } });
    expect(new Set(listed.map((f) => f.id))).toEqual(new Set(made.map((f) => f.id)));
    await b.upload({ kind: 'existing', fileId: made[0].id, mimeType: 'application/octet-stream' }, bytes(5, 9));
    await a.upload({ kind: 'existing', fileId: made[0].id, mimeType: 'application/octet-stream' }, bytes(5, 8));
    expect(server.contentOf(made[0].id)).toEqual(bytes(5, 8));
    expect((await a.revisions(made[0].id)).length).toBe(3);
  });
});
