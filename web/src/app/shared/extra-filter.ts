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

import type { KindExtra, KindFields } from '../core/kind-fields';
import type { HouseDto } from '../core/models';

/**
 * The list's filter by a kind's typed extra (S4b-BL-265; docs/03 §18.10): one chip row per enum or switch the kind has with
 * no house field, the choice of an option (or Yes/No for a switch) or nothing. A house has none, so its list, its address and
 * its saved state are as they were. The Kotlin twin is `KindFilter`; `docs/schemas/kinds/kind-filter-vectors.json` holds both
 * to the same rules.
 *
 * The value is a map of extra id to the chosen option id (`yes` or `no` for a switch), `null` for a row left on *Any*. A row is
 * written to the address as `x.<id>=<option>` and only while it is set, so the house's address has no new key.
 */
export type ExtraFilter = Readonly<Record<string, string | null>>;

/** The extras of `fields` the list can be filtered by, in the kind file's order: enums and switches. */
export function filterableExtras(fields: KindFields): KindExtra[] {
  return fields.others().filter((f) => f.type === 'enum' || f.type === 'bool');
}

/** The choices of a filterable extra: an enum's option ids, or `yes` and `no` for a switch. */
export function filterChoices(f: KindExtra): string[] {
  return f.type === 'bool' ? ['yes', 'no'] : (f.options ?? []).map((o) => o.id);
}

/** How many rows are set. */
export function extraFiltersOn(filter: ExtraFilter): number {
  return Object.values(filter).filter((v) => !!v).length;
}

/** Reads the rows from the address; an extra the kind does not filter by and a choice it does not offer are ignored. */
export function readExtraFilter(get: (name: string) => string | null, fields: KindFields): ExtraFilter {
  const out: Record<string, string> = {};
  for (const f of filterableExtras(fields)) {
    const v = get(`x.${f.id}`);
    if (v !== null && filterChoices(f).includes(v)) out[f.id] = v;
  }
  return out;
}

/**
 * Whether `h` passes every set row: an enum equals the option, a switch is on for `yes` and off for `no`. A candidate that
 * has no value there (not set, or not one the kind offers) passes no set row: *No* means a switch somebody turned off.
 */
export function extraFilterMatches(filter: ExtraFilter, fields: KindFields, h: Pick<HouseDto, 'extrasOther'>): boolean {
  for (const f of filterableExtras(fields)) {
    const want = filter[f.id];
    if (!want || !filterChoices(f).includes(want)) continue;
    const raw = h.extrasOther?.[f.id];
    const ok = f.type === 'bool' ? (want === 'yes' ? raw === true : raw === false) : raw === want;
    if (!ok) return false;
  }
  return true;
}
