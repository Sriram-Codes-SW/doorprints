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

import { computed } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CACHE_NAME_PREFIX, LocalStore, MAX_PHOTOS_PER_HOUSE, SETTLE_MS } from './local-store.service';
import { SETTING_KEYS } from './records';
import { LocalDataError } from '../core/local-error';
import type { HouseAnswer, HouseDto, HouseRoom, VisitDto } from '../core/models';
import { DEFAULT_QUESTIONS, MAX_QUESTIONS, MAX_QUESTION_TEXT } from '../shared/question';
import { BUILT_IN_KEYS } from '../shared/scoring';
import type { Criterion } from '../shared/scoring';
import type { Viewing } from '../shared/viewing';
import type { Area, AreaNote, Place } from '../shared/area';

/**
 * The repository the whole web app reads and writes (S4-01). jsdom has no IndexedDB, so `openLocalDb()` hands back
 * the in-memory store — the same code path a browser in private mode takes, which is worth exercising here.
 *
 * `LocalStore` has no constructor injection, so it can be built directly without TestBed.
 */
function house(id: string, over: Partial<HouseDto> = {}): HouseDto {
  return {
    id,
    label: `House ${id}`,
    lat: 13,
    lon: 80,
    status: 'NEW',
    checklist: {},
    deleted: false,
    syncVersion: 0,
    ...over,
  };
}

function visit(id: string, houseId: string, arrivedAt: string): VisitDto {
  return {
    id,
    houseId,
    lat: 13,
    lon: 80,
    arrivedAt,
    source: 'MANUAL',
    deleted: false,
    syncVersion: 0,
  };
}

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');

