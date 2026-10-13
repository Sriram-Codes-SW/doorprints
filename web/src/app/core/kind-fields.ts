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
import type { PriceType } from './models';
import { HOUSE_LABELS, type KindLabels } from './kind-labels';

/** The six extras types of the kind format (docs/03 §18.4 door 5). */
export type ExtraType = 'number' | 'money' | 'text' | 'enum' | 'date' | 'bool';

/** How often an amount is paid (door 7). */
export type Cadence = 'once' | 'day' | 'week' | 'month' | 'quarter' | 'year';

/** One typed extra of a kind, as the kind file places it (`extras` of `docs/schemas/kinds/<kind>.json`). */
export interface KindExtra {
  readonly id: string;
  readonly type: ExtraType;
  readonly section: string;
  readonly order: number;
  readonly integer?: boolean;
  readonly min?: number;
  readonly max?: number;
  readonly unit?: string;
  readonly cadences?: readonly Cadence[];
  readonly cadenceFrom?: 'money';
  /** Shown only while the candidate's money has one of these cadences, or none. */
  readonly forCadences?: readonly Cadence[];
  /** A text's most characters. */
  readonly maxLength?: number;
  /** An enum's options, in the kind file's order, each with its label key. */
  readonly options?: readonly { readonly id: string; readonly label: TKey }[];
  /**
   * Where the value lives on a house of the family before the reset, which the website's form still edits. A kind with
   * no such place (a school, S4b-BL-209) leaves it out: its values are the candidate's extras the house model does not
   * know (`HouseDto.extrasOther`, kept and written back by `core/candidate.ts`).
   */
  readonly was?: string;
}

/**
 * The control a typed extra is drawn with, the same on both stacks (`docs/schemas/kinds/kind-field-vectors.json`): an
 * integer field, a level (a floor: the field holds the level, a Basement switch its sign), whole rupees, a switch, a
 * calendar date, a line of text or a choice of options (text and enum since the schools kind, S4b-BL-209).
 */
export type ExtraControl = 'number' | 'level' | 'money' | 'bool' | 'date' | 'text' | 'enum';

export function controlOf(f: KindExtra): ExtraControl {
  if (f.type === 'number') return f.unit === 'level' ? 'level' : 'number';
  return f.type;
}

/** The cadence of a house's price type (`RENT` is `month`, `SALE` is `once`, none is none; door 7). */
export function cadenceOf(priceType: PriceType | null | undefined): Cadence | null {
  return priceType === 'RENT' ? 'month' : priceType === 'SALE' ? 'once' : null;
}

/**
 * A kind's typed extras and where they go (kinds PR 4, S4b-BL-208; docs/03 ADR-36 §18.8): the twin of `:shared`'s
 * `KindFields`. The renderer asks it for a section's extras for the candidate's money cadence, in the kind file's order,
 * with their labels from {@link KindLabels}; it holds a copy of the kind file's `extras`, as the resolver holds its
 * labels (`kind-fields.spec.ts` reads `house.json`, so the copy cannot drift).
 */
export class KindFields {
  constructor(
    readonly labels: KindLabels,
    private readonly all: readonly KindExtra[],
  ) {}

  get kind(): string {
    return this.labels.kind;
  }

  /** Every extra of the kind, in the kind file's order. */
  get extras(): readonly KindExtra[] {
    return this.all;
  }

  /** The extras of `section` shown for a candidate whose money has `cadence`, by `order`. */
  shown(section: string, cadence: Cadence | null): KindExtra[] {
    return this.all
      .filter((f) => f.section === section && this.labels.isVisible(`field.${f.id}`))
      .filter((f) => !cadence || !f.forCadences || f.forCadences.includes(cadence))
      .sort((a, b) => a.order - b.order);
  }

  /** The label key of an extra (the kind labels every field it has). */
  label(f: KindExtra): TKey {
    const key = this.labels.field(f.id)?.key;
    if (!key) throw new Error(`${this.kind} labels no ${f.id}`);
    return key;
  }
}

const MONEY_MAX = 1_000_000_000_000;
const MONTHS = { type: 'number', integer: true, min: 0, max: 120, unit: 'month', section: 'cost' } as const;
const RENT_ONLY = { forCadences: ['month'] } as const;

/** Houses (`docs/schemas/kinds/house.json`, `extras`). */
export const HOUSE_FIELDS = new KindFields(HOUSE_LABELS, [
  { id: 'bedrooms', type: 'number', integer: true, min: 0, unit: 'count', section: 'details', order: 50, was: 'bedrooms' },
  { id: 'areaSqft', type: 'number', integer: true, min: 1, max: 100000, unit: 'sqft', section: 'details', order: 60, was: 'areaSqft' },
  { id: 'floor', type: 'number', integer: true, min: -5, max: 200, unit: 'level', section: 'details', order: 70, was: 'floor' },
  { id: 'deposit', type: 'money', max: MONEY_MAX, cadences: ['once'], ...RENT_ONLY, section: 'cost', order: 10, was: 'cost.deposit' },
  { id: 'depositMonths', ...MONTHS, ...RENT_ONLY, order: 20, was: 'cost.depositMonths' },
  { id: 'maintenance', type: 'money', max: MONEY_MAX, cadences: ['month'], ...RENT_ONLY, section: 'cost', order: 30, was: 'cost.maintenance' },
  { id: 'maintenanceIncluded', type: 'bool', ...RENT_ONLY, section: 'cost', order: 40, was: 'cost.maintenanceIncluded' },
  { id: 'brokerage', type: 'money', max: MONEY_MAX, cadences: ['once'], section: 'cost', order: 50, was: 'cost.brokerage' },
  { id: 'brokerageMonths', ...MONTHS, order: 60, was: 'cost.brokerageMonths' },
  { id: 'lockInMonths', ...MONTHS, ...RENT_ONLY, order: 70, was: 'cost.lockInMonths' },
  { id: 'noticeMonths', ...MONTHS, ...RENT_ONLY, order: 80, was: 'cost.noticeMonths' },
  { id: 'availableFrom', type: 'date', section: 'cost', order: 90, was: 'cost.availableFrom' },
  { id: 'myOffer', type: 'money', max: MONEY_MAX, cadenceFrom: 'money', section: 'cost', order: 100, was: 'cost.myOffer' },
  { id: 'agreedPrice', type: 'money', max: MONEY_MAX, cadenceFrom: 'money', section: 'cost', order: 110, was: 'cost.agreedPrice' },
]);

/**
 * The built-in kind with this id, `undefined` for one this build does not have. The schools kind is not here: it is
 * reached only through the developer flag (`core/dev-kinds.ts`, S4b-BL-209).
 */
export function kindFields(kind: string): KindFields | undefined {
  return kind === HOUSE_FIELDS.kind ? HOUSE_FIELDS : undefined;
}
