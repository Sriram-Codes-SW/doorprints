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

// The hidden schools kind (kinds PR 5, S4b-BL-209; docs/03 ADR-36 §18.9) on the website: the kind file is valid, the
// website's copy of it (school-kind.ts) is the file, its words are school-vectors.json's in en, hi, ta and te, the
// developer flag is off unless a development build has it set, the renderer draws the vectors' controls and rows, and a
// school candidate goes through the store, the Drive sync file and a backup unchanged. The expected values are the kind
// file's and the vectors', never recomputed with the code under test.
import { describe, expect, it, vi } from 'vitest';
import Ajv2020 from 'ajv/dist/2020';
import schemaJson from '../../../../docs/schemas/kinds/kind.schema.json';
import schoolJson from '../../../../docs/schemas/kinds/school.json';
import vectorsJson from '../../../../docs/schemas/kinds/school-vectors.json';
import { en } from '../i18n/en';
import { hi } from '../i18n/hi';
import { ta } from '../i18n/ta';
import { te } from '../i18n/te';
import { DEV_KINDS_KEY, HOUSE, builtInKind, creatableKinds, devKindsOn } from './dev-kinds';
import { SCHOOL_FIELDS, SCHOOL_KEYS, SCHOOL_LABELS } from './school-kind';
import { kindLabels } from './kind-labels';
import { kindFields } from './kind-fields';
import { newHouse, type HouseDto } from './models';
import { candidateOf, houseOf, type CandidateDto } from './candidate';
import { KindExtras } from '../pages/house-detail/kind-extras';
import { checkData } from '../export/backup-check';
import { currentOf } from '../export/backup-family';
import { houseOfRow, storedRowOf } from '../data/candidate-rows';
import { syncJsonOf, syncRowAsBefore } from '../data/drive/sync-file';
import { tryHouseFromDto } from '../data/records';
import { searchText } from '../pages/map/map-list';

interface Label { web: string | null; android: string | null }
interface Field { id: string; type?: string; section: string; order: number; label: Label; options?: { id: string; label: Label }[]; [k: string]: unknown }
interface Kind { labels: Record<string, Label>; statuses: { id: string; label: Label }[]; sections: { id: string; label: Label }[]; core: Field[]; extras: Field[] }
interface FieldVector { id: string; section: string; control: string; web: { id: string; label: string; suffix: string | null; input: Record<string, unknown> }; stored?: { cadence: string } }
interface Vectors {
  format: string;
  kind: { id: string; hidden: { sections: string[]; fields: string[] }; layout: Record<string, string[]>; labels: { web: Record<string, Record<string, string | null>> } };
  fields: FieldVector[];
  rows: { stack: string; section: string; rows: string[][] }[];
  backup: Record<string, unknown> & { candidates: unknown[] };
  candidate: Record<string, unknown>;
}

const school = schoolJson as unknown as Kind;
const V = vectorsJson as unknown as Vectors;
const DICTS: Record<string, Readonly<Record<string, string>>> = { en, hi, ta, te };

/** Every label of the kind file under its vector ref (`option.<extra>.<id>` for an enum's options). */
function labelRefs(k: Kind): [string, Label][] {
  const refs: [string, Label][] = Object.entries(k.labels).map(([id, l]) => [`kind.${id}`, l]);
  k.statuses.forEach((s) => refs.push([`status.${s.id}`, s.label]));
  k.sections.forEach((s) => refs.push([`section.${s.id}`, s.label]));
  for (const f of [...k.core, ...k.extras]) {
    refs.push([`field.${f.id}`, f.label]);
    for (const o of f.options ?? []) refs.push([`option.${f.id}.${o.id}`, o.label]);
  }
  return refs;
}

