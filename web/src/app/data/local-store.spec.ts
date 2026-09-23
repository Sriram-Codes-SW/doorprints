import { computed } from '@angular/core';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CACHE_NAME_PREFIX, LocalStore, MAX_PHOTOS_PER_HOUSE, SETTLE_MS } from './local-store.service';
import { SETTING_KEYS } from './records';
import type { HouseDto, VisitDto } from '../core/models';

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
    const before = store.settled();
    await store.putHouseFromServer({ ...house('s1'), updatedAt: '2026-09-01T00:00:00.000Z' });
    await store.putHouseFromServer({ ...house('s2'), updatedAt: '2026-09-01T00:00:00.000Z' });
    await store.putHouseFromServer({ ...house('s3'), updatedAt: '2026-09-01T00:00:00.000Z' });
    // Still inside the quiet period: the views have not been told yet.
    expect(store.settled()).toBe(before);
    await new Promise((resolve) => setTimeout(resolve, SETTLE_MS + 50));
    expect(store.settled()).toBe(store.revision());
    expect(store.settled()).not.toBe(before);
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
    expect(await store.cursors()).toEqual({ house: 0, visit: 0, photo: 0 });
    await store.setSetting(SETTING_KEYS.houseCursor, '12');
    await store.setSetting(SETTING_KEYS.photoCursor, 'not a number');
    const cursors = await store.cursors();
    expect(cursors.house).toBe(12);
    expect(cursors.photo).toBe(0);
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
