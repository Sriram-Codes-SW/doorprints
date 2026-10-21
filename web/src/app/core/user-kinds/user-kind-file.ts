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

import { parseCapabilities, type KindCapabilities } from '../kind-capabilities';
import { scanJson, type ScanCode } from './json-scan';
import { USER_KIND_ID } from './user-kind-ids';

/**
 * The reader of a kind file a person imports (docs/03 ADR-39; docs/02 T-T25): the twin of the server's `KindFiles.parse`
 * (every rule of `kind.schema.json` it checks) with the user profile of `user-kind.schema.json` on top, and the same answers
 * as the phones' reader for every case of `docs/schemas/kinds/user-kind-vectors.json` (the fault's code and path). A kind
 * file is data only: nothing in it is run, fetched, parsed as markup or given to the map. It is refused, with the first fault
 * and where it is, when anything is outside the format: the size (64 KiB) before anything else, then the JSON itself
 * (`json-scan.ts`: one document, no repeated key, nesting, values, string length), then every key at every depth (no
 * `__proto__`, `constructor` or `prototype`, no map key), then the shape, in the file's order. Never throws; never reads a
 * prototype (only own keys, and the parsed values are copied into plain frozen objects).
 */

export type UserKindCode =
  | ScanCode
  | 'tooBig' | 'prototypeKey' | 'mapKey' | 'unknownKey' | 'notAllowed' | 'required' | 'type' | 'format' | 'id' | 'reserved'
  | 'textLength' | 'textChars' | 'range' | 'limit' | 'repeated' | 'value' | 'ref' | 'capabilities';

export interface UserKindFault {
  readonly code: UserKindCode;
  readonly path: string;
}

/** The limits of a kind a person adds (`user-kind-vectors.json` `limits`; the built-in format's are larger). */
export const USER_KIND_LIMITS = Object.freeze({
  maxBytes: 65_536, maxDepth: 8, maxValues: 4000, maxString: 2000, maxText: 60, maxNote: 500, maxSections: 13, maxCore: 14,
  maxExtras: 30, maxOptions: 30, maxOptionsTotal: 300, maxChecklist: 20, maxSearch: 80, maxKinds: 8,
});

/** The keys that configure a map; none is accepted anywhere in a kind file (India's boundaries apply to every kind, ADR-22). */
export const FORBIDDEN_MAP_KEYS: readonly string[] = ['style', 'tileUrl', 'tiles', 'sprite', 'glyphs', 'boundary', 'boundaries', 'geojson',
  'attribution', 'maxzoom', 'bounds', 'center', 'mapStyleUrl', 'geo'];
const PROTOTYPE_KEYS = ['__proto__', 'constructor', 'prototype'];

const CORE_IDS = ['label', 'status', 'rating', 'money', 'address', 'street', 'locality', 'contactName', 'contactPhone', 'brokerId',
  'listingUrl', 'notes', 'location', 'locationSource'];
/** The house's own extras, which the apps and the server read by id (`KindFiles.KNOWN_EXTRAS`): no kind a person adds may reuse one. */
const HOUSE_EXTRA_IDS = ['bedrooms', 'areaSqft', 'floor', 'deposit', 'depositMonths', 'maintenance', 'maintenanceIncluded', 'brokerage',
  'brokerageMonths', 'lockInMonths', 'noticeMonths', 'availableFrom', 'myOffer', 'agreedPrice'];
const SECTION_IDS = ['details', 'cost', 'rooms', 'questions', 'viewings', 'moveIn', 'areaNotes', 'distances', 'location', 'walks', 'checklist',
  'visits', 'photos'];
const STATUS_IDS = ['NEW', 'SHORTLISTED', 'REJECTED', 'TAKEN', 'NOT_CHOSEN'];
const CADENCES = ['once', 'day', 'week', 'month', 'quarter', 'year'];
const UNITS = ['count', 'level', 'sqft', 'sqm', 'cm', 'm', 'km', 'minute', 'day', 'month', 'year', 'percent'];
const TYPES = ['number', 'money', 'text', 'enum', 'date', 'bool'];
const BETTER = ['lower', 'higher'];
const SEEDS = ['docs/schemas/default-questions.json', 'docs/schemas/kinds/school-questions.json'];
/** The related records a `search` may name besides the kind's own fields. */
const RELATED = ['rooms', 'questions', 'areaNotes', 'moveIn'];
const ROOT_KEYS = ['format', 'id', 'version', 'note', 'labels', 'words', 'statuses', 'sections', 'core', 'extras', 'checklist', 'questions',
  'search', 'ai', 'capabilities'];
