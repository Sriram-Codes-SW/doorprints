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

// S4b-BL-278: the space words of the website (`space-words.ts`) are the `words` of the kind files, `house.json` and
// `school.json` (kind.schema.json `words`), as the label tables are their `labels`. The house's keys are pinned here by
// hand, from before the move, so a house space is word for word what it was; the file is valid against the schema
// (house-kind-file.spec.ts, school-kind.spec.ts) and the dictionaries hold every key in all four languages.
import { describe, expect, it } from 'vitest';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../docs/schemas/kinds/school.json';
import schemaJson from '../../../../docs/schemas/kinds/kind.schema.json';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import type { Lang } from '../i18n/languages';
import { SCHOOL_FIELDS } from './school-kind';
import { spaceWords } from './space-words';
import { TOOL_WORD_IDS, toolWord } from './space-tool-words';
import wordVectors from '../../../../docs/schemas/kinds/kind-word-vectors.json';

type Words = Record<string, { web: string | null; android?: string | null }>;
const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const IDS = ['heading', 'regionLabel', 'regionHint', 'add', 'addHint', 'addHintShort', 'search', 'listLabel', 'shown', 'loading',
  'emptyTitle', 'emptyBody', 'noMatch', 'create', 'areaNotesNone', 'areaInReach'];
/** The phone's other words (S4b-BL-316), after the sixteen, in the schema's order; the vectors file lists them too. */
const PHONE_IDS = (wordVectors as unknown as { words: { id: string }[] }).words.map((w) => w.id);

/** The house's keys as they were before the words moved into the kind file. */
const HOUSE_BEFORE = {
  heading: 'map.heading', regionLabel: 'map.regionLabel', regionHint: 'map.regionHint', add: 'map.add', addHint: 'map.addHint',
  addHintShort: 'map.addHintShort', search: 'map.search', listLabel: 'map.listLabel', shown: 'map.shown', loading: 'map.loading',
  emptyTitle: 'map.emptyTitle', emptyBody: 'map.emptyBody', noMatch: 'map.noMatch', create: 'house.create',
  areaNotesNone: 'areaNotes.none', areaInReach: 'areaNotes.inReach', many: 'map.stats.houses',
};

const webKeys = (w: Words) => Object.fromEntries(Object.entries(w).map(([id, v]) => [id, v.web]));
const pick = (o: Record<string, string | null>, ids: string[]) => Object.fromEntries(ids.map((id) => [id, o[id]]));

describe('the space words are the kind files’ (S4b-BL-278)', () => {
  it('the schema names the sixteen words and then the phone’s other words (S4b-BL-316)', () => {
    const names = (schemaJson as unknown as { properties: { words: { propertyNames: { enum: string[] } } } }).properties.words.propertyNames.enum;
    expect(names).toEqual([...IDS, ...PHONE_IDS]);
    expect(PHONE_IDS).toHaveLength(78);
  });

  it('house.json holds the house’s keys as they were, and the website reads them', () => {
    const file = (houseJson as unknown as { words: Words }).words;
    expect(Object.keys(file)).toEqual([...IDS, ...PHONE_IDS]);
    expect({ ...pick(webKeys(file), IDS), many: 'map.stats.houses' }).toEqual(HOUSE_BEFORE);
    expect(spaceWords('house')).toEqual(HOUSE_BEFORE);
    expect(spaceWords(null)).toEqual(HOUSE_BEFORE);
  });

  it('school.json holds the school’s keys, and the website reads them', () => {
    const file = (schoolJson as unknown as { words: Words }).words;
    expect(Object.keys(file)).toEqual([...IDS, ...PHONE_IDS]);
    expect(spaceWords('school')).toEqual({ ...pick(webKeys(file), IDS), many: 'school.many' });
  });

  for (const lang of LANGS) {
    it(`every word of both files has text in ${lang}`, () => {
      const dict = DICTIONARIES[lang] as Record<string, string>;
      for (const f of [houseJson, schoolJson]) {
        for (const [id, v] of Object.entries((f as unknown as { words: Words }).words)) if (v.web) expect(dict[v.web], `${lang} ${id}`).toBeTruthy();
      }
    });
  }
});

describe('the phone’s other words have ids in the kind files and the website’s table (S4b-BL-316)', () => {
  type Vec = { id: string; house: { web: string | null; android: string }; school: { web: string | null; android: string } };
  const vectors = (wordVectors as unknown as { words: Vec[] }).words;
  const house = (houseJson as unknown as { words: Words }).words;
  const school = (schoolJson as unknown as { words: Words }).words;

  it('the files hold the vectors’ keys, the phone key of every id and the website key where the website has the word', () => {
    for (const v of vectors) {
      expect(house[v.id].web, `house ${v.id}`).toBe(v.house.web);
      expect(house[v.id].android, `house ${v.id}`).toBe(v.house.android);
      expect(school[v.id].web, `school ${v.id}`).toBe(v.school.web);
      expect(school[v.id].android, `school ${v.id}`).toBe(v.school.android);
    }
  });

  it('the website’s table names exactly the ids that have a website key, and reads the school’s key for the house’s', () => {
    expect(Object.keys(TOOL_WORD_IDS).sort()).toEqual(vectors.filter((v) => v.house.web).map((v) => v.id).sort());
    for (const v of vectors.filter((x) => x.house.web)) {
      expect(TOOL_WORD_IDS[v.id], `house ${v.id}`).toBe(v.house.web);
      expect(toolWord('school', TOOL_WORD_IDS[v.id] as never), `school ${v.id}`).toBe(v.school.web);
    }
    // A word the website lacks has no key in either file, so there is nothing for the table to say.
    for (const v of vectors.filter((x) => !x.house.web)) expect(v.school.web, v.id).toBeNull();
  });
});

describe('which way a number is better is the kind file’s (S4b-BL-292)', () => {
  it('the school’s commute and both fees are better lower, nothing else says, and the website’s copy is the file', () => {
    const file = (schoolJson as unknown as { extras: { id: string; better?: string }[] }).extras;
    expect(file.filter((e) => e.better).map((e) => [e.id, e.better])).toEqual([['commuteMinutes', 'lower'], ['fees', 'lower'], ['admissionFee', 'lower']]);
    expect(SCHOOL_FIELDS.others(true).map((f) => [f.id, f.better ?? null])).toEqual(file.map((e) => [e.id, e.better ?? null]));
    expect((houseJson as unknown as { extras: { better?: string }[] }).extras.some((e) => e.better)).toBe(false);
  });
});
