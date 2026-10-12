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

import { CADENCES, EXTRA_ID_PATTERN, HOUSE_EXTRAS, KIND_PATTERN, MAX_EXTRA_TEXT, MAX_OTHER_EXTRAS } from '../core/candidate';
import type { Cadence } from '../core/candidate';
import { COST_FIELDS } from '../core/models';

/**
 * The backup family after the format reset (docs/03 ADR-36 §18.4 doors 1 and 2; S4b-BL-206; docs/schemas/README.md
 * §1.1): `doorprints-backup/1` with `search` and `candidates`, each one with `kind`, `money` and `extras`, and
 * `candidateId` on visits, photos and viewings. One mapping between it and the family before it (`houses`, `/1` to
 * `/3`), on the website as in Kotlin's `BackupWire` and the server's `BackupWire`, rule for rule:
 *
 * - **Writing**: every writer builds the rows it always built and {@link currentOf} turns them into the new family at
 *   the end, so a copy is always `/1` with `candidates`, keys in the contract's order (`backup-sample.json`).
 * - **Reading**: a file of the new family (it has `candidates`) is checked for what only it has and turned back with
 *   {@link legacyOf}, so the one reader of rows, ranges and ids (`backup-check.ts`) reads both families. **A file of the
 *   family before (it has `houses`) is read as it always was**: an older Doorprints' backup still imports and becomes
 *   candidates in the store. The two `/1` are told apart by their shape, not their number.
 */

/** The format id of the new family: there is one so far, for a full copy and an update file alike. */
export const CURRENT_FORMAT = 'doorprints-backup/1';
/** The family prefix of every format id. */
const FAMILY = 'doorprints-backup/';
/** What a file of the new family with a newer number reads as, for the reader of rows: newer than any it knows. */
export const NEWER_FORMAT = 'doorprints-backup/999';
/** The `search` object of a data set (door 10): the kind of its candidates. */
export const SEARCH = { kind: 'house' } as const;

type Obj = Record<string, unknown>;

/** A file the new family's rules refuse: the shape is not one any writer of the format produces. */
export class BrokenFamily extends Error {}

/** True for a `data.json` of the new family: it lists `candidates` (the family before listed `houses`). */
export function isCurrentFamily(root: unknown): boolean {
  return isObj(root) && 'candidates' in root;
}

/** The house kind's money extras with the cadence each one must have (`null`: as often as the candidate's money). */
const MONEY_EXTRAS: Readonly<Record<string, Cadence | null>> = { deposit: 'once', maintenance: 'month', brokerage: 'once', myOffer: null, agreedPrice: null };
/** The house kind's own values that are top-level keys of a house in the family before. */
const VALUES = ['bedrooms', 'areaSqft', 'floor'];
/** A candidate's keys in the contract's order (`backup-sample.json`); a key not listed follows them, as it came. */
const CANDIDATE_ORDER = ['id', 'kind', 'label', 'address', 'street', 'locality', 'lat', 'lon', 'status', 'money', 'rating', 'contactName', 'contactPhone', 'listingUrl', 'notes', 'locationSource', 'extras', 'rooms', 'answers', 'moveIn', 'brokerId', 'checklist', 'createdAt', 'updatedAt'];
/** A house's keys in the family before, in its order; a key not listed follows them, as it came. */
const HOUSE_ORDER = ['id', 'kind', 'label', 'address', 'street', 'locality', 'lat', 'lon', 'status', 'price', 'priceType', 'bedrooms', 'rating', 'contactName', 'contactPhone', 'listingUrl', 'notes', 'areaSqft', 'locationSource', 'cost', 'rooms', 'answers', 'moveIn', 'floor', 'brokerId', 'checklist', 'createdAt', 'updatedAt', 'extrasOther'];
/** The keys of a house of the family before that `money` and `extras` replace. */
const MOVED = new Set(['price', 'priceType', ...VALUES, 'cost', 'extrasOther', 'kind']);
/** Whole rupees (door 7). */
const MAX_AMOUNT = 1_000_000_000_000;

// ---- Writing: the family before, as every writer builds it, to the new family ----

/** A `data.json` of the family before as the new family writes it: `/1`, `search`, `candidates`, `candidateId`. */
export function currentOf(legacy: Obj): Obj {
  const out: Obj = {};
  for (const [key, value] of Object.entries(legacy)) {
    if (key === 'format') out['format'] = CURRENT_FORMAT;
    else if (key === 'exportedAt') {
      out['exportedAt'] = value;
      out['search'] = { ...SEARCH };
    } else if (key === 'houses') out['candidates'] = rows(value).map(candidateOfHouse);
    else if (key === 'visits' || key === 'photos' || key === 'viewings') out[key] = rows(value).map((r) => rename(r, 'houseId', 'candidateId'));
    else if (key === 'deleted') out[key] = rows(value).map((d) => (d['kind'] === 'house' ? { ...d, kind: 'candidate' } : d));
    else out[key] = value;
  }
  return out;
}