const ROOT_REQUIRED = ['format', 'id', 'version', 'labels', 'statuses', 'sections', 'core', 'extras', 'checklist', 'questions', 'search', 'ai'];
const CORE_KEYS = ['id', 'label', 'section', 'order', 'visible', 'forCadences', 'was', 'cadences'];
const EXTRA_KEYS: Readonly<Record<string, readonly string[]>> = {
  number: ['integer', 'min', 'max', 'unit', 'better'], money: ['max', 'better', 'cadences', 'cadenceFrom'], text: ['maxLength'],
  enum: ['options'], date: [], bool: [],
};
const EXTRA_BASE = ['id', 'type', 'label', 'section', 'order', 'visible', 'forCadences', 'was'];

const FIELD_ID = /^[a-z][A-Za-z0-9]{0,39}$/;
const CHECK_KEY = /^[a-z][a-z0-9_]{0,31}$/;
const OPTION_ID = /^[A-Z][A-Z0-9_]{0,31}$/;
/**
 * A character a person's words may not hold: markup and placeholder marks, controls, the zero-width space and the direction
 * marks, the line and paragraph separators and the overrides, the isolates, the BOM. The zero-width non-joiner and joiner
 * (U+200C, U+200D) stay: Hindi, Tamil and Telugu words need them.
 */
const FORBIDDEN_CHAR = /[\u0000-\u001f\u007f-\u009f<>{}\u200b\u200e\u200f\u2028-\u202e\u2066-\u2069\ufeff]/;
const MAX_AMOUNT = 1_000_000_000_000;

/** A text label of a kind a person adds: the words, as written. */
export interface UserText {
  readonly text: string;
}

export interface UserKindExtra {
  readonly id: string;
  readonly type: 'number' | 'money' | 'text' | 'enum' | 'date' | 'bool';
  readonly label: UserText;
  readonly section: string;
  readonly order: number;
  readonly visible?: boolean;
  readonly forCadences?: readonly string[];
  readonly integer?: boolean;
  readonly min?: number;
  readonly max?: number;
  readonly unit?: string;
  readonly better?: 'lower' | 'higher';
  readonly cadences?: readonly string[];
  readonly cadenceFrom?: 'money';
  readonly maxLength?: number;
  readonly options?: readonly { readonly id: string; readonly label: UserText }[];
}

export interface UserKindCore {
  readonly id: string;
  readonly label: UserText;
  readonly section: string;
  readonly order: number;
  readonly visible?: boolean;
  readonly forCadences?: readonly string[];
  readonly cadences?: readonly { readonly id: string; readonly label: UserText }[];
}

/** A kind file that passed every check, as plain frozen data. */
export interface UserKindFile {
  readonly format: 'doorprints-kind/1';
  readonly id: string;
  readonly version: number;
  readonly note?: string;
  readonly labels: { readonly new: UserText; readonly many: UserText; readonly delete: UserText };
  readonly statuses: readonly { readonly id: string; readonly label: UserText }[];
  readonly sections: readonly { readonly id: string; readonly label: UserText; readonly visible?: boolean }[];
  readonly core: readonly UserKindCore[];
  readonly extras: readonly UserKindExtra[];
  readonly checklist: readonly { readonly key: string; readonly label: UserText }[];
  readonly questions: { readonly seed: string; readonly scopes: readonly { readonly id: string; readonly cadences: readonly string[] }[] };
  readonly search: readonly string[];
  readonly ai: { readonly extract: readonly string[]; readonly ask: readonly string[]; readonly plan: readonly string[]; readonly never: readonly string[] };
  readonly capabilities?: Readonly<Record<string, unknown>>;
}

export type UserKindRead =
  | { readonly ok: true; readonly file: UserKindFile; readonly capabilities: KindCapabilities }
  | { readonly ok: false; readonly fault: UserKindFault };

class Fault {
  constructor(readonly fault: UserKindFault) {}
}

function fail(code: UserKindCode, path: string): never {
  throw new Fault({ code, path });
}

type Obj = Record<string, unknown>;
const own = (o: object, k: string): boolean => Object.prototype.hasOwnProperty.call(o, k);