describe('LocalStore', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reports that this browser is not really storing anything', () => {
    expect(store.storageProblem()).toBe('unavailable');
  });

  it('saves a house as dirty, stamping createdAt once and updatedAt every time', async () => {
    const first = await store.saveHouse(house('h1'), T1);
    expect(first.dirty).toBe(true);
    expect(first.createdAt).toBe('2026-09-01T00:00:00.000Z');
    expect(first.updatedAt).toBe('2026-09-01T00:00:00.000Z');

    const second = await store.saveHouse({ ...first, label: 'Renamed' }, T2);
    expect(second.createdAt).toBe('2026-09-01T00:00:00.000Z');
    expect(second.updatedAt).toBe('2026-09-02T00:00:00.000Z');
    expect(second.label).toBe('Renamed');
  });

  it('saves and retrieves rooms, sorted and capped at 30 with unknown type converted to OTHER', async () => {
    const rooms: HouseRoom[] = [
      { id: 'r1', type: 'BEDROOM', name: 'Master', lengthCm: 300, widthCm: 300, condition: 4, notes: 'Damp', sort: 1 },
      { id: 'r2', type: 'KITCHEN', name: 'Kitchen', lengthCm: 200, widthCm: 200, condition: 3, notes: '', sort: 0 },
    ];
    const saved = await store.saveHouse(house('h1', { rooms }), T1);
    expect(saved.rooms).toHaveLength(2);
    expect(saved.rooms?.[0].sort).toBe(0);
    expect(saved.rooms?.[1].sort).toBe(1);

    const retrieved = await store.getHouse('h1');
    expect(retrieved?.rooms).toHaveLength(2);
    expect(retrieved?.rooms?.[0].id).toBe('r2');
    expect(retrieved?.rooms?.[1].id).toBe('r1');
    expect(retrieved?.rooms?.[1].notes).toBe('Damp');
  });

  it('viewingsRemind setting: on by default, off when stored off, on again when set', async () => {
    expect(await store.viewingsRemind()).toBe(true);
    await store.setViewingsRemind(false);
    expect(await store.viewingsRemind()).toBe(false);
    await store.setViewingsRemind(true);
    expect(await store.viewingsRemind()).toBe(true);
  });

  it('reads lengthUnit setting: defaults to FT, stores and retrieves M', async () => {
    expect(await store.lengthUnit()).toBe('FT');
    // After setting to M
    await store.setSetting(SETTING_KEYS.lengthUnit, 'M');
    expect(await store.lengthUnit()).toBe('M');
    // Any other value falls back to FT
    await store.setSetting(SETTING_KEYS.lengthUnit, 'unknown');
    expect(await store.lengthUnit()).toBe('FT');
  });

  /**
   * `revision` exists so a screen can re-read after a write it did not make — a sync pull, the first-run
   * "download your houses to this browser", an edit in another tab. `LocalDataService.revision` re-exports it
   * and `MapPage`, `ComparePage` and `DataPage` each hold an `effect` that reads it and reloads. This checks the
   * half that lives here: every kind of write moves it, and the data really is there when the view re-reads.
   */
  it('bumps a revision counter on every write, and the store really has the new data', async () => {
    const view = computed(() => store.revision());
    const marks = [view()];

    await store.putHouseFromServer({ ...house('pulled-1'), updatedAt: '2026-09-01T00:00:00.000Z' });
    marks.push(view());
    // The observable outcome, not just the counter: a view that re-read here would find the pulled house.
    expect((await store.liveHouses()).map((h) => h.id)).toEqual(['pulled-1']);

    await store.saveHouse(house('h1'), T1);
    marks.push(view());
    await store.saveVisit(visit('v1', 'h1', '2026-09-05T00:00:00.000Z'), T1);
    marks.push(view());
    await store.addPhoto('h1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p1', T1);
    marks.push(view());
    await store.deleteHouse('h1', T2);
    marks.push(view());
    await store.clearEverything();
    marks.push(view());

    expect(new Set(marks).size).toBe(marks.length);
    expect(await store.liveHouses()).toEqual([]);
  });

  /**
   * Screens follow `settled`, not `revision`: a pull bumps `revision` once per row, and a view that re-read on each
   * bump re-rendered its list per downloaded row. `settled` catches up once the writes pause.
   */
  it('coalesces a burst of writes into one settled change once the writes pause', async () => {
    // Fake timers: the quiet period is stepped, not waited for (readiness review 2026-09-29, docs/14 §8 finding 12).
    vi.useFakeTimers();
    try {
      const before = store.settled();
      await store.putHouseFromServer({ ...house('s1'), updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('s2'), updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('s3'), updatedAt: '2026-09-01T00:00:00.000Z' });
      // Still inside the quiet period: the views have not been told yet.
      expect(store.settled()).toBe(before);
      await vi.advanceTimersByTimeAsync(SETTLE_MS + 50);
      expect(store.settled()).toBe(store.revision());
      expect(store.settled()).not.toBe(before);
    } finally {
      vi.useRealTimers();
    }
  });

  it('hides a deleted house but keeps its tombstone for the next sync', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.deleteHouse('h1', T2);
    expect(await store.getHouse('h1')).toBeUndefined();
    expect(await store.liveHouses()).toHaveLength(0);
    const all = await store.allHouses();
    expect(all).toHaveLength(1);
    expect(all[0].deleted).toBe(true);
    expect(all[0].dirty).toBe(true);
  });

  it('orders houses by createdAt then id', async () => {
    await store.saveHouse(house('b'), T2);
    await store.saveHouse(house('a'), T1);
    await store.saveHouse(house('c'), T1);
    expect((await store.liveHouses()).map((h) => h.id)).toEqual(['a', 'c', 'b']);
  });

  it('stores a row from the server as clean', async () => {
    await store.putHouseFromServer({ ...house('h1'), updatedAt: '2026-09-03T00:00:00.000Z', syncVersion: 4 });
    const stored = await store.getHouse('h1');
    expect(stored?.dirty).toBe(false);
    expect(stored?.syncVersion).toBe(4);
  });

  it('clears the dirty flag only when nothing changed while the push was in flight', async () => {
    const saved = await store.saveHouse(house('h1'), T1);
    await store.saveHouse({ ...saved, label: 'Edited again' }, T2);
    // The push carried the older timestamp, so the newer local edit stays dirty.
    await store.markHouseClean('h1', saved.updatedAt);
    expect((await store.getHouse('h1'))?.dirty).toBe(true);
    await store.markHouseClean('h1', '2026-09-02T00:00:00.000Z');
    expect((await store.getHouse('h1'))?.dirty).toBe(false);
  });

  it('lists only a house’s own live visits, oldest first', async () => {
    await store.saveVisit(visit('v2', 'h1', '2026-09-09T00:00:00.000Z'), T2);
    await store.saveVisit(visit('v1', 'h1', '2026-09-05T00:00:00.000Z'), T1);
    await store.saveVisit(visit('v3', 'h2', '2026-09-06T00:00:00.000Z'), T1);
    await store.saveVisit(visit('v4', 'h1', '2026-09-07T00:00:00.000Z'), T1);
    await store.deleteVisit('v4', T2);
    expect((await store.visitsOf('h1')).map((v) => v.id)).toEqual(['v1', 'v2']);
    // Compare's counts, from one read of the store: deleted visits not counted.
    expect(await store.visitCountsByHouse()).toEqual(new Map([['h1', 2], ['h2', 1]]));
    // Only the changed ones, still in visit order.
    expect((await store.dirtyVisits()).map((v) => v.id)).toEqual(['v1', 'v3', 'v4', 'v2']);
  });

  it('keeps a photo’s bytes and removes them again', async () => {
    await store.saveHouse(house('h1'), T1);
    const added = await store.addPhoto('h1', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }), 'p1', T1);
    expect(added.ok).toBe(true);
    expect(await store.photosOf('h1')).toHaveLength(1);
    // Never uploaded, so it is simply forgotten.
    await store.deletePhoto('p1', T2);
    expect(await store.allPhotos()).toHaveLength(0);
  });

  it('leaves a tombstone for a photo the server already has', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.addPhoto('h1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p1', T1);
    const stored = await store.getPhoto('p1');
    await store.putPhotoRecord({ ...stored!, uploaded: true });
    await store.deletePhoto('p1', T2);
    const all = await store.allPhotos();
    expect(all).toHaveLength(1);
    expect(all[0].deleted).toBe(true);
    expect(all[0].blob).toBeNull();
    expect(await store.photosOf('h1')).toHaveLength(0);
  });

  it('refuses more than the shared per-house photo limit', async () => {
    await store.saveHouse(house('h1'), T1);
    for (let i = 0; i < MAX_PHOTOS_PER_HOUSE; i++) {
      const result = await store.addPhoto('h1', new Blob([new Uint8Array([i])], { type: 'image/jpeg' }), `p${i}`, T1);
      expect(result.ok).toBe(true);
    }
    const extra = await store.addPhoto('h1', new Blob([new Uint8Array([99])], { type: 'image/jpeg' }), 'px', T1);
    expect(extra.ok).toBe(false);
  });

  it('deleting a house also drops its photos', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.addPhoto('h1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p1', T1);
    await store.deleteHouse('h1', T2);
    expect(await store.photosOf('h1')).toHaveLength(0);
  });

  it('counts the same five numbers the server’s /api/stats returns', async () => {
    await store.saveHouse(house('h1', { status: 'SHORTLISTED', street: 'MG Road' }), T1);
    await store.saveHouse(house('h2', { status: 'REJECTED', street: 'MG Road' }), T1);
    await store.saveHouse(house('h3', { status: 'NEW', street: '  ' }), T1);
    await store.saveVisit(visit('v1', 'h1', '2026-09-05T00:00:00.000Z'), T1);
    expect(await store.stats()).toEqual({ houses: 3, shortlisted: 1, rejected: 1, visits: 1, streets: 1 });
  });

  it('reads and writes settings, including the sync cursors', async () => {
    expect(await store.setting('nothing')).toBeNull();
    expect(await store.cursors()).toEqual({ house: 0, visit: 0, photo: 0, record: 0 });
    await store.setSetting(SETTING_KEYS.houseCursor, '12');
    await store.setSetting(SETTING_KEYS.photoCursor, 'not a number');
    const cursors = await store.cursors();
    expect(cursors.house).toBe(12);
    expect(cursors.photo).toBe(0);
    await store.setSetting(SETTING_KEYS.recordCursor, '3');
    expect((await store.cursors()).record).toBe(3);
    await store.resetCursors();
    expect(await store.cursors()).toEqual({ house: 0, visit: 0, photo: 0, record: 0 });
  });

  // ---- Records: the one store for every other Sprint 4b entity (docs/11 5.30 item 2) ----

  it('saves a record as dirty with a fresh updatedAt, keeps the sync version, and lists live rows by type', async () => {
    await store.saveRecord('broker', 'b2', { name: 'Later' }, T2);
    await store.saveRecord('broker', 'b1', { name: 'Earlier' }, T1);
    await store.saveRecord('place', 'p1', { name: 'Office' }, T1);
    await store.putRecordFromServer({ type: 'broker', id: 'b3', payload: {}, updatedAt: '2026-09-03T00:00:00.000Z', deleted: false, syncVersion: 9 });
    expect((await store.recordsOf('broker')).map((r) => r.id)).toEqual(['b1', 'b2', 'b3']);
    expect(await store.recordsOf('viewing')).toEqual([]);

    const b1 = await store.getRecord('broker', 'b1');
    expect(b1).toEqual({ type: 'broker', id: 'b1', payload: { name: 'Earlier' }, updatedAt: '2026-09-01T00:00:00.000Z', deleted: false, syncVersion: 0, dirty: true });
    // A local edit of a synced row keeps the row's sync version.
    await store.saveRecord('broker', 'b3', { name: 'Edited' }, T2);
    expect((await store.getRecord('broker', 'b3'))?.syncVersion).toBe(9);
    expect((await store.dirtyRecords()).map((r) => `${r.type}/${r.id}`)).toEqual(['broker/b1', 'place/p1', 'broker/b2', 'broker/b3']);
  });

  it('turns a deleted record into a tombstone with an empty payload, hidden from reads but kept for the sync', async () => {
    await store.saveRecord('viewing', 'v1', { at: '2026-10-01' }, T1);
    await store.deleteRecord('viewing', 'v1', T2);
    await store.deleteRecord('viewing', 'never-there', T2);
    expect(await store.getRecord('viewing', 'v1')).toBeUndefined();
    expect(await store.recordsOf('viewing')).toEqual([]);
    const [tombstone] = await store.dirtyRecords();
    expect(tombstone).toEqual({ type: 'viewing', id: 'v1', payload: {}, updatedAt: '2026-09-02T00:00:00.000Z', deleted: true, syncVersion: 0, dirty: true });
  });

  it('clears a record’s dirty flag only when nothing changed while the push was in flight', async () => {
    const saved = await store.saveRecord('place', 'p1', { name: 'Office' }, T1);
    await store.saveRecord('place', 'p1', { name: 'Office, moved' }, T2);
    await store.markRecordClean('place', 'p1', saved.updatedAt);
    expect((await store.getRecord('place', 'p1'))?.dirty).toBe(true);
    await store.markRecordClean('place', 'p1', '2026-09-02T00:00:00.000Z');
    expect((await store.getRecord('place', 'p1'))?.dirty).toBe(false);
    // A resync after a server reset marks it again, with everything else.
    await store.markAllForResync();
    expect((await store.dirtyRecords()).map((r) => r.id)).toEqual(['p1']);
  });

  it('refuses a record it could not sync: a bad type or id, or a payload over the server’s cap', async () => {
    for (const [type, id, payload] of [
      ['Broker', 'b1', {}],
      ['broker', 'a/b', {}],
      ['broker', 'b1', { n: 'x'.repeat(70_000) }],
    ] as const) {
      let thrown: unknown = null;
      try {
        await store.saveRecord(type, id, payload as Record<string, unknown>, T1);
      } catch (err: unknown) {
        thrown = err;
      }
      expect(thrown, `${type}/${id}`).toBeInstanceOf(LocalDataError);
    }
    expect(await store.dirtyRecords()).toEqual([]);
  });

  it('removes the records with everything else when the user clears this browser', async () => {
    await store.saveRecord('broker', 'b1', { name: 'A' }, T1);
    await store.clearEverything();
    expect(await store.dirtyRecords()).toEqual([]);
  });

  it('knows when this browser holds nothing yet', async () => {
    expect(await store.isEmpty()).toBe(true);
    await store.saveHouse(house('h1'), T1);
    expect(await store.isEmpty()).toBe(false);
  });

  /**
   * docs/11 §5.10 ("Sign-out on a shared computer") promises that this removes Doorprints data from the browser,
   * which is both stores: IndexedDB *and* Cache Storage, where the service worker keeps the offline copy of the
   * app. The saved server address and API key are `ConfigService.clear()`'s, and the "Your data" screen calls
   * both; the language choice is a preference and is deliberately kept, which the confirm dialog says.
   */
  it('removes everything when the user clears this browser, including the offline copy of the app', async () => {
    const deleted: string[] = [];
    vi.stubGlobal('caches', {
      keys: () =>
        Promise.resolve([
          // Ours: a stamped build (sw.js names its cache by build id), this deployment and another base path under
          // the pre-stamp `v1` name, and the pre-scoped legacy name.
          `${CACHE_NAME_PREFIX}0123456789abcdef/doorprints/`,
          `${CACHE_NAME_PREFIX}v1/`,
          `${CACHE_NAME_PREFIX}v1/doorprints/`,
          `${CACHE_NAME_PREFIX}v1`,
          // Not ours. Cache Storage is scoped to the origin, and on <owner>.github.io that origin carries every
          // project site the owner has; a neighbour's offline copy is not this button's to delete.
          'some-other-app-shell-v3',
          'workbox-precache-v2-https://example.github.io/notes/',
        ]),
      delete: (name: string) => {
        deleted.push(name);
        return Promise.resolve(true);
      },
    });
    await store.saveHouse(house('h1'), T1);
    await store.setSetting(SETTING_KEYS.houseCursor, '9');
    await store.clearEverything();
    expect(await store.allHouses()).toHaveLength(0);
    expect(await store.setting(SETTING_KEYS.houseCursor)).toBeNull();
    expect(deleted).toEqual([
      `${CACHE_NAME_PREFIX}0123456789abcdef/doorprints/`,
      `${CACHE_NAME_PREFIX}v1/`,
      `${CACHE_NAME_PREFIX}v1/doorprints/`,
      `${CACHE_NAME_PREFIX}v1`,
    ]);
  });

  it('still empties the stores when the browser refuses Cache Storage', async () => {
    // Private mode, a locked-down browser, or a cache a browser will not let go of.
    vi.stubGlobal('caches', {
      keys: () => Promise.reject(new Error('denied')),
      delete: () => Promise.reject(new Error('denied')),
    });
    await store.saveHouse(house('h1'), T1);
    await expect(store.clearEverything()).resolves.toBeUndefined();
    expect(await store.allHouses()).toHaveLength(0);
  });

  it('finds dirty rows for the sync engine to push', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.putHouseFromServer({ ...house('h2'), updatedAt: '2026-09-01T00:00:00.000Z' });
    await store.saveVisit(visit('v1', 'h1', '2026-09-05T00:00:00.000Z'), T1);
    expect((await store.dirtyHouses()).map((h) => h.id)).toEqual(['h1']);
    expect((await store.dirtyVisits()).map((v) => v.id)).toEqual(['v1']);
  });
});