/** A manifest of the family before as the new family writes it: `/1`, and `candidates` in place of `houses` in its counts. */
export function currentManifestOf<T extends { format: string; counts: object }>(legacy: T): T {
  return { ...legacy, format: CURRENT_FORMAT, counts: rename(legacy.counts as Obj, 'houses', 'candidates') } as T;
}

/** One house of the family before as a candidate: `kind`, `money` and `extras`, keys in the contract's order. */
function candidateOfHouse(h: Obj): Obj {
  const money = moneyOf(h['price'], h['priceType']);
  const out: Obj = { kind: typeof h['kind'] === 'string' ? h['kind'] : 'house' };
  if (money) out['money'] = money;
  const extras = extrasOf(h, money?.cadence ?? 'month');
  if (extras) out['extras'] = extras;
  for (const [key, value] of Object.entries(h)) if (!MOVED.has(key)) out[key] = value;
  return ordered(out, CANDIDATE_ORDER);
}

/** The server's `Money.of`: absent when neither is known, a price without a type monthly. */
function moneyOf(price: unknown, priceType: unknown): { amount?: number; cadence: Cadence } | null {
  const cadence: Cadence | null = priceType === 'RENT' ? 'month' : priceType === 'SALE' ? 'once' : null;
  const amount = typeof price === 'number' ? price : null;
  if (amount === null && cadence === null) return null;
  return amount === null ? { cadence: cadence ?? 'month' } : { amount, cadence: cadence ?? 'month' };
}

/** The extras of a house of the family before, in the kind file's order, then the unknown ones it kept by id. */
function extrasOf(h: Obj, ownCadence: Cadence): Obj | null {
  const cost = isObj(h['cost']) ? (h['cost'] as Obj) : {};
  const out: Obj = {};
  for (const id of HOUSE_EXTRAS) {
    const value = VALUES.includes(id) ? h[id] : cost[id];
    if (value === null || value === undefined) continue;
    if (id in MONEY_EXTRAS) out[id] = { amount: value, cadence: MONEY_EXTRAS[id] ?? ownCadence };
    else out[id] = value;
  }
  const other = isObj(h['extrasOther']) ? (h['extrasOther'] as Obj) : {};
  for (const id of Object.keys(other).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))) out[id] = other[id];
  return Object.keys(out).length === 0 ? null : out;
}

// ---- Reading: the new family to the rows the reader of both families checks ----

/**
 * A `data.json` of the new family as the family before holds it, for the reader of rows: `houses` (with `price`,
 * `priceType`, `bedrooms`, `areaSqft`, `floor`, `cost`, and `extrasOther` for the extras this app does not know),
 * `houseId`, a deletion of `house`. The format reads as `/3` for a file with deletions, `/2` otherwise, and a newer
 * number of the new family as {@link NEWER_FORMAT}.
 *
 * @throws BrokenFamily for what only the new family has and breaks its rules: a candidate without a kind id, money
 *   that is not an amount of whole rupees with a cadence of the vocabulary (a house's: `month` or `once`), a money
 *   extra without its amount or with a cadence it may not have, an unknown extra that is not a field id with a value of
 *   an extras type, more than 30 of them, extras that are not an object, or both `candidates` and `houses`.
 */
export function legacyOf(root: Obj): Obj {
  if ('houses' in root) throw new BrokenFamily('candidates and houses');
  const format = root['format'];
  const deleted = root['deleted'];
  const out: Obj = {};
  for (const [key, value] of Object.entries(root)) {
    if (key === 'format') {
      out['format'] = format === CURRENT_FORMAT ? (deleted != null ? 'doorprints-backup/3' : 'doorprints-backup/2') : typeof format === 'string' && format.startsWith(FAMILY) ? NEWER_FORMAT : format;
    } else if (key === 'search') continue;
    else if (key === 'candidates') out['houses'] = value == null ? value : list(value).map(houseOfCandidate);
    else if ((key === 'visits' || key === 'photos' || key === 'viewings') && Array.isArray(value)) out[key] = value.map((r) => (isObj(r) ? rename(r, 'candidateId', 'houseId') : r));
    else if (key === 'deleted' && Array.isArray(value)) out[key] = value.map((d) => (isObj(d) && d['kind'] === 'candidate' ? { ...d, kind: 'house' } : d));
    else out[key] = value;
  }
  return out;
}

/** A manifest of either family as the reader of the family before takes it: `candidates` counted as `houses`. */
export function legacyManifestOf(m: Obj): Obj {
  const counts = m['counts'];
  if (!isObj(counts) || 'houses' in counts || !('candidates' in counts)) return m;
  return { ...m, counts: rename(counts, 'candidates', 'houses') };
}

