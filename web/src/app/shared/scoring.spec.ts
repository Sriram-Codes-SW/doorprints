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
import { CHECKLIST } from '../core/models';
import {
  BUILT_IN_KEYS,
  DEFAULT_SCORING,
  compareRanked,
  criterionFromPayload,
  criterionToPayload,
  evaluateScore,
  isDefaultCriterion,
  newCustomKey,
  ratingShareOf,
  scoringOf,
} from './scoring';
import type { Criterion, CriterionRow, RankedHouse, Scoring } from './scoring';

/** The defaults with some criteria replaced, added or archived: what `scoringOf` merges from the records. */
function withCriteria(over: (Partial<Criterion> & { key: string })[], ratingShare = 0.5): Scoring {
  const rows: CriterionRow[] = over.map((c) => {
    const base = DEFAULT_SCORING.criteria.find((d) => d.key === c.key) ?? { key: c.key, weight: 2, mustHave: false, minScore: 3, sort: 100 };
    return { key: c.key, updatedAt: null, criterion: { ...base, ...c } as Criterion };
  });
  const scoring = scoringOf(rows, []);
  return { ...scoring, ratingShare };
}

describe('the built-in keys', () => {
  it('are the checklist keys, in order', () => {
    expect([...BUILT_IN_KEYS]).toEqual(CHECKLIST.map((item) => item.key));
  });
});