describe('the kind file for schools (docs/schemas/kinds/school.json)', () => {
  it('is valid against kind.schema.json, and is the vectors’ kind', () => {
    const validate = new Ajv2020({ allErrors: true, strict: true, strictRequired: false }).compile(schemaJson);
    expect(validate(schoolJson), JSON.stringify(validate.errors)).toBe(true);
    expect(V.format).toBe('doorprints-school-vectors/1');
    expect(V.kind.id).toBe('school');
  });

  it('the website’s copy has every ref of the file with its web key, and no other', () => {
    const want = Object.fromEntries(labelRefs(school).filter(([ref]) => !ref.startsWith('option.')).map(([ref, l]) => [ref, l.web]));
    expect(Object.fromEntries(SCHOOL_LABELS.refs.map((ref) => [ref, SCHOOL_LABELS.resolve(ref)!.key]))).toEqual(want);
    for (const f of school.extras) {
      const copy = SCHOOL_FIELDS.extras.find((e) => e.id === f.id)!;
      const { label: _l, options, ...rest } = f;
      const { options: copied, ...own } = copy;
      expect(own, f.id).toEqual(rest);
      expect(copied?.map((o) => [o.id, o.label]) ?? null, f.id).toEqual(options?.map((o) => [o.id, o.label.web]) ?? null);
    }
    expect(SCHOOL_FIELDS.extras.map((f) => f.id)).toEqual(school.extras.map((f) => f.id));
  });

  it('hides what the vectors say it hides: the money, the broker and five sections', () => {
    for (const s of V.kind.hidden.sections) expect([SCHOOL_LABELS.section(s), SCHOOL_LABELS.isVisible(`section.${s}`)], s).toEqual([undefined, false]);
    for (const f of V.kind.hidden.fields) expect([SCHOOL_LABELS.field(f), SCHOOL_LABELS.isVisible(`field.${f}`)], f).toEqual([undefined, false]);
    for (const [section, ids] of Object.entries(V.kind.layout)) {
      for (const id of ids) expect(SCHOOL_LABELS.isVisible(`field.${id}`), `${section} ${id}`).toBe(true);
    }
    expect(() => SCHOOL_KEYS.field('money')).toThrow();
  });

  it('every key shows the vectors’ words in en, hi, ta and te', () => {
    for (const lang of Object.keys(DICTS)) {
      for (const [ref, label] of labelRefs(school)) {
        const want = V.kind.labels.web[lang][ref];
        expect(label.web === null ? null : DICTS[lang][label.web], `${lang} ${ref}`).toBe(want);
      }
    }
  });
});

describe('the developer flag (dev-kinds.ts): off unless a development build has it set', () => {
  const store = (value: string | null) => ({ getItem: (k: string) => (k === DEV_KINDS_KEY ? value : null) });

  it('is off by default, off in a production build even when set, and off for any other value or a broken storage', () => {
    localStorage.removeItem(DEV_KINDS_KEY);
    expect(devKindsOn()).toBe(false);
    expect(devKindsOn(false, store('school'))).toBe(false);
    expect(devKindsOn(true, store('house'))).toBe(false);
    expect(devKindsOn(true, store('School'))).toBe(false);
    expect(devKindsOn(true, null)).toBe(false);
    expect(devKindsOn(true, { getItem: () => { throw new Error('blocked'); } })).toBe(false);
    expect(devKindsOn(true, store('school'))).toBe(true);
    localStorage.setItem(DEV_KINDS_KEY, 'school');
    expect(devKindsOn()).toBe(true);
    localStorage.removeItem(DEV_KINDS_KEY);
  });

  it('while off, the school cannot be created, offered or drawn: only the house, and a school row is drawn as a house', () => {
    expect(creatableKinds(false)).toEqual([HOUSE]);
    expect(builtInKind('school', false)).toBe(HOUSE);
    expect(builtInKind(undefined, false)).toBe(HOUSE);
    expect(kindLabels('school')).toBeUndefined();
    expect(kindFields('school')).toBeUndefined();
    expect(newHouse(26.14, 91.79).kind ?? null).toBeNull();
  });

  it('while on, the school is offered after the house and drawn with its own labels and fields', () => {
    expect(creatableKinds(true).map((k) => k.id)).toEqual(['house', 'school']);
    const k = builtInKind('school', true);
    // The listing extract fills a school since kinds PR 7 (S4b-BL-211): the kind file's `ai.extract` is not empty.
    expect([k.labels, k.keys, k.fields, k.extracts]).toEqual([SCHOOL_LABELS, SCHOOL_KEYS, SCHOOL_FIELDS, schoolJson.ai.extract.length > 0]);
    expect(k.extracts).toBe(true);
    expect(builtInKind('clinic', true)).toBe(HOUSE);
    expect(HOUSE.extracts).toBe(true);
  });
});

const draft = (): HouseDto => ({ ...newHouse(26.1433, 91.7898), kind: 'school', priceType: null, cost: {} });

