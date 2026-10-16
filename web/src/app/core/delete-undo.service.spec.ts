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
      expect(restored).toEqual({ name: 'House h1', photosGone: 1 });
      expect((await service.bin()).map((e) => e.id)).toEqual(['h2']);
      expect(await store.getHouse('h1')).toMatchObject({ deleted: false, dirty: true });
      expect(JSON.parse((await store.setting(SETTING_KEYS.binPhotos)) ?? '{}')).toEqual({});
    });

    it('says no photo was lost for a candidate that had none, and nothing for an entry restored elsewhere', async () => {
      await store.saveHouse(house('h1'), T0 - DAY);
      await store.deleteHouse('h1', T0 - 1000);
      const [entry] = await service.bin();
      expect(await service.restore(entry)).toEqual({ name: 'House h1', photosGone: 0 });
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
});