/** The eight vectors V1..V8 of the slice 2 contract; the same names in Kotlin `HouseScoreTest`. */
describe('scoring vectors', () => {
  it('V1 defaults: water 5, parking 4, rating 4 -> wc 4.5, overall 4.25, coverage 0.2', () => {
    const r = evaluateScore({ water: 5, parking: 4 }, 4, DEFAULT_SCORING);
    expect(r.overall).toBeCloseTo(4.25, 10);
    expect(r.coverage).toBeCloseTo(0.2, 10);
    expect(evaluateScore({ water: 5, parking: 4 }, null, DEFAULT_SCORING).overall).toBeCloseTo(4.5, 10);
    expect([r.scored, r.active]).toEqual([2, 10]);
  });

  it('V2 weights: water w3 s5, power w1 s1, no rating -> wc 4.0, overall 4.0', () => {
    const s = withCriteria([{ key: 'water', weight: 3 }, { key: 'power', weight: 1 }]);
    expect(evaluateScore({ water: 5, power: 1 }, null, s).overall).toBeCloseTo(4.0, 10);
  });

  it('V3 ignore and archive: water w0 s5, power w2 s3 -> wc 3.0; an archived criterion with a score is ignored too', () => {
    const ignored = withCriteria([{ key: 'water', weight: 0 }]);
    expect(evaluateScore({ water: 5, power: 3 }, null, ignored).overall).toBeCloseTo(3.0, 10);
    const archived = withCriteria([{ key: 'water', archived: true }]);
    expect(evaluateScore({ water: 5, power: 3 }, null, archived).overall).toBeCloseTo(3.0, 10);
    // Only ignored ones scored: nothing counts, so only the rating does.
    expect(evaluateScore({ water: 5 }, 2, ignored).overall).toBe(2);
    expect(evaluateScore({ water: 5 }, null, ignored).overall).toBeNull();
  });

  it('V4 must-have: below the minimum fails, unscored is unchecked and not failed, at the minimum is neither', () => {
    const s = withCriteria([{ key: 'security', mustHave: true, minScore: 4 }]);
    const failed = evaluateScore({ security: 2 }, null, s);
    expect(failed.failedMustHave).toEqual(['security']);
    expect(failed.uncheckedMustHave).toEqual([]);
    const unchecked = evaluateScore({ water: 5 }, null, s);
    expect(unchecked.failedMustHave).toEqual([]);
    expect(unchecked.uncheckedMustHave).toEqual(['security']);
    const ok = evaluateScore({ security: 4 }, null, s);
    expect(ok.failedMustHave).toEqual([]);
    expect(ok.uncheckedMustHave).toEqual([]);
  });

  it('V4 must-have: weight does not matter, but an archived must-have is not checked', () => {
    const ignored = withCriteria([{ key: 'security', mustHave: true, minScore: 4, weight: 0 }]);
    expect(evaluateScore({ security: 1 }, null, ignored).failedMustHave).toEqual(['security']);
    const archived = withCriteria([{ key: 'security', mustHave: true, minScore: 4, archived: true }]);
    expect(evaluateScore({ security: 1 }, null, archived).failedMustHave).toEqual([]);
  });

  it('V5 rating share 0.25: wc 4, rating 2 -> 3.5; no checklist score, rating 3 -> 3.0; neither -> null', () => {
    const s = withCriteria([], 0.25);
    expect(evaluateScore({ water: 4 }, 2, s).overall).toBeCloseTo(3.5, 10);
    expect(evaluateScore({}, 3, s).overall).toBe(3.0);
    expect(evaluateScore({}, null, s).overall).toBeNull();
  });

  it('V6 custom: c_ab12cd34 w3 s5 + water w2 s1 -> wc 3.4', () => {
    const s = withCriteria([{ key: 'c_ab12cd34', label: 'Pets', weight: 3 }]);
    expect(evaluateScore({ c_ab12cd34: 5, water: 1 }, null, s).overall).toBeCloseTo(3.4, 10);
    // A key that is no known criterion (a newer app's) is ignored.
    expect(evaluateScore({ c_ab12cd34: 5, water: 1, c_00000000: 0 }, null, s).overall).toBeCloseTo(3.4, 10);
  });

  it('V7 compatibility: default scoring, water 4, power 2, rating 5 -> 4.0 (the pre-slice formula)', () => {
    expect(evaluateScore({ water: 4, power: 2 }, 5, DEFAULT_SCORING).overall).toBeCloseTo(4.0, 10);
  });

  it('V8 ranking: C, B, D, E, A', () => {
    const base = { updatedAt: 1000 };
    const ok = (overall: number | null, coverage: number | null) => ({ overall, coverage, failedMustHave: [] as string[] });
    const a: RankedHouse = { id: 'A', result: { overall: 5, coverage: 1, failedMustHave: ['security'] }, price: null, ...base };
    const b: RankedHouse = { id: 'B', result: ok(4, 0.5), price: 20000, ...base };
    const c: RankedHouse = { id: 'C', result: ok(4, 0.5), price: 15000, ...base };
    const d: RankedHouse = { id: 'D', result: ok(4, 0.2), price: null, ...base };
    const e: RankedHouse = { id: 'E', result: ok(null, null), price: null, ...base };
    expect([a, b, c, d, e].sort(compareRanked).map((h) => h.id)).toEqual(['C', 'B', 'D', 'E', 'A']);
    expect([e, d, c, b, a].sort(compareRanked).map((h) => h.id)).toEqual(['C', 'B', 'D', 'E', 'A']);
  });

  it('ranks ties by the newest edit, then by id, so the order is total', () => {
    const r = { overall: 3, coverage: 0.5, failedMustHave: [] as string[] };
    const older: RankedHouse = { id: 'a', result: r, price: 1, updatedAt: 1 };
    const newer: RankedHouse = { id: 'b', result: r, price: 1, updatedAt: 2 };
    const same: RankedHouse = { id: 'c', result: r, price: 1, updatedAt: 2 };
    expect([older, same, newer].sort(compareRanked).map((h) => h.id)).toEqual(['b', 'c', 'a']);
  });
});

