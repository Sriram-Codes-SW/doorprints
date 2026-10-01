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
 * Offline maps on the website (docs/11 5.20, S4b-BL-79): the pure half. Tile arithmetic, the size estimate, the
 * cache keys of a saved area and what a delete may remove. No browser API is used here, so it is tested with vectors
 * (`offline-tiles.spec.ts`).
 *
 * Ported from Android's `OfflineTiles` (`android/ui/.../OfflineMaps.kt`, test `OfflineTilesTest`): the same Web
 * Mercator maths, the same limits and the same 50 KB a tile, so a box has the same size on both.
 */

/** A box on the map, in degrees. */
export interface GeoBounds {
  readonly south: number;
  readonly west: number;
  readonly north: number;
  readonly east: number;
}

/**
 * Street level: OpenFreeMap's Liberty tiles stop at zoom 14 and the map overzooms them, so 0 to 14 is the whole map
 * of the area, at every zoom (as on Android).
 */
export const MAX_ZOOM = 14;

/**
 * The most tiles one area may take: a whole large city at zoom 14 is a few hundred tiles, so 2,000 (about 100 MB) is
 * generous for a hunting area, small beside a browser's quota, and keeps one person's use of OpenFreeMap's public
 * tiles ordinary (docs/03 §11.2). The same number as Android's.
 */
export const MAX_TILES = 2_000;

/** What a tile weighs on average, for the estimate shown before the download (Liberty's city tiles, gzipped). */
export const AVERAGE_TILE_BYTES = 50_000;

/**
 * What a tile of the style's raster source weighs (Liberty's Natural Earth shading, zooms 0 to 6 only; 100 to 230 KB
 * a tile). Vector tiles are {@link AVERAGE_TILE_BYTES}.
 */
export const RASTER_TILE_BYTES = 150_000;

/** The highest zoom of the Natural Earth raster source in Liberty (`maxzoom: 6` in the style). */
export const RASTER_MAX_ZOOM = 6;

/**
 * The style's own files, saved once and shared by every area: the TileJSON, the sprite (about 230 KB with the @2x
 * sheet) and the glyph ranges of the three fonts for Latin and the Indian scripts (about 2 MB).
 */
export const ASSET_BYTES = 3_000_000;

/** How many areas one browser keeps, so the Settings list and the storage stay small. */
export const MAX_AREAS = 10;

/** Web Mercator's edge. */
const MAX_LAT = 85.05112878;

/** The tiles of one zoom level that cover a box: columns `xMin..xMax`, rows `yMin..yMax`, both ends included. */
export interface TileRange {
  readonly z: number;
  readonly xMin: number;
  readonly xMax: number;
  readonly yMin: number;
  readonly yMax: number;
}

function clamp(n: number, lo: number, hi: number): number {
  return Math.min(hi, Math.max(lo, n));
}

function tileX(lon: number, n: number): number {
  return clamp(Math.floor(((clamp(lon, -180, 180) + 180) / 360) * n), 0, n - 1);
}

function tileY(lat: number, n: number): number {
  const rad = (clamp(lat, -MAX_LAT, MAX_LAT) * Math.PI) / 180;
  const y = ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2) * n;
  return clamp(Math.floor(y), 0, n - 1);
}

/** The tile range of `bounds` at zoom `z`. */
export function tileRange(bounds: GeoBounds, z: number): TileRange {
  const n = 2 ** z;
  return {
    z,
    xMin: tileX(bounds.west, n),
    xMax: tileX(bounds.east, n),
    yMin: tileY(bounds.north, n),
    yMax: tileY(bounds.south, n),
  };
}

/** The tile ranges of `bounds` at every zoom from 0 to `maxZoom`. */
export function tileRanges(bounds: GeoBounds, maxZoom: number = MAX_ZOOM): TileRange[] {
  const out: TileRange[] = [];
  for (let z = 0; z <= maxZoom; z++) out.push(tileRange(bounds, z));
  return out;
}

/** How many tiles `bounds` needs from zoom 0 to `maxZoom`, each zoom's box counted whole. */
export function countTiles(bounds: GeoBounds, maxZoom: number = MAX_ZOOM): number {
  let total = 0;
  for (const r of tileRanges(bounds, maxZoom)) total += (r.xMax - r.xMin + 1) * (r.yMax - r.yMin + 1);
  return total;
}

/** The estimated download for `tiles` tiles, in bytes. */
export function estimateBytes(tiles: number): number {
  return tiles * AVERAGE_TILE_BYTES;
}

/**
 * Megabytes as the app shows them: one decimal under 10 (2.4), whole above (12), never less than 0.1 (Android's
 * `megabytesText`, as a number so the page formats it in the person's language).
 */
export function megabytes(bytes: number): number {
  const mb = bytes / 1_000_000;
  if (mb < 0.1) return 0.1;
  if (mb < 10) return Math.round(mb * 10) / 10;
  return Math.round(mb);
}

/** Fraction digits `megabytes()` is shown with: one under 10 MB, none above. */
export function megabyteDigits(bytes: number): number {
  const mb = megabytes(bytes);
  return mb < 10 ? 1 : 0;
}

