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
import vectorsJson from '../../../../docs/schemas/trace-repeat-vectors.json';
import { TRACE, VECTOR_CONSTANT_KEYS } from './trace-geo';
import type { TracePoint, TraceWalk } from './trace-geo';
import { RepeatAlert, detectRepeats, splitWalkIndexes, splitWalks } from './trace-repeats';

/**
 * The shared repeat vectors (`docs/schemas/trace-repeat-vectors.json`, docs/11 5.27.3, docs/06 TC-U-147): the
 * same cases the Kotlin `RepeatDetectorVectorsTest` runs. EVERY case runs; an expected value is never edited to
 * make this pass (if a case fails, the specification or the case is corrected in a documented change).
 */

type Pt = number[];
interface Stretch {
  fromM: number;
  toM: number;
}
interface RepeatsCase {
  id: string;
  kind: 'repeats';
  walks?: Pt[][];
  points?: Pt[];
  expect: { repeated: Stretch[][]; shown: Stretch[][]; walkCount?: number };
}
interface SplitCase {
  id: string;
  kind: 'split';
  points: Pt[];
  expect: { walks: number[][] };
}
interface AlertCase {
  id: string;
  kind: 'alert';
  live: Pt[];
  others: Pt[][];
  expect: { alertAtIndexes: number[] };
}
type Case = RepeatsCase | SplitCase | AlertCase;
const vectors = vectorsJson as unknown as {
  format: string;
  constants: Record<string, number>;
  cases: Case[];
  placeChecks: unknown[];
};

/** `[lat, lon, atMs, walkId?, resumed?]` as a point. */
const pt = (p: Pt): TracePoint => ({ lat: p[0], lon: p[1], atMs: p[2], walkId: p[3] ?? 0, resumed: p[4] === 1 });
const walkOf = (points: Pt[], i: number): TraceWalk => ({ key: `t:${i}`, points: points.map(pt) });
const close = (actual: Stretch[], expected: Stretch[], where: string) => {
  expect(actual.length, `${where}: number of stretches`).toBe(expected.length);
  actual.forEach((s, i) => {
    expect(Math.abs(s.fromM - expected[i].fromM), `${where} [${i}].fromM ${s.fromM} vs ${expected[i].fromM}`).toBeLessThanOrEqual(0.5);
    expect(Math.abs(s.toM - expected[i].toM), `${where} [${i}].toM ${s.toM} vs ${expected[i].toM}`).toBeLessThanOrEqual(0.5);
  });
};

const splitCases = vectors.cases.filter((c): c is SplitCase => c.kind === 'split');
const repeatCases = vectors.cases.filter((c): c is RepeatsCase => c.kind === 'repeats');
const alertCases = vectors.cases.filter((c): c is AlertCase => c.kind === 'alert');

describe('the vector file', () => {
  it('is the format this spec reads, and holds all 37 repeat cases and 21 place checks', () => {
    expect(vectors.format).toBe('doorprints-trace-repeat-vectors/1');
    expect(vectors.cases).toHaveLength(37);
    expect(splitCases.length + repeatCases.length + alertCases.length).toBe(37);
    expect(vectors.placeChecks).toHaveLength(21);
  });

  it('carries the same constants as the code (a drift fails here)', () => {
    expect(Object.keys(vectors.constants).sort()).toEqual([...VECTOR_CONSTANT_KEYS].sort());
    for (const key of VECTOR_CONSTANT_KEYS) expect(TRACE[key], key).toBe(vectors.constants[key]);
  });

  it('keeps the website-only and drawing-only constants out of the vector file', () => {
    expect(Object.keys(TRACE).filter((k) => !(VECTOR_CONSTANT_KEYS as readonly string[]).includes(k)).sort()).toEqual([
      'checkStretchM',
      'pauseSplitMs',
      'thinDistanceM',
      'thinGapMs',
    ]);
    expect(TRACE.pauseSplitMs).toBe(300_000);
    expect(TRACE.checkStretchM).toBe(60);
  });
});

describe('split vectors', () => {
  it.each(splitCases)('$id', (c) => {
    expect(splitWalkIndexes(c.points.map(pt))).toEqual(c.expect.walks);
    expect(splitWalks(c.points.map(pt)).map((w) => w.length)).toEqual(c.expect.walks.map((w) => w.length));
  });
});

describe('repeat vectors', () => {
  it.each(repeatCases)('$id', (c) => {
    const walks = c.walks ? c.walks.map(walkOf.bind(null)) : splitWalks(c.points!.map(pt)).map((points, i) => ({ key: `t:${i}`, points }));
    if (c.expect.walkCount !== undefined) expect(walks, 'walk count').toHaveLength(c.expect.walkCount);
    expect(walks.length, 'one expectation per walk').toBe(c.expect.repeated.length);
    for (const options of [{ plain: false }, { plain: true }]) {
      const result = detectRepeats(walks, options);
      expect(result).toHaveLength(walks.length);
      result.forEach((r, i) => {
        close(r.repeated, c.expect.repeated[i], `${c.id} repeated[${i}] (plain=${options.plain})`);
        close(r.shown, c.expect.shown[i], `${c.id} shown[${i}] (plain=${options.plain})`);
      });
    }
  });
});

describe('alert vectors', () => {
  it.each(alertCases)('$id', (c) => {
    const live = c.live.map(pt);
    const others = c.others.map(walkOf.bind(null));
    const alert = new RepeatAlert();
    const rang: number[] = [];
    for (let i = 0; i < live.length; i++) if (alert.onPoint(live.slice(0, i + 1), others)) rang.push(i);
    expect(rang).toEqual(c.expect.alertAtIndexes);
  });
});
