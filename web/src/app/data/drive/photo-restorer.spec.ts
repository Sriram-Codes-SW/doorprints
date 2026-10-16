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
import { hex } from '../crypto/bytes';
import { DRIVE_LAYOUT } from './drive-client';
import { DriveDeletionService } from './drive-deletion';
import type { AuthorizationGate } from './drive-deletion';
import { DriveFaults } from './fake-drive-faults';
import { FolderSession } from './drive-sync-seams';
import { DriveSyncEngine } from './drive-sync-engine';
import { DrivePhotos } from './drive-photos';
import { syncRowOf } from './drive-sync-backend';
import { DrivePhotoRestorer } from './photo-restorer';
import type { PhotoRestoreReport } from './photo-restorer';
import { photoRow, SyncWorld } from './drive-sync-test-world';
import type { TestDevice } from './drive-sync-test-world';
import { cleanMeta } from '../../shared/photo-tags';
import type { GonePhoto } from './photo-restore-rules';

/**
 * TC-U: getting a restored candidate's photos back from the person's own Google Drive (S4b-BL-293), on the fake Drive with
 * the real encryption core (Kotlin `PhotoRestorerTest`): the photos came from a real upload, were deleted with a real tombstone,
 * and the restorer reads the device's own sync file and the photo files like any other sync read.
 */
const DAY = 24 * 60 * 60 * 1000;

function bytes(n: number, seed: number): Uint8Array {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = (i * 31 + seed * 17 + (i >> 3)) & 0xff;
  return out;
}
const once = <T>(o: { subscribe: (h: { next: (v: T) => void; error: (e: unknown) => void; complete?: () => void }) => unknown }) =>
  new Promise<T>((res, rej) => {
    let v: T;
    o.subscribe({ next: (x) => { v = x; }, error: rej, complete: () => res(v) });
  });
const done = <T>(o: { subscribe: (h: { next: () => void; error: (e: unknown) => void; complete: () => void }) => unknown }) =>
  new Promise<void>((res, rej) => void o.subscribe({ next: () => undefined, error: rej, complete: () => res() }));

interface Scene {
  readonly w: SyncWorld;
  readonly a: TestDevice;
  readonly data: Record<string, Uint8Array>;
  /** The candidate's tombstone time. */
  readonly houseAt: number;
}

/** Device A has a candidate with photos in Drive, deletes the candidate, and its photos' tombstones are in A's sync file. */
async function scene(ids: string[] = ['p1', 'p2']): Promise<Scene> {
  const w = new SyncWorld();
  const a = await w.add('A');
  a.edit('h1', 'Blue gate');
  const data: Record<string, Uint8Array> = {};
  ids.forEach((id, i) => {
    data[id] = bytes(3000 + i, i + 1);
    a.local.put(photoRow(id, 'h1', a.now(), a.id), true);
  });
  const ba = a.backend();
  await done(ba.commitPushes());
  for (const id of ids) await once(ba.uploadPhoto('h1', new Blob([data[id] as BlobPart]), id));
  await done(ba.commitPushes());
  w.server.clock.advance(1000);
  a.delete('h1');
  const houseAt = a.local.stamp('h1')!.updatedAt;
  w.server.clock.advance(1000);
  const bd = a.backend();
  for (const id of ids) {
    await once(bd.deletePhoto(id));
    a.local.rows.delete(`photos\u0000${id}`);
  }
  await done(bd.commitPushes());
  w.server.clock.advance(1000);
  return { w, a, data, houseAt };
}

const restorer = (d: TestDevice) => new DrivePhotoRestorer(d.engine(), d.photos(), () => d.now());
const request = (houseAt: number, known: GonePhoto[] = []) => ({ houseId: 'h1', houseDeletedAt: houseAt, known });
const ids = (r: PhotoRestoreReport) => r.fetched.map((f) => f.photo.id).sort();
const reasons = (r: PhotoRestoreReport) => r.failed.map((f) => [f.photo.id, f.reason]).sort();
const same = (x: Uint8Array, y: Uint8Array) => hex(x) === hex(y);
const gone = (id: string, deletedAt: number): GonePhoto => ({ id, deletedAt, contentType: 'image/jpeg', createdAt: null, meta: cleanMeta(null), ref: null });

