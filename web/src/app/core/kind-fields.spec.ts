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

// The typed extras of a kind and the website's renderer of them (kinds PR 4, S4b-BL-208; docs/03 §18.8), against the
// kind file (`house.json`, whose copy `HOUSE_FIELDS` is) and the shared `kind-field-vectors.json`, which was written
// from the hand-written form before the renderer existed. Android's KindFieldsVectorsTest reads the same vectors.
import { describe, expect, it, vi } from 'vitest';
import { HOUSE_FIELDS, KindFields, cadenceOf, controlOf, kindFields, type KindExtra } from './kind-fields';
import { HOUSE_KEYS, HOUSE_LABELS, KindKeys, KindLabels } from './kind-labels';
import { KindExtras, MONEY_SECTION } from '../pages/house-detail/kind-extras';
import { newHouse, type HouseDto } from './models';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import fieldVectors from '../../../../docs/schemas/kinds/kind-field-vectors.json';
import labelVectors from '../../../../docs/schemas/kinds/kind-label-vectors.json';

interface FieldVector {
  id: string;
  section: string;
  control: string;
  forCadences?: string[];
  web: { id: string; label: string; suffix: string | null; input: Record<string, string>; switch?: { id: string } };
}
interface RowVector { stack: string; section: string; cadence: 'month' | 'once' | null; rows: string[][] }
const VECTORS = (fieldVectors as unknown as { format: string; kinds: { id: string; fields: FieldVector[]; rows: RowVector[] }[] });
const KIND = VECTORS.kinds[0];
const house = houseJson as unknown as { core: { id: string; section: string }[]; extras: (KindExtra & { label: unknown })[] };
const PRICE_TYPE = { month: 'RENT', once: 'SALE' } as const;

const draftOf = (cadence: 'month' | 'once' | null): HouseDto => ({ ...newHouse(22.5726, 88.3639), priceType: cadence ? PRICE_TYPE[cadence] : null, cost: {} });

describe('the kind fields (HOUSE_FIELDS), a copy of house.json’s extras', () => {
  it('holds every extra of the kind file, in its order, with its type, range, unit, cadences and place', () => {
    expect(HOUSE_FIELDS.extras.map((f) => ({ ...f }))).toEqual(house.extras.map(({ label: _label, ...f }) => f));
  });

  it('labels each extra with the resolver’s key', () => {
    for (const f of HOUSE_FIELDS.extras) expect(HOUSE_FIELDS.label(f), f.id).toBe(HOUSE_LABELS.field(f.id)!.key);
  });

  it('shows a section’s extras by order, and a rent-only one only for a rent or no cadence', () => {
    const ids = (s: string, c: 'month' | 'once' | null) => HOUSE_FIELDS.shown(s, c).map((f) => f.id);
    expect(ids('details', 'once')).toEqual(['bedrooms', 'areaSqft', 'floor']);
    expect(ids('cost', null)).toEqual(house.extras.filter((f) => f.section === 'cost').map((f) => f.id));
    expect(ids('cost', 'month')).toEqual(ids('cost', null));
    expect(ids('cost', 'once')).toEqual(['brokerage', 'brokerageMonths', 'availableFrom', 'myOffer', 'agreedPrice']);
    expect(ids('rooms', 'month')).toEqual([]);
  });

  it('hides an extra the kind hides, or whose section it hides', () => {
    const labels = new KindLabels({ kind: 'test', keys: { 'field.a': 'house.bhk', 'field.b': 'house.floor', 'section.s': 'house.cost' }, byCadence: {}, sections: { a: 's', b: 's' }, hidden: ['field.b'] });
    const fields = new KindFields(labels, [
      { id: 'b', type: 'bool', section: 's', order: 2, was: 'b' },
      { id: 'a', type: 'bool', section: 's', order: 1, was: 'a' },
    ]);
    expect(fields.shown('s', null).map((f) => f.id)).toEqual(['a']);
    const gone = new KindFields(new KindLabels({ kind: 'test', keys: { 'field.a': 'house.bhk', 'section.s': 'house.cost' }, byCadence: {}, sections: { a: 's' }, hidden: ['section.s'] }), fields.extras);
    expect(gone.shown('s', null)).toEqual([]);
    expect(() => fields.label({ id: 'zz', type: 'bool', section: 's', order: 1, was: 'zz' })).toThrow();
  });

  it('draws each type with its control; a level is a floor; text and enum have none yet', () => {
    const f = (type: KindExtra['type'], unit?: string): KindExtra => ({ id: 'x', type, unit, section: 'cost', order: 1, was: 'x' });
    expect([f('number', 'count'), f('number', 'level'), f('money'), f('bool'), f('date')].map(controlOf)).toEqual(['number', 'level', 'money', 'bool', 'date']);
    expect(() => controlOf(f('text'))).toThrow();
    expect(() => controlOf(f('enum'))).toThrow();
  });

  it('reads a price type as its cadence, and knows only the house', () => {
    expect([cadenceOf('RENT'), cadenceOf('SALE'), cadenceOf(null), cadenceOf(undefined)]).toEqual(['month', 'once', null, null]);
    expect(kindFields('house')).toBe(HOUSE_FIELDS);
    expect(kindFields('school')).toBeUndefined();
  });
});