describe('the renderer draws the school as school-vectors.json says (the website half)', () => {
  const extras = new KindExtras({ patch: () => undefined }, SCHOOL_FIELDS);

  it('each extra: its control, element id, label key, suffix, range and options', () => {
    const all = [...extras.of('details', draft()), ...extras.of('cost', draft())];
    expect(all.map((f) => f.id)).toEqual(V.fields.map((f) => f.id));
    for (const [i, v] of V.fields.entries()) {
      const f = all[i];
      expect([f.control, f.domId, f.label, f.suffix || null, f.narrow], v.id).toEqual([v.control, v.web.id, v.web.label, v.web.suffix, false]);
      expect([f.min, f.max, f.maxLength === null ? null : String(f.maxLength)], v.id)
        .toEqual([v.web.input['min'] ?? null, v.web.input['max'] ?? null, v.web.input['maxlength'] ?? null]);
      if (v.control === 'enum') expect([null, ...f.options.map((o) => o.id)], v.id).toEqual(v.web.input['options']);
      expect(f.cadence, v.id).toBe(v.stored?.cadence ?? null);
    }
  });

  for (const v of V.rows.filter((r) => r.stack === 'web')) {
    it(`lays the ${v.section} extras out in the vector’s rows (no money, so no price’s row)`, () => {
      expect(extras.rows(v.section, draft()).map((row) => row.map((f) => f.id))).toEqual(v.rows);
    });
  }

  it('writes each value as one of the candidate’s extras, in its type; an empty or wrong value is no value', () => {
    const patch = vi.fn();
    const own = new KindExtras({ patch }, SCHOOL_FIELDS);
    const d = draft();
    const byId = (id: string) => [...own.of('details', d), ...own.of('cost', d)].find((f) => f.id === id)!;
    own.set(d, byId('board'), 'ICSE');
    own.set(d, byId('medium'), 'TAMIL');
    own.set(d, byId('grades'), 'LKG to 10');
    own.set(d, byId('commuteMinutes'), 35);
    own.set(d, byId('fees'), 84000);
    own.set(d, byId('admissionFee'), 15000);
    expect(d.extrasOther).toEqual({ board: 'ICSE', grades: 'LKG to 10', commuteMinutes: 35, fees: { amount: 84000, cadence: 'year' }, admissionFee: { amount: 15000, cadence: 'once' } });
    expect(['board', 'medium', 'grades', 'commuteMinutes', 'fees'].map((id) => own.value(d, byId(id)))).toEqual(['ICSE', null, 'LKG to 10', 35, 84000]);
    own.set(d, byId('grades'), '');
    own.set(d, byId('fees'), null);
    expect(Object.keys(d.extrasOther!)).toEqual(['board', 'commuteMinutes', 'admissionFee']);
    own.setBool(d, byId('busService'), { target: { checked: true } } as unknown as Event);
    expect(patch).toHaveBeenCalledWith({ extrasOther: { board: 'ICSE', commuteMinutes: 35, admissionFee: { amount: 15000, cadence: 'once' }, busService: true } });
    const stale: HouseDto = { ...d, extrasOther: { board: 'NIOS', commuteMinutes: 'far', fees: 9 } };
    expect(['board', 'commuteMinutes', 'fees'].map((id) => own.value(stale, byId(id)))).toEqual([null, null, null]);
    for (const id of ['board', 'grades', 'commuteMinutes', 'fees']) own.set(stale, byId(id), undefined);
    expect(stale.extrasOther).toBeNull();
  });
});

describe('a school candidate goes through every store, sync and backup of the website unchanged', () => {
  const candidate = V.candidate as unknown as CandidateDto;

  it('the backup: a doorprints-backup/1 file’s candidates are read and written back byte for byte', () => {
    const checked = checkData(V.backup);
    if (!checked.ok) throw new Error(checked.problem);
    const written = currentOf(checked.data as unknown as Record<string, unknown>);
    expect(JSON.stringify(written['candidates'])).toBe(JSON.stringify(V.backup.candidates));
  });

  it('IndexedDB and /api/candidates: the candidate is the house the screens read and back, as it came', () => {
    const house = tryHouseFromDto(houseOf(candidate))!;
    expect(house.kind).toBe('school');
    expect(Object.keys(house.extrasOther!)).toEqual(Object.keys(candidate.extras!));
    expect(JSON.stringify(candidateOf(house))).toBe(JSON.stringify(candidate));
    const stored = storedRowOf(houseOfRow({ ...candidate, dirty: true }));
    expect(JSON.stringify(stored)).toBe(JSON.stringify({ ...candidate, dirty: true }));
  });

  it('the Drive sync file: this device writes the school as it came, and reads it back the same', () => {
    const { syncVersion: _s, ...row } = candidate as unknown as Record<string, unknown>;
    const by = 'd1a2b3c4d5e6f708';
    const asBefore = syncRowAsBefore({ kind: 'houses', json: { ...row, by } } as never);
    expect(JSON.stringify(syncJsonOf('houses', asBefore))).toBe(JSON.stringify({ ...row, by }));
  });

  it('search reads a school’s name, places and notes, and its extras through its kind (S4b-BL-210, kind-search.spec.ts)', () => {
    const h = tryHouseFromDto(houseOf(candidate))!;
    const text = searchText(h, '', [], undefined, (key) => en[key], SCHOOL_FIELDS);
    for (const token of ['pine hill school', 'gs road', 'dispur', 'waiting list', 'admissions office', 'cbse', 'nursery', 'english']) {
      expect(text, token).toContain(token);
    }
    // Since S4b-BL-212 a stored school is searched through its kind with the developer flag and the switch off too.
    for (const token of ['cbse', 'nursery']) expect(searchText(h, '', [], undefined, (key) => en[key]), token).toContain(token);
  });
});
