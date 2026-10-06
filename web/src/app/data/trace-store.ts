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
 * The website's path trace storage (docs/11 5.27.6 to 5.27.8, docs/03 section 6.2b row 4, S4b-FR-17): the 30-day trace
 * (`trace_points`), the walks the person saved to a house (`saved_walks`) and the trace settings, over the same IndexedDB
 * as the houses (`LocalStore.database()`), or memory where the browser refuses IndexedDB (a walk then lives for the page).
 *
 * **Local only** (PRV-028, T-I30): no export, backup, Drive or server sync or AI path reads these stores; the source test
 * of `trace-no-leaks.spec.ts` fails when one does. The stores are not in `LocalStore.revision`, so a walk never wakes the
 * sync engine. Nothing here logs.
 */

import { Injectable, inject } from '@angular/core';
import { TRACE } from '../shared/trace-geo';
import type { TracePoint, TraceWalk } from '../shared/trace-geo';
import type { PlaceWalk } from '../shared/trace-place-check';
import { splitWalks } from '../shared/trace-repeats';
import type { LocalDb } from './local-db';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS } from './records';
import {
  MAX_SAVED_WALKS_PER_DEVICE,
  MAX_SAVED_WALKS_PER_HOUSE,
  decodeWalk,
  encodeWalk,
  newWalkId,
  pointOfRow,
  pointRow,
  walkLengthM,
} from './trace-rows';
import type { SavedWalkRow, TracePointRow } from './trace-rows';

export { MAX_SAVED_WALKS_PER_DEVICE, MAX_SAVED_WALKS_PER_HOUSE } from './trace-rows';
export type { SavedWalkRow, TracePointRow } from './trace-rows';

/** The trace is kept this long (the saved walks are not pruned). */
export const TRACE_KEPT_DAYS = 30;
export const TRACE_KEPT_MS = TRACE_KEPT_DAYS * 86_400_000;

/** *Save this walk?* is asked only for a walk of at least this many points and metres; a shorter one is just kept 30 days. */
export const ASK_MIN_POINTS = 5;
export const ASK_MIN_LENGTH_M = 100;

/** How repeated paths look (docs/11 5.27.4): CLEAR is the default. */
export type RepeatLook = 'CLEAR' | 'SUBTLE' | 'OFF';
export const REPEAT_LOOKS: readonly RepeatLook[] = ['CLEAR', 'SUBTLE', 'OFF'];

/** Why a walk could not be saved; a refused save changes nothing and the walk stays in the 30-day trace. */
export type SaveWalkResult =
  | { readonly ok: true; readonly id: string }
  | { readonly ok: false; readonly reason: 'noWalk' | 'tooLong' | 'houseFull' | 'deviceFull' };

/** What the *Save this walk?* sheet shows about a walk. */
export interface WalkSummary {
  readonly walkId: number;
  readonly pointCount: number;
  readonly lengthM: number;
  readonly startedAt: number;
  readonly endedAt: number;
  /** Where the walk stopped (the house picker's nearest-house suggestion starts here). */
  readonly last: { readonly lat: number; readonly lon: number };
}

/** The walks the Map draws and the repeat detection reads: the 30-day trace and every saved walk. */
export interface AllWalks {
  readonly trace: TraceWalk[];
  readonly saved: { readonly row: SavedWalkRow; readonly walk: TraceWalk }[];
}

@Injectable({ providedIn: 'root' })
export class TraceStore {
  private readonly store = inject(LocalStore);

  private db(): Promise<LocalDb> {
    return this.store.database();
  }

  /** True where the data is really being kept; false in memory (private browsing): a walk then lives for the page only. */
  async persistent(): Promise<boolean> {
    return (await this.db()).kind === 'indexeddb';
  }

  // ---- The 30-day trace ----

  /** Writes one kept point (a closed tab loses nothing but the last point). Rejects with the browser's error (quota). */
  async putPoint(point: TracePoint, accuracyM: number): Promise<void> {
    await (await this.db()).put('trace_points', pointRow(point, accuracyM));
  }

  /** The points of one walk id, in time order. */
  async walkPoints(walkId: number): Promise<TracePoint[]> {
    const rows = await (await this.db()).getAllByIndex<TracePointRow>('trace_points', 'walk', walkId);
    return rows.map(pointOfRow).sort((a, b) => a.atMs - b.atMs);
  }

  /**
   * The walks of the last 30 days (or since `sinceMs`), as 5.27.3 step 1 splits them (by walk id and the 30-minute gap);
   * `key` is `t:<walkId>`. The trace is a few thousand rows at most, so it is read whole.
   */
  async traceWalks(nowMs: number, sinceMs: number = nowMs - TRACE_KEPT_MS): Promise<TraceWalk[]> {
    const rows = await (await this.db()).getAll<TracePointRow>('trace_points');
    return walksOf(rows.filter((r) => r.at >= sinceMs).map(pointOfRow));
  }

