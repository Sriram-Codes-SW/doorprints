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

// Compare per kind (kinds PR 27, docs/03 §18.21): what a kind file's `capabilities.compare` resolves to and what Compare shows
// for the sample entries of docs/schemas/kinds/compare-vectors.json. The expected values are the vectors' (worked by hand from the
// rule), never recomputed with the code under test.
import { describe, expect, it } from 'vitest';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../docs/schemas/kinds/school.json';
import vectorsJson from '../../../../docs/schemas/kinds/compare-vectors.json';
import { HOUSE, builtInKind } from '../core/dev-kinds';
import { KindFields } from '../core/kind-fields';
import { KindLabels } from '../core/kind-labels';
import { newHouse, type HouseDto } from '../core/models';
import { en } from '../i18n/en';
import { bestOf, bestOfExtra, compareCriteria, compareOutcome, comparedRows, kindCriterionName, resolveCompareCriteria, scoringForKind } from './compare-criteria';
import { DEFAULT_SCORING, defaultCriterion } from './scoring';
import type { Scoring } from './scoring';

interface Criterion { id: string; source: string; better: string | null; label?: string; labelKey?: { web: string | null; android: string | null } }
interface Entry { id: string; rating: number | null; checklist: Record<string, number>; extras: Record<string, unknown> }
interface Vectors {
  format: string;
  kinds: Record<string, { criteria: Criterion[] }>;
  unknown: string[];
  resolve: { name: string; kind: string; ids: string[]; resolved: Criterion[] }[];
  scoring: { house: { identity: boolean; keys: string[] }; school: { identity: boolean; criteria: { key: string; weight: number; mustHave: boolean }[] } };
  outcomes: { name: string; kind: string; ratingShare: number; entries: Entry[]; ranking: string[]; overall: Record<string, number | null>; best: Record<string, string[]> }[];
}
const V = vectorsJson as unknown as Vectors;
const SCHOOL = builtInKind('school', true);
const KINDS = { house: HOUSE, school: SCHOOL } as const;
const FILES = { house: houseJson, school: schoolJson } as unknown as Record<string, { checklist: { key: string; label: { web: string; android: string } }[]; extras: { id: string; label: { web: string; android: string } }[]; capabilities: { compare: { criteria: string[] } } }>;

const plain = (c: { id: string; source: string; better: string | null }) => ({ id: c.id, source: c.source, better: c.better });
const candidate = (kind: string, e: Entry): HouseDto => ({ ...newHouse(13, 80), id: e.id, kind, rating: e.rating, checklist: e.checklist, extrasOther: e.extras as HouseDto['extrasOther'] });