describe('the website’s renderer of the extras (KindExtras) against kind-field-vectors.json', () => {
  const extras = new KindExtras({ patch: () => undefined });

  it('is the vector format, for the house, with every extra of the kind file in its order', () => {
    expect(VECTORS.format).toBe('doorprints-kind-field-vectors/1');
    expect(KIND.id).toBe('house');
    expect(KIND.fields.map((f) => f.id)).toEqual(house.extras.map((f) => f.id));
    expect(KIND.fields.map((f) => f.section)).toEqual(house.extras.map((f) => f.section));
    expect(KIND.fields.map((f) => f.forCadences ?? null)).toEqual(house.extras.map((f) => f.forCadences ?? null));
  });

  it('draws each extra with the vector’s control, element id, label, suffix and input range', () => {
    const all = [...extras.of('details', draftOf(null)), ...extras.of('cost', draftOf(null))];
    expect(all.map((f) => f.id)).toEqual(KIND.fields.map((f) => f.id));
    for (const [i, v] of KIND.fields.entries()) {
      const f = all[i];
      expect([f.control, f.domId, f.label, f.suffix || null], v.id).toEqual([v.control, v.web.id, v.web.label, v.web.suffix]);
      expect([f.min, f.max], v.id).toEqual([v.web.input['min'] ?? null, v.web.input['max'] ?? null]);
      expect(f.switchId, v.id).toBe(v.web.switch?.id ?? null);
      expect(f.narrow, v.id).toBe(v.section === MONEY_SECTION);
    }
  });

  for (const v of KIND.rows.filter((r) => r.stack === 'web')) {
    it(`lays the ${v.section} extras out in the vector’s rows for ${v.cadence ?? 'no'} cadence`, () => {
      expect(extras.rows(v.section, draftOf(v.cadence)).map((row) => row.map((f) => f.id))).toEqual(v.rows);
    });
  }

  it('puts the money’s section where the kind file places the money', () => {
    expect(house.core.find((f) => f.id === 'money')!.section).toBe(MONEY_SECTION);
    expect(extras.rows('rooms', draftOf('month'))).toEqual([]);
    expect(new KindExtras({ patch: () => undefined }, new KindFields(HOUSE_LABELS, [])).rows(MONEY_SECTION, draftOf(null))).toEqual([]);
  });

  it('reads and writes each value where the form keeps it, a switch through patch', () => {
    const patch = vi.fn();
    const own = new KindExtras({ patch });
    const d: HouseDto = { ...draftOf('month'), bedrooms: 2, cost: { deposit: 50000, maintenanceIncluded: false } };
    const [bhk] = own.of('details', d);
    const [deposit, , , included] = own.of('cost', d);
    expect([own.value(d, bhk), own.value(d, deposit), own.value(d, included)]).toEqual([2, 50000, false]);
    own.set(d, bhk, 3);
    own.set(d, deposit, 64000);
    expect([d.bedrooms, d.cost!.deposit]).toEqual([3, 64000]);
    own.setBool(d, included, { target: { checked: true } } as unknown as Event);
    expect(patch).toHaveBeenCalledWith({ cost: { deposit: 64000, maintenanceIncluded: true } });
    const top = new KindExtras({ patch }, new KindFields(HOUSE_LABELS, [{ id: 'maintenanceIncluded', type: 'bool', section: 'cost', order: 1, was: 'pets' }]));
    top.setBool(d, top.of('cost', d)[0], { target: { checked: true } } as unknown as Event);
    expect(patch).toHaveBeenLastCalledWith({ pets: true });
    const noCost = { ...d, cost: undefined };
    expect(own.value(noCost, deposit)).toBeUndefined();
    own.set(noCost, deposit, 1);
    expect(noCost.cost).toBeUndefined();
  });

  it('gives an extra a later kind adds an id of its own, and refuses a level that is not the floor', () => {
    const later = new KindExtras({ patch: () => undefined }, new KindFields(HOUSE_LABELS, [{ id: 'bedrooms', type: 'number', integer: true, unit: 'count', section: 'cost', order: 1, was: 'rooms' }]));
    expect(later.of('cost', draftOf(null)).map((f) => f.domId)).toEqual(['house-bhk']);
    const fresh = new KindExtras({ patch: () => undefined }, new KindFields(new KindLabels({ kind: 't', keys: { 'field.gate': 'house.bhk' }, byCadence: {}, sections: {} }), [{ id: 'gate', type: 'date', section: 'cost', order: 1, was: 'gate' }]));
    expect(fresh.of('cost', draftOf(null))[0].domId).toBe('extra-gate');
    expect(() => new KindExtras({ patch: () => undefined }, new KindFields(HOUSE_LABELS, [{ id: 'floor', type: 'number', integer: true, unit: 'level', section: 'details', order: 1, was: 'cost.floor' }]))).toThrow();
  });
});

describe('the kind’s keys for templates (KindKeys)', () => {
  const cases = (labelVectors as unknown as { cases: { kind: string; ref: string; web: string | null; cadence?: string; resolved?: null }[] }).cases;

  it('give every field and section the resolver’s web key, as kind-label-vectors.json pins it', () => {
    const named = cases.filter((c) => c.kind === 'house' && c.web && c.resolved !== null && /^(field|section|cadence)\./.test(c.ref));
    expect(named.length).toBeGreaterThan(40);
    for (const c of named) expect(HOUSE_KEYS.ref(c.ref, c.cadence), c.ref).toBe(c.web);
    expect(HOUSE_KEYS.field('notes')).toBe('house.notes');
    expect(HOUSE_KEYS.section('photos')).toBe('house.photos');
  });

  it('throw for a place the kind has no text for, so a screen never shows an empty label', () => {
    expect(() => HOUSE_KEYS.field('schoolBoard')).toThrow();
    expect(() => new KindKeys(new KindLabels({ kind: 't', keys: { 'section.details': null }, byCadence: {}, sections: {} })).section('details')).toThrow();
  });
});
