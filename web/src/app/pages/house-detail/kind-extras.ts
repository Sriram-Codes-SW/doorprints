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
// did: a typed value in place (as `[(ngModel)]` did), a switch through `patch`. Since kinds PR 5 (S4b-BL-209) it also
// draws a kind whose values the house model has no place for (the hidden schools kind): they are the candidate's other
// extras (`HouseDto.extrasOther`, which `core/candidate.ts` keeps and writes back), each held to its type here.
import type { TKey } from '../../i18n/en';
import type { HouseDto } from '../../core/models';
import { country } from '../../shared/country/country-pack';
import { HOUSE_FIELDS, cadenceOf, controlOf, type Cadence, type ExtraControl, type KindExtra, type KindFields } from '../../core/kind-fields';

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
  /** A text's most characters. */
  readonly maxLength: number | null;
  /** An enum's options, each with its label key. */
  readonly options: readonly { readonly id: string; readonly label: TKey }[];
  /** Where the form keeps the value on the house (`was` of the kind file: `bedrooms`, `cost.deposit`). */
  readonly path: readonly string[];
  /** The value is one of the candidate's other extras (`extrasOther.<id>`), written in the extras' own shape. */
  readonly other: boolean;
  /** A money extra's cadence there: the first of the cadences the kind file fixes for it. */
  readonly cadence: Cadence | null;
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

/** The section of the candidate's own money (house.json `core`): its extras share the price's row, when it is shown. */
export const MONEY_SECTION = 'details';

function webExtra(fields: KindFields, f: KindExtra): WebExtra {
  const control = controlOf(f);
  const domId = DOM_IDS[f.id] ?? `extra-${f.id}`;
  const path = f.was ? f.was.split('.') : ['extrasOther', f.id];
  // A level is the house's floor: its sign lives in HouseFloor's Basement switch, which reads `floor`.
  if (control === 'level' && f.was !== 'floor') throw new Error(`the level extra ${f.id} is not the floor`);
  const bound = (v: number | undefined): string | null => (v === undefined ? null : String(v));
  return {
    id: f.id,
    control,
    domId,
    label: fields.label(f),
    suffix: control === 'money' ? ` (${country().money.symbol})` : '',
    narrow: f.section === MONEY_SECTION && fields.labels.isVisible('field.money'),
    min: control === 'money' || control === 'level' ? '0' : control === 'number' ? bound(f.min) : null,
    max: control === 'number' || control === 'level' ? bound(f.max) : null,
    maxLength: f.maxLength ?? null,
    options: f.options ?? [],
    path,
    other: !f.was,
    cadence: control === 'money' && !f.was ? (f.cadences?.[0] ?? null) : null,
    switchId: control === 'level' ? `${domId}-basement` : null,
  };
}

/** What the extras need from the page. */
export interface KindExtrasDeps {
  /** The page's one way to change the draft: merges the fields in and marks the page dirty. */
  patch: (changes: Partial<HouseDto>) => void;
}

type OtherExtras = NonNullable<HouseDto['extrasOther']>;

export class KindExtras {
  private readonly byId: ReadonlyMap<string, WebExtra>;
  private readonly moneyShown: boolean;

  constructor(
    private readonly deps: KindExtrasDeps,
    private readonly fields: KindFields = HOUSE_FIELDS,
  ) {
    this.byId = new Map(fields.extras.map((f) => [f.id, webExtra(fields, f)]));
    this.moneyShown = fields.labels.isVisible('field.money');
  }

  /** The extras of `section` the house shows, in the kind file's order. */
  of(section: string, d: HouseDto): WebExtra[] {
    return this.fields.shown(section, cadenceOf(d.priceType)).map((f) => this.byId.get(f.id)!);
  }

  /**
   * The rows of `section`: the money's section keeps its extras in one row (the price's) while the kind shows the
   * money; elsewhere two fields go side by side and a date stands alone, as the Cost card did.
   */
  rows(section: string, d: HouseDto): WebExtra[][] {
    const shown = this.of(section, d);
    if (section === MONEY_SECTION && this.moneyShown) return shown.length ? [shown] : [];
    const rows: WebExtra[][] = [];
    for (const f of shown) {
      const last = rows[rows.length - 1];
      if (f.control !== 'date' && last && last.length === 1 && last[0].control !== 'date') last.push(f);
      else rows.push([f]);
    }
    return rows;
  }