/** The size of `text` in UTF-8 bytes, counted without building the bytes. */
export function utf8Bytes(text: string): number {
  let n = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length && (text.charCodeAt(i + 1) & 0xfc00) === 0xdc00) {
      n += 4;
      i++;
    } else n += 3;
  }
  return n;
}

/**
 * Reads a kind file a person chose, from its text: the checks in order, the first fault, or the file. `reserved` are the
 * ids this build has as built-in kinds (a `u_` id never is one; the list is a second lock).
 */
export function readUserKind(text: string, reserved: readonly string[] = []): UserKindRead {
  try {
    if (typeof text !== 'string') fail('notJson', '$');
    if (utf8Bytes(text) > USER_KIND_LIMITS.maxBytes) fail('tooBig', '$');
    const body = text.replace(/^\ufeff/, '');
    const scanned = scanJson(body, USER_KIND_LIMITS);
    if (scanned) fail(scanned.code, scanned.path);
    const root: unknown = JSON.parse(body);
    scanKeys(root, '$');
    const file = check(root, reserved);
    const capabilities = parseCapabilities(file.capabilities ?? {});
    if (!capabilities) fail('capabilities', '$.capabilities');
    return { ok: true, file, capabilities };
  } catch (e) {
    if (e instanceof Fault) return { ok: false, fault: e.fault };
    return { ok: false, fault: { code: 'notJson', path: '$' } };
  }
}

/** Every key at every depth, in the file's order: no prototype key, no map key. */
function scanKeys(n: unknown, path: string): void {
  if (Array.isArray(n)) {
    n.forEach((item, i) => scanKeys(item, `${path}[${i}]`));
  } else if (typeof n === 'object' && n !== null) {
    for (const key of Object.keys(n)) {
      if (PROTOTYPE_KEYS.includes(key)) fail('prototypeKey', `${path}.${key}`);
      if (FORBIDDEN_MAP_KEYS.includes(key)) fail('mapKey', `${path}.${key}`);
      scanKeys((n as Obj)[key], `${path}.${key}`);
    }
  }
}

function object(v: unknown, path: string): Obj {
  if (typeof v !== 'object' || v === null || Array.isArray(v)) fail('type', path);
  return v as Obj;
}

function array(v: unknown, path: string, min: number, max: number): unknown[] {
  if (!Array.isArray(v)) fail('type', path);
  if (v.length < min || v.length > max) fail('limit', path);
  return v;
}

function only(o: Obj, path: string, keys: readonly string[], notAllowed: readonly string[] = []): void {
  for (const k of Object.keys(o)) {
    if (notAllowed.includes(k)) fail('notAllowed', `${path}.${k}`);
    if (!keys.includes(k)) fail('unknownKey', `${path}.${k}`);
  }
}

function required(o: Obj, path: string, keys: readonly string[]): void {
  for (const k of keys) if (!own(o, k)) fail('required', `${path}.${k}`);
}

function str(o: Obj, key: string, path: string): string {
  const v = o[key];
  if (typeof v !== 'string') fail('type', path);
  return v;
}

function oneOf(o: Obj, key: string, path: string, allowed: readonly string[]): string {
  const s = str(o, key, path);
  if (!allowed.includes(s)) fail('value', path);
  return s;
}

function matching(o: Obj, key: string, path: string, pattern: RegExp): string {
  const s = str(o, key, path);
  if (!pattern.test(s)) fail('value', path);
  return s;
}

function bool(o: Obj, key: string, path: string): boolean {
  const v = o[key];
  if (typeof v !== 'boolean') fail('type', path);
  return v;
}

function integer(o: Obj, key: string, path: string, min: number, max: number): number {
  const v = o[key];
  if (typeof v !== 'number' || !Number.isInteger(v)) fail('type', path);
  if (v < min || v > max) fail('range', path);
  return v;
}

/** Whether `s` has a lone surrogate (half of a pair), which no reader can show. */
function loneSurrogate(s: string): boolean {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      if (i + 1 < s.length && (s.charCodeAt(i + 1) & 0xfc00) === 0xdc00) i++;
      else return true;
    } else if (c >= 0xdc00 && c <= 0xdfff) return true;
  }
  return false;
}

/** A person's words: a string of 1 to `max` characters, trimmed, with none of the forbidden characters. */
function words(v: unknown, path: string, max: number): string {
  if (typeof v !== 'string') fail('type', path);
  if (v.length < 1 || v.length > max) fail('textLength', path);
  if (v.trim() !== v || FORBIDDEN_CHAR.test(v) || loneSurrogate(v)) fail('textChars', path);
  return v;
}

