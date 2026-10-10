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

import { describe, expect, it } from 'vitest';
// ajv is already in the tree (Angular's build tooling depends on it); this spec adds no package. Its 2020-12 build
// validates the kind schema's own dialect.
import Ajv2020 from 'ajv/dist/2020';
import { CHECKLIST, COST_FIELDS, STATUSES, STATUS_KEY, newHouse, type HouseDto } from './models';
import { en } from '../i18n/en';
import { hi } from '../i18n/hi';
import { ta } from '../i18n/ta';
import { te } from '../i18n/te';
import { MAX_AREA_SQFT, MAX_FLOOR, MAX_MONTHS, MAX_RUPEES, MIN_FLOOR } from '../data/records';
import { QUESTION_SCOPES } from '../shared/question';
import { searchText } from '../pages/map/map-list';
// The kind schema, the kind file for houses and its vectors (docs/03 ADR-36 §18, S4b-BL-205); resolveJsonModule is
// on for the specs. Android's HouseKindFileTest and the server's HouseKindFileTest read the same three files.
import schemaJson from '../../../../docs/schemas/kinds/kind.schema.json';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import vectorsJson from '../../../../docs/schemas/kinds/kind-vectors.json';
import sampleJson from '../../../../docs/schemas/backup-sample.json';

interface Label { web: string | null; android: string | null; byCadence?: Record<string, { web?: string; android?: string }> }
interface Field { id: string; section: string; order: number; label: Label; type?: string; min?: number; max?: number; was?: string; cadences?: unknown[] }
interface Kind {
  labels: Record<string, Label>;
  statuses: { id: string; label: Label }[];
  sections: { id: string; label: Label; visible?: boolean }[];
  core: Field[];
  extras: Field[];
  checklist: { key: string; label: Label }[];
  questions: { scopes: { id: string; cadences: string[] }[] };
  search: string[];
  ai: Record<string, string[]>;
}
interface Vectors { kinds: { id: string; statuses: string[]; checklist: string[]; search: string[]; notSearched: string[]; labels: { web: Record<string, Record<string, string | null>> } }[] }

const house = houseJson as unknown as Kind;
const vectors = (vectorsJson as unknown as Vectors).kinds[0];
const DICTS: Record<string, Readonly<Record<string, string>>> = { en, hi, ta, te };

/** Every label of the kind file under its vector id, as kind-vectors.json names them. */
function labelRefs(k: Kind): [string, Label][] {
  const refs: [string, Label][] = Object.entries(k.labels).map(([id, l]) => [`kind.${id}`, l]);
  k.statuses.forEach((s) => refs.push([`status.${s.id}`, s.label]));
  k.sections.forEach((s) => refs.push([`section.${s.id}`, s.label]));
  for (const f of [...k.core, ...k.extras]) {
    refs.push([`field.${f.id}`, f.label]);
    for (const [c, alt] of Object.entries(f.label.byCadence ?? {})) refs.push([`field.${f.id}@${c}`, { ...f.label, ...alt }]);
    for (const c of (f.cadences ?? []) as { id?: string; label?: Label }[]) if (c.id && c.label) refs.push([`cadence.${c.id}`, c.label]);
  }
  k.checklist.forEach((c) => refs.push([`check.${c.key}`, c.label]));
  return refs;
}

