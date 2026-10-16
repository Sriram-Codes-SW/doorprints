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

import { en, type TKey } from '../i18n/en';
import type { HouseDto } from '../core/models';
import type { KindExtra, KindFields } from '../core/kind-fields';
import { rupees } from '../export/deterministic';

/**
 * A candidate's typed extras as search and the readable copies read them (kinds PR 6, S4b-BL-210; docs/03 ADR-36
 * §18.10): the extras the kind has no `was` for (`KindFields.others`), held to their type as the house page holds them
 * (`kind-extras.ts`): a number, a money object with an amount, a switch, an option the kind offers, a text or a date (a
 * non-empty string). A wrong value is no value. A house has no such extra, so it reads nothing here.
 * The twin of `:shared`'s `KindValues`; `docs/schemas/kinds/kind-search-vectors.json` holds both to the same words.
 */
export type KindValue =
  | { readonly field: KindExtra; readonly type: 'money'; readonly amount: number }
  | { readonly field: KindExtra; readonly type: 'number'; readonly value: number }
  | { readonly field: KindExtra; readonly type: 'bool'; readonly value: boolean }
  | { readonly field: KindExtra; readonly type: 'enum'; readonly label: TKey }
  | { readonly field: KindExtra; readonly type: 'text'; readonly value: string };

/** The app's translation of a key (the person's language, or a copy's). */
export type Words = (key: TKey) => string;

/** The values `h` holds for the kind's other extras (only those `search` names when `searched`), in the kind's order. */
export function kindValues(fields: KindFields, h: Pick<HouseDto, 'extrasOther'>, searched = false): KindValue[] {
  const out: KindValue[] = [];
  for (const field of fields.others(searched)) {
    const value = valueOf(field, h.extrasOther?.[field.id]);
    if (value) out.push(value);
  }
  return out;
}

function valueOf(field: KindExtra, raw: unknown): KindValue | null {
  switch (field.type) {
    case 'money': {
      const m = raw as { amount?: unknown } | null;
      const ok = typeof m === 'object' && m !== null && typeof m.amount === 'number' && Number.isFinite(m.amount);
      return ok ? { field, type: 'money', amount: m.amount as number } : null;
    }
    case 'number':
      return typeof raw === 'number' && Number.isFinite(raw) ? { field, type: 'number', value: raw } : null;
    case 'bool':
      return typeof raw === 'boolean' ? { field, type: 'bool', value: raw } : null;
    case 'enum': {
      const option = field.options?.find((o) => o.id === raw);
      return option ? { field, type: 'enum', label: option.label } : null;
    }
    default:
      return typeof raw === 'string' && raw !== '' ? { field, type: 'text', value: raw } : null;
  }
}

/**
 * The words a candidate's extras add to the list search: an option's label and a switch's label (only when it is on)
 * in English and, when the app's language is another and its words differ, in that language too (as the floor is
 * found, S4b-BL-104 b); a number's digits; a text as typed; money as its digits and as rupees (`₹96,000`).
 */
export function kindSearchWords(fields: KindFields, h: Pick<HouseDto, 'extrasOther'>, t: Words): string[] {
  const out: string[] = [];
  const both = (key: TKey) => {
    out.push(en[key]);
    if (t(key) !== en[key]) out.push(t(key));
  };
  for (const v of kindValues(fields, h, true)) {
    if (v.type === 'enum') both(v.label);
    else if (v.type === 'bool') {
      if (v.value) both(fields.label(v.field));
    } else if (v.type === 'money') out.push(String(v.amount), rupees(v.amount));
    else out.push(String(v.value));
  }
  return out;
}
