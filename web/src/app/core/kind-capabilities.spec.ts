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
import Ajv2020 from 'ajv/dist/2020';
import { CHECKLIST } from './models';
import { HOUSE_LABELS } from './kind-labels';
import { SCHOOL_FIELDS, SCHOOL_LABELS } from './school-kind';
import { filterableExtras } from '../shared/extra-filter';
import { HOUSE_FIELDS } from './kind-fields';
import {
  CAPABILITY_COLOURS, CAPABILITY_ICONS, CAPABILITY_KINDS, DEFAULT_CAPABILITIES, MAX_HUNT_RADIUS_M, MIN_HUNT_RADIUS_M, capabilitiesOf, parseCapabilities,
} from './kind-capabilities';
// The kind schema, the two kind files and the capabilities vectors (docs/03 ADR-36 §18.19); resolveJsonModule is on
// for the specs. The phones' KindCapabilitiesVectorsTest reads the same files.
import schemaJson from '../../../../docs/schemas/kinds/kind.schema.json';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../docs/schemas/kinds/school.json';
import vectorsJson from '../../../../docs/schemas/kinds/capabilities-vectors.json';
import testAllJson from '../../../../docs/schemas/kinds/test-all.json';

interface KindFile { id: string; sections: { id: string; visible?: boolean }[]; core: { id: string; visible?: boolean }[]; extras: { id: string }[]; checklist: { key: string }[]; capabilities?: unknown }
interface Vectors {
  defaults: unknown;
  today: { huntAlertRadiusM: number; sectionsFollow: Record<string, string> };
  kinds: Record<string, unknown>;
  unknown: string[];
  synthetic: Record<string, unknown>;
  valid: { name: string; capabilities: unknown; resolved: unknown }[];
  invalid: { name: string; json: string }[];
  forbiddenMapKeys: string[];
  icons: string[];
  colours: string[];
}

const vectors = vectorsJson as unknown as Vectors;
const FILES: Record<string, KindFile> = { house: houseJson as unknown as KindFile, school: schoolJson as unknown as KindFile };
const validate = new Ajv2020({ allErrors: true, strict: true, strictRequired: false }).compile(schemaJson);

/** The house file with its `capabilities` replaced, validated against the schema. */
function schemaAccepts(capabilities: unknown): boolean {
  const copy = JSON.parse(JSON.stringify(houseJson)) as Record<string, unknown>;
  copy['capabilities'] = capabilities;
  return validate(copy) as boolean;
}

