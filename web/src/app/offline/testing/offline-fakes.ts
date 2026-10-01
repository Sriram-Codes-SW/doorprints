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

/**
 * Fakes for the offline maps' tests: Cache Storage in a Map, and an OpenFreeMap that serves a tiny style, a TileJSON, a
 * sprite, glyph ranges and tiles, and counts what it was asked for. Nothing here touches the network.
 */

import type { CacheLike } from '../offline-protocol';

/** Cache Storage in memory: bodies are copied in and out, as the real one does. */
export class FakeCache implements CacheLike {
  readonly entries = new Map<string, { body: ArrayBuffer; headers: [string, string][] }>();
  /** When set, `put` throws (the quota is full). */
  failPuts = false;

  async match(request: RequestInfo | URL): Promise<Response | undefined> {
    const e = this.entries.get(String(request));
    return e ? new Response(e.body.slice(0), { headers: e.headers }) : undefined;
  }

  async put(request: RequestInfo | URL, response: Response): Promise<void> {
    if (this.failPuts) throw new DOMException('Quota exceeded', 'QuotaExceededError');
    this.entries.set(String(request), { body: await response.arrayBuffer(), headers: [...response.headers.entries()] });
  }

  async delete(request: RequestInfo | URL): Promise<boolean> {
    return this.entries.delete(String(request));
  }

  has(key: string): boolean {
    return this.entries.has(key);
  }
}

export const STYLE_URL = 'https://tiles.openfreemap.org/styles/liberty';
export const TILEJSON_URL = 'https://tiles.openfreemap.org/planet';
export const VECTOR_TEMPLATE = 'https://tiles.openfreemap.org/planet/20260927_080001_pt/{z}/{x}/{y}.pbf';
export const RASTER_TEMPLATE = 'https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png';
export const SPRITE_BASE = 'https://tiles.openfreemap.org/sprites/ofm_f384/ofm';
export const GLYPHS_TEMPLATE = 'https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf';

/** A Liberty-shaped style: a raster source, a vector source by TileJSON address, a sprite, glyphs and two font stacks. */
export const FAKE_STYLE = {
  version: 8,
  sources: {
    ne2_shaded: { type: 'raster', tiles: [RASTER_TEMPLATE], maxzoom: 6, tileSize: 256 },
    openmaptiles: { type: 'vector', url: TILEJSON_URL },
  },
  sprite: SPRITE_BASE,
  glyphs: GLYPHS_TEMPLATE,
  layers: [
    { id: 'background', type: 'background' },
    { id: 'roads', type: 'symbol', source: 'openmaptiles', layout: { 'text-font': ['Noto Sans Regular'] } },
    { id: 'water', type: 'symbol', source: 'openmaptiles', layout: { 'text-font': ['Noto Sans Italic'] } },
    { id: 'places', type: 'symbol', source: 'openmaptiles', layout: { 'text-font': ['literal', ['Noto Sans Bold']] } },
  ],
};

export interface FakeNetwork {
  fetch: (url: string, init?: { signal?: AbortSignal }) => Promise<Response>;
  /** Every URL asked for, in order. */
  readonly requested: string[];
  /** URLs that answer with this status instead of 200. */
  readonly status: Map<string, number>;
  /** Called before each answer (to abort mid-download, say). */
  onRequest?: (url: string, count: number) => void;
}

/** An OpenFreeMap that knows `FAKE_STYLE`; any `.pbf` or `.png` tile answers with 4 bytes. */
export function fakeNetwork(style: unknown = FAKE_STYLE): FakeNetwork {
  const requested: string[] = [];
  const status = new Map<string, number>();
  const net: FakeNetwork = {
    requested,
    status,
    fetch: async (url, init) => {
      if (init?.signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      requested.push(url);
      net.onRequest?.(url, requested.length);
      const forced = status.get(url);
      if (forced !== undefined) return new Response('', { status: forced });
      if (url === STYLE_URL) return new Response(JSON.stringify(style), { headers: { 'content-type': 'application/json' } });
      if (url === TILEJSON_URL) {
        return new Response(JSON.stringify({ tilejson: '3.0.0', tiles: [VECTOR_TEMPLATE], maxzoom: 14 }), {
          headers: { 'content-type': 'application/json' },
        });
      }
      return new Response(new Uint8Array([1, 2, 3, 4]), { headers: { 'content-type': 'application/octet-stream' } });
    },
  };
  return net;
}

/** localStorage in a Map. */
export class MemoryStorage {
  data = new Map<string, string>();
  getItem = (k: string) => this.data.get(k) ?? null;
  setItem = (k: string, v: string) => void this.data.set(k, v);
  removeItem = (k: string) => void this.data.delete(k);
}

/** A browser for `OfflineMapsService` (`OFFLINE_ENV`): the fake cache and network, a quota, and switches. */
export function fakeEnv(seed?: unknown) {
  const cache = new FakeCache();
  const net = fakeNetwork();
  const storage = new MemoryStorage();
  if (seed !== undefined) storage.data.set('doorprints.offlineAreas', JSON.stringify(seed));
  let n = 0;
  const env = {
    quota: { usage: 1_000_000, quota: 500_000_000 } as { usage: number; quota: number } | null,
    isOnline: true,
    isMetered: null as boolean | null,
    hasCache: true,
    openCache: async () => cache,
    deleteCache: async () => cache.entries.clear(),
    fetch: net.fetch,
    storageEstimate: async () => env.quota,
    metered: () => env.isMetered,
    online: () => env.isOnline,
    storage,
    now: () => 1_000 + n,
    newId: () => `id${++n}`,
  };
  return { env, cache, net, storage };
}
