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

import { describe, expect, it } from 'vitest';
import schemaJson from '../../../../../docs/schemas/sync-1.schema.json';
import vectorsJson from '../../../../../docs/schemas/sync-vectors.json';
import { MAX_AHEAD_MS, nextStamp, planMerge, SHRINK_MIN_HOUSES, takesIncoming } from './drive-merge';
import type { MergePlan } from './drive-merge';
import {
  parseSyncFile,
  SYNC_EARLIEST_MS,
  SYNC_FORMAT,
  SYNC_KINDS,
  SYNC_MAX_BYTES,
  SYNC_MAX_DEPTH,
  SYNC_MAX_ROWS,
  SYNC_MAX_ROWS_OF,
  SYNC_MAX_SEQ,
  SYNC_MAX_VERSION,
  SyncFileError,
  syncTime,
} from './sync-file';
import type { SyncKind, SyncRow, SyncStamp } from './sync-file';
import { digestOf, simulate } from './sync-sim';

/**
 * The shared sync vectors (`docs/schemas/sync-vectors.json`, docs/schemas/README.md): the same times, stamps, order,
 * file problems, convergence, merge plans and random runs as Kotlin's `SyncVectorsTest`.
 */

type Obj = Record<string, unknown>;
interface Case extends Obj { name: string }
const vectors = vectorsJson as unknown as {
  format: string;
  limits: Obj;
  times: { text: string; ms: number | null }[];
  stamps: { name: string; now: number; previous: number | null; stamp: number }[];
  order: { name: string; local: SyncStamp | null; incoming: SyncStamp; takes: boolean }[];
  files: (Case & { expectedDeviceId: string; file?: Obj; text?: string; problem: string | null; counts?: Record<SyncKind, number>; keys?: Record<string, string[]> })[];
  converge: (Case & { now: number; local: LocalRow[]; files: Obj[]; final: Record<string, string> })[];
  plans: (Case & { now: number; local: LocalRow[]; steps: Step[]; final: Record<string, string> })[];
  random: { base: number; devices: string[]; skewsMs: number[]; steps: number; keys: string[]; cases: { seed: number; digest: string }[] };
};
interface LocalRow { kind: string; key: string; updatedAt: number; by: string; deleted: boolean }
interface Step {
  file: Obj;
  now?: number;
  confirmShrink?: boolean;
  expect: { stale: boolean; take: string[]; held: string[]; deferred: string[]; housesDeleted?: number; liveHouses?: number };
}

const same = <T>(a: T, b: T) => expect(a).toEqual(b);

function state(c: { local: LocalRow[] }): Map<string, SyncStamp> {
  return new Map(c.local.map((r) => [`${r.kind}/${r.key}`, { updatedAt: r.updatedAt, by: r.by, deleted: r.deleted }]));
}

function apply(rows: Map<string, SyncStamp>, seqs: Map<string, number>, file: Obj, now: number, confirm: boolean): MergePlan {
  const device = file['deviceId'] as string;
  const parsed = parseSyncFile(JSON.stringify(file), device);
  const live = [...rows].filter(([k, s]) => k.startsWith('houses/') && !s.deleted).length;
  const plan = planMerge(parsed, { now, highestSeq: seqs.get(device) ?? null, liveHouses: live, confirmShrink: confirm }, (kind, key) => rows.get(`${kind}/${key}`));
  if (!plan.stale) {
    for (const row of plan.take) rows.set(`${row.kind}/${row.key}`, row.stamp);
    seqs.set(device, Math.max(seqs.get(device) ?? 0, parsed.seq));
  }
  return plan;
}

const digestOfCase = (final: Record<string, string>) =>
  Object.entries(final)
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, v]) => `${k}=${v}`)
    .join(';');

function permutations(n: number): number[][] {
  if (n === 0) return [[]];
  return permutations(n - 1).flatMap((p) => Array.from({ length: p.length + 1 }, (_, i) => [...p.slice(0, i), n - 1, ...p.slice(i)]));
}

const paths = (rows: readonly SyncRow[]) => rows.map((r) => `${r.kind}/${r.key}`).sort();

