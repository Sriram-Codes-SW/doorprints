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
