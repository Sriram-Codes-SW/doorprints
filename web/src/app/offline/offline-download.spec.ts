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
import { FAKE_STYLE, FakeCache, GLYPHS_TEMPLATE, SPRITE_BASE, STYLE_URL, TILEJSON_URL, fakeNetwork } from './testing/offline-fakes';
import {
  CONCURRENCY,
  GLYPH_RANGE_STARTS,
  OfflineDownloadError,
  downloadArea,
  fontStacks,
  glyphUrls,
  spriteUrls,
} from './offline-download';
import { EMPTY_HEADER } from './offline-protocol';
import { areaKeys, cacheKey, countTiles, type GeoBounds } from './offline-tiles';

const small: GeoBounds = { south: 12.97, west: 77.64, north: 12.9701, east: 77.6401 }; // a point: one tile per zoom
const neighbourhood: GeoBounds = { south: 12.96, west: 77.63, north: 12.985, east: 77.655 };

const request = (bounds = small, id = 'a') => ({ id, name: 'Test', bounds, now: 1 });

function setup() {
  const cache = new FakeCache();
  const net = fakeNetwork();
  return { cache, net, deps: { cache, fetch: net.fetch, styleUrl: STYLE_URL } };
}

describe('reading the style', () => {
  it('finds every font stack, in layouts and in literal expressions', () => {
    expect(fontStacks(FAKE_STYLE).sort()).toEqual(['Noto Sans Bold', 'Noto Sans Italic', 'Noto Sans Regular']);
    expect(fontStacks({ layers: [{ layout: { 'text-font': ['Noto Sans Regular', 'Noto Sans Bold'] } }] })).toEqual(['Noto Sans Regular,Noto Sans Bold']);
    expect(fontStacks({ layers: [{ layout: {} }, {}] })).toEqual([]);
  });

  it('builds the glyph and sprite addresses', () => {
    const urls = glyphUrls(GLYPHS_TEMPLATE, ['Noto Sans Regular']);
    expect(urls).toHaveLength(GLYPH_RANGE_STARTS.length);
    expect(urls[0]).toBe('https://tiles.openfreemap.org/fonts/Noto Sans Regular/0-255.pbf');
    // Latin, the punctuation block, and Devanagari through Malayalam, so Hindi, Tamil and Telugu names draw.
    expect(urls.some((u) => u.endsWith('/2816-3071.pbf'))).toBe(true);
    expect(urls.some((u) => u.endsWith('/3072-3327.pbf'))).toBe(true);
    expect(spriteUrls('https://h/s/ofm')).toEqual(['https://h/s/ofm.json', 'https://h/s/ofm.png', 'https://h/s/ofm@2x.json', 'https://h/s/ofm@2x.png']);
  });
});

