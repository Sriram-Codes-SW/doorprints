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
import { sha256Hex } from '../../export/sha256';

/**
 * The address variants of docs/ai/evals/address-variants.json (S4b-BL-225, docs/ai/ai-design.md 8.3b), the port of the
 * server eval's `AddressVariants.java`. A named set changes the address-like text of the golden set's fixture houses so the
 * evals can be run with places the model knows and places it cannot. {@link applyAddressSet} is pure (the golden set it is
 * given is never changed), and the fingerprints recorded in the file pin both ports to the same result.
 *
 * What a set may change, and nothing else (anything else throws):
 * - a house: `address`, `street`, `locality`, `label` and `notesAppend` (the notes become the original, a space and the
 *   appended text). Ids, coordinates, status, price, bedrooms, rating, checklist, contact fields and visits stay.
 * - a case: `input.text` (extract) or `input.question` (ask, plan), `expected.mustContain`, `expected.locality`, and
 *   `expectedDelete: ['locality']` (the text holds no locality any more, so none is expected rather than guessed).
 * - `guards`: strings appended to `mustNotContain` (ask), `summaryMustNotContain` (plan) or `notesMustNotContain`
 *   (extract) of every case that runs.
 *
 * The variant-safe rule: a case runs under a set when the set rewrites it, or when none of the place words the set changed
 * is in its input or expected strings (the free-text `note` aside); any other case is returned in `notApplicable`. A place
 * word is a word of a house's old locality, label or city that the old text of the house held, that its new text (address,
 * street, locality, label, notes) no longer holds, with a letter, three characters or more, and not one of the file's
 * `genericWords`. Words are lower-cased runs of letters and digits.
 */

export const DEFAULT_ADDRESS_SET = 'default';
export const ANCHOR_RULES = ['city-word-in-address', 'city-word-in-notes', 'none'] as const;
export type AnchorRule = (typeof ANCHOR_RULES)[number];

export interface VariantHouseRow {
  address?: string;
  street?: string;
  locality?: string;
  label?: string;
  notesAppend?: string;
}

export interface VariantCaseRow {
  input?: { text?: string; question?: string };
  expected?: { mustContain?: string[]; locality?: string };
  expectedDelete?: string[];
}

export interface VariantSet {
  description: string;
  anchorRule: AnchorRule;
  houses: Record<string, VariantHouseRow>;
  cases: Record<string, VariantCaseRow>;
  guards?: string[];
}

/** address-variants.json (only the parts the port reads). */
export interface VariantsFile {
  version: string;
  genericWords: string[];
  sets: Record<string, VariantSet>;
  fingerprints: Record<string, string>;
}

type Json = Record<string, unknown>;

/** golden-set.json (only the parts the port reads). */
export interface GoldenLike {
  fixtureHouses: Json[];
  fixtureVisits: Json[];
  cases: { id: string; type: string; input: Json; expected: Json }[];
}

/** A set applied to a golden set: the golden set to run (the very same object for the default) and the case ids left out. */
export interface AppliedSet<G> {
  set: string;
  golden: G;
  notApplicable: string[];
}

const HOUSE_KEYS = ['address', 'street', 'locality', 'label', 'notesAppend'];
const CASE_KEYS = ['input', 'expected', 'expectedDelete'];
const EXPECTED_KEYS = ['mustContain', 'locality'];
const HOUSE_TEXT = ['address', 'street', 'locality', 'label', 'notes'];

/** True for the default run: unset, blank or `default`. */
export function isDefaultAddressSet(name: string | undefined | null): boolean {
  return name == null || name.trim() === '' || name.trim() === DEFAULT_ADDRESS_SET;
}

/** Lower-cased runs of letters and digits. */
export function placeWords(text: unknown): string[] {
  if (text == null) return [];
  return String(text).toLowerCase().split(/[^\p{L}\p{N}]+/u).filter((w) => w !== '');
}

const deepCopy = <T>(value: T): T => structuredClone(value);

/** The place words a house lost (see the file comment). */
function placeWordsLost(old: Json, now: Json, generic: Set<string>): string[] {
  const before = new Set<string>();
  const after = new Set<string>();
  for (const key of HOUSE_TEXT) {
    placeWords(old[key]).forEach((w) => before.add(w));
    placeWords(now[key]).forEach((w) => after.add(w));
  }
  const candidates = new Set<string>();
  for (const key of ['locality', 'label', 'city']) placeWords(old[key]).forEach((w) => candidates.add(w));
  return [...candidates].filter((w) => before.has(w) && !after.has(w) && /\p{L}/u.test(w) && w.length >= 3 && !generic.has(w));
}

/** Whether a string of the value (not the free-text `note`) holds one of the words. */
function mentions(value: unknown, tokens: Set<string>): boolean {
  if (typeof value === 'string') return placeWords(value).some((w) => tokens.has(w));
  if (Array.isArray(value)) return value.some((item) => mentions(item, tokens));
  if (value !== null && typeof value === 'object') {
    return Object.entries(value as Json).some(([key, item]) => key !== 'note' && mentions(item, tokens));
  }
  return false;
}

