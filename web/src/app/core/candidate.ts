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

import { COST_FIELDS } from './models';
import type { HouseCost, HouseDto, PriceType } from './models';

/**
 * A candidate as the format reset of kinds PR 2 shapes it (docs/03 ADR-36 §18.4 doors 3, 5 and 7; S4b-BL-206): `kind`,
 * `money` (amount and cadence) and `extras` (typed values keyed by extra id) in place of the house's `price`,
 * `priceType`, `bedrooms`, `areaSqft`, `floor` and `cost`. It is what the server's `/api/candidates` sends and takes and
 * what this browser's `houses` store holds since IndexedDB version 4 (`data/candidate-rows.ts`).
 *
 * The screens still read a {@link HouseDto}; only the public surfaces moved (§18.1), so this file is the one place the
 * two shapes meet: {@link candidateOf} and {@link houseOf}, the twins of the server's `Money.of`,
 * `CandidateExtras.of` and its `HouseDto` readers. An extra id this app does not know is kept (`HouseDto.extrasOther`)
 * and written back after the known ones, so a newer app's field survives this browser (door 5).
 */

/** The cadence vocabulary of the kind schema (`docs/schemas/kinds/kind.schema.json`, `$defs.cadence`). */
export type Cadence = 'once' | 'day' | 'week' | 'month' | 'quarter' | 'year';
/** Every cadence, in the schema's order. */
export const CADENCES: readonly Cadence[] = ['once', 'day', 'week', 'month', 'quarter', 'year'];

/**
 * An amount of whole rupees and how often it is paid (door 7). The candidate's own money may have a cadence and no
 * amount (a house marked as a rent before the rent is known); a money extra always has both.
 */
export interface Money {
  amount?: number;
  cadence: Cadence;
}

/** A value of one of the six extras types as the wire spells it: a number, a text (or enum id, or date), a bool, money. */
export type ExtraValue = number | string | boolean | { amount: number; cadence: Cadence };

/** A candidate's extras, keyed by extra id, known ids first in the kind file's order. */
export type CandidateExtras = Record<string, ExtraValue>;

/** The candidate on the wire and in the `houses` store: the server's `HouseDto` since kinds step 2 (#281). */
export interface CandidateDto {
  id: string;
  kind: string;
  label: string;
  address?: string | null;
  street?: string | null;
  locality?: string | null;
  lat: number;
  lon: number;
  status: HouseDto['status'];
  money?: Money | null;
  rating?: number | null;
  contactName?: string | null;
  contactPhone?: string | null;
  listingUrl?: string | null;
  notes?: string | null;
  locationSource?: HouseDto['locationSource'];
  extras?: CandidateExtras | null;
  rooms?: HouseDto['rooms'];
  answers?: HouseDto['answers'];
  moveIn?: HouseDto['moveIn'];
  brokerId?: string | null;
  checklist: Record<string, number>;
  createdAt?: string | null;
  updatedAt?: string | null;
  deleted: boolean;
  syncVersion: number;
  distanceMeters?: number | null;
}

/** The only kind there is today (`docs/schemas/kinds/house.json`); one kind per data set (§18.1). */
export const HOUSE_KIND = 'house';
/** A kind id (door 3). */
export const KIND_PATTERN = /^[a-z][a-z0-9_]{1,31}$/;
/** An extra id (the kind schema's `fieldId`, the server's `CandidateExtras.ID_PATTERN`). */
export const EXTRA_ID_PATTERN = /^[a-z][A-Za-z0-9]{0,39}$/;
/** At most this many extras this app does not know, on one candidate (the server's `MAX_OTHER`). */
export const MAX_OTHER_EXTRAS = 30;
/** The longest text value of an extra this app does not know (the server's `MAX_TEXT`). */
export const MAX_EXTRA_TEXT = 2000;
/** Whole rupees (door 7, `MAX_RUPEES`). */
const MAX_AMOUNT = 1_000_000_000_000;

/** The house kind's extras that are money, with the cadence each one has (`follows`: the candidate's own money's). */
const MONEY_EXTRAS: Readonly<Partial<Record<keyof HouseCost, Cadence | 'follows'>>> = {
  deposit: 'once',
  maintenance: 'month',
  brokerage: 'once',
  myOffer: 'follows',
  agreedPrice: 'follows',
};

