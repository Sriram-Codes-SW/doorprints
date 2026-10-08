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

import { millis } from '../records';
import type { MergeRule } from '../sync-rules';
import { compareStamps, SYNC_KINDS } from './sync-file';
import type { SyncFile, SyncKind, SyncRow, SyncStamp } from './sync-file';

/**
 * The Drive merge rule (S4b-BL-130, docs/15 §5.1, docs/03 ADR-33; Kotlin: `DriveMerge` in `android/shared`): **last write
 * wins on `updatedAt`, ties broken by the writing device's id, a tombstone above a live row on a full tie of both**
 * ({@link compareStamps}), whatever the row's `dirty` flag. Not `keepLocal`, the server's rule: with one snapshot file per
 * device, an older snapshot from a device that has not caught up must not overwrite a clean, newer row here. The order
 * is total on what it compares, so merging is a per-key maximum: the order of the files, merging one twice, and merging
 * through a third device give the same rows (`drive-merge.spec.ts`, `docs/schemas/sync-vectors.json`).
 */

/**
 * A row stamped more than this after the reader's clock is **held**, not applied and not dropped, until the clock is
 * within it: a clamp would rewrite the stamp differently on each device, and a far-future stamp must not win for ever.
 */
export const MAX_AHEAD_MS = 86_400_000;

/** The shrink guard trips when one file would delete at least this many live houses and more than half of them. */
export const SHRINK_MIN_HOUSES = 10;

/** The `updatedAt` of an edit or delete made here: `max(now, previous + 1)`, so a slow clock still moves a row forward. */
export function nextStamp(now: number, previousUpdatedAt: number | null | undefined): number {
  return previousUpdatedAt === null || previousUpdatedAt === undefined ? now : Math.max(now, previousUpdatedAt + 1);
}

/** True when `incoming` replaces `local`: there is none, or it is strictly greater. */
export function takesIncoming(local: SyncStamp | null | undefined, incoming: SyncStamp): boolean {
  return !local || compareStamps(incoming, local) > 0;
}

/** True when `stamp` is more than {@link MAX_AHEAD_MS} after `now` (this device's clock). */
export function isHeld(stamp: SyncStamp, now: number): boolean {
  return stamp.updatedAt > now + MAX_AHEAD_MS;
}

/**
 * The rule as the sync loop's {@link MergeRule} (`SyncService.pull`), over stored rows: a row's writer is its `by`, `''`
 * while the store does not keep it (S4b-BL-118 stores it).
 */
export const driveMerge: MergeRule = (local, incoming) =>
  !!local &&
  !takesIncoming(
    { updatedAt: millis(local.updatedAt), by: local.by ?? '', deleted: local.deleted === true },
    { updatedAt: millis(incoming.updatedAt), by: incoming.by ?? '', deleted: incoming.deleted === true },
  );

/**
 * What one sync file changes here (Kotlin: `MergePlan`): the rows to write, the rows held back (stamped too far ahead),
 * and the house deletions held for the person's confirmation by the shrink guard. A `stale` file (a lower `seq` than
 * one already merged from that device: rolled back by a Drive revision) changes nothing.
 */
export interface MergePlan {
  readonly stale: boolean;
  readonly take: readonly SyncRow[];
  readonly held: readonly SyncRow[];
  readonly deferred: readonly SyncRow[];
  /** Live houses here that the file's tombstones would delete (whether or not deferred). */
  readonly housesDeleted: number;
  /** Live houses here before the file. */
  readonly liveHouses: number;
}

/** The shrink guard stopped the house deletions: ask "This would delete N of your M houses" (L1). */
export function needsConfirmation(plan: MergePlan): boolean {
  return plan.deferred.length > 0;
}

/**
 * What a merge needs besides the file: this device's clock, the highest sequence already merged from the writer, the live house count and the person's answer to the shrink guard.
 */
export interface MergeContext {
  /** This device's clock. */
  readonly now: number;
  /** The highest `seq` already merged from the file's device; null when none. */
  readonly highestSeq: number | null;
  /** Live houses here. */
  readonly liveHouses: number;
  /** The person's yes to the shrink guard's question. */
  readonly confirmShrink?: boolean;
}

/** What `file` changes here; `local` answers this device's version of a row (undefined: none). */
export function planMerge(
  file: SyncFile,
  context: MergeContext,
  local: (kind: SyncKind, key: string) => SyncStamp | null | undefined,
): MergePlan {
  const { now, highestSeq, liveHouses } = context;
  if (highestSeq !== null && file.seq < highestSeq) {
    return { stale: true, take: [], held: [], deferred: [], housesDeleted: 0, liveHouses };
  }
  const take: SyncRow[] = [];
  const held: SyncRow[] = [];
  const houseDeletes: SyncRow[] = [];
  for (const kind of SYNC_KINDS) {
    for (const row of file.rows[kind]) {
      if (isHeld(row.stamp, now)) {
        held.push(row);
        continue;
      }
      const here = local(kind, row.key);
      if (!takesIncoming(here, row.stamp)) continue;
      if (kind === 'houses' && row.stamp.deleted && here && !here.deleted) houseDeletes.push(row);
      else take.push(row);
    }
  }
  const trips = !context.confirmShrink && houseDeletes.length >= SHRINK_MIN_HOUSES && houseDeletes.length * 2 > liveHouses;
  if (!trips) take.push(...houseDeletes);
  return { stale: false, take, held, deferred: trips ? houseDeletes : [], housesDeleted: houseDeletes.length, liveHouses };
}