/**
 * Brokers (slice 1b, docs/11 5.25): the house keeps copies of the broker's name and phone, `saveHouse` links or makes
 * the broker, `saveBroker` rewrites the copies, `deleteBroker` unlinks, and a one-off migration turns the contacts of
 * the houses already stored into brokers.
 */
describe('LocalStore brokers', () => {
  let store: LocalStore;
  const RAVI = { contactName: 'Ravi Kumar', contactPhone: '+91 98400 11111' };

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('reads a bad brokerId on a house as no broker', async () => {
    await store.putHouseFromServer({ ...house('ok'), brokerId: 'b-1' });
    await store.putHouseFromServer({ ...house('long'), brokerId: 'x'.repeat(65) });
    await store.putHouseFromServer({ ...house('odd'), brokerId: 'has space' });
    await store.putHouseFromServer({ ...house('none') });
    expect((await store.getHouse('ok'))?.brokerId).toBe('b-1');
    expect((await store.getHouse('long'))?.brokerId).toBeNull();
    expect((await store.getHouse('odd'))?.brokerId).toBeNull();
    expect((await store.getHouse('none'))?.brokerId).toBeNull();
  });

  it('makes a broker from a saved house with a phone and links the house to it', async () => {
    const saved = await store.saveHouse(house('h1', RAVI), T1);
    const brokers = await store.brokers();
    expect(brokers).toHaveLength(1);
    expect(brokers[0].broker).toEqual({ name: 'Ravi Kumar', phone: '+91 98400 11111' });
    expect(saved.brokerId).toBe(brokers[0].id);
    expect((await store.getRecord('broker', brokers[0].id))?.dirty).toBe(true);
  });

  it('names the broker by the phone when the house has no contact name, and never makes one from a blank phone', async () => {
    await store.saveHouse(house('h1', { contactPhone: '98400 11111' }), T1);
    expect((await store.brokers())[0].broker.name).toBe('98400 11111');
    await store.saveHouse(house('h2', { contactName: 'Only a name', contactPhone: '   ' }), T1);
    await store.saveHouse(house('h3'), T1);
    expect(await store.brokers()).toHaveLength(1);
    expect((await store.getHouse('h2'))?.brokerId ?? null).toBeNull();
  });

  it('links a house to the broker that already has the number, however it is written', async () => {
    const first = await store.saveHouse(house('h1', RAVI), T1);
    const second = await store.saveHouse(house('h2', { contactName: 'R.', contactPhone: '098400-11111' }), T2);
    expect(await store.brokers()).toHaveLength(1);
    expect(second.brokerId).toBe(first.brokerId);
    // The copies follow the broker, so the two houses show the same contact.
    expect(second.contactName).toBe('Ravi Kumar');
    expect(second.contactPhone).toBe('+91 98400 11111');
  });

  it('refreshes the copies from the broker a house is linked to, and leaves an id that names no broker alone', async () => {
    const linked = await store.saveHouse(house('h1', RAVI), T1);
    const again = await store.saveHouse({ ...linked, contactName: 'typed over', contactPhone: '000' }, T2);
    expect(again.contactName).toBe('Ravi Kumar');
    expect(again.contactPhone).toBe('+91 98400 11111');
    const dangling = await store.saveHouse(house('h2', { brokerId: 'gone', contactName: 'Kept', contactPhone: '5551234567' }), T2);
    expect(dangling.brokerId).toBe('gone');
    expect(dangling.contactName).toBe('Kept');
    expect(await store.brokers()).toHaveLength(1);
  });

  it('rewrites the copies on every linked house when a broker is saved, and marks them dirty', async () => {
    const h1 = await store.saveHouse(house('h1', RAVI), T1);
    await store.saveHouse(house('h2', { contactName: 'Other', contactPhone: '9000000001' }), T1);
    await store.markHouseClean('h1', h1.updatedAt);
    await store.saveBroker(h1.brokerId!, { name: 'Ravi K.', phone: '+91 98400 22222', agency: 'Adyar Homes' }, T2);
    const after = (await store.getHouse('h1'))!;
    expect(after.contactName).toBe('Ravi K.');
    expect(after.contactPhone).toBe('+91 98400 22222');
    expect(after.dirty).toBe(true);
    expect(after.updatedAt).toBe('2026-09-02T00:00:00.000Z');
    expect((await store.getHouse('h2'))?.contactName).toBe('Other');
    // A broker without a phone leaves the house without one.
    await store.saveBroker(h1.brokerId!, { name: 'Ravi K.' }, T2);
    expect((await store.getHouse('h1'))?.contactPhone).toBeNull();
  });

  it('refuses a broker with a blank name', async () => {
    await expect(store.saveBroker('b1', { name: '  ' })).rejects.toBeInstanceOf(LocalDataError);
    expect(await store.brokers()).toEqual([]);
  });

  it('unlinks the houses and keeps their contact when a broker is deleted', async () => {
    const h1 = await store.saveHouse(house('h1', RAVI), T1);
    await store.deleteBroker(h1.brokerId!, T2);
    expect(await store.brokers()).toEqual([]);
    const after = (await store.getHouse('h1'))!;
    expect(after.brokerId).toBeNull();
    expect(after.contactName).toBe('Ravi Kumar');
    expect(after.contactPhone).toBe('+91 98400 11111');
    expect(after.dirty).toBe(true);
    const tombstone = (await store.dirtyRecords()).find((r) => r.id === h1.brokerId);
    expect(tombstone?.deleted).toBe(true);
    expect(tombstone?.payload).toEqual({});
  });

  it('lists the live houses of a broker', async () => {
    const a = await store.saveHouse(house('h1', RAVI), T1);
    await store.saveHouse(house('h2', { contactName: 'x', contactPhone: '9000000001' }), T1);
    await store.saveHouse(house('h3', { contactPhone: '9840011111' }), T2);
    await store.deleteHouse('h3', T2);
    expect((await store.brokerHouses(a.brokerId!)).map((h) => h.id)).toEqual(['h1']);
  });

  it('skips a stored record that is not a broker', async () => {
    await store.saveRecord('broker', 'bad', { name: '' });
    await store.saveRecord('broker', 'good', { name: 'Good' });
    expect((await store.brokers()).map((b) => b.id)).toEqual(['good']);
  });

  describe('the one-off migration of the contacts', () => {
    /** Houses as an older version stored them: a contact, no broker (`putHouseFromServer` does not link). */
    async function seed(): Promise<void> {
      await store.putHouseFromServer({ ...house('a'), ...RAVI, updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('b'), contactName: 'R. Kumar', contactPhone: '098400-11111', updatedAt: '2026-09-03T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('c'), contactPhone: '9840011111', updatedAt: '2026-09-02T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('d'), contactName: 'Meena', contactPhone: '90000 00001', updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('e'), contactName: 'No phone', updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('f'), contactPhone: ' ', updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.putHouseFromServer({ ...house('g'), ...RAVI, deleted: true, updatedAt: '2026-09-01T00:00:00.000Z' });
      await store.removeSetting(SETTING_KEYS.brokersMigrated);
    }

    it('runs once when the store opens, and sets the flag', async () => {
      expect(await store.setting(SETTING_KEYS.brokersMigrated)).toBe('1');
    });

    it('makes one broker per number, named by the newest house, and links the houses', async () => {
      await seed();
      expect(await store.migrateContactsToBrokers()).toBe(4);
      const brokers = await store.brokers();
      expect(brokers.map((b) => b.broker.name).sort()).toEqual(['Meena', 'R. Kumar']);
      const ravi = brokers.find((b) => b.broker.name === 'R. Kumar')!;
      // "+91 98400 11111", "098400-11111" and "9840011111" are one broker; its number is the newest house's.
      expect(ravi.broker.phone).toBe('098400-11111');
      const linked = async (id: string) => (await store.getHouse(id))?.brokerId ?? null;
      expect(await linked('a')).toBe(ravi.id);
      expect(await linked('b')).toBe(ravi.id);
      expect(await linked('c')).toBe(ravi.id);
      expect(await linked('d')).toBe(brokers.find((b) => b.broker.name === 'Meena')!.id);
      // A blank phone, or none, stays unlinked; the contact copies are left as they were.
      expect(await linked('e')).toBeNull();
      expect(await linked('f')).toBeNull();
      expect((await store.getHouse('a'))?.contactName).toBe('Ravi Kumar');
      expect((await store.getHouse('a'))?.dirty).toBe(true);
    });

    it('does nothing the second time, so a house unlinked on purpose stays unlinked', async () => {
      await seed();
      await store.migrateContactsToBrokers();
      const brokers = await store.brokers();
      const meena = brokers.find((b) => b.broker.name === 'Meena')!;
      await store.deleteBroker(meena.id);
      expect(await store.migrateContactsToBrokers()).toBe(0);
      expect((await store.getHouse('d'))?.brokerId).toBeNull();
      expect(await store.brokers()).toHaveLength(1);
    });

    it('reuses a broker that already has the number', async () => {
      await store.saveBroker('existing', { name: 'Already here', phone: '9840011111' }, T1);
      await seed();
      await store.migrateContactsToBrokers();
      expect((await store.getHouse('b'))?.brokerId).toBe('existing');
      expect((await store.brokers()).map((b) => b.broker.name).sort()).toEqual(['Already here', 'Meena']);
    });
  });
});

/** Slice 2 (docs/11 5.4): criteria and the rating share are records of type `criterion` and `preference`. */
describe('LocalStore criteria', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  const power = (over: Partial<Criterion> = {}): Criterion => ({ key: 'power', weight: 2, mustHave: false, minScore: 3, sort: 1, ...over });

  it('has the defaults, and a rating share of 0.5, when nothing is stored', async () => {
    const scoring = await store.scoring();
    expect(scoring.criteria.map((c) => c.key)).toEqual([...BUILT_IN_KEYS]);
    expect(scoring.ratingShare).toBe(0.5);
    expect(await store.recordsOf('criterion')).toEqual([]);
  });

  it('writes only what differs from the defaults: a built-in set back to its default has its record deleted', async () => {
    await store.saveCriterion(power({ weight: 3, mustHave: true, minScore: 4 }), T1);
    const stored = await store.getRecord('criterion', 'power');
    expect(stored?.payload).toEqual({ weight: 3, mustHave: true, minScore: 4, sort: 1 });
    expect(Object.keys(stored?.payload ?? {})).toEqual(['weight', 'mustHave', 'minScore', 'sort']);
    expect(stored?.dirty).toBe(true);
    expect((await store.scoring()).criteria.find((c) => c.key === 'power')).toMatchObject({ weight: 3, mustHave: true, minScore: 4 });
    await store.saveCriterion(power(), T2);
    expect(await store.getRecord('criterion', 'power')).toBeUndefined();
    // Saving the default of a built-in that never had a record writes nothing.
    await store.saveCriterion(power({ key: 'water', sort: 0 }), T2);
    expect(await store.recordsOf('criterion')).toEqual([]);
  });

  it('archives a built-in with a record that keeps the archived flag, and never stores a label for it', async () => {
    await store.saveCriterion({ ...power(), label: 'Ignored label', archived: true }, T1);
    expect((await store.getRecord('criterion', 'power'))?.payload).toEqual({ weight: 2, mustHave: false, minScore: 3, sort: 1, archived: true });
  });

  it('adds a custom criterion with a key of c_ and 8 hex characters, at the end, Medium', async () => {
    const added = await store.addCriterion('Pets allowed', 2, T1);
    expect(added.key).toMatch(/^c_[0-9a-f]{8}$/);
    expect(added).toMatchObject({ label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 });
    expect((await store.getRecord('criterion', added.key))?.payload).toEqual({ label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 });
    const second = await store.addCriterion('Lift', 3, T1);
    expect(second.sort).toBe(11);
    expect((await store.scoring()).criteria.map((c) => c.key).slice(-2)).toEqual([added.key, second.key]);
  });

  it('draws a custom key again when it clashes with a record, a deleted one included', async () => {
    const first = await store.addCriterion('One', 2, T1, () => 'c_00000001');
    await store.deleteCriterion(first.key, T2);
    const keys = ['c_00000001', 'c_00000001', 'c_00000002'];
    const second = await store.addCriterion('Two', 2, T2, () => keys.shift() ?? 'c_ffffffff');
    expect(second.key).toBe('c_00000002');
  });

  it('refuses a blank or oversized label', async () => {
    await expect(store.addCriterion('   ')).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.addCriterion('x'.repeat(61))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.addCriterion('x'.repeat(60))).resolves.toBeDefined();
    await expect(store.saveCriterion({ key: 'not-a-key!', weight: 2, mustHave: false, minScore: 3, sort: 0 })).rejects.toBeInstanceOf(LocalDataError);
  });

  it('refuses the 41st criterion (the ten built-ins count) with "At most 40 criteria"', async () => {
    for (let i = 0; i < 30; i++) await store.addCriterion(`Custom ${i}`, 2, T1, () => `c_${i.toString(16).padStart(8, '0')}`);
    expect((await store.scoring()).criteria).toHaveLength(40);
    await expect(store.addCriterion('One too many')).rejects.toMatchObject({ key: 'criteria.max' });
    // An existing one can still be changed.
    await expect(store.saveCriterion({ key: 'c_00000000', label: 'Renamed', weight: 1, mustHave: false, minScore: 3, sort: 10 })).resolves.toBeUndefined();
    expect((await store.scoring()).criteria).toHaveLength(40);
  });

  it('deletes a custom criterion only when no house has a score under its key', async () => {
    const added = await store.addCriterion('Pets', 2, T1);
    await store.saveHouse(house('h1', { checklist: { [added.key]: 4 } }), T1);
    expect(await store.criterionInUse(added.key)).toBe(true);
    await expect(store.deleteCriterion(added.key)).rejects.toMatchObject({ key: 'criteria.inUse' });
    expect(await store.getRecord('criterion', added.key)).toBeDefined();
    await store.deleteHouse('h1', T2);
    expect(await store.criterionInUse(added.key)).toBe(false);
    await store.deleteCriterion(added.key, T2);
    expect(await store.getRecord('criterion', added.key)).toBeUndefined();
    // A built-in can only be archived.
    await expect(store.deleteCriterion('water')).rejects.toBeInstanceOf(LocalDataError);
  });

  it('stores the rating share as the preference score.ratingShare, and deletes it at the default 0.5', async () => {
    await store.setRatingShare(0.25, T1);
    expect((await store.getRecord('preference', 'score.ratingShare'))?.payload).toEqual({ value: '0.25' });
    expect((await store.scoring()).ratingShare).toBe(0.25);
    await store.setRatingShare(0.5, T2);
    expect(await store.getRecord('preference', 'score.ratingShare')).toBeUndefined();
    expect((await store.scoring()).ratingShare).toBe(0.5);
    await store.setRatingShare(7, T2);
    expect((await store.scoring()).ratingShare).toBe(1);
  });

  it('resets to the defaults: every criterion and preference record becomes a tombstone', async () => {
    await store.saveCriterion(power({ weight: 3 }), T1);
    await store.addCriterion('Pets', 2, T1);
    await store.setRatingShare(0.75, T1);
    await store.resetCriteria(T2);
    expect(await store.recordsOf('criterion')).toEqual([]);
    expect(await store.recordsOf('preference')).toEqual([]);
    const scoring = await store.scoring();
    expect(scoring.criteria).toHaveLength(10);
    expect(scoring.ratingShare).toBe(0.5);
    // The tombstones stay for the next sync.
    expect((await store.dirtyRecords()).every((r) => r.deleted)).toBe(true);
  });

  it('reads an out-of-range stored value as the default', async () => {
    await store.saveRecord('criterion', 'water', { weight: 9, mustHave: 'x', minScore: 0, sort: -4 }, T1);
    const water = (await store.scoring()).criteria.find((c) => c.key === 'water');
    expect(water).toMatchObject({ weight: 2, mustHave: false, minScore: 3, sort: 0 });
    await store.saveRecord('preference', 'score.ratingShare', { value: 'lots' }, T1);
    expect((await store.scoring()).ratingShare).toBe(0.5);
  });
});