/** The house kind's extra ids in the kind file's order: the three values, then the eleven cost fields. */
export const HOUSE_EXTRAS: readonly string[] = ['bedrooms', 'areaSqft', 'floor', ...COST_FIELDS];

/** `RENT` is `month`, `SALE` is `once` (door 7); anything else is unknown. */
export function cadenceOf(priceType: PriceType | null | undefined): Cadence | null {
  return priceType === 'RENT' ? 'month' : priceType === 'SALE' ? 'once' : null;
}

/** `month` is `RENT`, `once` is `SALE`; another cadence is not a house's and reads as unknown. */
export function priceTypeOf(cadence: Cadence | null | undefined): PriceType | null {
  return cadence === 'month' ? 'RENT' : cadence === 'once' ? 'SALE' : null;
}

/**
 * The house's money from its two values: absent when neither is known, a price without a type as monthly (the
 * server's `Money.of`).
 */
export function moneyOf(price: number | null | undefined, priceType: PriceType | null | undefined): Money | null {
  const amount = typeof price === 'number' && Number.isFinite(price) ? rupees(Math.round(price)) : null;
  const cadence = cadenceOf(priceType);
  if (amount === null && cadence === null) return null;
  return amount === null ? { cadence: cadence ?? 'month' } : { amount, cadence: cadence ?? 'month' };
}

/** The candidate a house is on the wire and in the store: `kind`, `money` and `extras`, keys in the format's order. */
export function candidateOf(h: HouseDto): CandidateDto {
  const money = moneyOf(h.price, h.priceType);
  const extras = extrasOf(h, money?.cadence ?? null);
  const out: Record<string, unknown> = { id: h.id, kind: h.kind ?? HOUSE_KIND, label: h.label };
  put(out, 'address', h.address);
  put(out, 'street', h.street);
  put(out, 'locality', h.locality);
  out['lat'] = h.lat;
  out['lon'] = h.lon;
  out['status'] = h.status;
  put(out, 'money', money);
  put(out, 'rating', h.rating);
  put(out, 'contactName', h.contactName);
  put(out, 'contactPhone', h.contactPhone);
  put(out, 'listingUrl', h.listingUrl);
  put(out, 'notes', h.notes);
  put(out, 'locationSource', h.locationSource);
  put(out, 'extras', extras);
  put(out, 'rooms', h.rooms);
  put(out, 'answers', h.answers);
  put(out, 'moveIn', h.moveIn);
  put(out, 'brokerId', h.brokerId);
  out['checklist'] = h.checklist ?? {};
  put(out, 'createdAt', h.createdAt);
  put(out, 'updatedAt', h.updatedAt);
  out['deleted'] = h.deleted === true;
  out['syncVersion'] = h.syncVersion ?? 0;
  put(out, 'distanceMeters', h.distanceMeters);
  return out as unknown as CandidateDto;
}

/**
 * The house the screens read from a candidate (untrusted: the server, a file, a stored row). A known extra with a
 * value of the wrong type is unknown (absent), as every coerced value; the ranges are checked by `tryHouseFromDto`
 * after this. An unknown extra is kept in `extrasOther` when it is a field id with a value of an extras type.
 */
export function houseOf(c: CandidateDto): HouseDto {
  const money = moneyValue(c.money);
  const extras = isObject(c.extras) ? (c.extras as Record<string, unknown>) : {};
  const cost: Record<string, unknown> = {};
  for (const field of COST_FIELDS) {
    const raw = extras[field];
    const cadence = MONEY_EXTRAS[field];
    const value = cadence === undefined ? plain(field, raw) : extraAmount(raw, cadence === 'follows' ? null : cadence);
    if (value !== null) cost[field] = value;
  }
  const other = cleanOtherExtras(Object.fromEntries(Object.entries(extras).filter(([id]) => !HOUSE_EXTRAS.includes(id))));
  const { money: _m, extras: _e, kind, ...rest } = c as CandidateDto & Record<string, unknown>;
  const house: HouseDto = {
    ...(rest as unknown as HouseDto),
    price: money?.amount ?? null,
    priceType: priceTypeOf(money?.cadence),
    bedrooms: num(extras['bedrooms']),
    areaSqft: num(extras['areaSqft']),
    cost: Object.keys(cost).length === 0 ? null : (cost as HouseCost),
    floor: num(extras['floor']),
  };
  if (typeof kind === 'string' && KIND_PATTERN.test(kind) && kind !== HOUSE_KIND) house.kind = kind;
  if (other) house.extrasOther = other;
  return house;
}

