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

import { capabilitiesOf } from '../core/kind-capabilities';
import type { KindExtra, KindFields } from '../core/kind-fields';
import type { HouseDto } from '../core/models';

/**
 * The list's filter by a kind's typed extra (S4b-BL-265; docs/03 §18.10, §18.27): one chip row per enum or switch the kind has with
 * no house field, the choice of an option (or Yes/No for a switch) or nothing, and, since §18.27, a minimum and a maximum for a number
 * or an amount. A house has none, so its list, its address and its saved state are as they were. The Kotlin twin is `KindFilter`;
 * `docs/schemas/kinds/kind-filter-vectors.json` holds both to the same rules.
 *
 * The value is a map of extra id to the chosen option id (`yes` or `no` for a switch) or to a range text `min:max` (either end may be
 * left out: `2:`, `:5`, `2:5`), `null` for a row left on *Any*. A row is written to the address as `x.<id>=<value>` and only while it
 * is set, so the house's address has no new key.
 */
export type ExtraFilter = Readonly<Record<string, string | null>>;

/**
 * The extras of `fields` the list can be filtered by: the ones the kind file names in `capabilities.filters` (kinds PR 30, docs/03
 * §18.24), in that order, that are enums, switches, numbers or amounts of the kind. A kind that names none, the house, has no rows.
 */
export function filterableExtras(fields: KindFields): KindExtra[] {
  const own = new Map(fields.others().map((f) => [f.id, f]));
  return capabilitiesOf(fields.kind).filters.flatMap((id) => {
    const f = own.get(id);
    return f && (f.type === 'enum' || f.type === 'bool' || isRangeExtra(f)) ? [f] : [];
  });
}

/** Whether the row of `f` is a range (a number or an amount) rather than a choice. */
export function isRangeExtra(f: KindExtra): boolean {
  return f.type === 'number' || f.type === 'money';
}

/** The choices of a filterable enum or switch: an enum's option ids, or `yes` and `no`. A range row has none. */
export function filterChoices(f: KindExtra): string[] {
  if (isRangeExtra(f)) return [];
  return f.type === 'bool' ? ['yes', 'no'] : (f.options ?? []).map((o) => o.id);
}

const END = String.raw`-?\d{1,12}(?:\.\d{1,6})?`;
const RANGE = new RegExp(`^(${END})?:(${END})?$`);

/**
 * A range text read: its minimum and maximum (`null` for an end left out), or `null` when the text is not a range (no end at all, a
 * minimum above the maximum, a number with more than 12 digits or 6 decimals).
 */
export function parseRange(text: string): { min: number | null; max: number | null } | null {
  const m = RANGE.exec(text);
  if (!m || (m[1] === undefined && m[2] === undefined)) return null;
  const min = m[1] === undefined ? null : Number(m[1]);
  const max = m[2] === undefined ? null : Number(m[2]);
  return min !== null && max !== null && min > max ? null : { min, max };
}

/**
 * The range text of the two ends a person typed (empty text is an end left out), the ends swapped when the minimum is above the
 * maximum; `null` for no range (both left out, or a text that is not a number).
 */
export function rangeText(min: string, max: string): string | null {
  let lo = min.trim();
  let hi = max.trim();
  const a = lo === '' ? null : Number(lo);
  const b = hi === '' ? null : Number(hi);
  if ((a !== null && !Number.isFinite(a)) || (b !== null && !Number.isFinite(b))) return null;
  if (a !== null && b !== null && a > b) [lo, hi] = [hi, lo];
  const text = `${lo}:${hi}`;
  return parseRange(text) ? text : null;
}

/** How many rows are set. */
export function extraFiltersOn(filter: ExtraFilter): number {
  return Object.values(filter).filter((v) => !!v).length;
}

/** Whether `want` is a value the row of `f` can hold: one of its choices, or a range text. */
function wanted(f: KindExtra, want: string): boolean {
  return isRangeExtra(f) ? parseRange(want) !== null : filterChoices(f).includes(want);
}

/** The rows set in an address (`x.<id>`, the `get` of its parameters); a value the kind does not offer is no row. */
export function readExtraFilter(get: (name: string) => string | null, fields: KindFields): ExtraFilter {
  const out: Record<string, string> = {};
  for (const f of filterableExtras(fields)) {
    const v = get(`x.${f.id}`);
    if (v !== null && wanted(f, v)) out[f.id] = v;
  }
  return out;
}

/** The number a candidate holds for a range row: a number extra's number, or an amount extra's amount; `null` for no value (unset, or not of the extra's type). */
function numberOf(f: KindExtra, raw: unknown): number | null {
  const v = f.type === 'money' && typeof raw === 'object' && raw !== null && !Array.isArray(raw) ? (raw as { amount?: unknown }).amount : f.type === 'number' ? raw : null;
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

/**
 * Whether a candidate passes every set row: an enum equals the option, a switch is on for `yes` and off for `no`, a number or an
 * amount is within the range (an amount is compared as stored, whatever its cadence). A candidate with no value there (not set, or
 * not one the kind offers) passes no set row: *No* means a switch somebody turned off. A row the kind does not have, or a value it
 * does not offer, is ignored.
 */
export function extraFilterMatches(filter: ExtraFilter, fields: KindFields, h: Pick<HouseDto, 'extrasOther'>): boolean {
  for (const f of filterableExtras(fields)) {
    const want = filter[f.id];
    if (!want || !wanted(f, want)) continue;
    const raw = h.extrasOther?.[f.id];
    if (isRangeExtra(f)) {
      const range = parseRange(want)!;
      const n = numberOf(f, raw);
      if (n === null || (range.min !== null && n < range.min) || (range.max !== null && n > range.max)) return false;
      continue;
    }
    const ok = f.type === 'bool' ? (want === 'yes' ? raw === true : raw === false) : raw === want;
    if (!ok) return false;
  }
  return true;
}