/** Every tile of `ranges` as `{z, x, y}`, row by row. */
export function* tilesOf(ranges: readonly TileRange[]): Generator<{ z: number; x: number; y: number }> {
  for (const r of ranges) {
    for (let y = r.yMin; y <= r.yMax; y++) {
      for (let x = r.xMin; x <= r.xMax; x++) yield { z: r.z, x, y };
    }
  }
}

/** A TileJSON `tiles` template (`…/{z}/{x}/{y}.pbf`) filled in for one tile. */
export function tileUrl(template: string, z: number, x: number, y: number): string {
  return template.replace('{z}', String(z)).replace('{x}', String(x)).replace('{y}', String(y));
}

/**
 * The key a URL is kept under in Cache Storage: the URL as the browser normalises it (spaces in a font name become
 * `%20`, the fragment goes), so a lookup by the address MapLibre asks for finds what was saved. A string that is not
 * a URL is returned as it is.
 */
export function cacheKey(url: string): string {
  try {
    const u = new URL(url);
    u.hash = '';
    return u.href;
  } catch {
    return url;
  }
}

/**
 * The estimate shown before the download: the vector tiles at {@link AVERAGE_TILE_BYTES}, the few raster tiles of
 * the low zooms at {@link RASTER_TILE_BYTES}, and the style's files ({@link ASSET_BYTES}). (Android shows `estimateBytes(count)` alone; its pack has no separate
 * raster allowance in the number, which is a few megabytes at most.)
 */
export function estimateAreaBytes(bounds: GeoBounds): number {
  return estimateBytes(countTiles(bounds)) + countTiles(bounds, RASTER_MAX_ZOOM) * RASTER_TILE_BYTES + ASSET_BYTES;
}

/** One source of tiles in the style: its TileJSON `tiles` template and the highest zoom it has. */
export interface TileSource {
  readonly template: string;
  readonly maxZoom: number;
}

/** What one saved area is: enough to list it, to find its tiles again and to delete exactly its own. */
export interface SavedArea {
  readonly id: string;
  readonly name: string;
  readonly bounds: GeoBounds;
  /** The tile sources the area was fetched from (OpenFreeMap's vector template carries the planet build's date). */
  readonly sources: readonly TileSource[];
  /** The highest zoom saved (a source with fewer zooms stops at its own). */
  readonly maxZoom: number;
  /** The style, TileJSON, sprite and glyph files the map needs beside the tiles, as saved. */
  readonly assets: readonly string[];
  /** How many tiles the area has in all (the download's total). */
  readonly tiles: number;
  /** Bytes saved so far (all of them once ready). */
  readonly bytes: number;
  readonly state: 'saving' | 'ready' | 'failed';
  /** When it was saved, milliseconds since 1970. */
  readonly savedAt: number;
}

/** The cache keys of one area's tiles. */
export function areaTileKeys(area: Pick<SavedArea, 'bounds' | 'sources' | 'maxZoom'>): string[] {
  const keys: string[] = [];
  for (const source of area.sources) {
    const top = Math.min(area.maxZoom, source.maxZoom);
    for (const t of tilesOf(tileRanges(area.bounds, top))) keys.push(cacheKey(tileUrl(source.template, t.z, t.x, t.y)));
  }
  return keys;
}

/** The cache keys of everything an area holds: its tiles and its asset files. */
export function areaKeys(area: Pick<SavedArea, 'bounds' | 'sources' | 'maxZoom' | 'assets'>): string[] {
  return [...area.assets.map(cacheKey), ...areaTileKeys(area)];
}

/**
 * What deleting `removed` may take out of the cache: the keys only it holds. Zoom 0 to 5 tiles and the style are
 * shared by every area (the world at low zoom is the same tiles), so a key another area also holds stays.
 */
export function keysToEvict(removed: SavedArea, remaining: readonly SavedArea[]): string[] {
  const kept = new Set<string>();
  for (const other of remaining) {
    if (other.id !== removed.id) for (const k of areaKeys(other)) kept.add(k);
  }
  return areaKeys(removed).filter((k) => !kept.has(k));
}

/** Why a new area cannot be saved, or null when it can. */
export type Refusal = 'too-large' | 'too-many' | 'no-room';

/**
 * Whether an area of `tiles` tiles may be saved now: not over the tile cap, not more than {@link MAX_AREAS} areas, and
 * (when the browser says) room for the estimate with a tenth to spare. `usage` and `quota` come from
 * `navigator.storage.estimate()`; unknown (null) is allowed, since many browsers do not say.
 */
export function refusal(
  tiles: number,
  bytesNeeded: number,
  areasSaved: number,
  storage: { usage: number; quota: number } | null,
): Refusal | null {
  if (tiles > MAX_TILES) return 'too-large';
  if (areasSaved >= MAX_AREAS) return 'too-many';
  if (storage && storage.quota > 0 && bytesNeeded * 1.1 > storage.quota - storage.usage) return 'no-room';
  return null;
}

/** The room left in the browser's storage for this origin, in bytes, or null when the browser does not say. */
export function freeBytes(storage: { usage: number; quota: number } | null): number | null {
  return storage && storage.quota > 0 ? Math.max(0, storage.quota - storage.usage) : null;
}