describe('the kind file for houses (docs/schemas/kinds)', () => {
  const validate = new Ajv2020({ allErrors: true, strict: true, strictRequired: false }).compile(schemaJson);

  it('is valid against kind.schema.json', () => {
    expect(validate(houseJson), JSON.stringify(validate.errors)).toBe(true);
  });

  it('the schema refuses what a kind file must never hold: inline text, a renamed status, an unknown type or unit', () => {
    const broken = (change: (k: Record<string, any>) => void): boolean => {
      const copy = JSON.parse(JSON.stringify(houseJson)) as Record<string, any>;
      change(copy);
      return validate(copy) as boolean;
    };
    expect(broken((k) => { k['labels'].new.web = 'New house'; })).toBe(false);
    expect(broken((k) => { k['statuses'][0].id = 'OPEN'; })).toBe(false);
    expect(broken((k) => { k['extras'][0].type = 'phone'; })).toBe(false);
    expect(broken((k) => { k['extras'][1].unit = 'acre'; })).toBe(false);
    expect(broken((k) => { k['extras'][0].type = 'bool'; })).toBe(false); // a unit left on a yes/no
    expect(broken((k) => { delete k['extras'][3].max; })).toBe(false); // money with no cap
    expect(broken((k) => { k['core'] = k['core'].filter((f: Field) => f.id !== 'location'); })).toBe(false);
    expect(broken((k) => { k['format'] = 'doorprints-kind/2'; })).toBe(false);
    expect(broken(() => undefined)).toBe(true);
  });

  it('every web label resolves, in the four languages, to the string the vectors pin', () => {
    const refs = labelRefs(house);
    expect(refs.map(([id]) => id).sort()).toEqual(Object.keys(vectors.labels.web['en']).sort());
    for (const lang of ['en', 'hi', 'ta', 'te']) {
      for (const [id, ref] of refs) {
        const resolved = ref.web === null ? null : DICTS[lang][ref.web];
        expect(resolved, `${lang} ${id} (${ref.web})`).not.toBeUndefined();
        expect(resolved, `${lang} ${id} (${ref.web})`).toBe(vectors.labels.web[lang][id]);
      }
    }
  });

  it('names today’s statuses, checklist, cost fields, question scopes and ranges of the web code', () => {
    expect(house.statuses.map((s) => s.id)).toEqual([...STATUSES]);
    expect(vectors.statuses).toEqual([...STATUSES]);
    expect(house.statuses.map((s) => s.label.web)).toEqual(STATUSES.map((s) => STATUS_KEY[s]));
    expect(house.checklist.map((c) => [c.key, c.label.web])).toEqual(CHECKLIST.map((c) => [c.key, c.labelKey]));
    expect(vectors.checklist).toEqual(CHECKLIST.map((c) => c.key));
    expect(house.extras.filter((f) => f.section === 'cost').map((f) => f.was)).toEqual(COST_FIELDS.map((f) => `cost.${f}`));
    expect(house.questions.scopes.map((s) => s.id).sort()).toEqual([...QUESTION_SCOPES].sort());
    const extra = (id: string) => house.extras.find((f) => f.id === id)!;
    expect([extra('areaSqft').min, extra('areaSqft').max]).toEqual([1, MAX_AREA_SQFT]);
    expect([extra('floor').min, extra('floor').max]).toEqual([MIN_FLOOR, MAX_FLOOR]);
    for (const f of house.extras) {
      if (f.type === 'money') expect(f.max, f.id).toBe(MAX_RUPEES);
      if (f.id.endsWith('Months')) expect(f.max, f.id).toBe(MAX_MONTHS);
    }
  });

  it('every key of a backup house today has a place in the kind file, and nothing else does', () => {
    // The sample's first house carries every key (backup-fields.spec.ts); nested lists and stamps are not fields.
    const nested = ['id', 'cost', 'rooms', 'answers', 'moveIn', 'checklist', 'createdAt', 'updatedAt'];
    const wire = Object.keys(sampleJson.houses[0]).filter((k) => !nested.includes(k));
    const placed = [...house.core, ...house.extras].flatMap((f) => (f.was ?? f.id).split(', ')).filter((w) => !w.startsWith('cost.'));
    expect(placed.sort()).toEqual(wire.sort());
  });

  it('search reads exactly the fields the kind file names (searchText, the list on the Map page)', () => {
    const token = (id: string) => `zq${id.toLowerCase()}`;
    const h: HouseDto = {
      ...newHouse(13.05, 80.28),
      label: token('label'), address: token('address'), street: token('street'), locality: token('locality'),
      notes: token('notes'), contactName: token('contactName'), contactPhone: '+91 98400 77123', listingUrl: `https://example.com/${token('listingUrl')}`,
      price: 31337, bedrooms: 7, areaSqft: 4321, floor: 9,
      rooms: [{ id: 'r1', type: 'HALL', name: token('rooms'), sort: 0 }],
      answers: [{ id: 'a1', text: token('questions'), status: 'OPEN', sort: 0 }],
      moveIn: { notes: token('moveIn') },
      cost: { deposit: 64099, agreedPrice: 30199, availableFrom: '2026-12-01' },
    };
    const text = searchText(h, token('brokerId'), [token('areaNotes')]);
    for (const id of vectors.search.filter((s) => s !== 'floor')) expect(text, id).toContain(token(id));
    expect(text).toContain('floor 9');
    for (const absent of ['98400', 'listingurl', '31337', '4321', '64099', '30199', '2026-12-01']) expect(text, absent).not.toContain(absent);
    expect(vectors.notSearched).toEqual(expect.arrayContaining(['contactPhone', 'listingUrl', 'money', 'areaSqft', 'deposit']));
    expect([...house.search].sort()).toEqual(vectors.search);
  });

  it('places every field in a listed section, once, and names only known fields in search and the AI slots', () => {
    const ids = [...house.core, ...house.extras].map((f) => f.id);
    expect(new Set(ids).size).toBe(ids.length);
    const sections = house.sections.map((s) => s.id);
    for (const f of [...house.core, ...house.extras]) expect(sections, f.id).toContain(f.section);
    const known = new Set([...ids, 'rooms', 'questions', 'areaNotes', 'moveIn', 'checklist']);
    for (const id of [...house.search, ...Object.values(house.ai).flat()]) expect(known.has(id), id).toBe(true);
    for (const never of house.ai['never']) expect([...house.ai['ask'], ...house.ai['plan']], never).not.toContain(never);
  });
});