describe('Compare per kind (compare-vectors.json)', () => {
  it('is the vectors’ format, and names the kinds the build has', () => {
    expect(V.format).toBe('doorprints-compare-vectors/1');
    expect(Object.keys(V.kinds).sort()).toEqual(['house', 'school', 'unknown']);
  });

  it('resolves each kind’s criteria in the kind file’s order, with the direction, the label key and the words', () => {
    for (const [id, kind] of Object.entries(KINDS)) {
      const got = compareCriteria(kind);
      expect(got.map(plain), id).toEqual(V.kinds[id].criteria.map(plain));
      expect(got.map((c) => c.id), `${id} follows the file`).toEqual(FILES[id].capabilities.compare.criteria);
      for (const [i, want] of V.kinds[id].criteria.entries()) {
        expect(got[i].label, `${id} ${want.id}`).toBe(want.labelKey!.web);
        expect(en[got[i].label], `${id} ${want.id}`).toBe(want.label);
        const file = want.source === 'check' ? FILES[id].checklist.find((c) => c.key === want.id) : FILES[id].extras.find((e) => e.id === want.id);
        expect(file!.label.web, `${id} ${want.id} file`).toBe(want.labelKey!.web);
        expect(file!.label.android, `${id} ${want.id} file`).toBe(want.labelKey!.android);
      }
    }
  });

  it('the house’s criteria are its ten checklist keys, in order (today’s Compare)', () => {
    expect(compareCriteria(HOUSE).map((c) => c.id)).toEqual(['water', 'power', 'parking', 'sunlight', 'ventilation', 'noise', 'security', 'maintenance', 'neighbourhood', 'commute']);
    expect(compareCriteria(HOUSE).every((c) => c.source === 'check' && c.better === 'higher')).toBe(true);
  });

  it('a lower figure is better for the fees and the commute because the kind file says so, not because the code does', () => {
    const extras = schoolJson.extras as { id: string; better?: string }[];
    for (const c of V.kinds['school'].criteria.filter((x) => x.source === 'extra')) {
      expect(extras.find((e) => e.id === c.id)!.better, c.id).toBe(c.better);
    }
  });

  for (const c of V.resolve) {
    it(`resolves a list of ids: ${c.name}`, () => {
      expect(resolveCompareCriteria(c.ids, KINDS[c.kind as keyof typeof KINDS]).map(plain)).toEqual(c.resolved);
    });
  }

  it('a kind this build does not know has no criteria', () => {
    for (const id of V.unknown) expect(compareCriteria({ ...HOUSE, id }), id).toEqual([]);
  });

  it('scores the house under the person’s own scoring (the same object) and the school under its eight items, Medium each', () => {
    const mine: Scoring = { criteria: [{ ...defaultCriterion('water', 0), mustHave: true }], ratingShare: 0.25 };
    expect(scoringForKind(HOUSE, mine)).toBe(mine);
    expect(V.scoring.house.keys).toEqual(FILES['house'].checklist.map((c) => c.key));
    const school = scoringForKind(SCHOOL, mine);
    expect(school.criteria.map((c) => ({ key: c.key, weight: c.weight, mustHave: c.mustHave }))).toEqual(V.scoring.school.criteria);
    expect(school.criteria.map((c) => c.sort)).toEqual([0, 1, 2, 3, 4, 5, 6, 7]);
    expect(school.ratingShare).toBe(0.25);
    expect(scoringForKind(SCHOOL, DEFAULT_SCORING).criteria.some((c) => c.key === 'water')).toBe(false);
  });

  it('counts a checklist item only when the kind’s Compare names it: the others are asked and ignored (weight 0)', () => {
    const labels = new KindLabels({ kind: 'school', keys: { ...Object.fromEntries(SCHOOL.labels.refs.map((r) => [r, SCHOOL.labels.resolve(r)!.key])), 'check.extraitem': 'school.check.safety' }, byCadence: {}, sections: {} });
    const kind = { ...SCHOOL, labels };
    const scoring = scoringForKind(kind, DEFAULT_SCORING);
    expect(scoring.criteria.map((c) => [c.key, c.weight])).toEqual([...V.scoring.school.criteria.map((c) => [c.key, c.weight]), ['extraitem', 0]]);
  });

  // A made-up kind (the built-ins have no extra that is better higher, none hidden and no item left uncounted).
  const MADE_UP = (() => {
    const keys = { ...Object.fromEntries(SCHOOL.labels.refs.map((r) => [r, SCHOOL.labels.resolve(r)!.key])), 'check.extraitem': 'school.check.safety', 'field.rank': 'school.grades', 'field.plain': 'school.grades', 'field.hiddenx': 'school.grades' } as never;
    const labels = new KindLabels({ kind: 'school', keys, byCadence: {}, sections: { rank: 'details', plain: 'details', hiddenx: 'rooms' } });
    const fields = new KindFields(labels, [
      { id: 'rank', type: 'number', section: 'details', order: 1, better: 'higher' },
      { id: 'plain', type: 'number', section: 'details', order: 2 },
      { id: 'hiddenx', type: 'number', section: 'rooms', order: 3, better: 'lower' },
    ]);
    return { ...SCHOOL, labels, fields };
  })();

  it('reads the direction from the extra: higher, lower, or none for a number that says nothing; and leaves out an extra the kind hides', () => {
    const got = resolveCompareCriteria(['rank', 'plain', 'hiddenx', 'extraitem'], MADE_UP);
    expect(got.map(plain)).toEqual([
      { id: 'rank', source: 'extra', better: 'higher' },
      { id: 'plain', source: 'extra', better: null },
      { id: 'extraitem', source: 'check', better: 'higher' },
    ]);
  });

  it('shows the rows of the items the kind’s Compare names for a school, and every active criterion of the person for the house', () => {
    const school = scoringForKind(MADE_UP, DEFAULT_SCORING);
    expect(comparedRows(SCHOOL, scoringForKind(SCHOOL, DEFAULT_SCORING)).map((c) => c.key)).toEqual(V.scoring.school.criteria.map((c) => c.key));
    expect(school.criteria.map((c) => c.key)).toContain('extraitem'); // asked on the page ...
    expect(comparedRows(MADE_UP, school).map((c) => c.key)).not.toContain('extraitem'); // ... and not compared (no `compare` names it)
    const mine: Scoring = { criteria: [{ ...defaultCriterion('water', 0), weight: 0 }, { ...defaultCriterion('power', 1), archived: true }, defaultCriterion('parking', 2)], ratingShare: 0.5 };
    expect(comparedRows(HOUSE, mine).map((c) => c.key)).toEqual(['water', 'parking']); // weight 0 stays, archived goes
  });

  it('names a criterion in the kind’s words: the school’s own, the house’s as before, a custom label kept', () => {
    const t = (key: string) => en[key as keyof typeof en];
    expect(kindCriterionName(SCHOOL, { key: 'safety' }, t)).toBe('Safety and CCTV');
    expect(kindCriterionName(HOUSE, { key: 'security' }, t)).toBe('Safety and security');
    expect(kindCriterionName(HOUSE, { key: 'c_0123abcd', label: 'Lift' }, t)).toBe('Lift');
    expect(kindCriterionName(SCHOOL, { key: 'water' }, t)).toBe('Water supply'); // not the school’s item: the house’s rule for a built-in key
    expect(kindCriterionName(SCHOOL, { key: 'zzz' }, t)).toBe('zzz'); // nobody’s: the key
  });

  for (const o of V.outcomes) {
    it(`shows what the vectors say: ${o.name}`, () => {
      const kind = KINDS[o.kind as keyof typeof KINDS];
      const base: Scoring = { ...DEFAULT_SCORING, ratingShare: o.ratingShare };
      const out = compareOutcome(kind, o.entries.map((e) => candidate(o.kind, e)), base);
      expect(out.ranking).toEqual(o.ranking);
      expect(out.overall).toEqual(o.overall);
      expect(out.best).toEqual(o.best);
      // the order the entries come in changes nothing
      const reversed = compareOutcome(kind, o.entries.slice().reverse().map((e) => candidate(o.kind, e)), base);
      expect(reversed.ranking).toEqual(o.ranking);
    });
  }

  it('marks the best by the direction: a lower figure for “lower”, a higher for “higher”, nothing for a tie, one value or no direction', () => {
    expect([...bestOfExtra([20, 35, null], 'lower')]).toEqual([0]);
    expect([...bestOfExtra([20, 35, null], 'higher')]).toEqual([1]);
    expect([...bestOfExtra([20, 20, null], 'lower')]).toEqual([]);
    expect([...bestOfExtra([20, null, null], 'lower')]).toEqual([]);
    expect([...bestOfExtra([20, 35], null)]).toEqual([]);
    expect([...bestOfExtra([null, null], 'lower')]).toEqual([]);
    expect([...bestOf([3, 5, null])]).toEqual([1]);
    expect([...bestOf([4, 4])]).toEqual([]);
    expect([...bestOf([4])]).toEqual([0]);
  });
});
