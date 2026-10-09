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
import { CACHE_NAME_PREFIX, LocalStore, SETTLE_MS } from './local-store.service';
import { MAX_PHOTOS_PER_HOUSE } from './photo-store';
import { SETTING_KEYS } from './records';
import { keepLocalRecord } from './sync-rules';
import { LocalDataError } from '../core/local-error';
import type { HouseAnswer, HouseDto, HouseRoom, VisitDto } from '../core/models';
import { DEFAULT_QUESTIONS, MAX_QUESTIONS, MAX_QUESTION_TEXT } from '../shared/question';
import { BUILT_IN_KEYS } from '../shared/scoring';
import type { Criterion } from '../shared/scoring';
import type { Viewing } from '../shared/viewing';

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
    await store.photos.add('h1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p1', T1);
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
    const added = await store.photos.add('h1', new Blob([new Uint8Array([1, 2, 3])], { type: 'image/jpeg' }), 'p1', T1);
    expect(added.ok).toBe(true);
    expect(await store.photos.ofHouse('h1')).toHaveLength(1);
    // Never uploaded, so it is simply forgotten.
    await store.photos.delete('p1', T2);
    expect(await store.photos.all()).toHaveLength(0);
  });

  it('leaves a tombstone for a photo the server already has', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p1', T1);
    const stored = await store.photos.get('p1');
    await store.photos.put({ ...stored!, uploaded: true });
    await store.photos.delete('p1', T2);
    const all = await store.photos.all();
    expect(all).toHaveLength(1);
    expect(all[0].deleted).toBe(true);
    expect(all[0].blob).toBeNull();
    expect(await store.photos.ofHouse('h1')).toHaveLength(0);
  });

  it('refuses more than the shared per-house photo limit', async () => {
    await store.saveHouse(house('h1'), T1);
    for (let i = 0; i < MAX_PHOTOS_PER_HOUSE; i++) {
      const result = await store.photos.add('h1', new Blob([new Uint8Array([i])], { type: 'image/jpeg' }), `p${i}`, T1);
      expect(result.ok).toBe(true);
    }
    const extra = await store.photos.add('h1', new Blob([new Uint8Array([99])], { type: 'image/jpeg' }), 'px', T1);
    expect(extra.ok).toBe(false);
  });

  it('deleting a house also drops its photos', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.photos.add('h1', new Blob([new Uint8Array([1])], { type: 'image/jpeg' }), 'p1', T1);
    await store.deleteHouse('h1', T2);
    expect(await store.photos.ofHouse('h1')).toHaveLength(0);
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
    await store.records.save('broker', 'b2', { name: 'Later' }, T2);
    await store.records.save('broker', 'b1', { name: 'Earlier' }, T1);
    await store.records.save('place', 'p1', { name: 'Office' }, T1);
    await store.records.putFromServer({ type: 'broker', id: 'b3', payload: {}, updatedAt: '2026-09-03T00:00:00.000Z', deleted: false, syncVersion: 9 });
    expect((await store.records.ofType('broker')).map((r) => r.id)).toEqual(['b1', 'b2', 'b3']);
    expect(await store.records.ofType('viewing')).toEqual([]);

    const b1 = await store.records.get('broker', 'b1');
    expect(b1).toEqual({ type: 'broker', id: 'b1', payload: { name: 'Earlier' }, updatedAt: '2026-09-01T00:00:00.000Z', deleted: false, syncVersion: 0, dirty: true });
    // A local edit of a synced row keeps the row's sync version.
    await store.records.save('broker', 'b3', { name: 'Edited' }, T2);
    expect((await store.records.get('broker', 'b3'))?.syncVersion).toBe(9);
    expect((await store.records.dirty()).map((r) => `${r.type}/${r.id}`)).toEqual(['broker/b1', 'place/p1', 'broker/b2', 'broker/b3']);
  });

  it('turns a deleted record into a tombstone with an empty payload, hidden from reads but kept for the sync', async () => {
    await store.records.save('viewing', 'v1', { at: '2026-10-01' }, T1);
    await store.records.delete('viewing', 'v1', T2);
    await store.records.delete('viewing', 'never-there', T2);
    expect(await store.records.get('viewing', 'v1')).toBeUndefined();
    expect(await store.records.ofType('viewing')).toEqual([]);
    const [tombstone] = await store.records.dirty();
    expect(tombstone).toEqual({ type: 'viewing', id: 'v1', payload: {}, updatedAt: '2026-09-02T00:00:00.000Z', deleted: true, syncVersion: 0, dirty: true });
  });

  it('clears a record’s dirty flag only when nothing changed while the push was in flight', async () => {
    const saved = await store.records.save('place', 'p1', { name: 'Office' }, T1);
    await store.records.save('place', 'p1', { name: 'Office, moved' }, T2);
    await store.records.markClean('place', 'p1', saved.updatedAt);
    expect((await store.records.get('place', 'p1'))?.dirty).toBe(true);
    await store.records.markClean('place', 'p1', '2026-09-02T00:00:00.000Z');
    expect((await store.records.get('place', 'p1'))?.dirty).toBe(false);
    // A resync after a server reset marks it again, with everything else.
    await store.markAllForResync();
    expect((await store.records.dirty()).map((r) => r.id)).toEqual(['p1']);
  });

  it('refuses a record it could not sync: a bad type or id, or a payload over the server’s cap', async () => {
    for (const [type, id, payload] of [
      ['Broker', 'b1', {}],
      ['broker', 'a/b', {}],
      ['broker', 'b1', { n: 'x'.repeat(70_000) }],
    ] as const) {
      let thrown: unknown = null;
      try {
        await store.records.save(type, id, payload as Record<string, unknown>, T1);
      } catch (err: unknown) {
        thrown = err;
      }
      expect(thrown, `${type}/${id}`).toBeInstanceOf(LocalDataError);
    }
    expect(await store.records.dirty()).toEqual([]);
  });

  it('removes the records with everything else when the user clears this browser', async () => {
    await store.records.save('broker', 'b1', { name: 'A' }, T1);
    await store.clearEverything();
    expect(await store.records.dirty()).toEqual([]);
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
    expect((await store.records.get('broker', brokers[0].id))?.dirty).toBe(true);
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
    const tombstone = (await store.records.dirty()).find((r) => r.id === h1.brokerId);
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
    await store.records.save('broker', 'bad', { name: '' });
    await store.records.save('broker', 'good', { name: 'Good' });
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
    expect(await store.records.ofType('criterion')).toEqual([]);
  });

  it('writes only what differs from the defaults: a built-in set back to its default has its record deleted', async () => {
    await store.saveCriterion(power({ weight: 3, mustHave: true, minScore: 4 }), T1);
    const stored = await store.records.get('criterion', 'power');
    expect(stored?.payload).toEqual({ weight: 3, mustHave: true, minScore: 4, sort: 1 });
    expect(Object.keys(stored?.payload ?? {})).toEqual(['weight', 'mustHave', 'minScore', 'sort']);
    expect(stored?.dirty).toBe(true);
    expect((await store.scoring()).criteria.find((c) => c.key === 'power')).toMatchObject({ weight: 3, mustHave: true, minScore: 4 });
    await store.saveCriterion(power(), T2);
    expect(await store.records.get('criterion', 'power')).toBeUndefined();
    // Saving the default of a built-in that never had a record writes nothing.
    await store.saveCriterion(power({ key: 'water', sort: 0 }), T2);
    expect(await store.records.ofType('criterion')).toEqual([]);
  });

  it('archives a built-in with a record that keeps the archived flag, and never stores a label for it', async () => {
    await store.saveCriterion({ ...power(), label: 'Ignored label', archived: true }, T1);
    expect((await store.records.get('criterion', 'power'))?.payload).toEqual({ weight: 2, mustHave: false, minScore: 3, sort: 1, archived: true });
  });

  it('adds a custom criterion with a key of c_ and 8 hex characters, at the end, Medium', async () => {
    const added = await store.addCriterion('Pets allowed', 2, T1);
    expect(added.key).toMatch(/^c_[0-9a-f]{8}$/);
    expect(added).toMatchObject({ label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 });
    expect((await store.records.get('criterion', added.key))?.payload).toEqual({ label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 });
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
    expect(await store.records.get('criterion', added.key)).toBeDefined();
    await store.deleteHouse('h1', T2);
    expect(await store.criterionInUse(added.key)).toBe(false);
    await store.deleteCriterion(added.key, T2);
    expect(await store.records.get('criterion', added.key)).toBeUndefined();
    // A built-in can only be archived.
    await expect(store.deleteCriterion('water')).rejects.toBeInstanceOf(LocalDataError);
  });

  it('stores the rating share as the preference score.ratingShare, and deletes it at the default 0.5', async () => {
    await store.setRatingShare(0.25, T1);
    expect((await store.records.get('preference', 'score.ratingShare'))?.payload).toEqual({ value: '0.25' });
    expect((await store.scoring()).ratingShare).toBe(0.25);
    await store.setRatingShare(0.5, T2);
    expect(await store.records.get('preference', 'score.ratingShare')).toBeUndefined();
    expect((await store.scoring()).ratingShare).toBe(0.5);
    await store.setRatingShare(7, T2);
    expect((await store.scoring()).ratingShare).toBe(1);
  });

  it('resets to the defaults: every criterion and preference record becomes a tombstone', async () => {
    await store.saveCriterion(power({ weight: 3 }), T1);
    await store.addCriterion('Pets', 2, T1);
    await store.setRatingShare(0.75, T1);
    await store.resetCriteria(T2);
    expect(await store.records.ofType('criterion')).toEqual([]);
    expect(await store.records.ofType('preference')).toEqual([]);
    const scoring = await store.scoring();
    expect(scoring.criteria).toHaveLength(10);
    expect(scoring.ratingShare).toBe(0.5);
    // The tombstones stay for the next sync.
    expect((await store.records.dirty()).every((r) => r.deleted)).toBe(true);
  });

  it('reads an out-of-range stored value as the default', async () => {
    await store.records.save('criterion', 'water', { weight: 9, mustHave: 'x', minScore: 0, sort: -4 }, T1);
    const water = (await store.scoring()).criteria.find((c) => c.key === 'water');
    expect(water).toMatchObject({ weight: 2, mustHave: false, minScore: 3, sort: 0 });
    await store.records.save('preference', 'score.ratingShare', { value: 'lots' }, T1);
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

  it('seeds every default once: fixed ids, the text of the current language, clean and stamped 2000-01-01', async () => {
    expect(await store.seedQuestions('ta', T1)).toBe(DEFAULT_QUESTIONS.length);
    const rows = await store.records.ofType('question');
    expect(rows.map((r) => r.id).sort()).toEqual(DEFAULT_QUESTIONS.map((d) => d.id).sort());
    // S4b-BL-90a: never pushed, and older than any real edit, so another device's edit or deletion always wins.
    expect(rows.every((r) => !r.dirty)).toBe(true);
    expect(rows.every((r) => r.updatedAt === '2000-01-01T00:00:00.000Z')).toBe(true);
    expect(await store.records.dirty()).toEqual([]);
    const pulledEdit = { ...rows[0], updatedAt: new Date(T1).toISOString() };
    expect(keepLocalRecord(rows[0], pulledEdit)).toBe(false);
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
    expect((await store.records.get('question', 'qd_water'))).toBeUndefined();
  });

  it('does not seed over a default that another device already sent', async () => {
    await store.records.save('question', 'qd_water', { text: 'Edited elsewhere', category: 'OTHER', appliesTo: 'BOTH', defaultOn: false, sort: 3 }, T1);
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
    const record = await store.records.get('question', added.id);
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
    for (const r of await store.records.dirty()) await store.records.markClean('question', r.id, r.updatedAt);
    expect(await store.records.dirty()).toEqual([]);
    const bank = await store.questions();
    const [a, b] = [bank[0], bank[1]];
    await store.saveQuestions([{ ...a, sort: b.sort }, { ...b, sort: a.sort }], T2);
    expect((await store.records.dirty()).map((r) => r.id).sort()).toEqual([a.id, b.id].sort());
    expect((await store.questions()).slice(0, 2).map((q) => q.id)).toEqual([b.id, a.id]);
  });

  it('archives and brings back a question, and writes archived only when true', async () => {
    await store.seedQuestions('en', T1);
    const q = (await store.questions())[0];
    await store.saveQuestion({ ...q, archived: true }, T2);
    expect((await store.records.get('question', q.id))!.payload['archived']).toBe(true);
    await store.saveQuestion({ ...q, archived: false }, T3);
    expect(Object.keys((await store.records.get('question', q.id))!.payload)).not.toContain('archived');
  });

  it('deletes any question, a seeded one too, as a tombstone the next sync sends', async () => {
    await store.seedQuestions('en', T1);
    await store.deleteQuestion('qd_pets', T2);
    expect(await ids()).not.toContain('qd_pets');
    const tomb = (await store.records.dirty()).find((r) => r.id === 'qd_pets');
    expect(tomb).toMatchObject({ deleted: true, payload: {} });
  });

  it('reads a stored row with a blank text as no question', async () => {
    await store.records.save('question', 'q_bad00001', { text: '  ', category: 'MONEY' }, T1);
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
describe('ViewingStore (LocalStore.viewings)', () => {
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
    await store.viewings.save(viewing('v_00000001', { notes: 'Bring a tape', withWhom: 'Meena' }), T1);
    const record = await store.records.get('viewing', 'v_00000001');
    expect(Object.keys(record!.payload)).toEqual(['houseId', 'startsAt', 'durationMin', 'kind', 'status', 'remindMin', 'withWhom', 'notes']);
    expect(record).toMatchObject({ dirty: true, deleted: false });
    expect(await store.viewings.all()).toEqual([viewing('v_00000001', { notes: 'Bring a tape', withWhom: 'Meena' })]);
  });

  it('writes only when something changed: an unchanged save keeps updatedAt and the sync queue as they were', async () => {
    await store.viewings.save(viewing('v_00000001'), T1);
    const first = await store.records.get('viewing', 'v_00000001');
    for (const r of await store.records.dirty()) await store.records.markClean('viewing', r.id, r.updatedAt);
    await store.viewings.save(viewing('v_00000001'), T2);
    expect((await store.records.get('viewing', 'v_00000001'))?.updatedAt).toBe(first?.updatedAt);
    expect(await store.records.dirty()).toEqual([]);
    await store.viewings.save(viewing('v_00000001', { notes: 'Now with notes' }), T2);
    expect((await store.records.dirty()).map((r) => r.id)).toEqual(['v_00000001']);
    expect((await store.records.get('viewing', 'v_00000001'))?.updatedAt).not.toBe(first?.updatedAt);
  });

  it('lists the viewings by start then id, and those of one house', async () => {
    await store.viewings.save(viewing('v_0000000b', { startsAt: NOW }), T1);
    await store.viewings.save(viewing('v_0000000a', { startsAt: NOW }), T1);
    await store.viewings.save(viewing('v_00000003', { startsAt: NOW - HOUR, houseId: 'h2' }), T1);
    expect((await store.viewings.all()).map((v) => v.id)).toEqual(['v_00000003', 'v_0000000a', 'v_0000000b']);
    expect((await store.viewings.ofHouse('h1')).map((v) => v.id)).toEqual(['v_0000000a', 'v_0000000b']);
    expect((await store.viewings.rows()).map((r) => r.id).sort()).toEqual(['v_00000003', 'v_0000000a', 'v_0000000b']);
  });

  it('draws a new v_ id that clashes with no record, a deleted one included', async () => {
    await store.viewings.save(viewing('v_aaaaaaaa'), T1);
    await store.viewings.delete('v_aaaaaaaa', T1);
    const drawn = ['v_aaaaaaaa', 'v_aaaaaaaa', 'v_bbbbbbbb'];
    expect(await store.viewings.newId(() => drawn.shift() ?? 'v_cccccccc')).toBe('v_bbbbbbbb');
    expect(await store.viewings.newId()).toMatch(/^v_[0-9a-f]{8}$/);
  });

  it('gives each saved viewing row its edit time', async () => {
    await store.viewings.save({ id: 'v_00000001', houseId: 'h1', startsAt: NOW + HOUR, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60 }, T2);
    expect(await store.viewings.rows()).toEqual([
      { id: 'v_00000001', updatedAt: '2026-09-02T00:00:00.000Z', viewing: expect.objectContaining({ id: 'v_00000001', houseId: 'h1' }) },
    ]);
  });

  it('deletes a viewing as a tombstone the next sync sends, and leaves the house alone', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.viewings.save(viewing('v_00000001'), T1);
    await store.viewings.delete('v_00000001', T2);
    expect(await store.viewings.all()).toEqual([]);
    expect((await store.records.dirty()).find((r) => r.id === 'v_00000001')).toMatchObject({ deleted: true, payload: {} });
    expect((await store.getHouse('h1'))?.deleted).toBe(false);
  });

  it('keeps the viewings of a house that is deleted (they are shown as a house that is gone)', async () => {
    await store.saveHouse(house('h1'), T1);
    await store.viewings.save(viewing('v_00000001'), T1);
    await store.deleteHouse('h1', T2);
    expect((await store.viewings.all()).map((v) => v.id)).toEqual(['v_00000001']);
  });

  it('finds the next PLANNED viewing of a house at or after now', async () => {
    await store.viewings.save(viewing('v_00000001', { startsAt: NOW + 5 * HOUR }), T1);
    await store.viewings.save(viewing('v_00000002', { startsAt: NOW + HOUR }), T1);
    await store.viewings.save(viewing('v_00000003', { startsAt: NOW + 30 * 60_000, status: 'CANCELLED' }), T1);
    await store.viewings.save(viewing('v_00000004', { startsAt: NOW - HOUR }), T1);
    await store.viewings.save(viewing('v_00000005', { startsAt: NOW + 30 * 60_000, houseId: 'h2' }), T1);
    expect((await store.viewings.next('h1', NOW))?.id).toBe('v_00000002');
    expect((await store.viewings.next('h1', NOW + 2 * HOUR))?.id).toBe('v_00000001');
    expect(await store.viewings.next('h1', NOW + 6 * HOUR)).toBeNull();
    expect(await store.viewings.next('nobody', NOW)).toBeNull();
  });

  it('marks a viewing done with its visit, keeping the old visit when none is given', async () => {
    await store.viewings.save(viewing('v_00000001'), T1);
    const done = await store.viewings.markDone('v_00000001', 'visit-1', T2);
    expect(done).toMatchObject({ status: 'DONE', visitId: 'visit-1' });
    expect((await store.records.get('viewing', 'v_00000001'))?.payload).toMatchObject({ status: 'DONE', visitId: 'visit-1' });
    await store.viewings.save(viewing('v_00000002', { visitId: 'old-visit' }), T1);
    expect(await store.viewings.markDone('v_00000002', undefined, T2)).toMatchObject({ status: 'DONE', visitId: 'old-visit' });
    await expect(store.viewings.markDone('v_99999999')).rejects.toBeInstanceOf(LocalDataError);
  });

  it('refuses a bad id, a blank house, a start that is not positive, a duration outside 5..480 and over-long text', async () => {
    const refused = (over: Partial<Viewing>) => expect(store.viewings.save(viewing('v_00000001', over))).rejects.toBeInstanceOf(LocalDataError);
    await expect(store.viewings.save(viewing('bad id'))).rejects.toBeInstanceOf(LocalDataError);
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
    expect(await store.viewings.all()).toEqual([]);
  });

  it('reads a stored row with no house or no time as no viewing', async () => {
    await store.records.save('viewing', 'v_00000001', { startsAt: NOW }, T1);
    await store.records.save('viewing', 'v_00000002', { houseId: 'h1' }, T1);
    expect(await store.viewings.all()).toEqual([]);
    expect(await store.viewings.rows()).toEqual([]);
  });

  it('refuses the 5 001st viewing, and still lets an existing one be edited at the cap', async () => {
    for (let i = 0; i < 5000; i++) {
      await store.records.save('viewing', 'v_' + i.toString(16).padStart(8, '0'), { houseId: 'h1', startsAt: NOW + i }, T1);
    }
    await expect(store.viewings.save(viewing('v_ffffffff'))).rejects.toMatchObject({ key: 'viewings.max' });
    await store.viewings.save(viewing('v_00000000', { startsAt: NOW, notes: 'edited at the cap' }), T2);
    expect((await store.records.get('viewing', 'v_00000000'))?.payload).toMatchObject({ notes: 'edited at the cap' });
  }, 60_000);
});
