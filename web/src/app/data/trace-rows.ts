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
 * The rows of the path trace in IndexedDB (docs/11 5.27.8, docs/03 section 6.2): their shapes, the plain-array coding
 * of a saved walk, and the delete that `LocalStore.deleteHouse` needs. No Angular, no `LocalStore` import, so
 * `local-store.service.ts` can use it without a cycle with `trace-store.ts`.
 *
 * **Privacy** (PRV-028, T-I30): nothing in this file or in `trace-store.ts` is read by any export, backup, Drive or server
 * sync or AI path; the source test of `trace-no-leaks.spec.ts` fails when one of them refers to these stores.
 */

import { TRACE, segmentLengthM } from '../shared/trace-geo';
import type { TracePoint } from '../shared/trace-geo';
import type { LocalDb } from './local-db';

/** One kept point of the 30-day trace (store `trace_points`, key `id`, index `walk`). */
export interface TracePointRow {
  /** `"<walkId>-<atMs>"`: a point is written once, and a second write of the same fix replaces it. */
  readonly id: string;
  /** The walk id: the `atMs` of the walk's first kept point; 0 is no id. */
  readonly walk: number;
  readonly at: number;
  readonly lat: number;
  readonly lon: number;
  /** The fix's reported accuracy in metres (the 50 m gate has passed). */
  readonly acc: number;
  /** Present, and true, only for the first point after the page was hidden for more than 5 minutes. */
  readonly resumed?: true;
}

/**
 * A walk the person linked to a house (store `saved_walks`, key `id`, index `houseId`). The points are a plain array
 * (no varint on the website), three numbers per point: the change in `round(lat * 1e6)`, the change in `round(lon * 1e6)`
 * and the change in whole seconds from `startedAt` (0 for the first point, whose time is `startedAt`). Accuracy is not
 * kept. No `dirty`, `updatedAt` or `deleted` column on purpose: nothing can sync it.
 */
export interface SavedWalkRow {
  readonly id: string;
  readonly houseId: string;
  /** The first point's time in epoch ms, also the walk id. */
  readonly startedAt: number;
  readonly endedAt: number;
  readonly savedAt: number;
  readonly pointCount: number;
  /** The walk's length in whole metres, for the lists. */
  readonly lengthM: number;
  readonly points: readonly number[];
  /** The indexes of the points that resume after a long hidden pause (absent when there are none). */
  readonly resumed?: readonly number[];
}

/** The row id of a kept point. */
export function pointId(walkId: number, atMs: number): string {
  return `${walkId}-${atMs}`;
}

/** A kept point as its row. */
export function pointRow(p: TracePoint, accuracyM: number): TracePointRow {
  const walk = p.walkId ?? 0;
  return { id: pointId(walk, p.atMs), walk, at: p.atMs, lat: p.lat, lon: p.lon, acc: accuracyM, ...(p.resumed === true ? { resumed: true as const } : {}) };
}

/** A row as a point. */
export function pointOfRow(r: TracePointRow): TracePoint {
  return { lat: r.lat, lon: r.lon, atMs: r.at, walkId: r.walk, resumed: r.resumed === true };
}

/** The length of a walk in metres: its segments, none into a resumed point. */
export function walkLengthM(points: readonly TracePoint[]): number {
  let sum = 0;
  for (let i = 1; i < points.length; i++) if (points[i].resumed !== true) sum += segmentLengthM(points[i - 1], points[i]);
  return sum;
}

/** The fields of a saved walk's row that come from its points (everything but the id, the house and the save time). */
export type EncodedWalk = Pick<SavedWalkRow, 'startedAt' | 'endedAt' | 'pointCount' | 'lengthM' | 'points' | 'resumed'>;

/** Encodes a walk's points (in time order) to the plain array: latitudes and longitudes to 1e-6 degrees, times to seconds. */
export function encodeWalk(points: readonly TracePoint[]): EncodedWalk {
  const startedAt = points[0].atMs;
  const out: number[] = [];
  const resumed: number[] = [];
  let lat = 0;
  let lon = 0;
  let seconds = 0;
  points.forEach((p, i) => {
    const latE6 = Math.round(p.lat * 1e6);
    const lonE6 = Math.round(p.lon * 1e6);
    const t = Math.round((p.atMs - startedAt) / 1000);
    out.push(latE6 - lat, lonE6 - lon, t - seconds);
    lat = latE6;
    lon = lonE6;
    seconds = t;
    if (p.resumed === true && i > 0) resumed.push(i);
  });
  return {
    startedAt,
    endedAt: points[points.length - 1].atMs,
    pointCount: points.length,
    lengthM: Math.round(walkLengthM(points)),
    points: out,
    ...(resumed.length > 0 ? { resumed } : {}),
  };
}

/**
 * The walk of a saved row, its points rounded to 1e-6 degrees and a second, each with the walk id `startedAt`; null when
 * the array is not three numbers per point or does not match `pointCount` (a damaged row is no walk, never a short one).
 */
export function decodeWalk(row: SavedWalkRow): TracePoint[] | null {
  const a = row.points;
  if (!Array.isArray(a) || a.length % 3 !== 0 || a.length / 3 !== row.pointCount || row.pointCount < 1) return null;
  if (!a.every((n) => Number.isFinite(n))) return null;
  const resumed = new Set(Array.isArray(row.resumed) ? row.resumed : []);
  const points: TracePoint[] = [];
  let lat = 0;
  let lon = 0;
  let seconds = 0;
  for (let i = 0; i < a.length; i += 3) {
    lat += a[i];
    lon += a[i + 1];
    seconds += a[i + 2];
    points.push({ lat: lat / 1e6, lon: lon / 1e6, atMs: row.startedAt + seconds * 1000, walkId: row.startedAt, resumed: resumed.has(i / 3) });
  }
  return points;
}

/** A new id for a saved walk. */
export function newWalkId(): string {
  const c = (globalThis as { crypto?: Crypto }).crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  const bytes = new Uint8Array(16);
  if (c) c.getRandomValues(bytes);
  else for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Deletes every saved walk of a house, in one transaction. `LocalStore.deleteHouse` calls it: a saved walk never outlives
 * its house (PRV-030, docs/11 5.27.6; the website has no undo for a delete).
 */
export async function deleteSavedWalksOfHouse(db: LocalDb, houseId: string): Promise<void> {
  const rows = await db.getAllByIndex<SavedWalkRow>('saved_walks', 'houseId', houseId);
  await db.deleteAll(
    'saved_walks',
    rows.map((r) => r.id),
  );
}

/** The limits of docs/11 5.27.6, from the shared constants. */
export const MAX_SAVED_WALKS_PER_HOUSE = 20;
export const MAX_SAVED_WALKS_PER_DEVICE = 200;
export const MAX_WALK_POINTS = TRACE.maxWalkPoints;
