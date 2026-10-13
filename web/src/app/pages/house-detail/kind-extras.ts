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

// The typed-extras renderer of the house page (kinds PR 4, S4b-BL-208; docs/03 ADR-36 §18.8): what the template's one
// `extra` template draws for each extra of a section, and in which rows, from the kind's fields (`core/kind-fields.ts`).
// It replaced the hand-written BHK, area, floor and cost fields; `kind-field-vectors.json` and the form's golden file
// (`house-form-fields.spec.ts`) hold it to what they drew. The page owns the draft; this changes it only as the fields
// did: a typed value in place (as `[(ngModel)]` did), a switch through `patch`.
import type { TKey } from '../../i18n/en';
import type { HouseDto } from '../../core/models';
import { HOUSE_FIELDS, cadenceOf, controlOf, type ExtraControl, type KindExtra, type KindFields } from '../../core/kind-fields';

/** One extra as the website's form draws it. */
export interface WebExtra {
  readonly id: string;
  readonly control: ExtraControl;
  /** The element id: the hand-written form's (other pages, the guide's tests and the live UI test find them by it). */
  readonly domId: string;
  readonly label: TKey;
  /** After the label: whole rupees say so, as the hand-written labels did. */
  readonly suffix: string;
  /** In the money's row of the Details card, beside the price and its type. */
  readonly narrow: boolean;
  readonly min: string | null;
  readonly max: string | null;
  /** Where the form keeps the value on the house (`was` of the kind file: `bedrooms`, `cost.deposit`). */
  readonly path: readonly string[];
  /** A level's Basement switch. */
  readonly switchId: string | null;
}

/** The ids the hand-written form gave its fields; an extra a later kind adds gets `extra-<id>`. */
const DOM_IDS: Readonly<Record<string, string>> = {
  bedrooms: 'house-bhk', areaSqft: 'house-area', floor: 'house-floor', deposit: 'cost-deposit',
  depositMonths: 'cost-deposit-months', maintenance: 'cost-maintenance', maintenanceIncluded: 'cost-maintenance-included',
  brokerage: 'cost-brokerage', brokerageMonths: 'cost-brokerage-months', lockInMonths: 'cost-lock-in',
  noticeMonths: 'cost-notice', availableFrom: 'cost-available', myOffer: 'cost-offer', agreedPrice: 'cost-agreed',
};

/** The section of the candidate's own money (house.json `core`): its extras share the price's row. */
export const MONEY_SECTION = 'details';

function webExtra(fields: KindFields, f: KindExtra): WebExtra {
  const control = controlOf(f);
  const domId = DOM_IDS[f.id] ?? `extra-${f.id}`;
  const path = f.was.split('.');
  // A level is the house's floor: its sign lives in HouseFloor's Basement switch, which reads `floor`.
  if (control === 'level' && f.was !== 'floor') throw new Error(`the level extra ${f.id} is not the floor`);
  const bound = (v: number | undefined): string | null => (v === undefined ? null : String(v));
  return {
    id: f.id,
    control,
    domId,
    label: fields.label(f),
    suffix: control === 'money' ? ' (₹)' : '',
    narrow: f.section === MONEY_SECTION,
    min: control === 'money' || control === 'level' ? '0' : control === 'number' ? bound(f.min) : null,
    max: control === 'number' || control === 'level' ? bound(f.max) : null,
    path,
    switchId: control === 'level' ? `${domId}-basement` : null,
  };
}

/** What the extras need from the page. */
export interface KindExtrasDeps {
  /** The page's one way to change the draft: merges the fields in and marks the page dirty. */
  patch: (changes: Partial<HouseDto>) => void;
}

export class KindExtras {
  private readonly byId: ReadonlyMap<string, WebExtra>;

  constructor(
    private readonly deps: KindExtrasDeps,
    private readonly fields: KindFields = HOUSE_FIELDS,
  ) {
    this.byId = new Map(fields.extras.map((f) => [f.id, webExtra(fields, f)]));
  }

  /** The extras of `section` the house shows, in the kind file's order. */
  of(section: string, d: HouseDto): WebExtra[] {
    return this.fields.shown(section, cadenceOf(d.priceType)).map((f) => this.byId.get(f.id)!);
  }

  /**
   * The rows of `section`: the money's section keeps its extras in one row (the price's); elsewhere two fields go side
   * by side and a date stands alone, as the Cost card did.
   */
  rows(section: string, d: HouseDto): WebExtra[][] {
    const shown = this.of(section, d);
    if (section === MONEY_SECTION) return shown.length ? [shown] : [];
    const rows: WebExtra[][] = [];
    for (const f of shown) {
      const last = rows[rows.length - 1];
      if (f.control !== 'date' && last && last.length === 1 && last[0].control !== 'date') last.push(f);
      else rows.push([f]);
    }
    return rows;
  }

  /** The value the form holds for `f`. */
  value(d: HouseDto, f: WebExtra): unknown {
    return this.holder(d, f)?.[f.path[f.path.length - 1]];
  }

  /** A typed value, written in place as the field's `[(ngModel)]` did (the page's input listener marks it dirty). */
  set(d: HouseDto, f: WebExtra, value: unknown): void {
    const holder = this.holder(d, f);
    if (holder) holder[f.path[f.path.length - 1]] = value;
  }

  /** A switch: through `patch`, as *Included in the rent* was. */
  setBool(d: HouseDto, f: WebExtra, event: Event): void {
    const on = (event.target as HTMLInputElement).checked;
    const key = f.path[f.path.length - 1];
    if (f.path.length === 1) this.deps.patch({ [key]: on } as Partial<HouseDto>);
    else this.deps.patch({ [f.path[0]]: { ...(this.holder(d, f) ?? {}), [key]: on } } as Partial<HouseDto>);
  }

  private holder(d: HouseDto, f: WebExtra): Record<string, unknown> | undefined {
    let o: unknown = d;
    for (const p of f.path.slice(0, -1)) o = (o as Record<string, unknown> | undefined)?.[p];
    return o as Record<string, unknown> | undefined;
  }
}