/** One candidate as a house of the family before, after the new family's own checks. */
function houseOfCandidate(c: unknown): Obj {
  if (!isObj(c)) throw new BrokenFamily('candidate');
  const kind = c['kind'];
  if (typeof kind !== 'string' || !KIND_PATTERN.test(kind)) throw new BrokenFamily('kind');
  const out: Obj = {};
  for (const [key, value] of Object.entries(c)) {
    if (key === 'kind') {
      if (kind !== 'house') out['kind'] = kind;
    } else if (key === 'money') Object.assign(out, legacyMoney(value, kind));
    else if (key === 'extras') Object.assign(out, legacyExtras(value));
    else out[key] = value;
  }
  return ordered(out, HOUSE_ORDER);
}

/** `row` with the keys of `order` first, in that order, then the others as they came. */
function ordered(row: Obj, order: readonly string[]): Obj {
  const out: Obj = {};
  for (const key of order) if (key in row) out[key] = row[key];
  for (const [key, value] of Object.entries(row)) if (!(key in out)) out[key] = value;
  return out;
}

/** `money` as `price` and `priceType`: an amount of whole rupees (or none) and a cadence of the vocabulary. */
function legacyMoney(value: unknown, kind: string): Obj {
  if (value == null) return {};
  const m = moneyValue(value, false);
  if (kind === 'house' && m.cadence !== 'month' && m.cadence !== 'once') throw new BrokenFamily('money.cadence');
  const out: Obj = {};
  if (m.amount !== undefined) out['price'] = m.amount;
  if (m.cadence === 'month') out['priceType'] = 'RENT';
  else if (m.cadence === 'once') out['priceType'] = 'SALE';
  return out;
}

/** `extras` as the house's values, its `cost` and `extrasOther`; the reader of rows then checks the values' types. */
function legacyExtras(value: unknown): Obj {
  if (value == null) return {};
  if (!isObj(value)) throw new BrokenFamily('extras');
  const out: Obj = {};
  const cost: Obj = {};
  const other: Obj = {};
  for (const [id, v] of Object.entries(value)) {
    if (VALUES.includes(id)) out[id] = v;
    else if (id in MONEY_EXTRAS) {
      if (v == null) continue;
      const m = moneyValue(v, true);
      const fixed = MONEY_EXTRAS[id];
      if (fixed !== null && m.cadence !== fixed) throw new BrokenFamily(`extras.${id}`);
      cost[id] = m.amount;
    } else if ((COST_FIELDS as readonly string[]).includes(id)) cost[id] = v;
    else {
      if (!EXTRA_ID_PATTERN.test(id) || !isExtraValue(v)) throw new BrokenFamily(`extras.${id}`);
      other[id] = v;
    }
  }
  if (Object.keys(other).length > MAX_OTHER_EXTRAS) throw new BrokenFamily('extras');
  if (Object.keys(cost).length > 0) out['cost'] = cost;
  if (Object.keys(other).length > 0) out['extrasOther'] = other;
  return out;
}

/** A money object: a cadence of the vocabulary and an amount of whole rupees, required for a money extra. */
function moneyValue(value: unknown, amountRequired: boolean): { amount?: number; cadence: Cadence } {
  if (!isObj(value)) throw new BrokenFamily('money');
  const cadence = value['cadence'];
  if (typeof cadence !== 'string' || !CADENCES.includes(cadence as Cadence)) throw new BrokenFamily('money.cadence');
  const amount = value['amount'];
  if (amount == null) {
    if (amountRequired) throw new BrokenFamily('money.amount');
    return { cadence: cadence as Cadence };
  }
  if (!isAmount(amount)) throw new BrokenFamily('money.amount');
  return { amount: amount as number, cadence: cadence as Cadence };
}

/** A value of one of the six extras types as the wire spells it (door 5). */
function isExtraValue(v: unknown): boolean {
  if (typeof v === 'boolean') return true;
  if (typeof v === 'number') return Number.isFinite(v);
  if (typeof v === 'string') return v.length <= MAX_EXTRA_TEXT;
  if (!isObj(v)) return false;
  return Object.keys(v).length === 2 && isAmount(v['amount']) && typeof v['cadence'] === 'string' && CADENCES.includes(v['cadence'] as Cadence);
}

function isAmount(v: unknown): boolean {
  return typeof v === 'number' && Number.isSafeInteger(v) && v >= 0 && v <= MAX_AMOUNT;
}

function isObj(v: unknown): v is Obj {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function list(v: unknown): unknown[] {
  if (!Array.isArray(v)) throw new BrokenFamily('candidates');
  return v;
}

function rows(v: unknown): Obj[] {
  return Array.isArray(v) ? (v as Obj[]) : [];
}

/** A copy of `row` with `from` named `to`, in the same position. */
function rename(row: Obj, from: string, to: string): Obj {
  return Object.fromEntries(Object.entries(row).map(([k, v]) => [k === from ? to : k, v]));
}
