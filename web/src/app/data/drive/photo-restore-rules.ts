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

import { cleanMeta } from '../../shared/photo-tags';
import type { PhotoMeta } from '../../shared/photo-tags';
import { refOfRow } from './drive-photo-seams';
import type { PhotoRef } from './drive-photo-seams';
import type { SyncRow } from './sync-file';
import { HOUSE_REF, FORMER_HOUSE_REF } from './sync-file';

/*
 * Getting a restored candidate's photos back from the person's own Google Drive (S4b-BL-293; docs/15 §1.4, docs/03 §20).
 * The rules only, pure: which photos belong to the candidate, which are still inside the 30 days, what the person is told.
 * Kotlin twin: `PhotoRestore.kt`, the same names; both read `docs/schemas/photo-restore-vectors.json`.
 */

/** How long Drive keeps the encrypted copy of a deleted photo, in days (docs/15 §1.4): the same 30 days as *Recently deleted*. */
export const RESTORE_WINDOW_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

/** A photo tombstoned at most this long before its candidate counts as deleted with it (a sync can stamp it a little later or earlier). */
export const WITH_CANDIDATE_MS = 60_000;

/** Why a photo did not come back; the order is the order the words list them. */
export type PhotoRestoreReason = 'NOT_CONNECTED' | 'OFFLINE' | 'PAST_WINDOW' | 'MISSING' | 'UNREADABLE';

/** The order the reasons are listed in. */
export const RESTORE_REASONS: readonly PhotoRestoreReason[] = ['NOT_CONNECTED', 'OFFLINE', 'PAST_WINDOW', 'MISSING', 'UNREADABLE'];

/** A deleted photo whose bytes are gone from this device: its id, when it was deleted and what is known of it. */
export interface GonePhoto {
  readonly id: string;
  /** Epoch milliseconds of the photo's tombstone. */
  readonly deletedAt: number;
  readonly contentType: string | null;
  readonly createdAt: string | null;
  readonly meta: PhotoMeta;
  /** The Drive file of its bytes, when a record names it. */
  readonly ref: PhotoRef | null;
}

/** The photo of a tombstone row of the sync file, or null when the row is not a photo tombstone. */
export function goneOfRow(row: SyncRow): GonePhoto | null {
  if (row.kind !== 'photos' || !row.stamp.deleted) return null;
  const json = row.json as Record<string, unknown>;
  const text = (k: string): string | null => (typeof json[k] === 'string' ? (json[k] as string) : null);
  return {
    id: row.key,
    deletedAt: row.stamp.updatedAt,
    contentType: text('contentType'),
    createdAt: text('createdAt'),
    meta: cleanMeta(json),
    ref: refOfRow(row),
  };
}

/** The candidate a photo row of the sync file belongs to. */
export function houseOfRow(row: SyncRow): string | null {
  const json = row.json as Record<string, unknown>;
  const id = json[HOUSE_REF] ?? json[FORMER_HOUSE_REF];
  return typeof id === 'string' ? id : null;
}

/** What to do with one photo. */
export type RestoreAction = 'FETCH' | 'PAST_WINDOW' | 'MISSING';

/** One photo and what to do with it. */
export interface PlanItem {
  readonly photo: GonePhoto;
  readonly action: RestoreAction;
}

/** True when a photo was deleted along with its candidate (not long before it). */
export function deletedWithCandidate(photoDeletedAt: number, candidateDeletedAt: number): boolean {
  return photoDeletedAt >= candidateDeletedAt - WITH_CANDIDATE_MS;
}

/**
 * The plan for one restored candidate: the photos known on this device (`local`: tombstones that still have a row here)
 * and those the sync file names (`file`: this device's own file, tombstones of this candidate), by photo id, only those
 * deleted with the candidate. A photo is fetched when a Drive file is named for it (the file's row, else the device's own
 * `refs`) and its tombstone is inside the 30 days; it is past the window when it is not; it is missing when no Drive file
 * is known for it. The local photos come first, then the file's. Pure.
 */
export function planRestore(input: {
  readonly houseId: string;
  readonly houseDeletedAt: number;
  readonly now: number;
  readonly local: readonly GonePhoto[];
  readonly file: readonly SyncRow[];
  readonly refs: Readonly<Record<string, PhotoRef>>;
}): PlanItem[] {
  const byId = new Map<string, GonePhoto>();
  const consider = (p: GonePhoto): void => {
    if (!deletedWithCandidate(p.deletedAt, input.houseDeletedAt)) return;
    const had = byId.get(p.id);
    byId.set(p.id, had ? { ...had, ref: had.ref ?? p.ref } : p);
  };
  for (const p of input.local) consider(p);
  for (const row of input.file) {
    const p = goneOfRow(row);
    if (p && houseOfRow(row) === input.houseId) consider(p);
  }
  return [...byId.values()].map((p): PlanItem => {
    const photo = { ...p, ref: p.ref ?? input.refs[p.id] ?? null };
    if (!photo.ref) return { photo, action: 'MISSING' };
    return { photo, action: input.now - photo.deletedAt > RESTORE_WINDOW_DAYS * DAY_MS ? 'PAST_WINDOW' : 'FETCH' };
  });
}

/** How far a run got with Drive itself, before any photo. */
export type RestoreConnection = 'OK' | 'NOT_CONNECTED' | 'OFFLINE';

/** What the person is told about the photos of one restored candidate. */
export interface RestoreSummary {
  /** How many photos the candidate had (at least the ones accounted for). */
  readonly total: number;
  /** How many came back (from this device's own copies or from Google Drive). */
  readonly restored: number;
  /** Why the others did not, each reason once, in {@link RESTORE_REASONS} order; empty when none is missing. */
  readonly reasons: readonly PhotoRestoreReason[];
}

/**
 * The summary: `noted` is how many photos the candidate had when it was deleted here (0 when unknown), `back` how many are
 * back, `failed` the reason of each photo that is not. A photo nobody recorded (noted above what is accounted for) is
 * missing, or not fetched for the reason Drive itself could not be reached. Pure.
 */
export function summarize(noted: number, back: number, failed: readonly PhotoRestoreReason[], connection: RestoreConnection): RestoreSummary {
  const accounted = back + failed.length;
  const total = Math.max(noted, accounted);
  const reasons = new Set<PhotoRestoreReason>(failed);
  if (total > accounted) reasons.add(connection === 'OK' ? 'MISSING' : connection);
  return { total, restored: back, reasons: RESTORE_REASONS.filter((r) => reasons.has(r)) };
}
