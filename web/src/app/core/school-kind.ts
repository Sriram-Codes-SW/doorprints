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
import { KindFields } from './kind-fields';
import { KindKeys, KindLabels } from './kind-labels';

/**
 * Schools, the second built-in kind (kinds PR 5, S4b-BL-209; docs/03 ADR-36 §18.9): a copy of
 * `docs/schemas/kinds/school.json`'s web keys and extras, as `HOUSE_LABELS` and `HOUSE_FIELDS` are of `house.json`
 * (`school-kind-file.spec.ts` reads the file, so the copy cannot drift). Hidden: reached only through the developer
 * flag (`core/dev-kinds.ts`); `kindLabels` and `kindFields` still know only the house. A place the school leaves out
 * (its money, the broker, the rooms, questions, viewings and moving in) has no key here, so the resolver
 * says it is not shown.
 */

const FIELDS = {
  label: 'school.name', status: 'house.status', rating: 'house.rating', address: 'house.address', street: 'house.street',
  locality: 'house.locality', contactName: 'house.contactName', contactPhone: 'house.contactPhone',
  listingUrl: 'school.website', notes: 'house.notes', board: 'school.board', medium: 'school.medium',
  grades: 'school.grades', commuteMinutes: 'school.commuteMinutes', busService: 'school.busService',
  fees: 'school.feesPerYear', admissionFee: 'school.admissionFee',
} as const satisfies Record<string, TKey>;
const LOCATION = { location: 'house.location', locationSource: 'house.approx' } as const satisfies Record<string, TKey>;
const STATUSES = ['NEW', 'SHORTLISTED', 'REJECTED', 'TAKEN', 'NOT_CHOSEN'] as const;
/** The school's checklist (`school.json`, `checklist`, kinds PR 27, docs/03 §18.21): eight items scored 0 to 5, in the file's order. */
const CHECKS = ['dropoff', 'grounds', 'sanitation', 'teachers', 'classsize', 'homework', 'activities', 'safety'] as const;

export const SCHOOL_LABELS = new KindLabels({
  kind: 'school',
  keys: {
    'kind.new': 'school.newTitle', 'kind.many': 'school.many', 'kind.delete': 'school.delete',
    ...Object.fromEntries(STATUSES.map((s) => [`status.${s}`, `status.${s}` as const])),
    'section.details': 'house.details', 'section.cost': 'school.fees', 'section.areaNotes': 'areaNotes.heading',
    'section.distances': 'distances.heading', 'section.location': 'house.location', 'section.walks': 'trace.house.title',
    'section.checklist': 'house.checklist', 'section.visits': 'house.visits', 'section.photos': 'house.photos',
    ...Object.fromEntries(CHECKS.map((c) => [`check.${c}`, `school.check.${c}` as const])),
    ...Object.fromEntries(Object.entries({ ...FIELDS, ...LOCATION }).map(([id, key]) => [`field.${id}`, key])),
  },
  byCadence: {},
  sections: {
    ...Object.fromEntries(Object.keys(FIELDS).map((id) => [id, 'details'])),
    fees: 'cost', admissionFee: 'cost', location: 'location', locationSource: 'location',
  },
});

/** The school's keys for templates. */
export const SCHOOL_KEYS = new KindKeys(SCHOOL_LABELS);

const MONEY_MAX = 1_000_000_000_000;
/** Every extra is searched (kinds PR 6, S4b-BL-210: school.json's `search`). */
const EXTRA_IDS = ['board', 'medium', 'grades', 'commuteMinutes', 'busService', 'fees', 'admissionFee'];
const OTHER = { id: 'OTHER', label: 'school.option.OTHER' } as const;
const option = <K extends string>(prefix: string, id: K) => ({ id, label: `${prefix}.${id}` as TKey });

/** The school's extras (`school.json`, `extras`); no `was`: their values are the candidate's other extras. */
export const SCHOOL_FIELDS = new KindFields(SCHOOL_LABELS, [
  { id: 'board', type: 'enum', section: 'details', order: 50,
    options: [...(['CBSE', 'ICSE', 'STATE', 'IB', 'IGCSE'] as const).map((id) => option('school.board', id)), OTHER] },
  { id: 'medium', type: 'enum', section: 'details', order: 60,
    options: [...(['ENGLISH', 'HINDI', 'REGIONAL'] as const).map((id) => option('school.medium', id)), OTHER] },
  { id: 'grades', type: 'text', maxLength: 40, section: 'details', order: 70 },
  { id: 'commuteMinutes', type: 'number', integer: true, min: 0, max: 300, unit: 'minute', better: 'lower', section: 'details', order: 72 },
  { id: 'busService', type: 'bool', section: 'details', order: 74 },
  { id: 'fees', type: 'money', max: MONEY_MAX, cadences: ['year'], better: 'lower', section: 'cost', order: 10 },
  { id: 'admissionFee', type: 'money', max: MONEY_MAX, cadences: ['once'], better: 'lower', section: 'cost', order: 20 },
], ['label', 'address', 'street', 'locality', 'notes', 'contactName', 'areaNotes', ...EXTRA_IDS]);
