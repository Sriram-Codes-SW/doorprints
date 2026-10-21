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

import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DeleteUndoService, UNDO_MS, nameOfRow } from './delete-undo.service';
import type { PhotoRestorer } from './delete-undo.service';
import { notConnectedReport } from '../data/drive/photo-restorer';
import type { PhotoRestoreReport, PhotoRestoreRequest } from '../data/drive/photo-restorer';
import type { PhotoRestoreReason } from '../data/drive/photo-restore-rules';
import { Announcer } from './announcer.service';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from './kind-setting';
import type { HouseDto } from './models';
import { LocalStore } from '../data/local-store.service';
import { StorageService } from '../data/storage.service';
import { SyncService } from '../data/sync.service';
import { SETTING_KEYS } from '../data/records';

/** Deleting one candidate with Undo, and the list behind *Recently deleted* (deletion safeguards PR 1; docs/06 TC-U-215). */
const T0 = Date.parse('2026-10-14T12:00:00.000Z');
const DAY = 24 * 60 * 60 * 1000;
const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({ id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over });
const jpeg = (): Blob => new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' });

describe('DeleteUndoService', () => {
  let store: LocalStore;
  let service: DeleteUndoService;
  let syncSoon: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(T0);
    syncSoon = vi.fn();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: SyncService, useValue: { syncSoon } },
        { provide: StorageService, useValue: { requestPersistence: () => Promise.resolve() } },
      ],
    });
    store = TestBed.inject(LocalStore);
    service = TestBed.inject(DeleteUndoService);
  });

  afterEach(() => {
    service.dismiss();
    vi.useRealTimers();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('names a row by its label, else its address, else nothing', () => {
    expect(nameOfRow({ label: ' Blue gate ', address: 'x' })).toBe('Blue gate');
    expect(nameOfRow({ label: ' ', address: 'MG Road' })).toBe('MG Road');
    expect(nameOfRow({ label: '' })).toBe('');
  });

  describe('delete with Undo', () => {
    it('deletes at once, tombstones sync, and offers Undo for ten seconds', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await service.remove('h1', 'Blue gate');
      expect(await store.getHouse('h1')).toBeUndefined();
      expect((await store.allHouses())[0]).toMatchObject({ deleted: true, dirty: true });
      expect(syncSoon).toHaveBeenCalledTimes(1);
      expect(service.offer()).toEqual({ id: 'h1', name: 'Blue gate' });
      await vi.advanceTimersByTimeAsync(UNDO_MS - 1);
      expect(service.offer()).not.toBeNull();
      await vi.advanceTimersByTimeAsync(1);
      expect(service.offer()).toBeNull();
      expect(await store.getHouse('h1')).toBeUndefined();
    });

    it('lasts ten seconds', () => {
      expect(UNDO_MS).toBe(10_000);
    });

    it('Undo brings back the candidate and its photos with their bytes, and syncs', async () => {
      await store.saveHouse(house('h1', { street: 'MG Road' }), T0 - DAY);
      await store.photos.add('h1', jpeg(), 'p1', T0 - DAY);
      await store.photos.put({ ...(await store.photos.get('p1'))!, uploaded: true });
      await store.photos.add('h1', jpeg(), 'p2', T0 - DAY);
      await service.remove('h1', 'Blue gate');
      expect(await store.photos.ofHouse('h1')).toEqual([]);
      vi.setSystemTime(T0 + 3000);
      const said = vi.spyOn(TestBed.inject(Announcer), 'announce');
      await service.undo();
      expect(service.offer()).toBeNull();
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false, dirty: true, street: 'MG Road', updatedAt: new Date(T0 + 3000).toISOString() });
      const photos = await store.photos.ofHouse('h1');
      expect(photos).toHaveLength(2);
      expect(photos.every((p) => p.blob !== null && !p.uploaded)).toBe(true);
      expect(syncSoon).toHaveBeenCalledTimes(2);
      expect(said).toHaveBeenCalledWith({ key: 'delete.restored', params: { name: 'Blue gate' } });
    });

    it('removeMany: many at once with one Undo that brings every one back with its photos', async () => {
      await store.saveHouse(house('a'), T0 - DAY);
      await store.saveHouse(house('b'), T0 - DAY);
      await store.saveHouse(house('c'), T0 - DAY);
      await store.photos.add('a', jpeg(), 'p1', T0 - DAY);
      await store.photos.add('b', jpeg(), 'p2', T0 - DAY);
      expect(await service.removeMany(['a', 'b'], 'Houses')).toBe(2);
      expect((await store.liveHouses()).map((h) => h.id)).toEqual(['c']);
      expect(service.offer()).toEqual({ id: 'a', name: '2', ids: ['a', 'b'], kindWords: 'Houses' });
      expect(syncSoon).toHaveBeenCalledTimes(1);
      vi.setSystemTime(T0 + 2000);
      const said = vi.spyOn(TestBed.inject(Announcer), 'announce');
      await service.undo();
      expect((await store.liveHouses()).map((h) => h.id).sort()).toEqual(['a', 'b', 'c']);
      expect(await store.photos.ofHouse('a')).toHaveLength(1);
      expect(await store.photos.ofHouse('b')).toHaveLength(1);
      expect(said).toHaveBeenCalledWith({ key: 'delete.restoredMany', params: { n: 2, kind: 'Houses' } });
    });

    it('removeMany of nothing live offers nothing, and after ten seconds the many stay in the list', async () => {
      expect(await service.removeMany(['nope'], 'Houses')).toBe(0);
      expect(service.offer()).toBeNull();
      await store.saveHouse(house('a'), T0 - DAY);
      await store.saveHouse(house('b'), T0 - DAY);
      await service.removeMany(['a', 'b'], 'Houses');
      await vi.advanceTimersByTimeAsync(UNDO_MS);
      expect(service.offer()).toBeNull();
      expect((await service.bin(T0 + UNDO_MS)).map((e) => e.id).sort()).toEqual(['a', 'b']);
    });

    it('Undo after the time is up does nothing', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await service.remove('h1', 'Blue gate');
      await vi.advanceTimersByTimeAsync(UNDO_MS);
      await service.undo();
      expect(await store.getHouse('h1')).toBeUndefined();
      expect(syncSoon).toHaveBeenCalledTimes(1);
    });

    it('holds the time while the pointer or the focus is on the snackbar and starts it again on leaving', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await service.remove('h1', 'Blue gate');
      await vi.advanceTimersByTimeAsync(UNDO_MS - 1000);
      service.hold(true);
      await vi.advanceTimersByTimeAsync(60_000);
      expect(service.offer()).not.toBeNull();
      service.hold(false);
      await vi.advanceTimersByTimeAsync(UNDO_MS - 1);
      expect(service.offer()).not.toBeNull();
      await vi.advanceTimersByTimeAsync(1);
      expect(service.offer()).toBeNull();
    });

    it('holding with nothing offered does not start a timer', () => {
      service.hold(false);
      expect(service.offer()).toBeNull();
    });

    it('a second delete replaces the offer; the first stays in the list', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await store.saveHouse(house('h2'), T0 - DAY);
      await service.remove('h1', 'One');
      await service.remove('h2', 'Two');
      expect(service.offer()).toEqual({ id: 'h2', name: 'Two' });
      await service.undo();
      expect(await store.getHouse('h2')).toBeDefined();
      expect(await store.getHouse('h1')).toBeUndefined();
      expect((await service.bin()).map((e) => e.id)).toEqual(['h1']);
    });

    it('offers nothing and throws when the delete cannot be done', async () => {
      vi.spyOn(store, 'deleteHouse').mockRejectedValue(new Error('disk'));
      await store.saveHouse(house('h1'), T0 - DAY);
      await expect(service.remove('h1', 'x')).rejects.toThrow('disk');
      expect(service.offer()).toBeNull();
    });
  });

  describe('the list', () => {
    it('shows tombstones under 30 days with their photo count, newest first, and Restore removes the entry and syncs', async () => {
      await store.saveHouse(house('h1'), T0 - 9 * DAY);
      await store.saveHouse(house('h2', { label: '' , address: 'Temple Road' } as Partial<HouseDto>), T0 - 9 * DAY);
      await store.saveHouse(house('h3'), T0 - 90 * DAY);
      await store.photos.add('h1', jpeg(), 'p1', T0 - 9 * DAY);
      await store.deleteHouse('h3', T0 - 60 * DAY);
      vi.setSystemTime(T0 - 5 * DAY);
      await service.remove('h1', 'House h1');
      vi.setSystemTime(T0 - 2 * DAY);
      await service.remove('h2', 'Temple Road');
      service.dismiss();
      vi.setSystemTime(T0);
      const list = await service.bin();
      expect(list.map((e) => [e.id, e.name, e.photos])).toEqual([['h2', 'Temple Road', 0], ['h1', 'House h1', 1]]);
      expect(list[0].deletedAt).toBe(new Date(T0 - 2 * DAY).toISOString());
      const restored = await service.restore(list[1]);
      // no Google Drive is offered here: the photo that has no bytes left is not connected
      expect(restored).toMatchObject({ name: 'House h1', photosTotal: 1, photosBack: 0, photosFromDrive: 0, reasons: ['NOT_CONNECTED'] });
      expect((await service.bin()).map((e) => e.id)).toEqual(['h2']);
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false, dirty: true });
      expect(JSON.parse((await store.setting(SETTING_KEYS.binPhotos)) ?? '{}')).toEqual({});
    });

    it('says no photo was lost for a candidate that had none, and nothing for an entry restored elsewhere', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await store.deleteHouse('h1', T0 - 1000);
      const [entry] = await service.bin();
      expect(await service.restore(entry)).toMatchObject({ name: 'House h1', photosTotal: 0, photosBack: 0, photosFromDrive: 0, reasons: [] });
      expect(await service.restore(entry)).toBeUndefined();
    });

    it('keeps the notes of tombstones that are still listed and drops the others', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await store.saveHouse(house('h2'), T0 - DAY);
      await store.photos.add('h1', jpeg(), 'p1', T0 - DAY);
      await service.remove('h1', 'a');
      await service.remove('h2', 'b');
      expect(JSON.parse((await store.setting(SETTING_KEYS.binPhotos))!)).toEqual({ h1: 1 });
    });

    it('lists only the current space, and a restore in it writes no other space\'s row', async () => {
      localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
      localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
      localStorage.setItem(DEFAULT_KIND_KEY, 'house');
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [
          provideRouter([]),
          { provide: SyncService, useValue: { syncSoon } },
          { provide: StorageService, useValue: { requestPersistence: () => Promise.resolve() } },
        ],
      });
      store = TestBed.inject(LocalStore);
      service = TestBed.inject(DeleteUndoService);
      await store.saveHouse(house('h1'), T0 - DAY);
      await store.saveHouse(house('s1', { kind: 'school' }), T0 - DAY);
      await store.deleteHouse('h1', T0 - 1000);
      await store.deleteHouse('s1', T0 - 1000);
      // No page is open, so the space is the default one: the house.
      expect((await service.bin()).map((e) => e.id)).toEqual(['h1']);
      const school = JSON.stringify((await store.allHouses()).find((h) => h.id === 's1'));
      await service.restore((await service.bin())[0]);
      expect(JSON.stringify((await store.allHouses()).find((h) => h.id === 's1'))).toBe(school);
    });
  });

  describe('photos from Google Drive (S4b-BL-293)', () => {
    const seed = async (ids: string[] = ['p1', 'p2']) => {
      await store.saveHouse(house('h1'), T0 - 9 * DAY);
      for (const id of ids) {
        await store.photos.add('h1', jpeg(), id, T0 - 9 * DAY, { roomId: 'r1', tags: ['KITCHEN'], caption: id });
        await store.photos.put({ ...(await store.photos.get(id))!, uploaded: true });
      }
      vi.setSystemTime(T0 - 2 * DAY);
      await service.remove('h1', 'House h1');
      service.dismiss();
      vi.setSystemTime(T0);
      syncSoon.mockClear();
      const [entry] = await service.bin();
      return entry;
    };
    const restorerOf = (answer: (req: PhotoRestoreRequest) => PhotoRestoreReport | Promise<PhotoRestoreReport>) => {
      const calls: PhotoRestoreRequest[] = [];
      const restorer: PhotoRestorer = { restorePhotos: async (req) => { calls.push(req); return answer(req); } };
      return { restorer, calls };
    };
    const fetched = (req: PhotoRestoreRequest, which: string[]): PhotoRestoreReport['fetched'] =>
      req.known.filter((p) => which.includes(p.id)).map((photo) => ({ photo, bytes: new Uint8Array([9, 8, 7, 6]) }));
    const failedOf = (req: PhotoRestoreRequest, which: string[], reason: PhotoRestoreReason): PhotoRestoreReport['failed'] =>
      req.known.filter((p) => which.includes(p.id)).map((photo) => ({ photo, reason }));
    const livePhotos = async () => (await store.photos.ofHouse('h1'));

    it('asks Drive for the photos that have no bytes left, with the candidate and when it was deleted, and puts every one back', async () => {
      const entry = await seed();
      const { restorer, calls } = restorerOf((req) => ({ connection: 'OK', fetched: fetched(req, ['p1', 'p2']), failed: [] }));
      const out = await service.restore(entry, T0, restorer);
      expect(out).toMatchObject({ name: 'House h1', photosTotal: 2, photosBack: 2, photosFromDrive: 2, reasons: [] });
      expect(calls).toHaveLength(1);
      expect(calls[0].houseId).toBe('h1');
      expect(calls[0].houseDeletedAt).toBe(T0 - 2 * DAY);
      expect(calls[0].known.map((p) => p.id).sort()).toEqual(['p1', 'p2']);
      const back = await livePhotos();
      expect(back).toHaveLength(2);
      for (const p of back) {
        expect(['p1', 'p2']).not.toContain(p.id); // new ids: the old ones are tombstones every device keeps
        expect(p).toMatchObject({ houseId: 'h1', deleted: false, uploaded: false, sizeBytes: 4, roomId: 'r1', tags: ['KITCHEN'], metaDirty: true });
        expect(new Uint8Array(await p.blob!.arrayBuffer())).toEqual(new Uint8Array([9, 8, 7, 6]));
      }
      expect(back.map((p) => p.caption).sort()).toEqual(['p1', 'p2']);
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false, dirty: true });
      expect(syncSoon).toHaveBeenCalledTimes(1);
    });

    it('some photos come back and the others say why', async () => {
      const entry = await seed();
      const { restorer } = restorerOf((req) => ({ connection: 'OK', fetched: fetched(req, ['p1']), failed: failedOf(req, ['p2'], 'MISSING') }));
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ name: 'House h1', photosTotal: 2, photosBack: 1, photosFromDrive: 1, reasons: ['MISSING'] });
      expect(await livePhotos()).toHaveLength(1);
    });

    it('Google Drive not connected: no photo comes back and it says so', async () => {
      const entry = await seed();
      const { restorer } = restorerOf((req) => notConnectedReport(req.known));
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ name: 'House h1', photosTotal: 2, photosBack: 0, photosFromDrive: 0, reasons: ['NOT_CONNECTED'] });
      expect(await livePhotos()).toEqual([]);
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false });
    });

    it('no Google Drive service at all reads as not connected', async () => {
      const entry = await seed();
      expect((await service.restore(entry, T0, null))?.reasons).toEqual(['NOT_CONNECTED']);
    });

    it.each([['PAST_WINDOW', 'OK'], ['OFFLINE', 'OFFLINE'], ['UNREADABLE', 'OK']] as const)('%s: the photo stays away and the reason is said', async (reason, connection) => {
      const entry = await seed(['p1']);
      const { restorer } = restorerOf((req) => ({ connection, fetched: [], failed: failedOf(req, ['p1'], reason) }));
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ name: 'House h1', photosTotal: 1, photosBack: 0, photosFromDrive: 0, reasons: [reason] });
      expect(await livePhotos()).toEqual([]);
    });

    it('photos that left no tombstone here are still counted from the note and say why they are not back', async () => {
      await store.saveHouse(house('h1'), T0 - 9 * DAY);
      await store.photos.add('h1', jpeg(), 'u1', T0 - 9 * DAY); // never uploaded: a delete removes it outright
      await store.photos.add('h1', jpeg(), 'u2', T0 - 9 * DAY);
      await service.remove('h1', 'House h1');
      service.dismiss();
      const [entry] = await service.bin();
      expect(entry.photos).toBe(2);
      const { restorer } = restorerOf((req) => notConnectedReport(req.known));
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ name: 'House h1', photosTotal: 2, photosBack: 0, photosFromDrive: 0, reasons: ['NOT_CONNECTED'] });
    });

    it('a candidate deleted on another device (no note, no tombstone here) still gets what the sync file names', async () => {
      await store.saveHouse(house('h1'), T0 - 9 * DAY);
      await store.deleteHouse('h1', T0 - DAY);
      const [entry] = await service.bin();
      const { restorer, calls } = restorerOf((req) => ({
        connection: 'OK',
        fetched: [{ photo: { id: 'far', deletedAt: T0 - DAY, contentType: 'image/jpeg', createdAt: null, meta: { roomId: null, tags: [], caption: null, metaUpdatedAt: 0 }, ref: null }, bytes: new Uint8Array([1]) }],
        failed: failedOf(req, [], 'MISSING'),
      }));
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ name: 'House h1', photosTotal: 1, photosBack: 1, photosFromDrive: 1, reasons: [] });
      expect(calls[0].known).toEqual([]);
      expect(await livePhotos()).toHaveLength(1);
    });

    it('a Drive that throws never fails the candidate\'s own restore', async () => {
      const entry = await seed(['p1']);
      const { restorer } = restorerOf(() => { throw new Error('boom'); });
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ photosBack: 0, reasons: ['NOT_CONNECTED'] });
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false });
    });

    it('a photo that cannot be stored is counted as not restored and the candidate is still restored', async () => {
      const entry = await seed(['q1']);
      vi.spyOn(store.photos, 'put').mockRejectedValueOnce(new Error('quota'));
      const { restorer } = restorerOf((req) => ({ connection: 'OK', fetched: fetched(req, ['q1']), failed: [] }));
      expect(await service.restore(entry, T0, restorer)).toMatchObject({ photosTotal: 1, photosBack: 0, photosFromDrive: 0, reasons: ['UNREADABLE'] });
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false });
    });
  });

  describe('the retry carries what earlier attempts settled (S4b-BL-293)', () => {
    const seed = async (ids: string[]) => {
      await store.saveHouse(house('h1'), T0 - 9 * DAY);
      for (const id of ids) {
        await store.photos.add('h1', jpeg(), id, T0 - 9 * DAY);
        await store.photos.put({ ...(await store.photos.get(id))!, uploaded: true });
      }
      vi.setSystemTime(T0 - 2 * DAY);
      await service.remove('h1', 'House h1');
      service.dismiss();
      vi.setSystemTime(T0);
      return (await service.bin())[0];
    };
    const restorerOf = (answer: (req: PhotoRestoreRequest) => PhotoRestoreReport) => {
      const calls: PhotoRestoreRequest[] = [];
      const restorer: PhotoRestorer = { restorePhotos: async (req) => { calls.push(req); return answer(req); } };
      return { restorer, calls };
    };
    const only = (req: PhotoRestoreRequest, ids: string[]) => req.known.filter((p) => ids.includes(p.id));
    const bytes = new Uint8Array([9, 8]);

    it('never stores a photo twice, though the sync file lists every photo of the candidate on each read', async () => {
      const entry = await seed(['p1', 'p2']);
      const all: PhotoRestoreRequest['known'][number][] = [];
      const { restorer, calls } = restorerOf((req) => {
        if (!all.length) all.push(...req.known);
        // the device's own sync file names every photo again each time, whatever was asked
        return { connection: 'OK', fetched: [{ photo: all[0], bytes }, ...(calls.length > 1 ? [{ photo: all[1], bytes }] : [])], failed: calls.length > 1 ? [] : [{ photo: all[1], reason: 'OFFLINE' as const }] };
      });
      const put = vi.spyOn(store.photos, 'put');
      const first = await service.restore(entry, T0, restorer);
      expect(first).toMatchObject({ photosBack: 1, reasons: ['OFFLINE'] });
      const second = await service.retryPhotos(first!.retry!, T0, restorer);
      expect(second).toMatchObject({ photosTotal: 2, photosBack: 2, photosFromDrive: 2, reasons: [] });
      expect(put).toHaveBeenCalledTimes(2);
      expect(await store.photos.ofHouse('h1')).toHaveLength(2);
      expect(second!.retry).toBeNull();
    });

    it('adds the words up across attempts: what came back and why the others did not, each reason once', async () => {
      const entry = await seed(['p1', 'p2', 'p3']);
      let n = 0;
      const { restorer } = restorerOf((req) => (n++ === 0
        ? { connection: 'OK', fetched: only(req, ['p1']).map((photo) => ({ photo, bytes })), failed: [{ photo: only(req, ['p2'])[0], reason: 'OFFLINE' }, { photo: only(req, ['p3'])[0], reason: 'PAST_WINDOW' }] }
        : { connection: 'OK', fetched: only(req, ['p2']).map((photo) => ({ photo, bytes })), failed: [] }));
      const first = (await service.restore(entry, T0, restorer))!;
      expect(first).toMatchObject({ photosTotal: 3, photosBack: 1, photosFromDrive: 1 });
      expect([...first.reasons].sort()).toEqual(['OFFLINE', 'PAST_WINDOW']);
      expect(first.retry).toMatchObject({ back: 1, settledReasons: ['PAST_WINDOW'] });
      const second = (await service.retryPhotos(first.retry!, T0, restorer))!;
      expect(second).toMatchObject({ photosTotal: 3, photosBack: 2, photosFromDrive: 2, reasons: ['PAST_WINDOW'] });
    });

    it('a failure a retry cannot fix (past the window, missing) is settled and never fetched again', async () => {
      const entry = await seed(['p1', 'p2', 'p3']);
      const { restorer, calls } = restorerOf((req) => ({
        connection: 'OK',
        fetched: [],
        failed: req.known.map((photo) => ({ photo, reason: photo.id === 'p1' ? ('OFFLINE' as const) : photo.id === 'p2' ? ('PAST_WINDOW' as const) : ('MISSING' as const) })),
      }));
      const first = (await service.restore(entry, T0, restorer))!;
      expect([...first.retry!.settled].sort()).toEqual(['p2', 'p3']);
      expect(first.retry!.known.map((p) => p.id)).toEqual(['p1']);
      const second = (await service.retryPhotos(first.retry!, T0, restorer))!;
      expect(calls[1].known.map((p) => p.id)).toEqual(['p1']);
      expect(second.retry!.known.map((p) => p.id)).toEqual(['p1']);
      expect([...second.retry!.settled].sort()).toEqual(['p2', 'p3']);
      expect([...second.retry!.settledReasons].sort()).toEqual(['MISSING', 'PAST_WINDOW']);
    });

    it('restore and retryPhotos both go through the one photosBackFor and give the same result for the same input', async () => {
      const entry = await seed(['p1', 'p2']);
      const { restorer } = restorerOf((req) => ({ connection: 'OK', fetched: only(req, ['p1']).map((photo) => ({ photo, bytes })), failed: only(req, ['p2']).map((photo) => ({ photo, reason: 'OFFLINE' as const })) }));
      const through = vi.spyOn(service as unknown as { photosBackFor: (...a: unknown[]) => unknown }, 'photosBackFor');
      const viaRestore = (await service.restore(entry, T0, restorer))!;
      // the very input the restore built, handed to a retry
      const start = through.mock.calls[0][0] as Parameters<DeleteUndoService['retryPhotos']>[0];
      const viaRetry = await service.retryPhotos(start, T0, restorer);
      expect(through).toHaveBeenCalledTimes(2);
      expect(viaRetry).toEqual(viaRestore);
    });

    it('a retry with nothing left to retry disappears', async () => {
      const entry = await seed(['p1', 'p2']);
      const { restorer } = restorerOf((req) => ({ connection: 'OK', fetched: only(req, ['p1']).map((photo) => ({ photo, bytes })), failed: only(req, ['p2']).map((photo) => ({ photo, reason: 'OFFLINE' as const })) }));
      const first = (await service.restore(entry, T0, restorer))!;
      expect(first.retry).not.toBeNull();
      const { restorer: again } = restorerOf((req) => ({ connection: 'OK', fetched: only(req, ['p2']).map((photo) => ({ photo, bytes })), failed: [] }));
      expect((await service.retryPhotos(first.retry!, T0, again))!.retry).toBeNull();
      // and a failure no second try can fix offers none either
      const { restorer: gone } = restorerOf((req) => ({ connection: 'OK', fetched: [], failed: req.known.map((photo) => ({ photo, reason: 'PAST_WINDOW' as const })) }));
      expect((await service.retryPhotos(first.retry!, T0, gone))!.retry).toBeNull();
    });

    it('offers the retry again while Drive is still not connected, over the same photos', async () => {
      const entry = await seed(['p1', 'p2']);
      const first = (await service.restore(entry, T0, null))!;
      expect(first).toMatchObject({ photosBack: 0, reasons: ['NOT_CONNECTED'] });
      expect(first.retry!.known.map((p) => p.id).sort()).toEqual(['p1', 'p2']);
      const second = (await service.retryPhotos(first.retry!, T0, null))!;
      expect(second.retry!.known.map((p) => p.id).sort()).toEqual(['p1', 'p2']);
    });
  });
});
