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

import type { Lang } from '../i18n/languages';
import type { ExportStringKey } from './export-strings';

/**
 * The words inside a table that name the candidate, in a copy that speaks in another kind (kinds PR 16, S4b-BL-266; docs/03
 * §18.9): the headings of the Schools table, the Visits and Photos tables' *School* and *School id* columns, the cover's
 * scope lines and the *Website* of a school's own link. They replace the house's words of `export-strings.ts` key for key, so a copy of houses is untouched
 * (`house-pins.spec.ts`) and the phone's `ExportStrings.kt` stays the contract of the keys. hi, ta and te are under review
 * (owner rule); the nouns are those of the kind's own labels (`school.many` in the app's dictionaries).
 */
export type KindExportWords = Readonly<Record<Lang, Readonly<Partial<Record<ExportStringKey, string>>>>>;

const SCHOOL: KindExportWords = {
  en: {
    'doc.title': 'Doorprints — my schools',
    'cover.houses': 'Schools',
    'cover.scope': 'Schools included',
    'cover.rejected': 'Rejected schools included',
    'scope.SELECTED': 'Selected schools',
    'table.houses': 'Schools',
    'col.houseId': 'School id',
    'col.house': 'School',
    'col.label': 'School',
    'col.listingUrl': 'Website',
    'col.houses': 'Schools',
  },
  hi: {
    'doc.title': 'Doorprints — मेरे स्कूल',
    'cover.houses': 'स्कूल',
    'cover.scope': 'शामिल स्कूल',
    'cover.rejected': 'अस्वीकृत स्कूल शामिल',
    'scope.SELECTED': 'चयनित स्कूल',
    'table.houses': 'स्कूल',
    'col.houseId': 'स्कूल आईडी',
    'col.house': 'स्कूल',
    'col.label': 'स्कूल',
    'col.listingUrl': 'वेबसाइट',
    'col.houses': 'स्कूल',
  },
  ta: {
    'doc.title': 'Doorprints — என் பள்ளிகள்',
    'cover.houses': 'பள்ளிகள்',
    'cover.scope': 'சேர்க்கப்பட்ட பள்ளிகள்',
    'cover.rejected': 'நிராகரிக்கப்பட்ட பள்ளிகளும் உள்ளன',
    'scope.SELECTED': 'தேர்ந்தெடுத்த பள்ளிகள்',
    'table.houses': 'பள்ளிகள்',
    'col.houseId': 'பள்ளி அடையாளம்',
    'col.house': 'பள்ளி',
    'col.label': 'பள்ளி',
    'col.listingUrl': 'இணையதளம்',
    'col.houses': 'பள்ளிகள்',
  },
  te: {
    'doc.title': 'Doorprints — నా పాఠశాలలు',
    'cover.houses': 'పాఠశాలలు',
    'cover.scope': 'చేర్చిన పాఠశాలలు',
    'cover.rejected': 'తిరస్కరించిన పాఠశాలలు కూడా ఉన్నాయి',
    'scope.SELECTED': 'ఎంచుకున్న పాఠశాలలు',
    'table.houses': 'పాఠశాలలు',
    'col.houseId': 'పాఠశాల ఐడీ',
    'col.house': 'పాఠశాల',
    'col.label': 'పాఠశాల',
    'col.listingUrl': 'వెబ్‌సైట్',
    'col.houses': 'పాఠశాలలు',
  },
};

const OVERLAYS: Readonly<Record<string, KindExportWords>> = { school: SCHOOL };

/** The words of `kind` for the keys of {@link ExportStringKey} it names, in `lang`; none for the house or an unknown kind. */
export function kindExportWords(kind: string | undefined, lang: Lang): Readonly<Partial<Record<ExportStringKey, string>>> | undefined {
  return kind && Object.prototype.hasOwnProperty.call(OVERLAYS, kind) ? OVERLAYS[kind][lang] : undefined;
}
