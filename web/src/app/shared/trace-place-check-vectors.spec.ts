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
import { TRACE } from './trace-geo';
import type { TracePoint } from './trace-geo';
import { placeCheck } from './trace-place-check';
import type { PlaceStatus } from './trace-place-check';

/**
 * The `placeChecks` section of the shared vector file (docs/11 5.27.13, docs/06 TC-U-153): all 21 cases, the status
 * exactly, `nearestM` and each row's distance to 0.5 m, `atMs` to 1 s, the order, band and source. The vector file is
 * never edited to make this pass.
 */

type Pt = number[];
interface PlaceCase {
  id: string;
  place: [number, number];
  walks: Pt[][];
  walkSources?: string[];
  fixAccuracyM?: number;
  expect: {
    status: string;
    fuzzy: boolean;
    nearestM: number | null;
    walks: { walkIndex: number; distanceM: number; atMs: number; band: string; source: string }[];
  };
}
const cases = (vectorsJson as unknown as { placeChecks: PlaceCase[] }).placeChecks;
const pt = (p: Pt): TracePoint => ({ lat: p[0], lon: p[1], atMs: p[2], walkId: p[3] ?? 0, resumed: p[4] === 1 });
const STATUS: Record<string, PlaceStatus> = {
  walked: 'WALKED',
  close: 'CLOSE',
  none: 'NONE',
  empty: 'EMPTY',
  imprecise: 'IMPRECISE',
  invalidPlace: 'INVALID_PLACE',
};

describe('place check vectors', () => {
  it('holds 21 cases', () => {
    expect(cases).toHaveLength(21);
  });

  it.each(cases)('$id', (c) => {
    const walks = c.walks.map((w, i) => ({ points: w.map(pt), source: (c.walkSources?.[i] ?? 'trace') === 'saved' ? ('SAVED' as const) : ('TRACE' as const) }));
    for (const noBoxRejection of [false, true]) {
      const r = placeCheck({ lat: c.place[0], lon: c.place[1] }, walks, c.fixAccuracyM ?? null, { noBoxRejection });
      expect(r.status).toBe(STATUS[c.expect.status]);
      expect(r.fuzzy).toBe(c.expect.fuzzy);
      if (c.expect.nearestM === null) expect(r.nearestM).toBeNull();
      else expect(Math.abs((r.nearestM ?? 1e9) - c.expect.nearestM)).toBeLessThanOrEqual(0.5);
      expect(r.rows).toHaveLength(c.expect.walks.length);
      r.rows.forEach((row, i) => {
        const e = c.expect.walks[i];
        expect(row.walkIndex, `row ${i} walk`).toBe(e.walkIndex);
        expect(Math.abs(row.distanceM - e.distanceM), `row ${i} distance ${row.distanceM} vs ${e.distanceM}`).toBeLessThanOrEqual(0.5);
        expect(Math.abs(row.atMs - e.atMs), `row ${i} time ${row.atMs} vs ${e.atMs}`).toBeLessThanOrEqual(1000);
        expect(row.walked ? 'walked' : 'close', `row ${i} band`).toBe(e.band);
        expect(row.saved ? 'saved' : 'trace', `row ${i} source`).toBe(e.source);
      });
    }
  });

  it('uses the tolerance and band the vector file names', () => {
    const constants = (vectorsJson as unknown as { constants: Record<string, number> }).constants;
    expect(TRACE.toleranceM).toBe(constants['toleranceM']);
    expect(TRACE.nearBandM).toBe(constants['nearBandM']);
    expect(TRACE.maxFixAccuracyM).toBe(constants['maxFixAccuracyM']);
  });
});
