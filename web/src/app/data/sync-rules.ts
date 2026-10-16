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
 * Conflict rules for the web sync loop, a line-for-line port of
 * `android/shared/src/commonMain/kotlin/app/doorprints/shared/sync/SyncRules.kt` so Android, iOS (later) and the
 * browser converge on the same row.
 *
 * "Last edit wins": when the server sends a row this browser also changed and has not pushed yet (dirty), the local
 * copy is kept only if it was edited strictly later. On a tie the server copy wins.
 */

import { millis } from './records';

/** A locally stored row that takes part in two-way sync (Kotlin: `SyncRecord`). */
export interface SyncRecord {
  /** Epoch milliseconds of the last edit on whichever device made it. */
  readonly updatedAt: number;
  /** True while this row has local changes the server has not seen yet. */
  readonly dirty: boolean;
}

/**
 * The last-edit-wins test: keep the local row only if it has unpushed changes and was edited strictly later than the incoming one. A tie goes to the incoming row.
 */
export function keepLocal(localDirty: boolean, localUpdatedAt: number, incomingUpdatedAt: number): boolean {
  return localDirty && localUpdatedAt > incomingUpdatedAt;
}

/** The stored form keeps `updatedAt` as an ISO-8601 string; this is the same rule over those rows. */
export function keepLocalRecord(
  local: { updatedAt?: string | null; dirty: boolean } | undefined | null,
  incoming: { updatedAt?: string | null },
): boolean {
  if (!local) return false;
  return keepLocal(local.dirty, millis(local.updatedAt), millis(incoming.updatedAt));
}

/**
 * How a pulled row meets the local copy of it (S4b-BL-70; Kotlin: `MergeRule`): true keeps the local row and drops the
 * incoming one. Each `SyncBackend` supplies its own: the server's is {@link serverMerge}; Drive's per-device files will
 * use last-write-wins on `updatedAt` with ties broken by the writing device (docs/15 §5.1, S4b-BL-130), because an
 * older device snapshot must not overwrite a clean, newer local row.
 */
export type MergeRule = (
  local: { updatedAt?: string | null; dirty: boolean; deleted?: boolean; by?: string | null } | undefined | null,
  incoming: { updatedAt?: string | null; deleted?: boolean; by?: string | null },
) => boolean;

/**
 * The server sync's merge rule (Kotlin: `SyncRules.serverMerge`): {@link keepLocalRecord}. Right for a server that has
 * already merged every device's writes, so any row it sends is at least as new as a clean local one.
 */
export const serverMerge: MergeRule = keepLocalRecord;

/**
 * True when the server's highest sync version (`maxSyncVersion` from `GET /api/stats`) is below one of this browser's
 * stored `cursors` (S4b-BL-20; Android: `SyncRules.serverBehind`). A cursor only ever holds a version the server
 * handed out, and the server's sequence never goes back while it keeps its data (not even after "delete all my
 * data"), so on a healthy server no cursor is above it. A missing or unreadable value (an older server) is unknown:
 * false.
 */
export function serverBehind(maxSyncVersion: unknown, cursors: readonly number[]): boolean {
  const highest = wireVersion(maxSyncVersion);
  if (highest === null) return false;
  return cursors.some((cursor) => cursor > highest);
}

/**
 * A row's `syncVersion` as a finite number, or `null` when it cannot be used to move a cursor.
 *
 * Deliberately **not** `Number(value)` with a list of exceptions: `Number()` coerces objects and arrays through
 * `valueOf`/`toString`, so `Number([])` is `0` and `Number(['5'])` is `5`. A hand-rolled or proxied body carrying
 * `"syncVersion": []` would then move the cursor to 0 and store the row as version 0 — exactly the poisoning this
 * function exists to prevent. So only the two primitive shapes the wire can legitimately use are accepted:
 *
 *  * a finite `number` — what the backend writes (`HouseDto.syncVersion` is a JSON number);
 *  * a `string` that is a finite number once trimmed — tolerated because a proxy or a hand-written fixture may
 *    quote it, and `"12"` means 12 to every reader. `""` and `"  "` are not numbers and are refused.
 *
 * Everything else — `undefined`, `null`, booleans, objects, arrays, `NaN`, `Infinity` — is `null`.
 */
export function wireVersion(value: unknown): number | null {
  if (typeof value === 'number') return Number.isFinite(value) ? value : null;
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed === '') return null;
  const n = Number(trimmed);
  return Number.isFinite(n) ? n : null;
}
