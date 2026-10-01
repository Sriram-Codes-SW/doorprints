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
import { FakeCache, fakeNetwork } from './testing/offline-fakes';
import {
  ASSET_SCHEME,
  EMPTY_HEADER,
  HttpStatusError,
  TILE_SCHEME,
  offlineActive,
  originalUrl,
  protocolHandler,
  rewriteForOffline,
  setOfflineActive,
} from './offline-protocol';

const TILE = 'https://tiles.openfreemap.org/planet/b1/12/3/4.pbf';
const STYLE = 'https://tiles.openfreemap.org/styles/liberty';

function deps(cache: FakeCache | null, net = fakeNetwork()) {
  return { net, d: { openCache: async () => cache, fetch: net.fetch } };
}

const bytes = (b: ArrayBuffer | string | object) => [...new Uint8Array(b as ArrayBuffer)];

describe('rewriteForOffline (the map asks only for what a saved area can serve)', () => {
  it('rewrites OpenFreeMap requests while an area is saved: tiles to the tile scheme, the rest to the file scheme', () => {
    expect(rewriteForOffline(TILE, 'Tile', true)).toEqual({ url: `${TILE_SCHEME}://tiles.openfreemap.org/planet/b1/12/3/4.pbf` });
    expect(rewriteForOffline(STYLE, 'Style', true)).toEqual({ url: `${ASSET_SCHEME}://tiles.openfreemap.org/styles/liberty` });
    expect(rewriteForOffline('https://tiles.openfreemap.org/planet', 'Source', true)?.url.startsWith(`${ASSET_SCHEME}://`)).toBe(true);
    expect(originalUrl(rewriteForOffline(TILE, 'Tile', true)!.url)).toBe(TILE);
  });

  it('leaves everything alone with no area saved, and every other host always (India boundaries file included)', () => {
    expect(rewriteForOffline(TILE, 'Tile', false)).toBeUndefined();
    expect(rewriteForOffline('https://doorprints.web.app/geo/in-boundaries.geojson', 'Unknown', true)).toBeUndefined();
    expect(rewriteForOffline('https://tiles.openfreemap.org.evil.example/x', 'Tile', true)).toBeUndefined();
    expect(rewriteForOffline('https://example.org/tiles.openfreemap.org/x', 'Tile', true)).toBeUndefined();
  });

  it('the active flag is a plain switch', () => {
    setOfflineActive(true);
    expect(offlineActive()).toBe(true);
    setOfflineActive(false);
    expect(offlineActive()).toBe(false);
  });
});

describe('the tile handler (cache first, never writes)', () => {
  it('serves a saved tile without the network', async () => {
    const cache = new FakeCache();
    await cache.put(TILE, new Response(new Uint8Array([9, 8, 7])));
    const { net, d } = deps(cache);
    const out = await protocolHandler(true, d)({ url: `${TILE_SCHEME}://${TILE.slice(8)}`, type: 'arrayBuffer' }, new AbortController());
    expect(bytes(out.data)).toEqual([9, 8, 7]);
    expect(net.requested).toEqual([]);
  });

  it('falls back to the network for a tile that is not saved, and saves nothing', async () => {
    const cache = new FakeCache();
    const { net, d } = deps(cache);
    const out = await protocolHandler(true, d)({ url: `${TILE_SCHEME}://${TILE.slice(8)}`, type: 'arrayBuffer' }, new AbortController());
    expect(bytes(out.data)).toEqual([1, 2, 3, 4]);
    expect(net.requested).toEqual([TILE]);
    expect(cache.entries.size).toBe(0);
  });

  it('fails like any offline tile when it is neither saved nor reachable', async () => {
    const net = fakeNetwork();
    const d = { openCache: async () => new FakeCache(), fetch: async () => Promise.reject(new TypeError('Failed to fetch')) };
    await expect(protocolHandler(true, d)({ url: `${TILE_SCHEME}://${TILE.slice(8)}`, type: 'arrayBuffer' }, new AbortController())).rejects.toThrow('Failed to fetch');
    expect(net.requested).toEqual([]);
  });

  it('a saved empty tile (the server said 404) is a 404 for MapLibre, which skips it quietly', async () => {
    const cache = new FakeCache();
    await cache.put(TILE, new Response(new ArrayBuffer(0), { headers: { [EMPTY_HEADER]: '1' } }));
    const { d } = deps(cache);
    const err = await protocolHandler(true, d)({ url: `${TILE_SCHEME}://${TILE.slice(8)}`, type: 'arrayBuffer' }, new AbortController()).catch((e) => e);
    expect(err).toBeInstanceOf(HttpStatusError);
    expect(err.status).toBe(404);
  });

  it('a network 404 for a tile that is not saved is a 404 too', async () => {
    const { net, d } = deps(new FakeCache());
    net.status.set(TILE, 404);
    const err = await protocolHandler(true, d)({ url: `${TILE_SCHEME}://${TILE.slice(8)}`, type: 'arrayBuffer' }, new AbortController()).catch((e) => e);
    expect(err.status).toBe(404);
  });

  it('works with no Cache Storage at all (an insecure page): the network answers', async () => {
    const { d } = deps(null);
    const out = await protocolHandler(true, d)({ url: `${TILE_SCHEME}://${TILE.slice(8)}`, type: 'arrayBuffer' }, new AbortController());
    expect(bytes(out.data)).toEqual([1, 2, 3, 4]);
  });
});