/**
 * The extras this app does not know, as kept: a field id that is not one of the house kind's, with a value of an
 * extras type (a finite number, a text of at most 2,000 characters, a boolean, or a money object with exactly an
 * amount in range and a cadence); at most 30, the first 30 by id. `null` when none is left.
 */
export function cleanOtherExtras(raw: unknown): CandidateExtras | null {
  if (!isObject(raw)) return null;
  const out: CandidateExtras = {};
  const ids = Object.keys(raw as object)
    .filter((id) => EXTRA_ID_PATTERN.test(id) && !HOUSE_EXTRAS.includes(id))
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  for (const id of ids) {
    if (Object.keys(out).length >= MAX_OTHER_EXTRAS) break;
    const value = extraValue((raw as Record<string, unknown>)[id]);
    if (value !== null) out[id] = value;
  }
  return Object.keys(out).length === 0 ? null : out;
}

/** The house's extras in the kind file's order, money with its cadence, then the unknown ones by id; null when none. */
function extrasOf(h: HouseDto, ownCadence: Cadence | null): CandidateExtras | null {
  const out: CandidateExtras = {};
  const cost = (h.cost ?? {}) as Record<string, unknown>;
  const values: Record<string, unknown> = { bedrooms: h.bedrooms, areaSqft: h.areaSqft, floor: h.floor, ...cost };
  for (const id of HOUSE_EXTRAS) {
    const value = values[id];
    if (value === null || value === undefined) continue;
    const cadence = MONEY_EXTRAS[id as keyof HouseCost];
    if (cadence === undefined) out[id] = value as ExtraValue;
    else if (typeof value === 'number') out[id] = { amount: value, cadence: cadence === 'follows' ? (ownCadence ?? 'month') : cadence };
  }
  const other = cleanOtherExtras(h.extrasOther);
  if (other) Object.assign(out, other);
  return Object.keys(out).length === 0 ? null : out;
}

/** A money object of the vocabulary: a cadence, and an amount when one is given; null for anything else. */
function moneyValue(raw: unknown): Money | null {
  if (!isObject(raw)) return null;
  const m = raw as Record<string, unknown>;
  if (typeof m['cadence'] !== 'string' || !CADENCES.includes(m['cadence'] as Cadence)) return null;
  const amount = rupees(m['amount'] as number);
  return amount === null ? { cadence: m['cadence'] as Cadence } : { amount, cadence: m['cadence'] as Cadence };
}

/** A money extra's amount, when its value is a money object with an amount (and, when fixed, the field's cadence). */
function extraAmount(raw: unknown, cadence: Cadence | null): number | null {
  const money = moneyValue(raw);
  if (!money || money.amount === undefined) return null;
  return cadence === null || money.cadence === cadence ? money.amount : null;
}

/** A plain cost value of the type its field has: a boolean for `maintenanceIncluded`, a date text, else a number. */
function plain(field: keyof HouseCost, raw: unknown): unknown {
  if (field === 'maintenanceIncluded') return typeof raw === 'boolean' ? raw : null;
  if (field === 'availableFrom') return typeof raw === 'string' ? raw : null;
  return num(raw);
}

/** One value of an extras type, or null. */
function extraValue(v: unknown): ExtraValue | null {
  if (typeof v === 'boolean') return v;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string') return v.length <= MAX_EXTRA_TEXT ? v : null;
  if (isObject(v)) {
    const keys = Object.keys(v as object);
    const m = v as Record<string, unknown>;
    if (keys.length !== 2 || !Number.isSafeInteger(m['amount']) || typeof m['cadence'] !== 'string') return null;
    const amount = m['amount'] as number;
    if (amount < 0 || amount > MAX_AMOUNT || !CADENCES.includes(m['cadence'] as Cadence)) return null;
    return { amount, cadence: m['cadence'] as Cadence };
  }
  return null;
}

function rupees(v: unknown): number | null {
  return typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 && v <= MAX_AMOUNT ? v : null;
}

function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function isObject(v: unknown): boolean {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** Sets `key` only when the value is known: an absent value is never written as `null` (the format's rule). */
function put(out: Record<string, unknown>, key: string, value: unknown): void {
  if (value !== null && value !== undefined) out[key] = value;
}