describe('kind capabilities (docs/schemas/kinds, ADR-36 §18.19)', () => {
  it('the built-in kind files are valid against the schema, and a kind file without capabilities still is', () => {
    for (const file of Object.values(FILES)) expect(validate(file), JSON.stringify(validate.errors)).toBe(true);
    const bare = JSON.parse(JSON.stringify(houseJson)) as Record<string, unknown>;
    delete bare['capabilities'];
    expect(validate(bare), JSON.stringify(validate.errors)).toBe(true);
  });

  it('the resolver answers, for each built-in kind, what its kind file declares and what the vectors pin', () => {
    expect([...CAPABILITY_KINDS].sort()).toEqual(Object.keys(FILES).sort());
    for (const [id, file] of Object.entries(FILES)) {
      const declared = parseCapabilities(file.capabilities);
      expect(declared, id).not.toBeNull();
      expect(capabilitiesOf(id), id).toEqual(declared);
      expect(capabilitiesOf(id), id).toEqual(vectors.kinds[id]);
    }
    expect(Object.keys(vectors.kinds).sort()).toEqual(Object.keys(FILES).sort());
  });

  it('the synthetic kind of the conformance suite resolves to the set the vectors pin, and a number or an amount may be a filter (docs/03 §18.28)', () => {
    const file = testAllJson as unknown as KindFile;
    expect(validate(file), JSON.stringify(validate.errors)).toBe(true);
    const resolved = parseCapabilities(file.capabilities);
    expect(resolved).toEqual(vectors.synthetic['zz_all']);
    expect(resolved?.filters).toEqual(['board', 'includesMeals', 'seats', 'fees']);
    expect(resolved?.copyOrder).toEqual(['visits', 'notes', 'cost']);
    for (const id of ['house', 'school']) expect(capabilitiesOf(id).copyOrder, id).toEqual([]);
  });

  it('the house\'s set is today\'s hard-coded behaviour: Hunt\'s radius, the checklist keys, the sections it shows, the broker rule', () => {
    const house = capabilitiesOf('house');
    const file = FILES['house'];
    expect(house.hunt).toEqual({ alertRadiusM: vectors.today.huntAlertRadiusM, stayCreates: true, streetMemory: true, areas: true });
    expect(house.compare?.criteria).toEqual(CHECKLIST.map((c) => c.key));
    expect(house.compare?.criteria).toEqual(file.checklist.map((c) => c.key));
    expect(house.contacts).toEqual({ role: 'broker', autoCreateFromPhone: HOUSE_LABELS.isVisible('field.brokerId') });
    expect(house.viewings).toEqual({ questionsSeed: 'house' });
    expect(house.map).toBe(true);
  });

  it('every kind\'s switches follow the sections its file shows, and its contact follows the broker field', () => {
    const labels = { house: HOUSE_LABELS, school: SCHOOL_LABELS } as const;
    for (const [id, file] of Object.entries(FILES)) {
      const shown = (section: string) => file.sections.some((s) => s.id === section && s.visible !== false);
      const caps = capabilitiesOf(id);
      expect(caps.rooms, `${id} rooms`).toBe(shown(vectors.today.sectionsFollow['rooms']));
      expect(caps.moveIn, `${id} moveIn`).toBe(shown(vectors.today.sectionsFollow['moveIn']));
      expect(caps.viewings !== null, `${id} viewings`).toBe(shown(vectors.today.sectionsFollow['viewings']));
      expect(caps.paths, `${id} paths`).toBe(shown(vectors.today.sectionsFollow['paths']));
      expect(caps.viewings !== null, `${id} questions`).toBe(shown(vectors.today.sectionsFollow['questions']));
      // The broker field is the file's own, and the broker is made from a phone only where the file's contact says so (kinds PR 29).
      expect(caps.contacts?.role === 'broker', `${id} broker field`).toBe(file.core.some((c) => c.id === 'brokerId' && c.visible !== false));
      expect(caps.contacts?.autoCreateFromPhone ?? false, `${id} broker`).toBe(caps.contacts?.role === 'broker');
      // The labels show a place a capability switches only while the capability is on, and only where the file lists it.
      const L = labels[id as keyof typeof labels];
      expect(L.isVisible('section.rooms'), `${id} rooms shown`).toBe(caps.rooms && shown('rooms'));
      expect(L.isVisible('section.moveIn'), `${id} moveIn shown`).toBe(caps.moveIn && shown('moveIn'));
      expect(L.isVisible('section.viewings'), `${id} viewings shown`).toBe(caps.viewings !== null && shown('viewings'));
      expect(L.isVisible('section.questions'), `${id} questions shown`).toBe(caps.viewings !== null && shown('questions'));
      expect(L.isVisible('field.brokerId'), `${id} broker shown`).toBe(caps.contacts?.role === 'broker');
      const known = [...file.checklist.map((c) => c.key), ...file.extras.map((e) => e.id)];
      for (const c of caps.compare?.criteria ?? []) expect(known, `${id} criterion ${c}`).toContain(c);
    }
  });

  it('the school has Hunt at 100 m with a stay that makes a school, the street memory and the areas (kinds PR 26); every kind\'s radius is inside 30 to 300', () => {
    expect(capabilitiesOf('school').hunt).toEqual({ alertRadiusM: 100, stayCreates: true, streetMemory: true, areas: true });
    for (const id of CAPABILITY_KINDS) {
      const r = capabilitiesOf(id).hunt?.alertRadiusM;
      if (r !== undefined) expect(r >= MIN_HUNT_RADIUS_M && r <= MAX_HUNT_RADIUS_M, id).toBe(true);
    }
  });

  // Kinds PR 30 (docs/03 §18.24): the list's filter rows are the kind file's `capabilities.filters`.
  it('every filter of a kind is an enum or switch extra of its file, in the order the file gives, and the house has none', () => {
    for (const [id, file] of Object.entries(FILES)) {
      const extras = (file as unknown as { extras: { id: string; type: string }[] }).extras;
      for (const f of capabilitiesOf(id).filters) expect(['enum', 'bool'], `${id} ${f}`).toContain(extras.find((e) => e.id === f)?.type);
    }
    expect(capabilitiesOf('house').filters).toEqual([]);
    expect(capabilitiesOf('school').filters).toEqual(['board', 'medium', 'busService']);
    expect(filterableExtras(SCHOOL_FIELDS).map((f) => f.id)).toEqual(capabilitiesOf('school').filters);
    expect(filterableExtras(HOUSE_FIELDS)).toEqual([]);
  });

  it('a kind this build does not know has the defaults: the map and the paths, nothing else', () => {
    expect(parseCapabilities({})).toEqual(DEFAULT_CAPABILITIES);
    expect(DEFAULT_CAPABILITIES).toEqual(vectors.defaults);
    for (const id of vectors.unknown) expect(capabilitiesOf(id), id).toBe(DEFAULT_CAPABILITIES);
    expect(capabilitiesOf(null)).toBe(DEFAULT_CAPABILITIES);
    expect(capabilitiesOf(undefined)).toBe(DEFAULT_CAPABILITIES);
    expect(parseCapabilities(undefined)).toBeNull();
  });

  it('the resolved sets cannot be changed by a caller', () => {
    for (const id of [...CAPABILITY_KINDS, 'nope']) {
      const caps = capabilitiesOf(id);
      expect(Object.isFrozen(caps), id).toBe(true);
      if (caps.compare) expect(Object.isFrozen(caps.compare.criteria), id).toBe(true);
      if (caps.hunt) expect(Object.isFrozen(caps.hunt), id).toBe(true);
    }
  });

  it('valid objects resolve to the vectors, in the reader and in the schema', () => {
    for (const c of vectors.valid) {
      expect(parseCapabilities(c.capabilities), c.name).toEqual(c.resolved);
      expect(schemaAccepts(c.capabilities), c.name + JSON.stringify(validate.errors)).toBe(true);
    }
  });

  it('invalid objects are refused by the schema and by the reader, a prototype key among them', () => {
    expect(vectors.invalid.length).toBeGreaterThan(55);
    for (const c of vectors.invalid) {
      const parsed: unknown = JSON.parse(c.json);
      expect(parseCapabilities(parsed), c.name).toBeNull();
      expect(schemaAccepts(parsed), c.name).toBe(false);
    }
    // The three pollution cases really carry the key as an own property, and nothing leaks into Object.prototype.
    const own = JSON.parse('{"__proto__":{"map":false}}') as object;
    expect(Object.keys(own)).toEqual(['__proto__']);
    expect(({} as Record<string, unknown>)['map']).toBeUndefined();
  });

  it('the icon and colour lists are the schema\'s and the vectors\'', () => {
    const identity = (schemaJson as any).properties.capabilities.properties.identity.properties;
    expect([...CAPABILITY_ICONS]).toEqual(identity.icon.enum);
    expect([...CAPABILITY_COLOURS]).toEqual(identity.colour.enum);
    expect(vectors.icons).toEqual([...CAPABILITY_ICONS]);
    expect(vectors.colours).toEqual([...CAPABILITY_COLOURS]);
    expect(CAPABILITY_ICONS.length).toBe(24);
  });

  it('the limits are the schema\'s: the radius 30 to 300 and ten criteria', () => {
    const p = (schemaJson as any).properties.capabilities.properties;
    expect([p.hunt.properties.alertRadiusM.minimum, p.hunt.properties.alertRadiusM.maximum]).toEqual([30, 300]);
    expect(p.hunt.required).toEqual(['alertRadiusM', 'stayCreates', 'streetMemory', 'areas']);
    expect(p.compare.properties.criteria.maxItems).toBe(10);
    expect(p.viewings.properties.questionsSeed.enum).toEqual(['house', 'school']);
  });
  it('a kind file has no map configuration: map keys are refused at the root, in a section, in capabilities and in identity, and map is a boolean only', () => {
    expect(vectors.forbiddenMapKeys.length).toBe(14);
    const house = () => JSON.parse(JSON.stringify(houseJson)) as Record<string, any>;
    const accepted = (change: (k: Record<string, any>) => void): boolean => { const k = house(); change(k); return validate(k) as boolean; };
    expect(accepted(() => undefined)).toBe(true);
    for (const key of vectors.forbiddenMapKeys) {
      expect(accepted((k) => { k[key] = 'x'; }), `root ${key}`).toBe(false);
      expect(accepted((k) => { k['sections'][0][key] = 'x'; }), `section ${key}`).toBe(false);
      expect(accepted((k) => { k['capabilities'][key] = 'x'; }), `capabilities ${key}`).toBe(false);
      expect(accepted((k) => { k['capabilities'].identity = { icon: 'home', colour: 'plum', [key]: 'x' }; }), `identity ${key}`).toBe(false);
      expect(accepted((k) => { k['capabilities'].map = { [key]: 'x' }; }), `map object ${key}`).toBe(false);
    }
    expect(accepted((k) => { k['capabilities'].map = { enabled: true }; })).toBe(false);
  });
});
