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

import { uuid } from '../core/models';
import { spaceOf } from '../core/space.service';
import type { LocalDb } from './local-db';
import type { HouseRecord, PhotoRecord } from './records';
import { isoNow, millis, photoMetaOf, withPhotoMeta } from './records';
import type { GonePhoto } from './drive/photo-restore-rules';

// The bin behind *Recently deleted* (deletion safeguards PR 1, docs/04 §tombstone and restore, docs/11 5.27.6): a deleted
// candidate is already a tombstone (`deleted: true`), kept and synced for ever; this file lists the ones younger than 30
// days of one space and flips one back. It writes nothing a sync does not already carry (a row with `deleted: false`, a
// newer `updatedAt`, `dirty: true` wins last-write-wins on the server and in Drive) and reads no other space's row.

/** How long a deleted candidate stays in the list, in days: the 30 days photo bytes are kept in Drive (docs/15 §1.4). */
export const BIN_DAYS = 30;
const DAY_MS = 24 * 60 * 60 * 1000;

/** What the space is, to tell whose tombstone a row is: the spaces switch, the default kind and the kinds with a space. */
export interface BinSpace {
  readonly kind: string;
  readonly multi: boolean;
  readonly dflt: string;
  readonly available: readonly string[];
}

/** True when the row is a tombstone young enough for the list (`deleted && updatedAt > now - 30 days`). */
export function inBin(row: Pick<HouseRecord, 'deleted' | 'updatedAt'>, now: number): boolean {
  return row.deleted === true && millis(row.updatedAt) > now - BIN_DAYS * DAY_MS;
}

/**
 * This space's tombstones younger than 30 days, the newest first. With one space (spaces off) every tombstone is this
 * space's, as every live candidate is (`SpaceService.holds`). Pure.
 */
export function binRows(rows: readonly HouseRecord[], space: BinSpace, now: number): HouseRecord[] {
  return rows
    .filter((r) => inBin(r, now) && (!space.multi || spaceOf(r, true, space.dflt, space.available) === space.kind))
    .sort((a, b) => millis(b.updatedAt) - millis(a.updatedAt) || (a.id < b.id ? -1 : 1));
}

/** The stamp of a row written now: never at or before the one it replaces, so a device whose clock is behind still moves it on. */
export function nextStamp(previous: string | null | undefined, now: number): string {
  return isoNow(Math.max(now, millis(previous) + 1));
}

/** What a restore brought back. */
export interface RestoreResult {
  /** The candidate, live again, dirty. */
  readonly house: HouseRecord;
  /** Photos whose bytes were still here and are back (they upload again on the next sync). */
  readonly photosBack: number;
  /** Photo tombstones whose bytes are gone (a delete drops them at once; Drive keeps the encrypted copy for 30 days, docs/15 §1.4). */
  readonly photosGone: number;
  /** Those photos, for getting them back from Google Drive (S4b-BL-293). */
  readonly gone: readonly GonePhoto[];
  /** Epoch milliseconds of the candidate's tombstone, before this restore moved it on. */
  readonly deletedAt: number;
}

/** A tombstone of a photo on this device, without its bytes, as the Drive restore reads it. */
export function goneOfRecord(p: PhotoRecord): GonePhoto {
  return { id: p.id, deletedAt: millis(p.updatedAt), contentType: p.contentType || null, createdAt: p.createdAt, meta: photoMetaOf(p), ref: null };
}

/**
 * The row of a photo that came back from Google Drive: a new id (the old one is a tombstone every device and the Drive sync
 * file keep), the bytes, the room, tags and caption it had, not uploaded (the next sync sends it like any new photo).
 */
export function restoredPhoto(gone: GonePhoto, houseId: string, bytes: Uint8Array, stamp: string): PhotoRecord {
  const type = gone.contentType || 'image/jpeg';
  const row: PhotoRecord = {
    id: uuid(),
    houseId,
    blob: new Blob([bytes as BlobPart], { type }),
    contentType: type,
    sizeBytes: bytes.length,
    createdAt: gone.createdAt ?? stamp,
    updatedAt: stamp,
    deleted: false,
    syncVersion: 0,
    uploaded: false,
  };
  return (gone.meta.metaUpdatedAt ?? 0) > 0 ? withPhotoMeta(row, gone.meta, true) : row;
}

/**
 * Flips one tombstone back: `deleted: false`, `updatedAt` bumped, `dirty: true`, in `db` and nowhere else. Its photos:
 * `held` are copies taken before the delete (the Undo window), which come back under new ids with their bytes (the old
 * ids are tombstones the server may already have); a tombstone still holding bytes comes back the same way. A photo
 * tombstone without bytes cannot, and is counted. Undefined when the row is missing or not deleted.
 */
export async function restoreRows(db: LocalDb, id: string, now: number, held: readonly PhotoRecord[] = []): Promise<RestoreResult | undefined> {
  const existing = await db.get<HouseRecord>('houses', id);
  if (!existing?.deleted) return undefined;
  const stamp = nextStamp(existing.updatedAt, now);
  const house: HouseRecord = { ...existing, deleted: false, dirty: true, updatedAt: stamp };
  await db.put('houses', house);
  const heldIds = new Set(held.map((p) => p.id));
  const stored = (await db.getAllByIndex<PhotoRecord>('photos', 'houseId', id)).filter((p) => p.deleted && !heldIds.has(p.id));
  let photosBack = 0;
  const gone: GonePhoto[] = [];
  for (const p of [...held, ...stored]) {
    if (!p.blob) {
      gone.push(goneOfRecord(p));
      continue;
    }
    const hasMeta = (p.metaUpdatedAt ?? 0) > 0;
    await db.put('photos', { ...p, id: uuid(), deleted: false, uploaded: false, updatedAt: stamp, syncVersion: 0, ...(hasMeta ? { metaDirty: true } : {}) });
    if (stored.includes(p)) await db.delete('photos', p.id);
    photosBack++;
  }
  return { house, photosBack, photosGone: gone.length, gone, deletedAt: millis(existing.updatedAt) };
}

/**
 * How many photos each deleted candidate had, kept in the settings store (this browser only, never exported or synced) so
 * the list can say "photos could not be restored" for a candidate whose photo rows are already gone. A JSON object of
 * `id: count`; unreadable reads as empty.
 */
export function parseNotes(raw: string | null): Record<string, number> {
  try {
    const value: unknown = raw ? JSON.parse(raw) : {};
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return {};
    return Object.fromEntries(Object.entries(value).filter(([, n]) => typeof n === 'number' && n > 0));
  } catch {
    return {};
  }
}

/** The notes with `id` set to `count` (removed for 0) and every id not in `keep` dropped, so the note outlives its tombstone by nothing. */
export function withNote(notes: Record<string, number>, id: string, count: number, keep: ReadonlySet<string>): Record<string, number> {
  const next = Object.fromEntries(Object.entries(notes).filter(([k]) => k !== id && keep.has(k)));
  if (count > 0) next[id] = count;
  return next;
}