describe('the file handler (network first, the saved copy when it fails)', () => {
  it('uses the network while it works, and parses JSON when MapLibre asks for json', async () => {
    const cache = new FakeCache();
    await cache.put(STYLE, new Response('{"saved":true}'));
    const { net, d } = deps(cache);
    const out = await protocolHandler(false, d)({ url: `${ASSET_SCHEME}://${STYLE.slice(8)}`, type: 'json' }, new AbortController());
    expect((out.data as { version: number }).version).toBe(8);
    expect(net.requested).toEqual([STYLE]);
  });

  it('serves the saved style when the network fails (offline)', async () => {
    const cache = new FakeCache();
    await cache.put(STYLE, new Response('{"saved":true}', { headers: { 'content-type': 'application/json' } }));
    const d = { openCache: async () => cache, fetch: async () => Promise.reject(new TypeError('offline')) };
    const out = await protocolHandler(false, d)({ url: `${ASSET_SCHEME}://${STYLE.slice(8)}`, type: 'json' }, new AbortController());
    expect(out.data).toEqual({ saved: true });
  });

  it('serves the saved copy on a server error too, and throws the network error when nothing is saved', async () => {
    const cache = new FakeCache();
    const { net, d } = deps(cache);
    net.status.set(STYLE, 503);
    await expect(protocolHandler(false, d)({ url: `${ASSET_SCHEME}://${STYLE.slice(8)}`, type: 'json' }, new AbortController())).rejects.toBeInstanceOf(HttpStatusError);
    await cache.put(STYLE, new Response('{"saved":true}'));
    const out = await protocolHandler(false, d)({ url: `${ASSET_SCHEME}://${STYLE.slice(8)}`, type: 'json' }, new AbortController());
    expect(out.data).toEqual({ saved: true });
  });

  it('never answers an abort from the cache', async () => {
    const cache = new FakeCache();
    await cache.put(STYLE, new Response('{}'));
    const d = { openCache: async () => cache, fetch: async () => Promise.reject(new DOMException('Aborted', 'AbortError')) };
    await expect(protocolHandler(false, d)({ url: `${ASSET_SCHEME}://${STYLE.slice(8)}`, type: 'json' }, new AbortController())).rejects.toThrow('Aborted');
  });

  it('finds a glyph file by its normalised address (a space in the font name)', async () => {
    const cache = new FakeCache();
    await cache.put('https://tiles.openfreemap.org/fonts/Noto%20Sans%20Regular/0-255.pbf', new Response(new Uint8Array([5])));
    const d = { openCache: async () => cache, fetch: async () => Promise.reject(new TypeError('offline')) };
    const out = await protocolHandler(false, d)(
      { url: `${ASSET_SCHEME}://tiles.openfreemap.org/fonts/Noto Sans Regular/0-255.pbf`, type: 'arrayBuffer' },
      new AbortController(),
    );
    expect(bytes(out.data)).toEqual([5]);
  });
});