describe('DrivePhotoRestorer', () => {
  it('brings every photo back from Drive with its exact bytes, reading only', async () => {
    const { a, data, houseAt, w } = await scene();
    const before = w.server.requests.length;
    const report = await restorer(a).restore(request(houseAt));
    expect(report.connection).toBe('OK');
    expect(ids(report)).toEqual(['p1', 'p2']);
    for (const f of report.fetched) expect(same(f.bytes, data[f.photo.id])).toBe(true);
    expect(report.failed).toEqual([]);
    const ops = new Set(w.server.requests.slice(before).map((r) => r.op));
    expect([...ops].every((op) => ['LIST', 'GET', 'DOWNLOAD'].includes(op))).toBe(true);
  });

  it('brings back only the photos whose files are still there and says which are missing', async () => {
    const { a, data, houseAt, w } = await scene();
    w.server.deleteByHand(a.photoState.refs['p2'].driveFileId);
    const report = await restorer(a).restore(request(houseAt));
    expect(ids(report)).toEqual(['p1']);
    expect(same(report.fetched[0].bytes, data['p1'])).toBe(true);
    expect(reasons(report)).toEqual([['p2', 'MISSING']]);
  });

  it('a file in the bin is not read', async () => {
    const { a, houseAt, w } = await scene(['p1']);
    await a.drive.trash(a.photoState.refs['p1'].driveFileId);
    const file = a.photoState.refs['p1'].driveFileId;
    const before = w.server.requests.length;
    const report = await restorer(a).restore(request(houseAt));
    expect(reasons(report)).toEqual([['p1', 'MISSING']]);
    expect(w.server.requests.slice(before).some((r) => r.op === 'DOWNLOAD' && r.about === file)).toBe(false);
  });

  it('past the 30 days nothing is fetched', async () => {
    const { a, houseAt, w } = await scene();
    w.server.clock.advance(31 * DAY);
    const before = w.server.requests.length;
    const report = await restorer(a).restore(request(houseAt));
    expect(report.fetched).toEqual([]);
    expect(reasons(report)).toEqual([['p1', 'PAST_WINDOW'], ['p2', 'PAST_WINDOW']]);
    expect(w.server.requests.slice(before).some((r) => r.op === 'DOWNLOAD' && r.about !== null && r.about === a.photoState.refs['p1'].driveFileId)).toBe(false);
  });

  it('offline at the first read: every known photo says offline and nothing is thrown', async () => {
    const { a, houseAt, w } = await scene();
    w.server.faults.always(DriveFaults.offline);
    const report = await restorer(a).restore(request(houseAt, [gone('p1', houseAt + 1000), gone('p2', houseAt + 1000)]));
    expect(report.connection).toBe('OFFLINE');
    expect(report.fetched).toEqual([]);
    expect(reasons(report)).toEqual([['p1', 'OFFLINE'], ['p2', 'OFFLINE']]);
  });

  it('the connection drops after the first photo: that one is back, the rest say offline', async () => {
    const { a, data, houseAt, w } = await scene();
    // of this run's downloads the first is the sync file, the second the first photo: from there on every download fails
    const downloads = w.server.requests.filter((r) => r.op === 'DOWNLOAD').length;
    w.server.faults.on('DOWNLOAD', downloads + 2, DriveFaults.interleave((s) => { s.faults.always(DriveFaults.offline, 'DOWNLOAD'); }));
    const report = await restorer(a).restore(request(houseAt));
    expect(report.connection).toBe('OFFLINE');
    expect(ids(report)).toEqual(['p1']);
    expect(same(report.fetched[0].bytes, data['p1'])).toBe(true);
    expect(reasons(report)).toEqual([['p2', 'OFFLINE']]);
  });

  it('a photo file that was changed in Drive does not check out and is reported as unreadable, the others still come', async () => {
    const { a, data, houseAt, w } = await scene();
    const file = a.photoState.refs['p1'].driveFileId;
    const content = w.server.contentOf(file)!;
    content[content.length - 5] ^= 1;
    w.server.editByHand(file, content);
    const report = await restorer(a).restore(request(houseAt));
    expect(reasons(report)).toEqual([['p1', 'UNREADABLE']]);
    expect(ids(report)).toEqual(['p2']);
    expect(same(report.fetched[0].bytes, data['p2'])).toBe(true);
  });

  it('a photo whose Drive file is another photo\'s is refused by its checksum', async () => {
    const { a, houseAt } = await scene();
    const r2 = a.photoState.refs['p2'];
    a.photoState = { ...a.photoState, refs: { ...a.photoState.refs, p1: { driveFileId: r2.driveFileId, sha256: a.photoState.refs['p1'].sha256 } } };
    // the file's own tombstone rows name the files too, so ask with only the local records
    const report = await new DrivePhotoRestorer(
      Object.assign(a.engine(), { ownPhotoRows: async () => [] }), a.photos(), () => a.now(),
    ).restore(request(houseAt, [gone('p1', houseAt + 1000)]));
    expect(reasons(report)).toEqual([['p1', 'UNREADABLE']]);
  });

  it('another device that took the tombstones in a sync restores them too', async () => {
    const { a, data, houseAt, w } = await scene();
    const b = await w.add('B');
    b.photoState = { ...b.photoState }; // B has learned nothing of the photos yet
    for (let i = 0; i < 2; i++) {
      w.server.clock.advance(1000);
      await done(b.backend().commitPushes());
    }
    const report = await restorer(b).restore(request(houseAt));
    expect(ids(report)).toEqual(['p1', 'p2']);
    for (const f of report.fetched) expect(same(f.bytes, data[f.photo.id])).toBe(true);
    expect(a).toBeDefined();
  });

  it('a photo deleted by hand long before its candidate is not brought back with it', async () => {
    const { a, houseAt } = await scene();
    const report = await restorer(a).restore(request(houseAt + 10 * DAY));
    expect(report.fetched).toEqual([]);
    expect(report.failed).toEqual([]);
  });

  it('without a pin nothing is opened: not connected', async () => {
    const { a, houseAt } = await scene();
    const s = a.session();
    const unpinned = new FolderSession(s.rootId, s.deviceKid, s.keys, { ...s.guard, watermark: async () => null } as unknown as FolderSession['guard']);
    const eng = new DriveSyncEngine(a.drive, a.p, unpinned, a.store, a.local, () => a.now());
    const ph = new DrivePhotos(a.drive, a.p, unpinned, a.photoStore, () => a.now());
    const report = await new DrivePhotoRestorer(eng, ph, () => a.now()).restore(request(houseAt, [gone('p1', houseAt + 1000)]));
    expect(report.connection).toBe('NOT_CONNECTED');
    expect(reasons(report)).toEqual([['p1', 'NOT_CONNECTED']]);
  });

  it('a failure that is not about the connection (a bug in one photo\'s read) is reported as unreadable and the others still come', async () => {
    const { a, houseAt, data } = await scene();
    const real = a.photos();
    const flaky = Object.assign(Object.create(real) as DrivePhotos, {
      refs: () => real.refs(),
      fetchForRestore: async (ref: { driveFileId: string; sha256: string }) => {
        if (ref.driveFileId === a.photoState.refs['p1'].driveFileId) throw new Error('boom');
        return real.fetchForRestore(ref);
      },
    });
    const report = await new DrivePhotoRestorer(a.engine(), flaky, () => a.now()).restore(request(houseAt));
    expect(report.connection).toBe('OK');
    expect(reasons(report)).toEqual([['p1', 'UNREADABLE']]);
    expect(ids(report)).toEqual(['p2']);
    expect(same(report.fetched[0].bytes, data['p2'])).toBe(true);
  });

  it('while sync is paused (the folder is being deleted) nothing is opened: not connected', async () => {
    const { a, houseAt, w } = await scene();
    const paused = new DriveSyncEngine(a.drive, a.p, a.session(), a.store, a.local, () => a.now(), async () => true);
    const before = w.server.requests.length;
    const report = await new DrivePhotoRestorer(paused, a.photos(), () => a.now()).restore(request(houseAt, [gone('p1', houseAt + 1000)]));
    expect(report.connection).toBe('NOT_CONNECTED');
    expect(reasons(report)).toEqual([['p1', 'NOT_CONNECTED']]);
    expect(w.server.requests.length).toBe(before);
  });

  it('a tombstone staged for the next pass is found before any file has it', async () => {
    const { a, houseAt, data } = await scene(['p1']);
    const eng = a.engine();
    const row = syncRowOf('photos', { id: 'p9', houseId: 'h1', contentType: 'image/jpeg', sizeBytes: 1, updatedAt: new Date(houseAt + 5000).toISOString(), deleted: true, driveFileId: a.photoState.refs['p1'].driveFileId, sha256: a.photoState.refs['p1'].sha256 }, a.id);
    eng.stage(row);
    const report = await new DrivePhotoRestorer(eng, a.photos(), () => a.now()).restore(request(houseAt));
    expect(ids(report)).toEqual(['p1', 'p9']);
    expect(same(report.fetched.find((f) => f.photo.id === 'p9')!.bytes, data['p1'])).toBe(true);
  });

  it('reads the device\'s own file once while it is the one it wrote last: the sync file is not downloaded again', async () => {
    const { a, houseAt, w } = await scene();
    const eng = a.engine();
    await a.sync({ engine: eng });
    const downloads = () => w.server.requests.filter((r) => r.op === 'DOWNLOAD').length;
    const before = downloads();
    const report = await new DrivePhotoRestorer(eng, a.photos(), () => a.now()).restore(request(houseAt));
    expect(ids(report)).toEqual(['p1', 'p2']);
    expect(downloads() - before).toBe(2); // the two photo files only
  });

  it('after Delete everything nothing is left to restore', async () => {
    const { a, houseAt, w } = await scene();
    const gate: AuthorizationGate = { isGenuine: async () => true, stillHolds: async () => true };
    const service = new DriveDeletionService({
      drive: a.drive, gate, store: memoryDeletionStore(), isOnline: () => true, now: () => w.server.clock.now(), saveEvery: 3,
    });
    const pre = await service.preflight(w.rootId, { type: 'everything' });
    if (pre.kind !== 'ready') throw new Error(`refused ${pre.reason}`);
    const out = await service.delete(pre.plan, { level: pre.plan.level, issuedAtMs: w.server.clock.now(), operationId: pre.plan.operationId, proof: 'ok' });
    expect(out.kind).toBe('ran');
    expect(w.server.allFiles().filter((f) => f.appProperties[DRIVE_LAYOUT.kind] === 'photo')).toEqual([]);
    const report = await restorer(a).restore(request(houseAt, [gone('p1', houseAt + 1000), gone('p2', houseAt + 1000)]));
    expect(report.fetched).toEqual([]);
    expect(report.failed.map((f) => f.reason)).toEqual(['MISSING', 'MISSING']);
  });
});

function memoryDeletionStore() {
  let pending: import('./drive-deletion').PendingDeletion | null = null;
  let marker: import('./drive-deletion').DeletedMarker | null = null;
  return {
    pending: async () => pending,
    savePending: async (p: import('./drive-deletion').PendingDeletion) => { pending = p; },
    clearPending: async () => { pending = null; },
    marker: async () => marker,
    recordFinished: async (m: import('./drive-deletion').DeletedMarker) => { marker = m; },
  };
}
