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

import type { TKey } from '../i18n/en';
import { kindById } from './kind-setting';

/**
 * The words of a space's first screen and of its candidate page that name the kind (kinds PR 8, S4b-BL-212; S4b-BL-277,
 * S4b-BL-259, S4b-BL-256): the list heading, the map's region, the Add button and its hint, the search, the list's
 * counts, the loading, empty and no-match lines, the new candidate's save button and its area-notes line. The house's
 * are today's keys, so a house space is word for word what it was; the counter is the kind file's `kind.many` (the
 * resolver). The kind file has no place for the rest yet (its `labels` are `new`, `many` and `delete`), so this table is
 * the website's own, as the renderer's element ids are (§18.8); moving them into the kind files with the phone's is
 * S4b-BL-278.
 */
export interface SpaceWords {
  heading: TKey;
  many: TKey;
  regionLabel: TKey;
  regionHint: TKey;
  add: TKey;
  addHint: TKey;
  addHintShort: TKey;
  search: TKey;
  listLabel: TKey;
  shown: TKey;
  loading: TKey;
  emptyTitle: TKey;
  emptyBody: TKey;
  noMatch: TKey;
  create: TKey;
  areaNotesNone: TKey;
}

const HOUSE: Omit<SpaceWords, 'many'> = {
  heading: 'map.heading',
  regionLabel: 'map.regionLabel',
  regionHint: 'map.regionHint',
  add: 'map.add',
  addHint: 'map.addHint',
  addHintShort: 'map.addHintShort',
  search: 'map.search',
  listLabel: 'map.listLabel',
  shown: 'map.shown',
  loading: 'map.loading',
  emptyTitle: 'map.emptyTitle',
  emptyBody: 'map.emptyBody',
  noMatch: 'map.noMatch',
  create: 'house.create',
  areaNotesNone: 'areaNotes.none',
};

const SCHOOL: Omit<SpaceWords, 'many'> = {
  heading: 'school.heading',
  regionLabel: 'school.regionLabel',
  regionHint: 'school.regionHint',
  add: 'school.add',
  addHint: 'school.addHint',
  addHintShort: 'school.addHintShort',
  search: 'school.search',
  listLabel: 'school.listLabel',
  shown: 'school.shown',
  loading: 'school.loading',
  emptyTitle: 'school.emptyTitle',
  emptyBody: 'school.emptyBody',
  noMatch: 'school.noMatch',
  create: 'school.create',
  areaNotesNone: 'school.areaNotesNone',
};

const WORDS: Readonly<Record<string, Omit<SpaceWords, 'many'>>> = { house: HOUSE, school: SCHOOL };

/** The space words of `kind`; a kind with no table of its own reads the house's. */
export function spaceWords(kind: string | null | undefined): SpaceWords {
  const k = kindById(kind ?? 'house');
  const table = Object.prototype.hasOwnProperty.call(WORDS, k.id) ? WORDS[k.id] : HOUSE;
  return { ...table, many: k.keys.ref('kind.many') as TKey };
}