  /** Deletes the trace points older than 30 days. Never reads or touches `saved_walks`. Returns how many went. */
  async prune(nowMs: number): Promise<number> {
    const db = await this.db();
    const cutoff = nowMs - TRACE_KEPT_MS;
    const old = (await db.getAll<TracePointRow>('trace_points')).filter((r) => r.at < cutoff);
    await db.deleteAll(
      'trace_points',
      old.map((r) => r.id),
    );
    return old.length;
  }

  /** *Clear the path*: the 30-day trace only (the saved walks stay). */
  async clearTrace(): Promise<void> {
    await (await this.db()).clear('trace_points');
  }

  /** *Delete this walk*: the points of one walk id. */
  async deleteTraceWalk(walkId: number): Promise<void> {
    const db = await this.db();
    const rows = await db.getAllByIndex<TracePointRow>('trace_points', 'walk', walkId);
    await db.deleteAll(
      'trace_points',
      rows.map((r) => r.id),
    );
  }

  // ---- The walk to ask about ----

  /**
   * The walk the *Save this walk?* sheet is for (docs/11 5.27.6): the newest walk id in the trace that is not the live
   * walk's, is above the watermark `askedUpTo` and has at least {@link ASK_MIN_POINTS} points and {@link ASK_MIN_LENGTH_M}
   * metres; null when there is none. Only the newest candidate counts: a shorter walk is just kept for 30 days, and an
   * unanswered older walk is skipped. Computed from the rows, not stored, so a walk cut by a closed tab is asked about once.
   */
  async lastEndedWalk(liveWalkId: number, askedUpTo: number): Promise<WalkSummary | null> {
    const rows = await (await this.db()).getAll<TracePointRow>('trace_points');
    let newest = 0;
    for (const r of rows) if (r.walk !== 0 && r.walk !== liveWalkId && r.walk > askedUpTo && r.walk > newest) newest = r.walk;
    if (newest === 0) return null;
    const points = rows
      .filter((r) => r.walk === newest)
      .map(pointOfRow)
      .sort((a, b) => a.atMs - b.atMs);
    const lengthM = walkLengthM(points);
    if (points.length < ASK_MIN_POINTS || lengthM < ASK_MIN_LENGTH_M) return null;
    return summaryOf(newest, points, lengthM);
  }

  /** The summary of one walk id in the trace, or null when it has no points. */
  async walkSummary(walkId: number): Promise<WalkSummary | null> {
    const points = await this.walkPoints(walkId);
    return points.length === 0 ? null : summaryOf(walkId, points, walkLengthM(points));
  }

  // ---- Saved walks ----

  /**
   * Saves a trace walk to a house: ONE transaction writes the `saved_walks` row and deletes the walk's `trace_points`, so
   * nothing is stored twice and the 30-day prune cannot touch it. Refused (nothing changes) for a walk with no points, one
   * of more than 5 000 points, a house that already holds 20 saved walks or a device that holds 200.
   */
  async saveWalk(walkId: number, houseId: string, nowMs: number, newId: () => string = newWalkId): Promise<SaveWalkResult> {
    const db = await this.db();
    const points = await this.walkPoints(walkId);
    if (points.length < 2) return { ok: false, reason: 'noWalk' };
    if (points.length > TRACE.maxWalkPoints) return { ok: false, reason: 'tooLong' };
    if ((await db.count('saved_walks', 'houseId', houseId)) >= MAX_SAVED_WALKS_PER_HOUSE) return { ok: false, reason: 'houseFull' };
    if ((await db.count('saved_walks')) >= MAX_SAVED_WALKS_PER_DEVICE) return { ok: false, reason: 'deviceFull' };
    const id = newId();
    const row: SavedWalkRow = { id, houseId, savedAt: nowMs, ...encodeWalk(points) };
    await db.batch([
      { op: 'put', store: 'saved_walks', value: row },
      ...points.map((p) => ({ op: 'delete' as const, store: 'trace_points' as const, key: `${p.walkId ?? 0}-${p.atMs}` })),
    ]);
    return { ok: true, id };
  }

  /** The saved walks of a house, newest first, as rows (about 40 KB each at most). */
  async savedWalksOf(houseId: string): Promise<SavedWalkRow[]> {
    const rows = await (await this.db()).getAllByIndex<SavedWalkRow>('saved_walks', 'houseId', houseId);
    return rows.sort((a, b) => b.startedAt - a.startedAt);
  }

  /** One saved walk, decoded; null when it is gone or damaged. */
  async savedWalk(id: string): Promise<{ row: SavedWalkRow; walk: TraceWalk } | null> {
    const row = await (await this.db()).get<SavedWalkRow>('saved_walks', id);
    return row ? decoded(row) : null;
  }

  /** How many saved walks the device holds, and how many one house holds, without reading them. */
  async savedCount(houseId?: string): Promise<number> {
    const db = await this.db();
    return houseId === undefined ? db.count('saved_walks') : db.count('saved_walks', 'houseId', houseId);
  }