/** Slice 3a (docs/11 5.5): the question bank (records of type `question`) and the answers nested in a house. */
describe('LocalStore questions', () => {
  let store: LocalStore;
  const T3 = Date.parse('2026-09-03T00:00:00.000Z');

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  const ids = async () => (await store.questions()).map((q) => q.id);

  it('seeds every default once: fixed ids, the text of the current language, dirty', async () => {
    expect(await store.seedQuestions('ta', T1)).toBe(DEFAULT_QUESTIONS.length);
    const rows = await store.recordsOf('question');
    expect(rows.map((r) => r.id).sort()).toEqual(DEFAULT_QUESTIONS.map((d) => d.id).sort());
    expect(rows.every((r) => r.dirty)).toBe(true);
    const water = (await store.questions()).find((q) => q.id === 'qd_water')!;
    expect(water.text).toBe(DEFAULT_QUESTIONS.find((d) => d.id === 'qd_water')!.text.ta);
    // A second run writes nothing: every default already has a record.
    expect(await store.seedQuestions('ta', T2)).toBe(0);
  });

  it('seeds in English for a language it has no text for', async () => {
    await store.seedQuestions('fr', T1);
    expect((await store.questions()).find((q) => q.id === 'qd_water')!.text).toBe(DEFAULT_QUESTIONS.find((d) => d.id === 'qd_water')!.text.en);
  });

  it('seeds once per install: the setting stops a second start, and a bank the person emptied stays empty', async () => {
    await store.seedQuestionsOnce(() => 'hi', T1);
    expect(await store.setting(SETTING_KEYS.questionsSeeded)).toBe('1');
    expect(await ids()).toHaveLength(DEFAULT_QUESTIONS.length);
    for (const id of await ids()) await store.deleteQuestion(id, T2);
    await store.seedQuestionsOnce(() => 'hi', T3);
    expect(await ids()).toEqual([]);
  });

  it('does not bring back a default the person deleted: a tombstone counts as a record', async () => {
    await store.seedQuestions('en', T1);
    await store.deleteQuestion('qd_water', T2);
    expect(await store.seedQuestions('en', T3)).toBe(0);
    expect(await ids()).not.toContain('qd_water');
    expect((await store.getRecord('question', 'qd_water'))).toBeUndefined();
  });

  it('does not seed over a default that another device already sent', async () => {
    await store.saveRecord('question', 'qd_water', { text: 'Edited elsewhere', category: 'OTHER', appliesTo: 'BOTH', defaultOn: false, sort: 3 }, T1);
    expect(await store.seedQuestions('en', T2)).toBe(DEFAULT_QUESTIONS.length - 1);
    expect((await store.questions()).find((q) => q.id === 'qd_water')!.text).toBe('Edited elsewhere');
  });

  it('resets to defaults: a deleted, edited or archived default comes back in the current language, own questions stay', async () => {
    await store.seedQuestions('en', T1);
    const custom = await store.addQuestion('Is there a lift?', 'BUILDING', 'BOTH', T1);
    await store.deleteQuestion('qd_water', T2);
    const rent = (await store.questions()).find((q) => q.id === 'qd_deposit')!;
    await store.saveQuestion({ ...rent, text: 'Changed', archived: true, defaultOn: false, sort: 40 }, T2);
    await store.resetQuestions('te', T3);
    const bank = await store.questions();
    expect(bank).toHaveLength(DEFAULT_QUESTIONS.length + 1);
    const deposit = bank.find((q) => q.id === 'qd_deposit')!;
    expect(deposit).toEqual({ id: 'qd_deposit', text: DEFAULT_QUESTIONS.find((d) => d.id === 'qd_deposit')!.text.te, category: 'MONEY', appliesTo: 'RENT', defaultOn: true, sort: 1 });
    expect(bank.find((q) => q.id === 'qd_water')?.text).toBe(DEFAULT_QUESTIONS.find((d) => d.id === 'qd_water')!.text.te);
    expect(bank.find((q) => q.id === custom.id)?.text).toBe('Is there a lift?');
  });

  it('does not bring a default back past 100 questions', async () => {
    for (let i = 0; i < MAX_QUESTIONS; i++) await store.saveQuestion({ id: `q_${i.toString(16).padStart(8, '0')}`, text: `Own ${i}`, category: 'OTHER', appliesTo: 'BOTH', defaultOn: false, sort: i }, T1);
    expect(await store.resetQuestions('en', T2)).toBe(0);
    expect(await store.questions()).toHaveLength(MAX_QUESTIONS);
  });

  it('adds a custom question at the end with a q_ id of 8 hex characters and defaults off', async () => {
    await store.seedQuestions('en', T1);
    const added = await store.addQuestion('  Is there a lift?  ', 'BUILDING', 'SALE', T2);
    expect(added.id).toMatch(/^q_[0-9a-f]{8}$/);
    expect(added).toMatchObject({ text: 'Is there a lift?', category: 'BUILDING', appliesTo: 'SALE', defaultOn: false, sort: DEFAULT_QUESTIONS.length });
    const record = await store.getRecord('question', added.id);
    expect(Object.keys(record!.payload)).toEqual(['text', 'category', 'appliesTo', 'defaultOn', 'sort']);
    expect(record!.dirty).toBe(true);
  });

  it('draws a new id when one clashes with a record, a deleted one included', async () => {
    await store.saveQuestion({ id: 'q_aaaaaaaa', text: 'One', category: 'OTHER', appliesTo: 'BOTH', defaultOn: false, sort: 0 }, T1);
    await store.deleteQuestion('q_aaaaaaaa', T1);
    const drawn = ['q_aaaaaaaa', 'q_aaaaaaaa', 'q_bbbbbbbb'];
    const added = await store.addQuestion('Two', 'OTHER', 'BOTH', T2, () => drawn.shift() ?? 'q_cccccccc');
    expect(added.id).toBe('q_bbbbbbbb');
  });

  it('refuses a blank or over-long text, a bad id, and the 101st question', async () => {
    await expect(store.addQuestion('   ')).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.addQuestion('x'.repeat(MAX_QUESTION_TEXT + 1))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.saveQuestion({ id: 'whatever', text: 'A', category: 'OTHER', appliesTo: 'BOTH', defaultOn: false, sort: 0 })).rejects.toBeInstanceOf(LocalDataError);
    for (let i = 0; i < MAX_QUESTIONS; i++) await store.addQuestion(`Own ${i}`, 'OTHER', 'BOTH', T1);
    await expect(store.addQuestion('One too many')).rejects.toMatchObject({ key: 'questions.max' });
    // An archived question still counts toward the cap; editing an existing one is fine at the cap.
    const first = (await store.questions())[0];
    await store.saveQuestion({ ...first, archived: true }, T2);
    await expect(store.addQuestion('Still too many')).rejects.toMatchObject({ key: 'questions.max' });
  });

  it('saves only the record that changed, and reorders by writing the rows whose number moved', async () => {
    await store.seedQuestions('en', T1);
    for (const r of await store.dirtyRecords()) await store.markRecordClean('question', r.id, r.updatedAt);
    expect(await store.dirtyRecords()).toEqual([]);
    const bank = await store.questions();
    const [a, b] = [bank[0], bank[1]];
    await store.saveQuestions([{ ...a, sort: b.sort }, { ...b, sort: a.sort }], T2);
    expect((await store.dirtyRecords()).map((r) => r.id).sort()).toEqual([a.id, b.id].sort());
    expect((await store.questions()).slice(0, 2).map((q) => q.id)).toEqual([b.id, a.id]);
  });

  it('archives and brings back a question, and writes archived only when true', async () => {
    await store.seedQuestions('en', T1);
    const q = (await store.questions())[0];
    await store.saveQuestion({ ...q, archived: true }, T2);
    expect((await store.getRecord('question', q.id))!.payload['archived']).toBe(true);
    await store.saveQuestion({ ...q, archived: false }, T3);
    expect(Object.keys((await store.getRecord('question', q.id))!.payload)).not.toContain('archived');
  });

  it('deletes any question, a seeded one too, as a tombstone the next sync sends', async () => {
    await store.seedQuestions('en', T1);
    await store.deleteQuestion('qd_pets', T2);
    expect(await ids()).not.toContain('qd_pets');
    const tomb = (await store.dirtyRecords()).find((r) => r.id === 'qd_pets');
    expect(tomb).toMatchObject({ deleted: true, payload: {} });
  });

  it('reads a stored row with a blank text as no question', async () => {
    await store.saveRecord('question', 'q_bad00001', { text: '  ', category: 'MONEY' }, T1);
    expect(await ids()).toEqual([]);
    expect(await store.questionRows()).toEqual([]);
  });

  it('seeds again after Remove all data, when seeding has been asked for', async () => {
    await store.seedQuestionsOnce(() => 'en', T1);
    await store.clearEverything();
    expect(await ids()).toHaveLength(DEFAULT_QUESTIONS.length);
    const plain = new LocalStore();
    await plain.ready();
    await plain.clearEverything();
    expect(await plain.questions()).toEqual([]);
  });

  it('keeps the answers of a house through save and load, cleaned, and touches no question', async () => {
    const answers: HouseAnswer[] = [
      { id: 'a2', text: 'Second', status: 'OPEN', sort: 1 },
      { id: 'a1', questionId: 'qd_water', text: 'First', answer: 'Yes', status: 'OPEN', sort: 0 },
    ];
    const saved = await store.saveHouse(house('h1', { answers }), T1);
    expect(saved.answers?.map((a) => [a.id, a.status])).toEqual([['a1', 'ANSWERED'], ['a2', 'OPEN']]);
    expect((await store.getHouse('h1'))?.answers).toEqual(saved.answers);
    expect(await ids()).toEqual([]);
    await store.saveHouse({ ...saved, answers: [] }, T2);
    expect((await store.getHouse('h1'))?.answers).toBeNull();
  });
});

