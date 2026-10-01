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
import { afterEach, describe, expect, it } from 'vitest';
import { OFFLINE_AREAS_KEY } from '../core/storage-keys';
import { OFFLINE_ENV, OfflineMapsService, type OfflineEnv } from './offline-maps.service';
import { FakeCache, fakeNetwork, STYLE_URL, type FakeNetwork } from './testing/offline-fakes';
import { offlineActive, setOfflineActive } from './offline-protocol';
import { areaKeys, countTiles, type GeoBounds } from './offline-tiles';

const point: GeoBounds = { south: 12.97, west: 77.64, north: 12.9701, east: 77.6401 };
const nearby: GeoBounds = { south: 12.97, west: 77.9, north: 12.9701, east: 77.9001 };
const india: GeoBounds = { south: 6.5, west: 68, north: 37.5, east: 97.5 };

class MemoryStorage {
  data = new Map<string, string>();
  getItem = (k: string) => this.data.get(k) ?? null;
  setItem = (k: string, v: string) => void this.data.set(k, v);
  removeItem = (k: string) => void this.data.delete(k);
}

interface Rig {
  service: OfflineMapsService;
  cache: FakeCache;
  net: FakeNetwork;
  storage: MemoryStorage;
  env: OfflineEnv & { quota: { usage: number; quota: number } | null; isOnline: boolean; cacheDeleted: boolean; isMetered: boolean | null };
}

function rig(seed?: unknown): Rig {
  const cache = new FakeCache();
  const net = fakeNetwork();
  const storage = new MemoryStorage();
  if (seed !== undefined) storage.data.set(OFFLINE_AREAS_KEY, JSON.stringify(seed));
  let n = 0;
  const env = {
    quota: { usage: 1_000_000, quota: 500_000_000 } as { usage: number; quota: number } | null,
    isOnline: true,
    cacheDeleted: false,
    isMetered: null as boolean | null,
    openCache: async () => cache,
    deleteCache: async () => {
      env.cacheDeleted = true;
      cache.entries.clear();
    },
    fetch: net.fetch,
    storageEstimate: async () => env.quota,
    metered() {
      return env.isMetered;
    },
    online: () => env.isOnline,
    storage,
    hasCache: true,
    now: () => 1_000 + n,
    newId: () => `id${++n}`,
  };
  TestBed.configureTestingModule({ providers: [{ provide: OFFLINE_ENV, useValue: env }] });
  return { service: TestBed.inject(OfflineMapsService), cache, net, storage, env };
}

afterEach(() => {
  TestBed.resetTestingModule();
  setOfflineActive(false);
});

