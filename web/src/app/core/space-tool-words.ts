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

/**
 * The words of Compare, Ask, Plan, the copies and the share target in a space other than the house (kinds PR B,
 * S4b-BL-279; docs/03 §18.13). A page asks for its house key and gets the kind's own where the kind has one, so the
 * house's words are exactly the keys they were: `toolWord('house', 'compare.title')` is `'compare.title'`. The space's
 * list and map words are `core/space-words.ts` (S4b-BL-277); moving both into the kind files is S4b-BL-278.
 */
const SCHOOL: Readonly<Partial<Record<TKey, TKey>>> = {
  'compare.title': 'school.compareTitle',
  'compare.empty': 'school.compareEmpty',
  'compare.addSome': 'school.compareAdd',
  'compare.pickerLabel': 'school.comparePicker',
  'compare.choose': 'school.compareChoose',
  'compare.pickMore': 'school.comparePickMore',
  'compare.caption': 'school.compareCaption',
  'compare.search': 'school.compareSearch',
  'compare.onlyOne': 'school.compareOnlyOne',
  'compare.footnote': 'school.compareFootnote',
  'ask.title': 'school.askTitle',
  'ask.intro': 'school.askIntro',
  'ask.placeholder': 'school.askPlaceholder',
  'ask.notGrounded': 'school.askNotGrounded',
  'ask.sources': 'school.askSources',
  'plan.title': 'school.planTitle',
  'plan.intro': 'school.planIntro',
  'plan.placeholder': 'school.planPlaceholder',
  'plan.noStops': 'school.planNoStops',
  'data.scope': 'school.dataScope',
  'data.scopeAll': 'school.dataScopeAll',
  'data.includeRejected': 'school.dataIncludeRejected',
  'data.photosShortlisted': 'school.dataPhotosShortlisted',
  'data.empty': 'school.dataEmpty',
  'data.emptyAction': 'school.dataEmptyAction',
  'data.noShortlisted': 'school.dataNoShortlisted',
  'data.noneMatch': 'school.dataNoneMatch',
  'data.countHouses': 'data.countKind',
  'data.preview': 'data.previewKind',
  'share.create': 'school.shareCreate',
  'share.hint': 'school.shareHint',
};

const TABLES: Readonly<Record<string, Readonly<Partial<Record<TKey, TKey>>>>> = { school: SCHOOL };

/** The key to show for `key` in the space of `kind`: the kind's own where it has one, else `key` (the house's). */
export function toolWord(kind: string | null | undefined, key: TKey): TKey {
  const table = kind && Object.prototype.hasOwnProperty.call(TABLES, kind) ? TABLES[kind] : undefined;
  return table?.[key] ?? key;
}