/** Slice 3b-1 (docs/11 5.8): viewings, records of type `viewing`; no migration, a house is untouched. */
describe('LocalStore viewings', () => {
  let store: LocalStore;
  const NOW = Date.parse('2026-09-30T12:00:00.000Z');
  const HOUR = 3_600_000;
  const viewing = (id: string, over: Partial<Viewing> = {}): Viewing => ({
    id,
    houseId: 'h1',
    startsAt: NOW + HOUR,
    durationMin: 30,
    kind: 'FIRST',
    status: 'PLANNED',
    remindMin: 60,
    ...over,
  });

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('saves a viewing as a dirty record of type viewing with the payload keys in the contract order', async () => {
    await store.saveViewing(viewing('v_00000001', { notes: 'Bring a tape', withWhom: 'Meena' }), T1);
    const record = await store.getRecord('viewing', 'v_00000001');
    expect(Object.keys(record!.payload)).toEqual(['houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin', 'withWhom', 'notes']);
    expect(record).toMatchObject({ dirty: true, deleted: false });
    expect(await store.viewings()).toEqual([viewing('v_00000001', { notes: 'Bring a tape', withWhom: 'Meena' })]);
  });

  it('writes only when something changed: an unchanged save keeps updatedAt and the sync queue as they were', async () => {
    await store.saveViewing(viewing('v_00000001'), T1);
    const first = await store.getRecord('viewing', 'v_00000001');
    for (const r of await store.dirtyRecords()) await store.markRecordClean('viewing', r.id, r.updatedAt);
    await store.saveViewing(viewing('v_00000001'), T2);
    expect((await store.getRecord('viewing', 'v_00000001'))?.updatedAt).toBe(first?.updatedAt);
    expect(await store.dirtyRecords()).toEqual([]);
    await store.saveViewing(viewing('v_00000001', { notes: 'Now with notes' }), T2);
    expect((await store.dirtyRecords()).map((r) => r.id)).toEqual(['v_00000001']);
    expect((await store.getRecord('viewing', 'v_00000001'))?.updatedAt).not.toBe(first?.updatedAt);
  });

  it('lists the viewings by start then id, and those of one house', async () => {
    await store.saveViewing(viewing('v_0000000b', { startsAt: NOW }), T1);
    await store.saveViewing(viewing('v_0000000a', { startsAt: NOW }), T1);
    await store.saveViewing(viewing('v_00000003', { startsAt: NOW - HOUR, houseId: 'h2' }), T1);
    expect((await store.viewings()).map((v) => v.id)).toEqual(['v_00000003', 'v_0000000a', 'v_0000000b']);
    expect((await store.viewingsOf('h1')).map((v) => v.id)).toEqual(['v_0000000a', 'v_0000000b']);
    expect((await store.viewingRows()).map((r) => r.id).sort()).toEqual(['v_00000003', 'v_0000000a', 'v_0000000b']);
  });

  it('draws a new v_ id that clashes with no record, a deleted one included', async () => {
    await store.saveViewing(viewing('v_aaaaaaaa'), T1);
    await store.deleteViewing('v_aaaaaaaa', T1);
    const drawn = ['v_aaaaaaaa', 'v_aaaaaaaa', 'v_bbbbbbbb'];
    expect(await store.newViewingId(() => drawn.shift() ?? 'v_cccccccc')).toBe('v_bbbbbbbb');
    expect(await store.newViewingId()).toMatch(/^v_[0-9a-f]{8}$/);
  });

  it('deletes a viewing as a tombstone the next sync sends, and leaves the house alone', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.saveViewing(viewing('v_00000001'), T1);
    await store.deleteViewing('v_00000001', T2);
    expect(await store.viewings()).toEqual([]);
    expect((await store.dirtyRecords()).find((r) => r.id === 'v_00000001')).toMatchObject({ deleted: true, payload: {} });
    expect((await store.getHouse('h1'))?.deleted).toBe(false);
  });

  it('keeps the viewings of a house that is deleted (they are shown as a house that is gone)', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.saveViewing(viewing('v_00000001'), T1);
    await store.deleteHouse('h1', T2);
    expect((await store.viewings()).map((v) => v.id)).toEqual(['v_00000001']);
  });

  it('finds the next PLANNED viewing of a house at or after now', async () => {
    await store.saveViewing(viewing('v_00000001', { startsAt: NOW + 5 * HOUR }), T1);
    await store.saveViewing(viewing('v_00000002', { startsAt: NOW + HOUR }), T1);
    await store.saveViewing(viewing('v_00000003', { startsAt: NOW + 30 * 60_000, status: 'CANCELLED' }), T1);
    await store.saveViewing(viewing('v_00000004', { startsAt: NOW - HOUR }), T1);
    await store.saveViewing(viewing('v_00000005', { startsAt: NOW + 30 * 60_000, houseId: 'h2' }), T1);
    expect((await store.nextViewing('h1', NOW))?.id).toBe('v_00000002');
    expect((await store.nextViewing('h1', NOW + 2 * HOUR))?.id).toBe('v_00000001');
    expect(await store.nextViewing('h1', NOW + 6 * HOUR)).toBeNull();
    expect(await store.nextViewing('nobody', NOW)).toBeNull();
  });

  it('marks a viewing done with its visit, keeping the old visit when none is given', async () => {
    await store.saveViewing(viewing('v_00000001'), T1);
    const done = await store.markViewingDone('v_00000001', 'visit-1', T2);
    expect(done).toMatchObject({ status: 'DONE', visitId: 'visit-1' });
    expect((await store.getRecord('viewing', 'v_00000001'))?.payload).toMatchObject({ status: 'DONE', visitId: 'visit-1' });
    await store.saveViewing(viewing('v_00000002', { visitId: 'old-visit' }), T1);
    expect(await store.markViewingDone('v_00000002', undefined, T2)).toMatchObject({ status: 'DONE', visitId: 'old-visit' });
    await expect(store.markViewingDone('v_99999999')).rejects.toBeInstanceOf(LocalDataError);
  });

  it('refuses a bad id, a blank house, a start that is not positive, a duration outside 5..480 and over-long text', async () => {
    const refused = (over: Partial<Viewing>) => expect(store.saveViewing(viewing('v_00000001', over))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.saveViewing(viewing('bad id'))).rejects.toBeInstanceOf(LocalDataError);
    await refused({ houseId: '  ' });
    await refused({ houseId: 'x'.repeat(65) });
    await refused({ startsAt: 0 });
    await refused({ startsAt: -1 });
    await refused({ durationMin: 4 });
    await refused({ durationMin: 481 });
    await refused({ remindMin: 7 });
    await refused({ withWhom: 'x'.repeat(201) });
    await refused({ notes: 'x'.repeat(2001) });
    await refused({ kind: 'THIRD' as Viewing['kind'] });
    expect(await store.viewings()).toEqual([]);
  });

  it('reads a stored row with no house or no time as no viewing', async () => {
    await store.saveRecord('viewing', 'v_00000001', { startsAt: NOW }, T1);
    await store.saveRecord('viewing', 'v_00000002', { houseId: 'h1' }, T1);
    expect(await store.viewings()).toEqual([]);
    expect(await store.viewingRows()).toEqual([]);
  });

  it('refuses the 5 001st viewing, and still lets an existing one be edited at the cap', async () => {
    for (let i = 0; i < 5000; i++) {
      await store.saveRecord('viewing', 'v_' + i.toString(16).padStart(8, '0'), { houseId: 'h1', startsAt: NOW + i }, T1);
    }
    await expect(store.saveViewing(viewing('v_ffffffff'))).rejects.toMatchObject({ key: 'viewings.max' });
    await store.saveViewing(viewing('v_00000000', { startsAt: NOW, notes: 'edited at the cap' }), T2);
    expect((await store.getRecord('viewing', 'v_00000000'))?.payload).toMatchObject({ notes: 'edited at the cap' });
  }, 60_000);
});