  /** The value the form holds for `f`; one of the other extras only when it has the extra's type. */
  value(d: HouseDto, f: WebExtra): unknown {
    const raw = this.holder(d, f)?.[f.path[f.path.length - 1]];
    return f.other ? shownValue(f, raw) : raw;
  }

  /** A typed value, written in place as the field's `[(ngModel)]` did (the page's input listener marks it dirty). */
  set(d: HouseDto, f: WebExtra, value: unknown): void {
    if (f.other) {
      d.extrasOther = withOther(d.extrasOther, f, value);
      return;
    }
    const holder = this.holder(d, f);
    if (holder) holder[f.path[f.path.length - 1]] = value;
  }

  /** A switch: through `patch`, as *Included in the rent* was. */
  setBool(d: HouseDto, f: WebExtra, event: Event): void {
    const on = (event.target as HTMLInputElement).checked;
    const key = f.path[f.path.length - 1];
    if (f.other) this.deps.patch({ extrasOther: withOther(d.extrasOther, f, on) });
    else if (f.path.length === 1) this.deps.patch({ [key]: on } as Partial<HouseDto>);
    else this.deps.patch({ [f.path[0]]: { ...(this.holder(d, f) ?? {}), [key]: on } } as Partial<HouseDto>);
  }

  private holder(d: HouseDto, f: WebExtra): Record<string, unknown> | undefined {
    let o: unknown = d;
    for (const p of f.path.slice(0, -1)) o = (o as Record<string, unknown> | undefined)?.[p];
    return o as Record<string, unknown> | undefined;
  }
}

/** One renderer per kind, each made once: a new candidate's kind can change while its page is open (the kind picker). */
export function extrasFor(deps: KindExtrasDeps): (fields: KindFields) => KindExtras {
  const made = new Map<KindFields, KindExtras>();
  return (fields) => {
    if (!made.has(fields)) made.set(fields, new KindExtras(deps, fields));
    return made.get(fields)!;
  };
}

/** What a field shows of a stored other extra: the value when it has the extra's type (an option it offers), else none. */
function shownValue(f: WebExtra, raw: unknown): unknown {
  switch (f.control) {
    case 'money':
      return isMoney(raw) ? raw.amount : null;
    case 'number':
      return typeof raw === 'number' && Number.isFinite(raw) ? raw : null;
    case 'bool':
      return typeof raw === 'boolean' ? raw : null;
    case 'enum':
      return f.options.some((o) => o.id === raw) ? raw : null;
    default:
      return typeof raw === 'string' ? raw : null;
  }
}

/** The other extras with `f` set to what was typed, in the extras' shape (money with its cadence); none is absent. */
function withOther(current: HouseDto['extrasOther'], f: WebExtra, value: unknown): OtherExtras | null {
  const next: OtherExtras = { ...(current ?? {}) };
  const stored = storedValue(f, value);
  if (stored === null) delete next[f.id];
  else next[f.id] = stored;
  return Object.keys(next).length === 0 ? null : next;
}

function storedValue(f: WebExtra, value: unknown): OtherExtras[string] | null {
  const finite = typeof value === 'number' && Number.isFinite(value);
  switch (f.control) {
    case 'money':
      return finite && value >= 0 && f.cadence ? { amount: Math.round(value), cadence: f.cadence } : null;
    case 'number':
      // The kind file's range (S4b-BL-261): a number outside it is no value, as the floor's is.
      return finite && (f.min === null || value >= Number(f.min)) && (f.max === null || value <= Number(f.max)) ? value : null;
    case 'bool':
      return typeof value === 'boolean' ? value : null;
    case 'enum':
      return f.options.some((o) => o.id === value) ? (value as string) : null;
    default:
      return typeof value === 'string' && value !== '' ? value : null;
  }
}

function isMoney(v: unknown): v is { amount: number } {
  return typeof v === 'object' && v !== null && typeof (v as { amount?: unknown }).amount === 'number';
}
