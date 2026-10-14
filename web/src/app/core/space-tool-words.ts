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
  // S4b-BL-259 and S4b-BL-277: a school's page, tab titles and walk check (the house's words left on them).
  'title.newHouse': 'school.titleNew',
  'title.house': 'school.titleDetails',
  'title.map': 'school.titleMap',
  'house.namePlaceholder': 'school.namePlaceholder',
  'house.notFound': 'school.notFound',
  'house.nameRequired': 'school.nameRequired',
  'house.locationRequired': 'school.locationRequired',
  'house.locationUnset': 'school.locationUnset',
  'house.placeFound': 'school.placeFound',
  'house.mapLabel': 'school.mapLabel',
  'house.saveFirstVisits': 'school.saveFirstVisits',
  'house.saveFirstPhotos': 'school.saveFirstPhotos',
  'listingFill.hint': 'school.listingHint',
  'ai.disclosure': 'school.aiDisclosure',
  'ai.disclosureOwnKeyHost': 'school.aiDisclosureOwnKeyHost',
  'map.fitTitle': 'school.fitTitle',
  'trace.house.empty': 'school.walksEmpty',
  'trace.here.buttonHouse': 'school.walkTitle',
  'trace.here.titleHouse': 'school.walkTitle',
  'trace.here.placeHouse': 'school.walkPlace',
  'trace.here.labelHouse': 'school.walkLabel',
  'trace.here.approxHouse': 'school.walkApprox',
  // S4b-BL-256: the Areas and Places pages and the area cards, read in the space on screen.
  'areas.intro': 'school.areasIntro',
  'areas.dataBody': 'school.areasDataBody',
  'areas.confirmDelete': 'school.areasConfirmDelete',
  'areaNotes.empty': 'school.areaNotesEmpty',
  'places.dataBody': 'school.placesDataBody',
  'places.intro': 'school.placesIntro',
  'places.empty': 'school.placesEmpty',
  'places.confirmDelete': 'school.placesConfirmDelete',
  // S4b-BL-256 and S4b-BL-266: the words of a school's readable copy (cover, options, ranking, empty line).
  'exp.title': 'school.expTitle',
  'exp.counts': 'school.expCounts',
  'exp.scopeAll': 'school.expScopeAll',
  'exp.scopeShortlisted': 'school.expScopeShortlisted',
  'exp.scopeSelected': 'school.expScopeSelected',
  'exp.rejectedIncluded': 'school.expRejectedIncluded',
  'exp.rejectedLeftOut': 'school.expRejectedLeftOut',
  'exp.photosAll': 'school.expPhotosAll',
  'exp.photosShortlisted': 'school.expPhotosShortlisted',
  'exp.colHouse': 'school.expColSchool',
  'exp.noHouses': 'school.expNone',
};

const TABLES: Readonly<Record<string, Readonly<Partial<Record<TKey, TKey>>>>> = { school: SCHOOL };

/** The key to show for `key` in the space of `kind`: the kind's own where it has one, else `key` (the house's). */
export function toolWord(kind: string | null | undefined, key: TKey): TKey {
  const table = kind && Object.prototype.hasOwnProperty.call(TABLES, kind) ? TABLES[kind] : undefined;
  return table?.[key] ?? key;
}