  async deleteSavedWalk(id: string): Promise<void> {
    await (await this.db()).delete('saved_walks', id);
  }

  /** *Delete all saved walks* (the confirmation shows {@link savedCount}). */
  async deleteAllSavedWalks(): Promise<void> {
    await (await this.db()).clear('saved_walks');
  }

  /**
   * Hands each saved walk to `visit` one at a time, decoded and then dropped (200 walks of 5 000 points are about 8 MB: never
   * all decoded at once). The rows are listed by key and read one by one.
   */
  async forEachSavedWalk(visit: (row: SavedWalkRow, walk: TraceWalk) => void): Promise<void> {
    const db = await this.db();
    for (const key of await db.keys('saved_walks')) {
      const row = await db.get<SavedWalkRow>('saved_walks', key);
      const d = row ? decoded(row) : null;
      if (d) visit(d.row, d.walk);
    }
  }

  /** What the Map draws and the detection reads: the 30-day trace and every saved walk, whatever its age. */
  async allWalks(nowMs: number): Promise<AllWalks> {
    const trace = await this.traceWalks(nowMs);
    const saved: { row: SavedWalkRow; walk: TraceWalk }[] = [];
    await this.forEachSavedWalk((row, walk) => saved.push({ row, walk }));
    return { trace, saved };
  }

  /**
   * The walks the place check compares with (docs/11 5.27.13): the 30-day trace and every saved walk, whether or not the trace
   * is switched on. `leaveOutWalkId` is the walk now recording, left out only for the *here* source. The check itself drops a
   * trace walk whose id equals a saved walk's (a save cut between its writes).
   */
  async placeWalks(nowMs: number, leaveOutWalkId = 0): Promise<PlaceWalk[]> {
    const { trace, saved } = await this.allWalks(nowMs);
    const walks: PlaceWalk[] = [];
    for (const w of trace) {
      const id = w.points[0].walkId ?? 0;
      if (leaveOutWalkId !== 0 && id === leaveOutWalkId) continue;
      walks.push({ points: w.points, source: 'TRACE', walkId: id });
    }
    for (const s of saved) walks.push({ points: s.walk.points, source: 'SAVED', walkId: s.row.startedAt });
    return walks;
  }

  // ---- Settings (per browser, never exported) ----

  async traceOn(): Promise<boolean> {
    return (await this.store.setting(SETTING_KEYS.traceOn)) === '1';
  }
  async setTraceOn(on: boolean): Promise<void> {
    await this.store.setSetting(SETTING_KEYS.traceOn, on ? '1' : '0');
  }

  async look(): Promise<RepeatLook> {
    const v = await this.store.setting(SETTING_KEYS.traceLook);
    return REPEAT_LOOKS.includes(v as RepeatLook) ? (v as RepeatLook) : 'CLEAR';
  }
  async setLook(look: RepeatLook): Promise<void> {
    await this.store.setSetting(SETTING_KEYS.traceLook, look);
  }

  async alertOn(): Promise<boolean> {
    return (await this.store.setting(SETTING_KEYS.traceAlert)) === '1';
  }
  async setAlertOn(on: boolean): Promise<void> {
    await this.store.setSetting(SETTING_KEYS.traceAlert, on ? '1' : '0');
  }

  async keepAwake(): Promise<boolean> {
    return (await this.store.setting(SETTING_KEYS.traceKeepAwake)) === '1';
  }
  async setKeepAwake(on: boolean): Promise<void> {
    await this.store.setSetting(SETTING_KEYS.traceKeepAwake, on ? '1' : '0');
  }

  /** The newest walk id the *Save this walk?* sheet has handled (0 when none). */
  async askedUpTo(): Promise<number> {
    const n = Number(await this.store.setting(SETTING_KEYS.traceAskedUpTo));
    return Number.isFinite(n) && n > 0 ? n : 0;
  }
  /** Any answer or dismissal sets it, so each walk is asked once; it never goes backwards. */
  async setAskedUpTo(walkId: number): Promise<void> {
    if (walkId > (await this.askedUpTo())) await this.store.setSetting(SETTING_KEYS.traceAskedUpTo, String(walkId));
  }
}

/** The walks of a flat list of trace points: split by walk id and the gap rule, keyed `t:<walkId>`. */
export function walksOf(points: readonly TracePoint[]): TraceWalk[] {
  return splitWalks(points).map((walkPoints) => ({ key: `t:${walkPoints[0].walkId ?? 0}`, points: walkPoints }));
}

function summaryOf(walkId: number, points: readonly TracePoint[], lengthM: number): WalkSummary {
  const last = points[points.length - 1];
  return { walkId, pointCount: points.length, lengthM, startedAt: points[0].atMs, endedAt: last.atMs, last: { lat: last.lat, lon: last.lon } };
}

function decoded(row: SavedWalkRow): { row: SavedWalkRow; walk: TraceWalk } | null {
  const points = decodeWalk(row);
  return points === null ? null : { row, walk: { key: `s:${row.id}`, points } };
}