describe('downloadArea', () => {
  it('saves the style, the TileJSON, the sprite, the glyphs and every tile of both sources', async () => {
    const { cache, deps, net } = setup();
    const seen: number[] = [];
    let planned = false;
    const area = await downloadArea(request(), deps, { planned: () => (planned = true), progress: (p) => seen.push(p.done) });
    expect(planned).toBe(true);
    expect(area.state).toBe('ready');
    // A point: 15 vector tiles (zoom 0..14) and 7 raster (0..6).
    expect(area.tiles).toBe(22);
    expect(area.sources.map((s) => s.maxZoom)).toEqual([6, 14]);
    expect(area.maxZoom).toBe(14);
    for (const key of areaKeys(area)) expect(cache.has(cacheKey(key)), key).toBe(true);
    expect(cache.has(cacheKey(STYLE_URL))).toBe(true);
    expect(cache.has(cacheKey(TILEJSON_URL))).toBe(true);
    for (const u of spriteUrls(SPRITE_BASE)) expect(cache.has(cacheKey(u)), u).toBe(true);
    expect(cache.has('https://tiles.openfreemap.org/fonts/Noto%20Sans%20Italic/2304-2559.pbf')).toBe(true);
    expect(area.assets).toContain(STYLE_URL);
    // 1 style + 1 tilejson + 4 sprite + 3 fonts x 8 ranges + 22 tiles.
    expect(net.requested).toHaveLength(1 + 1 + 4 + 24 + 22);
    expect(area.bytes).toBeGreaterThan(0);
    expect(seen[0]).toBe(0);
    expect(seen.at(-1)).toBe(22);
    expect(seen).toEqual([...seen].sort((a, b) => a - b));
  });

  it('stores bodies without the server encoding headers, and a content type', async () => {
    const { cache, deps } = setup();
    await downloadArea(request(), deps);
    const stored = cache.entries.get('https://tiles.openfreemap.org/planet/20260927_080001_pt/0/0/0.pbf')!;
    expect(stored.headers.map(([k]) => k)).toEqual(['content-type']);
  });

  it('a tile the server has none for (404) is kept as an empty marker, not an error', async () => {
    const { cache, deps, net } = setup();
    net.status.set('https://tiles.openfreemap.org/planet/20260927_080001_pt/0/0/0.pbf', 404);
    const area = await downloadArea(request(), deps);
    expect(area.state).toBe('ready');
    const stored = cache.entries.get('https://tiles.openfreemap.org/planet/20260927_080001_pt/0/0/0.pbf')!;
    expect(stored.headers.find(([k]) => k === EMPTY_HEADER)?.[1]).toBe('1');
    expect(stored.body.byteLength).toBe(0);
  });

  it('retries a failing tile twice, then gives up with a named error', async () => {
    const { deps, net } = setup();
    const bad = 'https://tiles.openfreemap.org/planet/20260927_080001_pt/3/5/3.pbf';
    net.status.set(bad, 500);
    const err = await downloadArea(request(), deps).catch((e) => e);
    expect(err).toBeInstanceOf(OfflineDownloadError);
    expect(err.reason).toBe('fetch');
    expect(net.requested.filter((u) => u === bad)).toHaveLength(3);
  });

  it('a glyph range the server lacks is skipped; a missing sprite is an error', async () => {
    const a = setup();
    a.net.status.set('https://tiles.openfreemap.org/fonts/Noto Sans Bold/3328-3583.pbf', 404);
    const area = await downloadArea(request(), a.deps);
    expect(area.assets).not.toContain('https://tiles.openfreemap.org/fonts/Noto Sans Bold/3328-3583.pbf');
    const b = setup();
    b.net.status.set(`${SPRITE_BASE}.png`, 404);
    const err = await downloadArea(request(), b.deps).catch((e) => e);
    expect(err.reason).toBe('no-style');
  });

  it('refuses a box over the cap before fetching anything', async () => {
    const { deps, net } = setup();
    const err = await downloadArea(request({ south: 6.5, west: 68, north: 37.5, east: 97.5 }), deps).catch((e) => e);
    expect(err.reason).toBe('too-large');
    expect(net.requested).toEqual([]);
  });

  it('reports an unreadable style, and a style with no tiles', async () => {
    const a = setup();
    a.net.status.set(STYLE_URL, 500);
    expect(((await downloadArea(request(), a.deps).catch((e) => e)) as OfflineDownloadError).reason).toBe('fetch');
    const cache = new FakeCache();
    const net = fakeNetwork({ version: 8, sources: {}, layers: [] });
    const err = await downloadArea(request(), { cache, fetch: net.fetch, styleUrl: STYLE_URL }).catch((e) => e);
    expect(err.reason).toBe('no-tiles');
  });

  it('does not fetch again what another area already saved (shared low zooms, sprite, glyphs)', async () => {
    const { cache, deps, net } = setup();
    await downloadArea(request(neighbourhood, 'a'), deps);
    const before = net.requested.length;
    await downloadArea(request(neighbourhood, 'b'), deps);
    // Only the style and the TileJSON, which are always read fresh.
    expect(net.requested.length - before).toBe(2);
    expect(cache.entries.size).toBeGreaterThan(40);
  });

  it('a full storage stops it with a storage error', async () => {
    const { cache, deps } = setup();
    cache.failPuts = true;
    const err = await downloadArea(request(), deps).catch((e) => e);
    expect(err.reason).toBe('storage');
  });

  it('stops at once when aborted, and fetches no more', async () => {
    const { deps, net } = setup();
    const controller = new AbortController();
    net.onRequest = (_u, n) => {
      if (n === 40) controller.abort();
    };
    const err = await downloadArea(request(neighbourhood), deps, { signal: controller.signal }).catch((e) => e);
    expect(err.name).toBe('AbortError');
    expect(net.requested.length).toBeLessThan(40 + CONCURRENCY + 2);
    expect(net.requested.length).toBeLessThan(1 + 1 + 4 + 24 + countTiles(neighbourhood) + 14);
  });
});