describe('Scoring.of: the defaults merged with the records', () => {
  const row = (key: string, criterion: Partial<Criterion>): CriterionRow => ({
    key,
    updatedAt: null,
    criterion: { key, weight: 2, mustHave: false, minScore: 3, sort: 0, ...criterion },
  });

  it('is the ten defaults, Medium and in order, with a rating share of 0.5, when there is no record', () => {
    const s = scoringOf([], []);
    expect(s.criteria.map((c) => c.key)).toEqual([...BUILT_IN_KEYS]);
    expect(s.criteria.every((c) => c.weight === 2 && !c.mustHave && c.minScore === 3 && !c.archived)).toBe(true);
    expect(s.ratingShare).toBe(0.5);
  });

  it('replaces a built-in by its record, adds a custom one, and sorts by sort then key', () => {
    const s = scoringOf(
      [row('water', { weight: 3, mustHave: true, minScore: 4, sort: 20 }), row('c_1a2b3c4d', { label: 'Pets', sort: 2 })],
      [{ key: 'score.ratingShare', value: '0.4', updatedAt: null }],
    );
    expect(s.criteria).toHaveLength(11);
    expect(s.criteria.map((c) => c.key).slice(0, 3)).toEqual(['power', 'c_1a2b3c4d', 'parking']);
    expect(s.criteria[s.criteria.length - 1].key).toBe('water');
    expect(s.criteria.find((c) => c.key === 'water')).toMatchObject({ weight: 3, mustHave: true, minScore: 4 });
    expect(s.ratingShare).toBe(0.4);
  });

  it('reads an unparsable or out-of-range rating share as 0.5', () => {
    for (const bad of ['', 'x', '1.5', '-0.1', 'NaN']) expect(ratingShareOf(bad), bad).toBe(0.5);
    expect(ratingShareOf(null)).toBe(0.5);
    expect(ratingShareOf('0')).toBe(0);
    expect(ratingShareOf('1')).toBe(1);
  });
});

describe('a criterion payload', () => {
  it('round-trips with the keys in the contract order, the label for a custom one only, archived only when true', () => {
    const custom: Criterion = { key: 'c_1a2b3c4d', label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 };
    expect(Object.keys(criterionToPayload(custom))).toEqual(['label', 'weight', 'mustHave', 'minScore', 'sort']);
    expect(criterionFromPayload('c_1a2b3c4d', criterionToPayload(custom))).toEqual(custom);
    const water: Criterion = { key: 'water', label: 'Ignored', weight: 3, mustHave: true, minScore: 4, sort: 0, archived: true };
    expect(Object.keys(criterionToPayload(water))).toEqual(['weight', 'mustHave', 'minScore', 'sort', 'archived']);
    // A built-in never carries a label, even when the caller gave one.
    expect(criterionFromPayload('water', criterionToPayload(water))).toEqual({ key: 'water', weight: 3, mustHave: true, minScore: 4, sort: 0, archived: true });
  });

  it('reads an out-of-range weight, minimum score or sort as the default and skips a bad key', () => {
    expect(criterionFromPayload('water', { weight: 4, minScore: 9, sort: -1, mustHave: 'yes' })).toEqual({
      key: 'water',
      weight: 2,
      mustHave: false,
      minScore: 3,
      sort: 0,
    });
    expect(criterionFromPayload('parking', { sort: 7.5 })?.sort).toBe(2);
    expect(criterionFromPayload('bad key!', {})).toBeNull();
    expect(criterionFromPayload('x'.repeat(65), {})).toBeNull();
    expect(criterionFromPayload('water', null)).toBeNull();
  });

  it('knows a built-in equal to its default, which needs no record', () => {
    expect(isDefaultCriterion({ key: 'power', weight: 2, mustHave: false, minScore: 3, sort: 1 })).toBe(true);
    expect(isDefaultCriterion({ key: 'power', weight: 3, mustHave: false, minScore: 3, sort: 1 })).toBe(false);
    expect(isDefaultCriterion({ key: 'power', weight: 2, mustHave: false, minScore: 3, sort: 4 })).toBe(false);
    expect(isDefaultCriterion({ key: 'c_1a2b3c4d', weight: 2, mustHave: false, minScore: 3, sort: 1 })).toBe(false);
  });

  it('makes custom keys of c_ and eight lowercase hex characters', () => {
    for (let i = 0; i < 20; i++) expect(newCustomKey()).toMatch(/^c_[0-9a-f]{8}$/);
  });
});
