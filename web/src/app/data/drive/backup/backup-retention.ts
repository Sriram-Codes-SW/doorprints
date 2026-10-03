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
 * Retention, 7 daily, 4 weekly and 6 monthly, and the shrink guard, as one pure function (S4b-BL-116; docs/15 §1.4
 * item 4). The twin of Kotlin's `BackupRetention`; vectors: docs/schemas/backup-vectors.json `retention`.
 */

/**
 * One finished, checked backup as retention sees it. `createdAt` and `houses` are the **authenticated** values of its
 * metadata (`BackupMeta`), never Drive's times or the file name.
 */
export interface RetentionEntry {
  readonly id: string;
  readonly createdAt: number;
  readonly houses: number;
}

/**
 * The shrink guard's finding: the newest backup holds `houses`, fewer than half the `previousHouses` of the largest
 * backup before it. Until the person confirms the drop on this device nothing is pruned.
 */
export interface ShrinkHold {
  readonly backupId: string;
  readonly houses: number;
  readonly previousId: string;
  readonly previousHouses: number;
}

/** `keep` and `prune` are ids, newest first; `prune` is empty whenever `hold` is set. */
export interface RetentionResult {
  readonly keep: string[];
  readonly prune: string[];
  readonly hold: ShrinkHold | null;
}

export const RETENTION_DAILY = 7;
export const RETENTION_WEEKLY = 4;
export const RETENTION_MONTHLY = 6;

const DAY_MS = 86_400_000;
const MINUTE_MS = 60_000;

/** Days since 1970-01-01 in local time (`utcOffsetMinutes` east of UTC). */
export function localDay(at: number, utcOffsetMinutes: number): number {
  return Math.floor((at + utcOffsetMinutes * MINUTE_MS) / DAY_MS);
}

/** The Monday-to-Sunday week of `day` (1970-01-01 was a Thursday, so day -3 is week 0's Monday). */
export function weekOf(day: number): number {
  return Math.floor((day + 3) / 7);
}

/** Year, month (1..12) and day (1..31) of `day` in the proleptic Gregorian calendar (H. Hinnant's days-to-civil). */
export function civil(day: number): [number, number, number] {
  const z = day + 719_468;
  const era = Math.floor(z / 146_097);
  const doe = z - era * 146_097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36_524) - Math.floor(doe / 146_096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const month = mp < 10 ? mp + 3 : mp - 9;
  const year = yoe + era * 400 + (month <= 2 ? 1 : 0);
  return [year, month, d];
}

/** `year * 12 + month - 1` of `day`. */
export function monthOf(day: number): number {
  const [year, month] = civil(day);
  return year * 12 + month - 1;
}

/**
 * The newest backup against the largest of the backups before it, counted from the newest one the person confirmed
 * (that one included: its level is accepted, so later backups of the same size do not ask again).
 */
function shrinkHold(newestFirst: readonly RetentionEntry[], confirmedDrops: ReadonlySet<string>): ShrinkHold | null {
  const newest = newestFirst[0];
  if (confirmedDrops.has(newest.id)) return null;
  let reference: RetentionEntry | null = null;
  for (const e of newestFirst.slice(1)) {
    if (reference == null || e.houses > reference.houses) reference = e;
    if (confirmedDrops.has(e.id)) break;
  }
  if (reference == null) return null;
  return newest.houses * 2 < reference.houses
    ? { backupId: newest.id, houses: newest.houses, previousId: reference.id, previousHouses: reference.houses }
    : null;
}

/**
 * Newest first by `createdAt`, ties by id (ascending, by UTF-16 code unit as Kotlin's String order). The newest is
 * always kept; the union of the daily, weekly and monthly buckets is at most 17 files. The entries must be distinct
 * backups (the caller collapses copies of one file first).
 */
export function selectRetention(
  entries: readonly RetentionEntry[],
  utcOffsetMinutes: number,
  confirmedDrops: ReadonlySet<string> = new Set(),
  daily = RETENTION_DAILY,
  weekly = RETENTION_WEEKLY,
  monthly = RETENTION_MONTHLY,
): RetentionResult {
  if (entries.length === 0) return { keep: [], prune: [], hold: null };
  const newestFirst = [...entries].sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  const keep = new Set<string>([newestFirst[0].id]);
  const bucket = (count: number, of: (day: number) => number) => {
    const seen = new Set<number>();
    for (const e of newestFirst) {
      if (seen.size >= count) break;
      const key = of(localDay(e.createdAt, utcOffsetMinutes));
      if (!seen.has(key)) {
        seen.add(key);
        keep.add(e.id);
      }
    }
  };
  bucket(daily, (d) => d);
  bucket(weekly, weekOf);
  bucket(monthly, monthOf);
  const hold = shrinkHold(newestFirst, confirmedDrops);
  return {
    keep: newestFirst.filter((e) => keep.has(e.id)).map((e) => e.id),
    prune: hold ? [] : newestFirst.filter((e) => !keep.has(e.id)).map((e) => e.id),
    hold,
  };
}