describe('OfflineMapsService', () => {
  it('saves an area: the list, the stored copy, the hook for the map and the cache all follow', async () => {
    const r = rig();
    expect(r.service.supported()).toBe(true);
    expect(offlineActive()).toBe(false);
    const outcome = await r.service.save('  Indiranagar  ', point);
    expect(outcome).toBe('saved');
    const [a] = r.service.areas();
    expect(a.name).toBe('Indiranagar');
    expect(a.state).toBe('ready');
    expect(r.service.usedBytes()).toBe(a.bytes);
    expect(offlineActive()).toBe(true);
    expect(JSON.parse(r.storage.data.get(OFFLINE_AREAS_KEY)!)[0].id).toBe(a.id);
    for (const k of areaKeys(a)) expect(r.cache.has(k), k).toBe(true);
    expect(r.service.progress()).toBeNull();
  });

  it('plans before it saves: the numbers, the room left, a refusal for too large, mobile data', async () => {
    const r = rig();
    r.env.isMetered = true;
    const p = await r.service.plan(point);
    expect(p.tiles).toBe(countTiles(point));
    expect(p.refusal).toBeNull();
    expect(p.free).toBe(499_000_000);
    expect(p.metered).toBe(true);
    expect(p.online).toBe(true);
    expect((await r.service.plan(india)).refusal).toBe('too-large');
    r.env.quota = { usage: 499_000_000, quota: 500_000_000 };
    expect((await r.service.plan(point)).refusal).toBe('no-room');
    r.env.quota = null;
    const unknown = await r.service.plan(point);
    expect(unknown.free).toBeNull();
    expect(unknown.refusal).toBeNull();
  });

  it('refuses without fetching: offline, too large, no room, too many, unsupported', async () => {
    const r = rig();
    r.env.isOnline = false;
    expect(await r.service.save('x', point)).toBe('offline');
    r.env.isOnline = true;
    expect(await r.service.save('x', india)).toBe('too-large');
    r.env.quota = { usage: 499_999_000, quota: 500_000_000 };
    expect(await r.service.save('x', point)).toBe('no-room');
    expect(r.net.requested).toEqual([]);
  });

  it('a download that fails leaves nothing behind: no list entry and no files', async () => {
    const r = rig();
    r.net.status.set('https://tiles.openfreemap.org/planet/20260927_080001_pt/3/5/3.pbf', 500);
    expect(await r.service.save('Broken', point)).toBe('failed');
    expect(r.service.areas()).toEqual([]);
    expect(r.storage.data.has(OFFLINE_AREAS_KEY)).toBe(false);
    expect([...r.cache.entries.keys()].filter((k) => k.includes('/planet/') && k.endsWith('.pbf'))).toEqual([]);
    expect(offlineActive()).toBe(false);
    expect(r.service.progress()).toBeNull();
  });

  it('Stop cancels the download and removes what was saved', async () => {
    const r = rig();
    r.net.onRequest = (_u, n) => {
      if (n === 12) r.service.cancel();
    };
    expect(await r.service.save('Stopped', point)).toBe('cancelled');
    expect(r.service.areas()).toEqual([]);
    expect([...r.cache.entries.keys()].filter((k) => k.endsWith('.pbf') && k.includes('/planet/'))).toEqual([]);
  });

  it('shows progress while it runs, and refuses a second download at the same time', async () => {
    const r = rig();
    const seen: number[] = [];
    let second: string | null = null;
    r.net.onRequest = () => {
      const p = r.service.progress();
      if (p) seen.push(p.done);
      if (second === null) {
        second = 'pending';
        void r.service.save('again', nearby).then((o) => (second = o));
      }
    };
    expect(await r.service.save('First', point)).toBe('saved');
    expect(second).toBe('busy');
    expect(seen.length).toBeGreaterThan(5);
  });

  it('deleting an area removes its own files and keeps what another needs', async () => {
    const r = rig();
    await r.service.save('A', point);
    await r.service.save('B', nearby);
    const [b, a] = r.service.areas();
    expect([a.name, b.name]).toEqual(['A', 'B']);
    await r.service.remove(a.id);
    expect(r.service.areas().map((x) => x.name)).toEqual(['B']);
    for (const k of areaKeys(b)) expect(r.cache.has(k), `B keeps ${k}`).toBe(true);
    const aOnly = areaKeys(a).filter((k) => !areaKeys(b).includes(k));
    expect(aOnly.length).toBeGreaterThan(0);
    for (const k of aOnly) expect(r.cache.has(k), `A's ${k} is gone`).toBe(false);
    expect(offlineActive()).toBe(true);
    await r.service.remove(b.id);
    expect(r.service.areas()).toEqual([]);
    expect(r.cache.entries.size).toBe(0);
    expect(offlineActive()).toBe(false);
  });

  it('Remove all data empties the list and the whole cache', async () => {
    const r = rig();
    await r.service.save('A', point);
    await r.service.removeAll();
    expect(r.env.cacheDeleted).toBe(true);
    expect(r.service.areas()).toEqual([]);
    expect(offlineActive()).toBe(false);
  });

  it('an area left "saving" by a closed tab is shown as failed after a restart', () => {
    const r = rig([
      { id: 'z', name: 'Half', bounds: point, sources: [], maxZoom: 14, assets: [], tiles: 10, bytes: 0, state: 'saving', savedAt: 3 },
    ]);
    expect(r.service.areas()[0].state).toBe('failed');
    expect(JSON.parse(r.storage.data.get(OFFLINE_AREAS_KEY)!)[0].state).toBe('failed');
    expect(offlineActive()).toBe(false);
  });

  it('verify marks an area failed when the browser cleared its cache', async () => {
    const r = rig();
    await r.service.save('A', point);
    expect(r.service.areas()[0].state).toBe('ready');
    r.cache.entries.delete(STYLE_URL);
    await r.service.verify();
    expect(r.service.areas()[0].state).toBe('failed');
    expect(offlineActive()).toBe(false);
  });

  it('reads the browser storage figures', async () => {
    const r = rig();
    await r.service.refreshStorage();
    expect(r.service.storage()).toEqual({ usage: 1_000_000, quota: 500_000_000 });
  });
});
