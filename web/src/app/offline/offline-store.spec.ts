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

import { afterEach, describe, expect, it } from 'vitest';
import { OFFLINE_AREAS_KEY } from '../core/storage-keys';
import { offlineActive } from './offline-protocol';
import { loadAreas, storeAreas, syncOfflineActive } from './offline-store';
import type { SavedArea } from './offline-tiles';

class MemoryStorage {
  data = new Map<string, string>();
  fail = false;
  getItem = (k: string) => this.data.get(k) ?? null;
  setItem = (k: string, v: string) => {
    if (this.fail) throw new DOMException('full', 'QuotaExceededError');
    this.data.set(k, v);
  };
  removeItem = (k: string) => void this.data.delete(k);
}

const ready: SavedArea = {
  id: 'a',
  name: 'Indiranagar',
  bounds: { south: 12.96, west: 77.63, north: 12.985, east: 77.655 },
  sources: [{ template: 'https://tiles.openfreemap.org/planet/b/{z}/{x}/{y}.pbf', maxZoom: 14 }],
  maxZoom: 14,
  assets: ['https://tiles.openfreemap.org/styles/liberty'],
  tiles: 30,
  bytes: 1_500_000,
  state: 'ready',
  savedAt: 5,
};

afterEach(() => storeAreas(null, []));

describe('the saved areas list', () => {
  it('round-trips and tells the map hook whether an area is ready', () => {
    const s = new MemoryStorage();
    expect(storeAreas(s, [ready])).toBe(true);
    expect(offlineActive()).toBe(true);
    expect(loadAreas(s)).toEqual([ready]);
    storeAreas(s, [{ ...ready, state: 'saving' }]);
    expect(offlineActive()).toBe(false);
    storeAreas(s, []);
    expect(s.data.has(OFFLINE_AREAS_KEY)).toBe(false);
  });

  it('reads newest first and drops what cannot be read', () => {
    const s = new MemoryStorage();
    s.setItem(OFFLINE_AREAS_KEY, JSON.stringify([{ ...ready, id: 'old', savedAt: 1 }, 'junk', null, { id: 1 }, { ...ready, id: 'new', savedAt: 9 }, { ...ready, id: 'x', state: 'weird' }]));
    expect(loadAreas(s).map((a) => a.id)).toEqual(['new', 'old']);
    s.setItem(OFFLINE_AREAS_KEY, '{not json');
    expect(loadAreas(s)).toEqual([]);
    s.setItem(OFFLINE_AREAS_KEY, '{"a":1}');
    expect(loadAreas(s)).toEqual([]);
    expect(loadAreas(null)).toEqual([]);
  });

  it('a full storage is reported, not thrown; the hook still follows the list', () => {
    const s = new MemoryStorage();
    s.fail = true;
    expect(storeAreas(s, [ready])).toBe(false);
    expect(offlineActive()).toBe(true);
  });

  it('syncOfflineActive reads storage: on a map start with no network the saved style is already asked for', () => {
    const s = new MemoryStorage();
    storeAreas(s, [ready]);
    storeAreas(null, []);
    expect(offlineActive()).toBe(false);
    syncOfflineActive(s);
    expect(offlineActive()).toBe(true);
  });
});
