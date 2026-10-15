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
import { kindWordKey } from './kind-word-table';

/**
 * The words of a space's first screen and of its candidate page that name the kind (kinds PR 8, S4b-BL-212; S4b-BL-277,
 * S4b-BL-259, S4b-BL-256): the list heading, the map's region, the Add button and its hint, the search, the list's
 * counts, the loading, empty and no-match lines, the new candidate's save button and its area-notes line. The house's
 * are today's keys, so a house space is word for word what it was; the counter is the kind file's `kind.many` (the
 * resolver). The words are the `words` of the kind files (`house.json`, `school.json`; S4b-BL-278), read through the file
 * generated from them (`kind-word-table.ts`, S4b-BL-316): no table of words is written by hand here.
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
  /** The group of areas a candidate is in, in the area note composer (S4b-BL-256). */
  areaInReach: TKey;
}

const IDS = ['heading', 'regionLabel', 'regionHint', 'add', 'addHint', 'addHintShort', 'search', 'listLabel', 'shown', 'loading',
  'emptyTitle', 'emptyBody', 'noMatch', 'create', 'areaNotesNone', 'areaInReach'] as const;

/** The space words of `kind`: the kind file's `words`, the house's for a kind with none (`kind-word-table.ts`). */
export function spaceWords(kind: string | null | undefined): SpaceWords {
  const k = kindById(kind ?? 'house');
  const w = (id: (typeof IDS)[number]): TKey => kindWordKey(k.id, id) as TKey;
  return {
    heading: w('heading'), regionLabel: w('regionLabel'), regionHint: w('regionHint'), add: w('add'), addHint: w('addHint'),
    addHintShort: w('addHintShort'), search: w('search'), listLabel: w('listLabel'), shown: w('shown'), loading: w('loading'),
    emptyTitle: w('emptyTitle'), emptyBody: w('emptyBody'), noMatch: w('noMatch'), create: w('create'),
    areaNotesNone: w('areaNotesNone'), areaInReach: w('areaInReach'), many: k.keys.ref('kind.many') as TKey,
  };
}
