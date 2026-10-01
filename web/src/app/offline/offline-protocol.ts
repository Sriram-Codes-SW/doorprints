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
 * Offline maps on the website (S4b-BL-79): how a saved area's files reach MapLibre.
 *
 * The service worker ignores cross-origin requests (`public/sw.js`: third-party tiles, their terms, the storage they
 * would take), so the saved files are served by MapLibre's own hook instead. While at least one area is saved,
 * `transformRequest` ({@link rewriteForOffline}) sends every request to OpenFreeMap through two custom protocols
 * registered with `addProtocol` (`map-style.ts`):
 *
 *  * {@link TILE_SCHEME}: a tile. Cache first (a tile's address names the planet build, so it never changes), then the
 *    network. Nothing is ever *added* to the cache here: only a download the person started writes to it, so browsing
 *    the map costs no storage and OpenFreeMap sees only what it always saw.
 *  * {@link ASSET_SCHEME}: the style, the TileJSON, the sprite and the glyphs. Network first, so the style stays
 *    current while online, and the saved copy when the network fails (offline, or a flaky connection).
 *
 * With no area saved nothing is rewritten and the map behaves exactly as before. India's boundary rules are not
 * touched: they are applied to the style on every `style.load` (`india-boundaries.ts`), whichever way the style came
 * (ADR-22), and the overlay file is the app's own, same-origin and precached.
 *
 * Pure of the browser's globals (the cache and `fetch` are passed in), so the policy is tested with fakes.
 */

import { cacheKey } from './offline-tiles';

/** The one host whose requests are served from a saved area (the app's base map, `MAP_STYLE_URL`). */
export const MAP_ORIGIN = 'https://tiles.openfreemap.org';

/** The Cache Storage cache every saved area's files go into (not `doorprints-shell-`: the service worker owns those). */
export const OFFLINE_CACHE = 'doorprints-offline-maps-v1';

/** The name prefix of this feature's caches, for "Remove all data". */
export const OFFLINE_CACHE_PREFIX = 'doorprints-offline-';

export const TILE_SCHEME = 'dpmap-tile';
export const ASSET_SCHEME = 'dpmap-file';

/** Marks a cached tile that the server answered with 404 (no data there): served back as a 404 so MapLibre skips it. */
export const EMPTY_HEADER = 'x-doorprints-empty';

/** The part of `Cache` the handler and the download use (a fake in the unit tests). */
export type CacheLike = Pick<Cache, 'match' | 'put' | 'delete'>;

/** What the handler needs from the browser. */
export interface OfflineDeps {
  /** The offline cache, or null where Cache Storage is missing (an insecure page, some private modes). */
  openCache(): Promise<CacheLike | null>;
  fetch(url: string, init?: { signal?: AbortSignal }): Promise<Response>;
}

/** The request parameters MapLibre passes a protocol handler (the part used here). */
export interface ProtocolRequest {
  readonly url: string;
  readonly type?: 'string' | 'json' | 'arrayBuffer' | 'image';
}

/** An error MapLibre reads `status` from (it quietly skips a tile that answers 404). */
export class HttpStatusError extends Error {
  constructor(
    readonly status: number,
    url: string,
  ) {
    super(`${status} ${url}`);
    this.name = 'HttpStatusError';
  }
}

/**
 * The request MapLibre should make instead of `url`, or undefined to leave it as it is: only OpenFreeMap's, and only
 * while `active` (an area is saved). `type` is MapLibre's `ResourceType` for the request.
 */
export function rewriteForOffline(url: string, type: string | undefined, active: boolean): { url: string } | undefined {
  if (!active || !url.startsWith(`${MAP_ORIGIN}/`)) return undefined;
  const scheme = type === 'Tile' ? TILE_SCHEME : ASSET_SCHEME;
  return { url: `${scheme}://${url.slice('https://'.length)}` };
}

/** The https address behind a rewritten one. */
export function originalUrl(url: string): string {
  const at = url.indexOf('://');
  return at < 0 ? url : `https://${url.slice(at + 3)}`;
}

/** The body of `res` in the shape MapLibre asked for (`RequestParameters.type`). */
async function readAs(res: Response, type: ProtocolRequest['type']): Promise<ArrayBuffer | string | object> {
  if (type === 'json') return (await res.json()) as object;
  if (type === 'string') return res.text();
  // 'arrayBuffer' for tiles and glyphs; 'image' for the sprite: MapLibre decodes the encoded bytes itself.
  return res.arrayBuffer();
}

function isAbort(e: unknown): boolean {
  return e instanceof DOMException && e.name === 'AbortError';
}

/**
 * The handler for one scheme. `cacheFirst` is the tile policy, otherwise network first (see the file comment). Throws
 * when neither has the file, which MapLibre reports as an ordinary load error (the same as being offline with no area).
 */
export function protocolHandler(
  cacheFirst: boolean,
  deps: OfflineDeps,
): (params: ProtocolRequest, abort: AbortController) => Promise<{ data: ArrayBuffer | string | object }> {
  return async (params, abort) => {
    const url = originalUrl(params.url);
    const key = cacheKey(url);
    const saved = async (): Promise<Response | null> => {
      try {
        const cache = await deps.openCache();
        return (await cache?.match(key)) ?? null;
      } catch {
        return null;
      }
    };
    const network = async (): Promise<Response> => {
      const res = await deps.fetch(url, { signal: abort.signal });
      if (!res.ok) throw new HttpStatusError(res.status, url);
      return res;
    };
    let res: Response;
    if (cacheFirst) {
      res = (await saved()) ?? (await network());
    } else {
      try {
        res = await network();
      } catch (e) {
        if (isAbort(e)) throw e;
        const copy = await saved();
        if (!copy) throw e;
        res = copy;
      }
    }
    if (res.headers.get(EMPTY_HEADER)) throw new HttpStatusError(404, url);
    return { data: await readAs(res, params.type) };
  };
}

/**
 * Whether an area is saved, read by `transformRequest` on every request (so a plain variable, not storage). The
 * offline service keeps it current; {@link setOfflineActive}.
 */
let active = false;

export function offlineActive(): boolean {
  return active;
}

export function setOfflineActive(value: boolean): void {
  active = value;
}