/** A label of a kind a person adds: exactly `{ "text": … }`; a key label (`web`, `android`) is not theirs to give. */
function label(v: unknown, path: string): UserText {
  const o = object(v, path);
  if (own(o, 'web') || own(o, 'android') || own(o, 'byCadence')) fail('notAllowed', path);
  only(o, path, ['text']);
  required(o, path, ['text']);
  return Object.freeze({ text: words(o['text'], `${path}.text`, USER_KIND_LIMITS.maxText) });
}

function cadenceList(v: unknown, path: string): string[] {
  const list = array(v, path, 1, 6);
  const out: string[] = [];
  list.forEach((c, i) => {
    if (typeof c !== 'string' || !CADENCES.includes(c)) fail('value', `${path}[${i}]`);
    if (out.includes(c)) fail('repeated', `${path}[${i}]`);
    out.push(c);
  });
  return out;
}

function placement(o: Obj, p: string): { section: string; order: number; visible?: boolean; forCadences?: string[] } {
  required(o, p, ['section', 'order']);
  const section = oneOf(o, 'section', `${p}.section`, SECTION_IDS);
  const order = integer(o, 'order', `${p}.order`, 0, 10_000);
  const out: { section: string; order: number; visible?: boolean; forCadences?: string[] } = { section, order };
  if (own(o, 'visible')) out.visible = bool(o, 'visible', `${p}.visible`);
  if (own(o, 'forCadences')) out.forCadences = cadenceList(o['forCadences'], `${p}.forCadences`);
  return out;
}