function rewrite(set: string, c: GoldenLike['cases'][number], row: VariantCaseRow): void {
  for (const key of Object.keys(row)) {
    if (!CASE_KEYS.includes(key)) throw new Error(`Set '${set}' may not change '${key}' of case ${c.id}`);
  }
  const wanted = c.type === 'extract' ? 'text' : 'question';
  for (const [key, value] of Object.entries(row.input ?? {})) {
    if (key !== wanted) throw new Error(`Set '${set}' may change only input.${wanted} of the ${c.type} case ${c.id}, not input.${key}`);
    if (typeof value !== 'string') throw new Error(`Set '${set}': input.${key} of ${c.id} must be a string`);
    c.input[key] = value;
  }
  for (const [key, value] of Object.entries(row.expected ?? {})) {
    if (!EXPECTED_KEYS.includes(key)) throw new Error(`Set '${set}' may not change expected.${key} of case ${c.id}; allowed: mustContain, locality`);
    c.expected[key] = deepCopy(value);
  }
  for (const key of row.expectedDelete ?? []) {
    if (key !== 'locality') throw new Error(`Set '${set}' may delete only expected.locality of case ${c.id}`);
    delete c.expected[key];
  }
}

function addGuards(c: GoldenLike['cases'][number], guards: string[]): void {
  if (guards.length === 0) return;
  const key = c.type === 'ask' ? 'mustNotContain' : c.type === 'plan' ? 'summaryMustNotContain' : 'notesMustNotContain';
  const list = [...((c.expected[key] as string[] | undefined) ?? [])];
  for (const g of guards) if (!list.includes(g)) list.push(g);
  c.expected[key] = list;
}

/**
 * The golden set under `name`. `default` (or unset) returns `golden` itself, and so does a set that changes nothing
 * (`known`). A set that is not in the file, a row that names a house or a case that is not in the golden set, or a key
 * outside the lists in the file comment, throws.
 */
export function applyAddressSet<G extends GoldenLike>(golden: G, variants: VariantsFile, name: string | undefined | null): AppliedSet<G> {
  if (isDefaultAddressSet(name)) return { set: DEFAULT_ADDRESS_SET, golden, notApplicable: [] };
  const set = name!.trim();
  const def = variants.sets[set];
  if (!def) throw new Error(`Unknown address set '${set}'; the sets are default, ${Object.keys(variants.sets).join(', ')}`);
  const houseRows = def.houses ?? {};
  const caseRows = def.cases ?? {};
  const guards = def.guards ?? [];
  if (Object.keys(houseRows).length === 0 && Object.keys(caseRows).length === 0 && guards.length === 0) {
    return { set, golden, notApplicable: [] };
  }

  const copy = deepCopy(golden);
  const byId = new Map(copy.fixtureHouses.map((h) => [String(h['id']).toLowerCase(), h]));
  const generic = new Set(variants.genericWords.map((w) => w.toLowerCase()));
  const tokens = new Set<string>();
  for (const [id, row] of Object.entries(houseRows)) {
    const house = byId.get(id.toLowerCase());
    if (!house) throw new Error(`Set '${set}' names house ${id}, which is not a fixture house`);
    for (const [key, value] of Object.entries(row)) {
      if (!HOUSE_KEYS.includes(key)) throw new Error(`Set '${set}' may not change '${key}' of a house; allowed: ${[...HOUSE_KEYS].sort().join(', ')}`);
      if (typeof value !== 'string') throw new Error(`Set '${set}': '${key}' of house ${id} must be a string`);
    }
    const old = { ...house };
    for (const key of ['address', 'street', 'locality', 'label'] as const) {
      if (row[key] !== undefined) house[key] = row[key];
    }
    if (row.notesAppend !== undefined) {
      const notes = house['notes'] == null ? '' : String(house['notes']);
      house['notes'] = notes.trim() === '' ? row.notesAppend : `${notes} ${row.notesAppend}`;
    }
    placeWordsLost(old, house, generic).forEach((w) => tokens.add(w));
  }

  const caseIds = new Set(copy.cases.map((c) => c.id));
  for (const id of Object.keys(caseRows)) {
    if (!caseIds.has(id)) throw new Error(`Set '${set}' rewrites case ${id}, which is not in the golden set`);
  }

  const kept: GoldenLike['cases'] = [];
  const notApplicable: string[] = [];
  for (const c of copy.cases) {
    const row = caseRows[c.id];
    if (row) {
      rewrite(set, c, row);
    } else if (tokens.size > 0 && (mentions(c.input, tokens) || mentions(c.expected, tokens))) {
      notApplicable.push(c.id);
      continue;
    }
    addGuards(c, guards);
    kept.push(c);
  }
  copy.cases = kept;
  return { set, golden: copy, notApplicable };
}

/** `JSON.stringify` of a string, which is what the canonical form (and the Java and Python ports of it) uses. */
const quote = (s: string): string => JSON.stringify(s);

/** Keys sorted, no white space, integral numbers without a fraction; the same text as `AddressVariants.canonicalJson` in Java. */
export function canonicalJson(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'boolean') return String(value);
  if (typeof value === 'number') return Number.isInteger(value) ? String(value) : JSON.stringify(value);
  if (typeof value === 'string') return quote(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
  if (typeof value === 'object') {
    const o = value as Json;
    return `{${Object.keys(o).sort().map((k) => `${quote(k)}:${canonicalJson(o[k])}`).join(',')}}`;
  }
  throw new Error(`not JSON: ${typeof value}`);
}

/**
 * SHA-256 (hex) of the canonical JSON of what a run uses: `fixtureHouses`, `fixtureVisits` and `cases` of the (applied)
 * golden set. Recorded per set in the file; the Java test computes the same.
 */
export function fingerprint(golden: GoldenLike): string {
  const body = canonicalJson({ fixtureHouses: golden.fixtureHouses, fixtureVisits: golden.fixtureVisits, cases: golden.cases });
  return sha256Hex(new TextEncoder().encode(body));
}
