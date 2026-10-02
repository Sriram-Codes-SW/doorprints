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

import { nextStamp, planMerge } from './drive-merge';
import type { MergePlan } from './drive-merge';
import { encodeSyncFile, parseSyncFile, syncFile, syncRow, SYNC_KINDS } from './sync-file';
import type { SyncKind, SyncRow, SyncStamp } from './sync-file';

/**
 * Test support for the Drive merge (S4b-BL-130): a simulated device and the xorshift32 random run, a line-for-line twin
 * of Kotlin's `SyncSim.kt`, so both stacks reach the digests in `docs/schemas/sync-vectors.json`.
 */

export const kindOf = (path: string): SyncKind => SYNC_KINDS.find((k) => path.startsWith(`${k}/`))!;

export function digestOf(stamps: ReadonlyMap<string, SyncStamp>): string {
  return [...stamps.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, s]) => `${k}=${s.by}@${s.updatedAt}${s.deleted ? '~' : ''}`)
    .join(';');
}

export function simRow(path: string, updatedAt: number, by: string, deleted: boolean, label = 'edit'): SyncRow {
  const kind = kindOf(path);
  const key = path.slice(kind.length + 1);
  const fields: Record<string, unknown> = {};
  if (kind === 'records') {
    fields['type'] = key.slice(0, key.indexOf('/'));
    fields['id'] = key.slice(key.indexOf('/') + 1);
    fields['payload'] = deleted ? {} : { name: label };
  } else if (kind === 'houses') {
    fields['id'] = key;
    fields['label'] = deleted ? '' : label;
    fields['lat'] = 12.97;
    fields['lon'] = 77.59;
  } else if (kind === 'visits') {
    fields['id'] = key;
    fields['houseId'] = null;
    fields['lat'] = 12.97;
    fields['lon'] = 77.59;
    fields['arrivedAt'] = new Date(updatedAt - 600_000).toISOString();
  } else {
    fields['id'] = key;
    fields['houseId'] = 'h0';
  }
  fields['updatedAt'] = new Date(updatedAt).toISOString();
  fields['deleted'] = deleted;
  fields['by'] = by;
  return syncRow(kind, fields);
}

export class SimDevice {
  readonly rows = new Map<string, SyncRow>();
  seq = 0;
  readonly highestSeq = new Map<string, number>();

  constructor(
    readonly id: string,
    readonly skewMs = 0,
  ) {}

  stamp(path: string): SyncStamp | undefined {
    return this.rows.get(path)?.stamp;
  }

  liveHouses(): number {
    return [...this.rows.values()].filter((r) => r.kind === 'houses' && !r.stamp.deleted).length;
  }

  /** An edit or delete made here at `clockMs` (this device's clock adds `skewMs`). */
  edit(path: string, clockMs: number, deleted: boolean, label = 'edit'): void {
    const t = nextStamp(clockMs + this.skewMs, this.rows.get(path)?.stamp.updatedAt);
    this.rows.set(path, simRow(path, t, this.id, deleted, label));
  }

  /** This device's sync file, written now (a new `seq`). */
  snapshot(clockMs: number): string {
    this.seq++;
    const by: Partial<Record<SyncKind, SyncRow[]>> = {};
    for (const row of this.rows.values()) (by[row.kind] ??= []).push(row);
    return encodeSyncFile(syncFile(this.id, this.seq, clockMs + this.skewMs, by));
  }

  /** Merges `text`, the file of device `from`, at `clockMs`; returns the plan it applied. */
  merge(text: string, from: string, clockMs: number, confirmShrink = false): MergePlan {
    const file = parseSyncFile(text, from);
    const plan = planMerge(
      file,
      { now: clockMs + this.skewMs, highestSeq: this.highestSeq.get(from) ?? null, liveHouses: this.liveHouses(), confirmShrink },
      (kind, key) => this.rows.get(`${kind}/${key}`)?.stamp,
    );
    if (!plan.stale) {
      for (const row of plan.take) this.rows.set(`${row.kind}/${row.key}`, row);
      this.highestSeq.set(from, Math.max(this.highestSeq.get(from) ?? 0, file.seq));
    }
    return plan;
  }

  digest(): string {
    return digestOf(new Map([...this.rows].map(([k, r]) => [k, r.stamp])));
  }
}

export class XorShift32 {
  private x: number;
  constructor(seed: number) {
    this.x = seed | 0;
  }
  next(n: number): number {
    let x = this.x;
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    this.x = x | 0;
    return (x >>> 0) % n;
  }
}

export function simulate(seed: number, base: number, ids: readonly string[], skews: readonly number[], steps: number, keys: readonly string[]): SimDevice[] {
  const rng = new XorShift32(seed);
  const devices = ids.map((id, i) => new SimDevice(id, skews[i]));
  let clock = base;
  for (let step = 0; step < steps; step++) {
    clock += rng.next(3000);
    const d = rng.next(3);
    const action = rng.next(10);
    if (action < 5) devices[d].edit(keys[rng.next(keys.length)], clock, false, `s${step}`);
    else if (action < 7) devices[d].edit(keys[rng.next(keys.length)], clock, true);
    else {
      const e = (d + 1 + rng.next(2)) % 3;
      devices[d].merge(devices[e].snapshot(clock), devices[e].id, clock);
    }
  }
  for (let round = 0; round < 2; round++) {
    for (let d = 0; d < 3; d++) {
      for (let e = 0; e < 3; e++) {
        if (e === d) continue;
        clock += 1;
        devices[d].merge(devices[e].snapshot(clock), devices[e].id, clock);
      }
    }
  }
  return devices;
}
