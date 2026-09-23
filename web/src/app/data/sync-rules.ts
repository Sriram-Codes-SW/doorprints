/**
 * Conflict rules for the web sync loop, a line-for-line port of
 * `android/shared/src/commonMain/kotlin/com/househunt/shared/sync/SyncRules.kt` so Android, iOS (later) and the
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
