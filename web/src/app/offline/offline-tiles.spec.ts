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
import {
  ASSET_BYTES,
  AVERAGE_TILE_BYTES,
  MAX_AREAS,
  MAX_TILES,
  RASTER_TILE_BYTES,
  areaKeys,
  areaTileKeys,
  cacheKey,
  countTiles,
  estimateAreaBytes,
  estimateBytes,
  freeBytes,
  keysToEvict,
  megabyteDigits,
  megabytes,
  refusal,
  tileRange,
  tileUrl,
  type GeoBounds,
  type SavedArea,
} from './offline-tiles';

/** The vectors of Android's `OfflineTilesTest` (docs/11 5.20): the same boxes, the same counts, the same limits. */
const neighbourhood: GeoBounds = { south: 12.96, west: 77.63, north: 12.985, east: 77.655 };
const city: GeoBounds = { south: 12.83, west: 77.45, north: 13.15, east: 77.78 };
const india: GeoBounds = { south: 6.5, west: 68.0, north: 37.5, east: 97.5 };

const area = (id: string, bounds: GeoBounds, over: Partial<SavedArea> = {}): SavedArea => ({
  id,
  name: id,
  bounds,
  sources: [{ template: 'https://tiles.openfreemap.org/planet/b1/{z}/{x}/{y}.pbf', maxZoom: 14 }],
  maxZoom: 14,
  assets: ['https://tiles.openfreemap.org/styles/liberty'],
  tiles: 0,
  bytes: 0,
  state: 'ready',
  savedAt: 1,
  ...over,
});

describe('offline tile arithmetic (ported from Android OfflineTiles)', () => {
  it('a neighbourhood is a few dozen tiles and a city a few hundred', () => {
    const small = countTiles(neighbourhood);
    expect(small, `neighbourhood: ${small} tiles`).toBeGreaterThanOrEqual(15);
    expect(small).toBeLessThanOrEqual(40);
    const big = countTiles(city);
    expect(big, `city: ${big} tiles`).toBeGreaterThanOrEqual(300);
    expect(big).toBeLessThanOrEqual(800);
    expect(big).toBeLessThanOrEqual(MAX_TILES);
  });

  it('the whole country is over the limit', () => {
    expect(countTiles(india)).toBeGreaterThan(MAX_TILES);
  });

  it('zoom zero is one tile, each zoom adds at least one, a point is one tile per zoom', () => {
    expect(countTiles(neighbourhood, 0)).toBe(1);
    expect(countTiles(neighbourhood, 1)).toBe(2);
    expect(countTiles({ south: 12.97, west: 77.64, north: 12.9701, east: 77.6401 })).toBe(15);
  });

  it('the estimate is 50 KB a tile and the text is one decimal under 10 MB, whole above, never under 0.1', () => {
    expect(AVERAGE_TILE_BYTES).toBe(50_000);
    expect(estimateBytes(400)).toBe(50_000 * 400);
    expect(megabytes(estimateBytes(400))).toBe(20);
    expect(megabytes(2_450_000)).toBe(2.5);
    expect(megabytes(12)).toBe(0.1);
    expect(megabyteDigits(2_450_000)).toBe(1);
    expect(megabyteDigits(20_000_000)).toBe(0);
  });

  it('the area estimate adds the low-zoom raster tiles and the style files to the vector tiles', () => {
    const tiles = countTiles(neighbourhood);
    const raster = countTiles(neighbourhood, 6);
    expect(estimateAreaBytes(neighbourhood)).toBe(tiles * AVERAGE_TILE_BYTES + raster * RASTER_TILE_BYTES + ASSET_BYTES);
  });

  it('a known tile: Bengaluru centre at zoom 10 is column 732, row 474 (OSM tile numbering)', () => {
    const r = tileRange({ south: 12.9716, west: 77.5946, north: 12.9716, east: 77.5946 }, 10);
    expect([r.xMin, r.yMin]).toEqual([732, 474]);
    expect([r.xMax, r.yMax]).toEqual([732, 474]);
  });

  it('latitudes and longitudes beyond Web Mercator are clamped into the grid', () => {
    const r = tileRange({ south: -90, west: -200, north: 90, east: 200 }, 3);
    expect([r.xMin, r.xMax, r.yMin, r.yMax]).toEqual([0, 7, 0, 7]);
  });
});