describe('the shared sync vectors', () => {
  it('is the format the specs know', () => {
    expect(vectors.format).toBe('doorprints-sync-vectors/1');
  });

  it('has limits that are the codes', () => {
    const l = vectors.limits;
    expect(l['format']).toBe(SYNC_FORMAT);
    expect(l['maxVersion']).toBe(SYNC_MAX_VERSION);
    expect(l['maxBytes']).toBe(SYNC_MAX_BYTES);
    expect(l['maxRows']).toBe(SYNC_MAX_ROWS);
    expect(l['maxHouses']).toBe(SYNC_MAX_ROWS_OF.houses);
    expect(l['maxVisits']).toBe(SYNC_MAX_ROWS_OF.visits);
    expect(l['maxRecords']).toBe(SYNC_MAX_ROWS_OF.records);
    expect(l['maxPhotos']).toBe(SYNC_MAX_ROWS_OF.photos);
    expect(l['maxSeq']).toBe(SYNC_MAX_SEQ);
    expect(l['maxDepth']).toBe(SYNC_MAX_DEPTH);
    expect(l['earliestMs']).toBe(SYNC_EARLIEST_MS);
    expect(l['maxAheadMs']).toBe(MAX_AHEAD_MS);
    expect(l['shrinkMinHouses']).toBe(SHRINK_MIN_HOUSES);
  });

  it('is described by a schema with the same limits', () => {
    const props = (schemaJson as unknown as { properties: Record<string, Obj> }).properties;
    expect(props['format']['const']).toBe(SYNC_FORMAT);
    for (const kind of SYNC_KINDS) expect(props[kind]['maxItems'], kind).toBe(SYNC_MAX_ROWS_OF[kind]);
    expect(props['seq']['maximum']).toBe(SYNC_MAX_SEQ);
  });

  it('reads times', () => {
    for (const c of vectors.times) expect(syncTime(c.text), JSON.stringify(c.text)).toBe(c.ms);
  });

  it('stamps edits', () => {
    for (const c of vectors.stamps) expect(nextStamp(c.now, c.previous), c.name).toBe(c.stamp);
  });

  it('orders row versions', () => {
    for (const c of vectors.order) expect(takesIncoming(c.local, c.incoming), c.name).toBe(c.takes);
  });

  it('reads and refuses files', () => {
    for (const c of vectors.files) {
      const text = c.text ?? JSON.stringify(c.file);
      let problem: string | null = null;
      let parsed;
      try {
        parsed = parseSyncFile(text, c.expectedDeviceId);
      } catch (e) {
        if (!(e instanceof SyncFileError)) throw e;
        problem = e.problem;
      }
      expect(problem, c.name).toBe(c.problem);
      if (parsed) {
        for (const kind of SYNC_KINDS) expect(parsed.rows[kind].length, c.name).toBe(c.counts![kind]);
        for (const [kind, keys] of Object.entries(c.keys ?? {})) {
          same(parsed.rows[kind as SyncKind].map((r) => r.key), keys);
        }
      }
    }
  });

  it('converges in every order of the files', () => {
    for (const c of vectors.converge) {
      const expected = digestOfCase(c.final);
      for (const order of permutations(c.files.length)) {
        const rows = state(c);
        const seqs = new Map<string, number>();
        for (let round = 0; round < 2; round++) for (const i of order) apply(rows, seqs, c.files[i], c.now, false);
        expect(digestOf(rows), `${c.name} ${order}`).toBe(expected);
      }
    }
  });

  it('plans merges', () => {
    for (const c of vectors.plans) {
      const rows = state(c);
      const seqs = new Map<string, number>();
      c.steps.forEach((step, i) => {
        const plan = apply(rows, seqs, step.file, step.now ?? c.now, step.confirmShrink ?? false);
        const e = step.expect;
        const name = `${c.name}, step ${i + 1}`;
        expect(plan.stale, name).toBe(e.stale);
        same(paths(plan.take), [...e.take].sort());
        same(paths(plan.held), [...e.held].sort());
        same(paths(plan.deferred), [...e.deferred].sort());
        if (e.housesDeleted !== undefined) expect(plan.housesDeleted, name).toBe(e.housesDeleted);
        if (e.liveHouses !== undefined) expect(plan.liveHouses, name).toBe(e.liveHouses);
      });
      expect(digestOf(rows), c.name).toBe(digestOfCase(c.final));
    }
  });

  it('reaches the digests of the random runs that Kotlin wrote', () => {
    const r = vectors.random;
    expect(r.cases.length).toBeGreaterThan(0);
    for (const c of r.cases) {
      for (const d of simulate(c.seed, r.base, r.devices, r.skewsMs, r.steps, r.keys)) {
        expect(d.digest(), `seed ${c.seed}, ${d.id}`).toBe(c.digest);
      }
    }
  });
});