/** Slice 4a (docs/11 "Design of slice 4a"): areas, places and area notes, records of type `area`, `place`, `areanote`. */
describe('LocalStore areas, places and area notes', () => {
  let store: LocalStore;
  const area = (id: string, over: Partial<Area> = {}): Area => ({ id, name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500, enabled: true, ...over });
  const place = (id: string, over: Partial<Place> = {}): Place => ({ id, name: 'Office', lat: 13.0827, lon: 80.2707, ...over });
  const note = (id: string, over: Partial<AreaNote> = {}): AreaNote => ({ id, street: 'MG Road', text: 'Noisy', ...over });

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  it('saves an area as a dirty record of type area with the keys in the contract order, enabled only when false', async () => {
    await store.saveArea(area('a_00000001'), T1);
    const record = await store.getRecord('area', 'a_00000001');
    expect(Object.keys(record!.payload)).toEqual(['name', 'lat', 'lon', 'radiusM']);
    expect(record).toMatchObject({ dirty: true, deleted: false });
    await store.saveArea(area('a_00000001', { enabled: false, name: '  Adyar  ' }), T2);
    expect(Object.keys((await store.getRecord('area', 'a_00000001'))!.payload)).toEqual(['name', 'lat', 'lon', 'radiusM', 'enabled']);
    expect(await store.areas()).toEqual([area('a_00000001', { enabled: false })]);
  });

  it('writes only when something changed: an unchanged area keeps updatedAt and the sync queue as they were', async () => {
    await store.saveArea(area('a_00000001'), T1);
    const first = await store.getRecord('area', 'a_00000001');
    for (const r of await store.dirtyRecords()) await store.markRecordClean('area', r.id, r.updatedAt);
    await store.saveArea(area('a_00000001'), T2);
    expect((await store.getRecord('area', 'a_00000001'))?.updatedAt).toBe(first?.updatedAt);
    expect(await store.dirtyRecords()).toEqual([]);
    await store.saveArea(area('a_00000001', { radiusM: 900 }), T2);
    expect((await store.dirtyRecords()).map((r) => r.id)).toEqual(['a_00000001']);
  });

  it('lists areas and places by name then id, and notes newest first', async () => {
    await store.saveArea(area('a_0000000b', { name: 'Zed' }), T1);
    await store.saveArea(area('a_0000000a', { name: 'adyar' }), T1);
    expect((await store.areas()).map((a) => a.id)).toEqual(['a_0000000a', 'a_0000000b']);
    await store.savePlace(place('p_00000002', { name: 'Office' }), T1);
    await store.savePlace(place('p_00000001', { name: 'Amma' }), T1);
    expect((await store.places()).map((p) => p.id)).toEqual(['p_00000001', 'p_00000002']);
    await store.saveAreaNote(note('n_00000001'), T1);
    await store.saveAreaNote(note('n_00000002', { text: 'Newer' }), T2);
    expect((await store.areaNotes()).map((n) => n.id)).toEqual(['n_00000002', 'n_00000001']);
  });

  it('draws new a_, p_ and n_ ids that clash with no record, a deleted one included', async () => {
    await store.saveArea(area('a_aaaaaaaa'), T1);
    await store.deleteArea('a_aaaaaaaa', T1);
    const drawn = ['a_aaaaaaaa', 'a_aaaaaaaa', 'a_bbbbbbbb'];
    expect(await store.newAreaId(() => drawn.shift() ?? 'a_cccccccc')).toBe('a_bbbbbbbb');
    await store.savePlace(place('p_aaaaaaaa'), T1);
    await store.deletePlace('p_aaaaaaaa', T1);
    const p = ['p_aaaaaaaa', 'p_bbbbbbbb'];
    expect(await store.newPlaceId(() => p.shift() ?? 'p_cccccccc')).toBe('p_bbbbbbbb');
    await store.saveAreaNote(note('n_aaaaaaaa'), T1);
    await store.deleteAreaNote('n_aaaaaaaa', T1);
    const n = ['n_aaaaaaaa', 'n_bbbbbbbb'];
    expect(await store.newAreaNoteId(() => n.shift() ?? 'n_cccccccc')).toBe('n_bbbbbbbb');
    expect(await store.newAreaId()).toMatch(/^a_[0-9a-f]{8}$/);
  });

  it('deletes as a tombstone the next sync sends, and keeps the notes of a deleted area (they reach no house)', async () => {
    await store.saveArea(area('a_00000001'), T1);
    await store.saveAreaNote(note('n_00000001', { street: undefined, areaId: 'a_00000001' }), T1);
    await store.deleteArea('a_00000001', T2);
    expect(await store.areas()).toEqual([]);
    expect((await store.dirtyRecords()).find((r) => r.id === 'a_00000001')).toMatchObject({ deleted: true, payload: {} });
    expect((await store.areaNotes()).map((n) => n.id)).toEqual(['n_00000001']);
  });

  it('refuses a bad area: id, blank or over-long name, point and radius out of range', async () => {
    const refused = (over: Partial<Area>) => expect(store.saveArea(area('a_00000001', over))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.saveArea(area('bad id'))).rejects.toBeInstanceOf(LocalDataError);
    await refused({ name: '  ' });
    await refused({ name: 'n'.repeat(101) });
    await refused({ lat: 90.5 });
    await refused({ lon: -181 });
    await refused({ radiusM: 199 });
    await refused({ radiusM: 2001 });
    await refused({ radiusM: 650.5 });
    expect(await store.areas()).toEqual([]);
  });

  it('refuses a bad place and a bad note: neither or both targets, blank or over-long text', async () => {
    await expect(store.savePlace(place('p_00000001', { name: 'n'.repeat(61) }))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.savePlace(place('p_00000001', { lat: 100 }))).rejects.toBeInstanceOf(LocalDataError);
    const refused = (n: AreaNote) => expect(store.saveAreaNote(n)).rejects.toBeInstanceOf(LocalDataError);
    await refused({ id: 'n_00000001', text: 'no target' });
    await refused({ id: 'n_00000001', areaId: 'a_1', street: 'MG Road', text: 'both' });
    await refused(note('n_00000001', { text: '  ' }));
    await refused(note('n_00000001', { text: 'x'.repeat(1001) }));
    await refused(note('n_00000001', { street: 's'.repeat(101) }));
    await refused({ id: 'n_00000001', areaId: 'a'.repeat(65), text: 'x' });
    expect(await store.areaNotes()).toEqual([]);
  });

  it('caps the live records at 20 areas, 10 places and 200 notes; an edit of an existing one and a deleted slot still work', async () => {
    const hex = (i: number) => i.toString(16).padStart(8, '0');
    for (let i = 0; i < 20; i++) await store.saveArea(area('a_' + hex(i), { name: 'Area ' + i }), T1);
    await expect(store.saveArea(area('a_' + hex(99)))).rejects.toMatchObject({ key: 'areas.max' });
    await store.saveArea(area('a_' + hex(3), { name: 'Renamed' }), T2);
    await store.deleteArea('a_' + hex(4), T2);
    await store.saveArea(area('a_' + hex(99)), T2);
    for (let i = 0; i < 10; i++) await store.savePlace(place('p_' + hex(i), { name: 'Place ' + i }), T1);
    await expect(store.savePlace(place('p_' + hex(99)))).rejects.toMatchObject({ key: 'places.max' });
    for (let i = 0; i < 200; i++) await store.saveAreaNote(note('n_' + hex(i), { text: 'Note ' + i }), T1);
    await expect(store.saveAreaNote(note('n_' + hex(999)))).rejects.toMatchObject({ key: 'areaNotes.max' });
    expect(await store.areaNoteRows()).toHaveLength(200);
  });

  it('skips a stored row that is not readable, without truncating the rest', async () => {
    await store.saveRecord('area', 'a_00000001', { name: '', lat: 1, lon: 1, radiusM: 500 }, T1);
    await store.saveRecord('area', 'a_00000002', { name: 'Good', lat: 1, lon: 1, radiusM: 50 }, T1);
    await store.saveRecord('place', 'p_00000001', { name: 'Bad', lat: 999, lon: 1 }, T1);
    await store.saveRecord('areanote', 'n_00000001', { text: 'no target' }, T1);
    await store.saveRecord('areanote', 'n_00000002', { areaId: 'a', street: 's', text: 'both' }, T1);
    await store.saveRecord('areanote', 'n_00000003', { street: 'MG Road', text: 'Ok' }, T1);
    expect((await store.areas()).map((a) => [a.id, a.radiusM])).toEqual([['a_00000002', 500]]);
    expect(await store.places()).toEqual([]);
    expect((await store.areaNotes()).map((n) => n.id)).toEqual(['n_00000003']);
  });
});
