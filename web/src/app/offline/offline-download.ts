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
 * Offline maps on the website (S4b-BL-79): the download. It reads the base map's style (`MAP_STYLE_URL`), finds the
 * tile sources, the sprite and the fonts it names, and saves them with every tile of the person's box from zoom 0 to
 * street level into one cache ({@link OFFLINE_CACHE}). The browser's `fetch` and Cache Storage are passed in, so the
 * whole run is tested with fakes.
 *
 * Polite to the provider by design: the box is capped at {@link MAX_TILES} tiles, a few requests run at a time, a
 * file already saved (another area's, the sprite, a glyph range) is not fetched again, and nothing is retried more
 * than twice (docs/03 §11.2).
 */

import { EMPTY_HEADER, MAP_ORIGIN, type CacheLike } from './offline-protocol';
import {
  MAX_TILES,
  MAX_ZOOM,
  areaTileKeys,
  cacheKey,
  countTiles,
  tileRanges,
  tileUrl,
  tilesOf,
  type GeoBounds,
  type SavedArea,
  type TileSource,
} from './offline-tiles';

/** How many files are fetched at the same time. */
export const CONCURRENCY = 6;

/** Retries of one file after a failure (so up to three tries). */
export const RETRIES = 2;

/**
 * The first code point of each glyph range saved for every font: Latin (0, 256), the punctuation block (8192), and
 * Devanagari through Malayalam (2304 to 3583), so Hindi, Tamil and Telugu place names draw offline too. Ranges the
 * server does not have are skipped.
 */
export const GLYPH_RANGE_STARTS: readonly number[] = [0, 256, 2304, 2560, 2816, 3072, 3328, 8192];

export interface DownloadProgress {
  /** Files saved so far (tiles only). */
  readonly done: number;
  /** Tiles in all. */
  readonly total: number;
  readonly bytes: number;
}

export interface DownloadDeps {
  readonly cache: CacheLike;
  fetch(url: string, init?: { signal?: AbortSignal }): Promise<Response>;
  readonly styleUrl: string;
}

export interface DownloadRequest {
  readonly id: string;
  readonly name: string;
  readonly bounds: GeoBounds;
  readonly now: number;
}

export interface DownloadHooks {
  readonly signal?: AbortSignal;
  /** Called once the style is read and the area's files are known, before any tile is fetched. */
  planned?(area: SavedArea): void;
  progress?(p: DownloadProgress): void;
}

/** The download failed for a reason worth saying: the box is too big, or a file the map cannot do without is missing. */
export class OfflineDownloadError extends Error {
  constructor(
    readonly reason: 'too-large' | 'no-style' | 'no-tiles' | 'fetch' | 'storage',
    message: string,
  ) {
    super(message);
    this.name = 'OfflineDownloadError';
  }
}

interface StyleLike {
  sources?: Record<string, { type?: string; url?: string; tiles?: string[]; maxzoom?: number }>;
  sprite?: unknown;
  glyphs?: unknown;
  layers?: { layout?: Record<string, unknown> }[];
}

const OPERATORS: ReadonlySet<string> = new Set([
  'literal', 'get', 'case', 'step', 'match', 'coalesce', 'interpolate', 'let', 'var', 'concat', 'format', 'to-string', 'zoom',
]);

/** Every font stack the style's layers use (`text-font`), as the `fontstack` path part (names joined by commas). */
export function fontStacks(style: StyleLike): string[] {
  const out = new Set<string>();
  const walk = (v: unknown): void => {
    if (!Array.isArray(v)) return;
    if (v.length > 0 && v.every((x) => typeof x === 'string') && !OPERATORS.has(v[0] as string)) {
      out.add((v as string[]).join(','));
      return;
    }
    if (v[0] === 'literal' && Array.isArray(v[1])) {
      walk(v[1]);
      return;
    }
    for (const x of v) walk(x);
  };
  for (const layer of style.layers ?? []) walk(layer.layout?.['text-font']);
  return [...out];
}

/** The glyph file addresses for `fontstacks`, from the style's `glyphs` template. */
export function glyphUrls(template: string, fontstacks: readonly string[]): string[] {
  const out: string[] = [];
  for (const stack of fontstacks) {
    for (const start of GLYPH_RANGE_STARTS) {
      out.push(template.replace('{fontstack}', stack).replace('{range}', `${start}-${start + 255}`));
    }
  }
  return out;
}

/** The four sprite files (`.json` and `.png`, normal and @2x) for the style's `sprite` base address. */
export function spriteUrls(base: string): string[] {
  return [`${base}.json`, `${base}.png`, `${base}@2x.json`, `${base}@2x.png`];
}

function fromMap(url: unknown): url is string {
  return typeof url === 'string' && url.startsWith(`${MAP_ORIGIN}/`);
}

async function getOk(deps: DownloadDeps, url: string, signal: AbortSignal | undefined): Promise<Response> {
  let last: unknown;
  for (let attempt = 0; attempt <= RETRIES; attempt++) {
    signal?.throwIfAborted();
    try {
      const res = await deps.fetch(url, { signal });
      if (res.ok || res.status === 404) return res;
      last = new OfflineDownloadError('fetch', `${res.status} ${url}`);
    } catch (e) {
      if (signal?.aborted) throw e;
      last = e;
    }
  }
  throw last instanceof OfflineDownloadError ? last : new OfflineDownloadError('fetch', `Could not fetch ${url}`);
}

/** Saves a copy of `res` under the key of `url`; a fresh Response with only a content type, so no encoding header survives. */
async function store(cache: CacheLike, url: string, res: Response, empty = false): Promise<number> {
  const body = empty ? new ArrayBuffer(0) : await res.arrayBuffer();
  const headers: Record<string, string> = { 'content-type': res.headers.get('content-type') ?? 'application/octet-stream' };
  if (empty) headers[EMPTY_HEADER] = '1';
  try {
    await cache.put(cacheKey(url), new Response(body, { headers }));
  } catch (e) {
    throw new OfflineDownloadError('storage', e instanceof Error ? e.message : 'The browser would not store it.');
  }
  return body.byteLength;
}

/**
 * Downloads one area. Resolves with the finished {@link SavedArea} (`state: 'ready'`). Rejects with an
 * `AbortError` when `hooks.signal` aborts, and with {@link OfflineDownloadError} otherwise; either way what was
 * already saved stays in the cache for the caller to remove (the planned area says which keys are its own).
 */
export async function downloadArea(req: DownloadRequest, deps: DownloadDeps, hooks: DownloadHooks = {}): Promise<SavedArea> {
  const { signal } = hooks;
  if (countTiles(req.bounds, MAX_ZOOM) > MAX_TILES) throw new OfflineDownloadError('too-large', 'The area is too large.');

  // The style: fetched fresh and saved, so the map can start from it with no network.
  const styleRes = await getOk(deps, deps.styleUrl, signal);
  if (!styleRes.ok) throw new OfflineDownloadError('no-style', 'The map style could not be read.');
  const styleText = await styleRes.text();
  let style: StyleLike;
  try {
    style = JSON.parse(styleText) as StyleLike;
  } catch {
    throw new OfflineDownloadError('no-style', 'The map style could not be read.');
  }
  await store(deps.cache, deps.styleUrl, new Response(styleText, { headers: { 'content-type': 'application/json' } }));
  const assets: string[] = [deps.styleUrl];

  // The tile sources the style names, by TileJSON address or by template.
  const sources: TileSource[] = [];
  for (const source of Object.values(style.sources ?? {})) {
    if (source.type !== 'vector' && source.type !== 'raster') continue;
    let template: string | undefined = source.tiles?.[0];
    let maxzoom = source.maxzoom;
    if (!template && fromMap(source.url)) {
      const res = await getOk(deps, source.url, signal);
      if (!res.ok) throw new OfflineDownloadError('no-tiles', 'The tile list could not be read.');
      const text = await res.text();
      const tj = JSON.parse(text) as { tiles?: string[]; maxzoom?: number };
      await store(deps.cache, source.url, new Response(text, { headers: { 'content-type': 'application/json' } }));
      assets.push(source.url);
      template = tj.tiles?.[0];
      maxzoom = tj.maxzoom ?? maxzoom;
    }
    if (fromMap(template)) sources.push({ template, maxZoom: Math.min(MAX_ZOOM, maxzoom ?? MAX_ZOOM) });
  }
  if (sources.length === 0) throw new OfflineDownloadError('no-tiles', 'The style names no tiles.');

  const files: string[] = [];
  if (fromMap(style.sprite)) files.push(...spriteUrls(style.sprite));
  const glyphs = typeof style.glyphs === 'string' && fromMap(style.glyphs) ? glyphUrls(style.glyphs, fontStacks(style)) : [];

  const area: SavedArea = {
    id: req.id,
    name: req.name,
    bounds: req.bounds,
    sources,
    maxZoom: MAX_ZOOM,
    assets: [...assets, ...files, ...glyphs],
    tiles: 0,
    bytes: 0,
    state: 'saving',
    savedAt: req.now,
  };
  const tileKeys = areaTileKeys(area);
  const planned: SavedArea = { ...area, tiles: tileKeys.length };
  hooks.planned?.(planned);

  // Sprite and glyphs: the sprite is needed (icons), glyph ranges the server lacks are simply skipped. A file already
  // in the cache (another area's) is not fetched again.
  let bytes = 0;
  const kept: string[] = [...assets];
  for (const url of files) {
    if (!(await deps.cache.match(cacheKey(url)))) {
      const res = await getOk(deps, url, signal);
      if (!res.ok) throw new OfflineDownloadError('no-style', 'The map symbols could not be read.');
      bytes += await store(deps.cache, url, res);
    }
    kept.push(url);
  }
  await pool(glyphs, signal, async (url) => {
    if (await deps.cache.match(cacheKey(url))) {
      kept.push(url);
      return;
    }
    try {
      const res = await getOk(deps, url, signal);
      if (res.ok) {
        bytes += await store(deps.cache, url, res);
        kept.push(url);
      }
    } catch (e) {
      if (signal?.aborted || (e instanceof OfflineDownloadError && e.reason === 'storage')) throw e;
      // A glyph range that cannot be fetched: labels in that script may be missing offline; the map still works.
    }
  });

  // The tiles.
  const queue: string[] = [];
  for (const source of sources) {
    const top = Math.min(MAX_ZOOM, source.maxZoom);
    for (const t of tilesOf(tileRanges(req.bounds, top))) queue.push(tileUrl(source.template, t.z, t.x, t.y));
  }
  let done = 0;
  const total = queue.length;
  hooks.progress?.({ done, total, bytes });
  await pool(queue, signal, async (url) => {
    const key = cacheKey(url);
    const have = await deps.cache.match(key);
    if (have) {
      bytes += (await have.arrayBuffer()).byteLength;
    } else {
      const res = await getOk(deps, url, signal);
      bytes += await store(deps.cache, url, res, !res.ok);
    }
    done++;
    hooks.progress?.({ done, total, bytes });
  });

  return { ...planned, assets: kept, tiles: tileKeys.length, bytes, state: 'ready' };
}

/** Runs `work` over `items`, {@link CONCURRENCY} at a time; the first failure stops the rest and is thrown. */
async function pool<T>(items: readonly T[], signal: AbortSignal | undefined, work: (item: T) => Promise<void>): Promise<void> {
  let next = 0;
  let failed: unknown = null;
  let hasFailed = false;
  const worker = async (): Promise<void> => {
    while (!hasFailed && next < items.length) {
      signal?.throwIfAborted();
      const item = items[next++];
      try {
        await work(item);
      } catch (e) {
        if (!hasFailed) {
          hasFailed = true;
          failed = e;
        }
        return;
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(CONCURRENCY, items.length) }, worker));
  if (hasFailed) throw failed;
  signal?.throwIfAborted();
}