describe('offline cache keys and eviction', () => {
  it('a tile URL is filled from the template and the key is the normalised address', () => {
    expect(tileUrl('https://h/p/{z}/{x}/{y}.pbf', 12, 3, 4)).toBe('https://h/p/12/3/4.pbf');
    expect(cacheKey('https://h/fonts/Noto Sans Regular/0-255.pbf#x')).toBe('https://h/fonts/Noto%20Sans%20Regular/0-255.pbf');
    expect(cacheKey('not a url')).toBe('not a url');
  });

  it('an area has one key per tile of every source, a source stopping at its own top zoom', () => {
    const a = area('a', neighbourhood, {
      sources: [
        { template: 'https://h/v/{z}/{x}/{y}.pbf', maxZoom: 14 },
        { template: 'https://h/r/{z}/{x}/{y}.png', maxZoom: 6 },
      ],
    });
    expect(areaTileKeys(a)).toHaveLength(countTiles(neighbourhood) + countTiles(neighbourhood, 6));
    expect(areaKeys(a)).toHaveLength(areaTileKeys(a).length + 1);
    expect(new Set(areaKeys(a)).size).toBe(areaKeys(a).length);
  });

  it('deleting an area keeps the tiles another area still needs, and its own are removed', () => {
    const a = area('a', neighbourhood);
    const b = area('b', { south: 12.96, west: 77.7, north: 12.985, east: 77.725 }); // nearby, shares the low zooms
    const gone = new Set(keysToEvict(a, [a, b]));
    const bKeys = new Set(areaKeys(b));
    expect(gone.size).toBeGreaterThan(0);
    for (const k of gone) expect(bKeys.has(k)).toBe(false);
    // The world tile at zoom 0 and the style are shared, so they stay.
    expect(gone.has('https://tiles.openfreemap.org/planet/b1/0/0/0.pbf')).toBe(false);
    expect(gone.has('https://tiles.openfreemap.org/styles/liberty')).toBe(false);
    // Deleted alone, everything goes.
    expect(new Set(keysToEvict(a, [a]))).toEqual(new Set(areaKeys(a)));
  });

  it('areas built from different planet builds share nothing but the style', () => {
    const a = area('a', neighbourhood);
    const c = area('c', neighbourhood, { sources: [{ template: 'https://tiles.openfreemap.org/planet/b2/{z}/{x}/{y}.pbf', maxZoom: 14 }] });
    expect(keysToEvict(a, [a, c])).toHaveLength(countTiles(neighbourhood));
  });
});

describe('refusing an area', () => {
  it('too large over the cap, too many at the area limit, no room when the estimate does not fit', () => {
    expect(refusal(MAX_TILES + 1, 1, 0, null)).toBe('too-large');
    expect(refusal(100, 1, MAX_AREAS, null)).toBe('too-many');
    expect(refusal(100, 10_000_000, 1, { usage: 95_000_000, quota: 100_000_000 })).toBe('no-room');
    expect(refusal(100, 10_000_000, 1, { usage: 10_000_000, quota: 100_000_000 })).toBeNull();
    // A browser that does not say its quota never blocks.
    expect(refusal(100, 10_000_000, 1, null)).toBeNull();
    expect(refusal(MAX_TILES, 1, 0, null)).toBeNull();
  });

  it('free space is the quota less the usage, or unknown', () => {
    expect(freeBytes({ usage: 30, quota: 100 })).toBe(70);
    expect(freeBytes({ usage: 130, quota: 100 })).toBe(0);
    expect(freeBytes({ usage: 0, quota: 0 })).toBeNull();
    expect(freeBytes(null)).toBeNull();
  });
});
