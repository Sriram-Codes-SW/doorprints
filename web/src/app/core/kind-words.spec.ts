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
import { spaceWords } from './space-words';

type Words = Record<string, { web: string; android?: string | null }>;
const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const IDS = ['heading', 'regionLabel', 'regionHint', 'add', 'addHint', 'addHintShort', 'search', 'listLabel', 'shown', 'loading',
  'emptyTitle', 'emptyBody', 'noMatch', 'create', 'areaNotesNone', 'areaInReach'];

/** The house's keys as they were before the words moved into the kind file. */
const HOUSE_BEFORE = {
  heading: 'map.heading', regionLabel: 'map.regionLabel', regionHint: 'map.regionHint', add: 'map.add', addHint: 'map.addHint',
  addHintShort: 'map.addHintShort', search: 'map.search', listLabel: 'map.listLabel', shown: 'map.shown', loading: 'map.loading',
  emptyTitle: 'map.emptyTitle', emptyBody: 'map.emptyBody', noMatch: 'map.noMatch', create: 'house.create',
  areaNotesNone: 'areaNotes.none', areaInReach: 'areaNotes.inReach', many: 'map.stats.houses',
};

const webKeys = (w: Words) => Object.fromEntries(Object.entries(w).map(([id, v]) => [id, v.web]));

describe('the space words are the kind files’ (S4b-BL-278)', () => {
  it('the schema names the same sixteen words', () => {
    const names = (schemaJson as unknown as { properties: { words: { propertyNames: { enum: string[] } } } }).properties.words.propertyNames.enum;
    expect(names).toEqual(IDS);
  });

  it('house.json holds the house’s keys as they were, and the website reads them', () => {
    const file = (houseJson as unknown as { words: Words }).words;
    expect(Object.keys(file)).toEqual(IDS);
    expect({ ...webKeys(file), many: 'map.stats.houses' }).toEqual(HOUSE_BEFORE);
    expect(spaceWords('house')).toEqual(HOUSE_BEFORE);
    expect(spaceWords(null)).toEqual(HOUSE_BEFORE);
  });

  it('school.json holds the school’s keys, and the website reads them', () => {
    const file = (schoolJson as unknown as { words: Words }).words;
    expect(Object.keys(file)).toEqual(IDS);
    expect(spaceWords('school')).toEqual({ ...webKeys(file), many: 'school.many' });
  });

  for (const lang of LANGS) {
    it(`every word of both files has text in ${lang}`, () => {
      const dict = DICTIONARIES[lang] as Record<string, string>;
      for (const f of [houseJson, schoolJson]) {
        for (const [id, v] of Object.entries((f as unknown as { words: Words }).words)) expect(dict[v.web], `${lang} ${id}`).toBeTruthy();
      }
    });
  }
});