function check(raw: unknown, reserved: readonly string[]): UserKindFile {
  const root = object(raw, '$');
  only(root, '$', ROOT_KEYS, ['words']);
  required(root, '$', ROOT_REQUIRED);
  if (str(root, 'format', '$.format') !== 'doorprints-kind/1') fail('format', '$.format');
  const id = str(root, 'id', '$.id');
  if (!USER_KIND_ID.test(id)) fail('id', '$.id');
  if (reserved.includes(id)) fail('reserved', '$.id');
  const version = integer(root, 'version', '$.version', 1, 1000);
  const note = own(root, 'note') ? words(root['note'], '$.note', USER_KIND_LIMITS.maxNote) : undefined;

  const l = object(root['labels'], '$.labels');
  only(l, '$.labels', ['new', 'many', 'delete']);
  required(l, '$.labels', ['new', 'many', 'delete']);
  const labels = Object.freeze({ new: label(l['new'], '$.labels.new'), many: label(l['many'], '$.labels.many'), delete: label(l['delete'], '$.labels.delete') });

  const statuses = array(root['statuses'], '$.statuses', 5, 5).map((s, i) => {
    const p = `$.statuses[${i}]`;
    const o = object(s, p);
    only(o, p, ['id', 'label']);
    required(o, p, ['id', 'label']);
    if (str(o, 'id', `${p}.id`) !== STATUS_IDS[i]) fail('value', `${p}.id`);
    return Object.freeze({ id: STATUS_IDS[i], label: label(o['label'], `${p}.label`) });
  });

  const sectionIds: string[] = [];
  const sections = array(root['sections'], '$.sections', 1, USER_KIND_LIMITS.maxSections).map((s, i) => {
    const p = `$.sections[${i}]`;
    const o = object(s, p);
    only(o, p, ['id', 'label', 'visible']);
    required(o, p, ['id', 'label']);
    const sid = oneOf(o, 'id', `${p}.id`, SECTION_IDS);
    if (sectionIds.includes(sid)) fail('repeated', `${p}.id`);
    sectionIds.push(sid);
    const out: { id: string; label: UserText; visible?: boolean } = { id: sid, label: label(o['label'], `${p}.label`) };
    if (own(o, 'visible')) out.visible = bool(o, 'visible', `${p}.visible`);
    return Object.freeze(out);
  });

  const coreIds: string[] = [];
  const core = array(root['core'], '$.core', 2, USER_KIND_LIMITS.maxCore).map((c, i) => {
    const p = `$.core[${i}]`;
    const o = object(c, p);
    only(o, p, CORE_KEYS, ['was']);
    required(o, p, ['id', 'label']);
    const cid = oneOf(o, 'id', `${p}.id`, CORE_IDS);
    if (coreIds.includes(cid)) fail('repeated', `${p}.id`);
    coreIds.push(cid);
    const lab = label(o['label'], `${p}.label`);
    const out: UserKindCore & { cadences?: UserKindCore['cadences'] } = { id: cid, label: lab, ...placement(o, p) };
    if ((cid === 'money') !== own(o, 'cadences')) fail(cid === 'money' ? 'required' : 'notAllowed', `${p}.cadences`);
    if (own(o, 'cadences')) {
      const seen: string[] = [];
      const list = array(o['cadences'], `${p}.cadences`, 1, 6).map((x, j) => {
        const cp = `${p}.cadences[${j}]`;
        const co = object(x, cp);
        only(co, cp, ['id', 'label'], ['was']);
        required(co, cp, ['id', 'label']);
        const cad = oneOf(co, 'id', `${cp}.id`, CADENCES);
        if (seen.includes(cad)) fail('repeated', `${cp}.id`);
        seen.push(cad);
        return Object.freeze({ id: cad, label: label(co['label'], `${cp}.label`) });
      });
      return Object.freeze({ ...out, cadences: Object.freeze(list) });
    }
    return Object.freeze(out);
  });
  if (!coreIds.includes('label') || !coreIds.includes('location')) fail('required', '$.core');

  const extraIds: string[] = [];
  let optionsTotal = 0;
  const extras = array(root['extras'], '$.extras', 0, USER_KIND_LIMITS.maxExtras).map((e, i) => {
    const p = `$.extras[${i}]`;
    const o = object(e, p);
    required(o, p, ['id']);
    const eid = matching(o, 'id', `${p}.id`, FIELD_ID);
    if (CORE_IDS.includes(eid) || HOUSE_EXTRA_IDS.includes(eid)) fail('reserved', `${p}.id`);
    if (extraIds.includes(eid)) fail('repeated', `${p}.id`);
    extraIds.push(eid);
    required(o, p, ['type']);
    const type = oneOf(o, 'type', `${p}.type`, TYPES) as UserKindExtra['type'];
    only(o, p, [...EXTRA_BASE, ...EXTRA_KEYS[type]], ['was']);
    required(o, p, ['label']);
    const out: Record<string, unknown> = { id: eid, type, label: label(o['label'], `${p}.label`), ...placement(o, p) };
    if (own(o, 'better')) out['better'] = oneOf(o, 'better', `${p}.better`, BETTER);
    switch (type) {
      case 'number': {
        required(o, p, ['integer', 'unit']);
        out['integer'] = bool(o, 'integer', `${p}.integer`);
        out['unit'] = oneOf(o, 'unit', `${p}.unit`, UNITS);
        if (own(o, 'min')) out['min'] = integer(o, 'min', `${p}.min`, -MAX_AMOUNT, MAX_AMOUNT);
        if (own(o, 'max')) out['max'] = integer(o, 'max', `${p}.max`, -MAX_AMOUNT, MAX_AMOUNT);
        if (typeof out['min'] === 'number' && typeof out['max'] === 'number' && out['min'] > out['max']) fail('range', `${p}.min`);
        break;
      }
      case 'money': {
        required(o, p, ['max']);
        out['max'] = integer(o, 'max', `${p}.max`, 1, MAX_AMOUNT);
        if (own(o, 'cadences') === own(o, 'cadenceFrom')) fail('value', `${p}.cadenceFrom`);
        if (own(o, 'cadences')) out['cadences'] = Object.freeze(cadenceList(o['cadences'], `${p}.cadences`));
        else {
          if (o['cadenceFrom'] !== 'money') fail('value', `${p}.cadenceFrom`);
          out['cadenceFrom'] = 'money';
        }
        break;
      }
      case 'text':
        required(o, p, ['maxLength']);
        out['maxLength'] = integer(o, 'maxLength', `${p}.maxLength`, 1, 2000);
        break;
      case 'enum': {
        required(o, p, ['options']);
        const seen: string[] = [];
        const options = array(o['options'], `${p}.options`, 2, USER_KIND_LIMITS.maxOptions).map((x, j) => {
          const op = `${p}.options[${j}]`;
          const oo = object(x, op);
          only(oo, op, ['id', 'label']);
          required(oo, op, ['id', 'label']);
          const oid = matching(oo, 'id', `${op}.id`, OPTION_ID);
          if (seen.includes(oid)) fail('repeated', `${op}.id`);
          seen.push(oid);
          return Object.freeze({ id: oid, label: label(oo['label'], `${op}.label`) });
        });
        optionsTotal += options.length;
        out['options'] = Object.freeze(options);
        break;
      }
      default:
        break;
    }
    return Object.freeze(out) as unknown as UserKindExtra;
  });
  if (optionsTotal > USER_KIND_LIMITS.maxOptionsTotal) fail('limit', '$.extras');

  const checkKeys: string[] = [];
  const checklist = array(root['checklist'], '$.checklist', 0, USER_KIND_LIMITS.maxChecklist).map((c, i) => {
    const p = `$.checklist[${i}]`;
    const o = object(c, p);
    only(o, p, ['key', 'label']);
    required(o, p, ['key', 'label']);
    const key = matching(o, 'key', `${p}.key`, CHECK_KEY);
    if (checkKeys.includes(key)) fail('repeated', `${p}.key`);
    checkKeys.push(key);
    return Object.freeze({ key, label: label(o['label'], `${p}.label`) });
  });

  const q = object(root['questions'], '$.questions');
  only(q, '$.questions', ['seed', 'scopes']);
  required(q, '$.questions', ['seed', 'scopes']);
  const seed = oneOf(q, 'seed', '$.questions.seed', SEEDS);
  const scopes = array(q['scopes'], '$.questions.scopes', 1, 1).map((s, i) => {
    const p = `$.questions.scopes[${i}]`;
    const o = object(s, p);
    only(o, p, ['id', 'cadences']);
    required(o, p, ['id', 'cadences']);
    if (str(o, 'id', `${p}.id`) !== 'BOTH') fail('value', `${p}.id`);
    return Object.freeze({ id: 'BOTH', cadences: Object.freeze(cadenceList(o['cadences'], `${p}.cadences`)) });
  });

  const searchable = [...coreIds, ...extraIds, ...RELATED];
  const search: string[] = [];
  array(root['search'], '$.search', 0, USER_KIND_LIMITS.maxSearch).forEach((s, i) => {
    const p = `$.search[${i}]`;
    if (typeof s !== 'string') fail('type', p);
    if (!FIELD_ID.test(s)) fail('value', p);
    if (search.includes(s)) fail('repeated', p);
    if (!searchable.includes(s)) fail('ref', p);
    search.push(s);
  });

  const ai = object(root['ai'], '$.ai');
  only(ai, '$.ai', ['extract', 'ask', 'plan', 'never'], ['words']);
  required(ai, '$.ai', ['extract', 'ask', 'plan', 'never']);
  for (const slot of ['extract', 'ask', 'plan', 'never']) {
    if (!Array.isArray(ai[slot])) fail('type', `$.ai.${slot}`);
    if ((ai[slot] as unknown[]).length) fail('notAllowed', `$.ai.${slot}`);
  }

  let capabilities: Readonly<Record<string, unknown>> | undefined;
  if (own(root, 'capabilities')) {
    const c = object(root['capabilities'], '$.capabilities');
    if (!parseCapabilities(c)) fail('capabilities', '$.capabilities');
    if (c['baseline'] === true) fail('notAllowed', '$.capabilities.baseline');
    const compare = c['compare'] as { criteria: string[] } | undefined;
    compare?.criteria.forEach((k, i) => {
      if (!checkKeys.includes(k) && !extraIds.includes(k)) fail('ref', `$.capabilities.compare.criteria[${i}]`);
    });
    (c['filters'] as string[] | undefined)?.forEach((f, i) => {
      const e = extras.find((x) => x.id === f);
      if (!e || (e.type !== 'enum' && e.type !== 'bool')) fail('ref', `$.capabilities.filters[${i}]`);
    });
    capabilities = Object.freeze(JSON.parse(JSON.stringify(c)) as Record<string, unknown>);
  }

  const file: UserKindFile = {
    format: 'doorprints-kind/1', id, version, ...(note !== undefined ? { note } : {}), labels, statuses: Object.freeze(statuses),
    sections: Object.freeze(sections), core: Object.freeze(core), extras: Object.freeze(extras), checklist: Object.freeze(checklist),
    questions: Object.freeze({ seed, scopes: Object.freeze(scopes) }), search: Object.freeze(search),
    ai: Object.freeze({ extract: [], ask: [], plan: [], never: [] }), ...(capabilities ? { capabilities } : {}),
  };
  return Object.freeze(file);
}
