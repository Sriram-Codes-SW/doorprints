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
import { CHECKLIST, STATUSES, STATUS_KEY } from './models';
import { en } from '../i18n/en';
import { hi } from '../i18n/hi';
import { ta } from '../i18n/ta';
import { te } from '../i18n/te';
// The resolver's vectors and the labels they point at (docs/03 ADR-36 §18.7, S4b-BL-207); Android's
// KindLabelsVectorsTest and KindLabelsScreensTest read the same two files.
import labelVectorsJson from '../../../../docs/schemas/kinds/kind-label-vectors.json';
import kindVectorsJson from '../../../../docs/schemas/kinds/kind-vectors.json';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import { HOUSE_LABELS, KindLabels, kindLabels, labelKey, statusKeys } from './kind-labels';
import { SCHOOL_LABELS } from './school-kind';

interface Label { web: string | null; byCadence?: Record<string, { web?: string }> }
interface Placed { id: string; section: string; label: Label; visible?: boolean; cadences?: unknown[] }
interface HouseFile {
  labels: Record<string, Label>;
  statuses: { id: string; label: Label }[];
  sections: { id: string; label: Label; visible?: boolean }[];
  core: Placed[];
  extras: Placed[];
  checklist: { key: string; label: Label }[];
}

interface LabelCase {
  name: string;
  kind: string;
  ref: string;
  cadence?: string;
  web?: string | null;
  android?: string | null;
  visible?: boolean;
  vector?: string;
  resolved?: null;
}

const labelVectors = labelVectorsJson as unknown as { format: string; cases: LabelCase[] };
const pinned = (kindVectorsJson as unknown as { kinds: { labels: { web: Record<string, Record<string, string | null>> } }[] })
  .kinds[0].labels.web;
const DICTS: Record<string, Readonly<Record<string, string>>> = { en, hi, ta, te };
const LANGS = ['en', 'hi', 'ta', 'te'];
const resolving = labelVectors.cases.filter((c) => c.resolved !== null);
const caseOf = (ref: string) => resolving.find((c) => c.ref === ref && c.cadence === undefined)!;

/**
 * The safety test of kinds PR 3, written before anything moved (S4b-BL-207, docs/06 TC-U-209): the keys the vectors
 * pin are the ones the website reads today, and they show the pinned words in the four languages.
 */
describe('the label vectors and today’s screens (kind-label-vectors.json)', () => {
  it('is the vectors file this reader knows, and every label of kind-vectors.json has a case', () => {
    expect(labelVectors.format).toBe('doorprints-kind-label-vectors/1');
    expect(new Set(resolving.map((c) => c.vector)).size).toBe(Object.keys(pinned['en']).length);
    expect([...new Set(resolving.map((c) => c.vector!))].sort()).toEqual(Object.keys(pinned['en']).sort());
  });

  it('every pinned web key shows the words kind-vectors.json pins, in en, hi, ta and te', () => {
    for (const c of resolving) {
      for (const lang of LANGS) {
        const text = c.web === null ? null : DICTS[lang][c.web!];
        expect(text, `${lang} ${c.name} (${c.web})`).not.toBeUndefined();
        expect(text, `${lang} ${c.name} (${c.web})`).toBe(pinned[lang][c.vector!]);
      }
    }
  });

  it('the status and checklist tables the screens read are the pinned keys', () => {
    for (const s of STATUSES) expect(STATUS_KEY[s], s).toBe(caseOf(`status.${s}`).web);
    for (const item of CHECKLIST) expect(item.labelKey, item.key).toBe(caseOf(`check.${item.key}`).web);
    expect(CHECKLIST.length).toBe(resolving.filter((c) => c.ref.startsWith('check.')).length);
  });
});

/** The resolver (S4b-BL-207, docs/06 TC-U-209): the vectors, today's words in four languages, and its copy of house.json. */
describe('KindLabels, the label and visibility resolver', () => {
  it('every case resolves to the pinned web key and visibility, and nothing where the kind has no such place', () => {
    for (const c of labelVectors.cases) {
      const labels = kindLabels(c.kind);
      const resolved = labels?.resolve(c.ref, c.cadence);
      if (c.resolved === null) {
        expect(resolved, c.name).toBeUndefined();
        expect(labels?.isVisible(c.ref) ?? false, c.name).toBe(false);
        continue;
      }
      expect(resolved, c.name).toEqual({ key: c.web, visible: c.visible });
    }
  });

  it('the resolved labels show today’s words in en, hi, ta and te', () => {
    for (const c of resolving) {
      const key = HOUSE_LABELS.resolve(c.ref, c.cadence)!.key;
      for (const lang of LANGS) expect(key === null ? null : DICTS[lang][key], `${lang} ${c.name}`).toBe(pinned[lang][c.vector!]);
    }
  });

  it('its copy of the house kind is house.json: keys, cadence keys, sections, nothing hidden', () => {
    const house = houseJson as unknown as HouseFile;
    const want: Record<string, string | null> = {};
    const byCadence: Record<string, string> = {};
    Object.entries(house.labels).forEach(([id, l]) => (want[`kind.${id}`] = l.web));
    house.statuses.forEach((s) => (want[`status.${s.id}`] = s.label.web));
    house.sections.forEach((s) => {
      want[`section.${s.id}`] = s.label.web;
      expect(s.visible ?? true, s.id).toBe(true);
    });
    for (const f of [...house.core, ...house.extras]) {
      want[`field.${f.id}`] = f.label.web;
      expect(f.visible ?? true, f.id).toBe(true);
      expect(HOUSE_LABELS.isVisible(`section.${f.section}`), f.id).toBe(true);
      for (const [c, alt] of Object.entries(f.label.byCadence ?? {})) if (alt.web) byCadence[`field.${f.id}@${c}`] = alt.web;
      for (const c of (f.cadences ?? []) as { id?: string; label?: Label }[]) if (c.id && c.label) want[`cadence.${c.id}`] = c.label.web;
    }
    house.checklist.forEach((c) => (want[`check.${c.key}`] = c.label.web));
    expect(HOUSE_LABELS.kind).toBe('house');
    expect(HOUSE_LABELS.refs.sort()).toEqual(Object.keys(want).sort());
    for (const [ref, key] of Object.entries(want)) expect(HOUSE_LABELS.resolve(ref), ref).toEqual({ key, visible: true });
    expect(byCadence).toEqual({}); // the website's price label does not change with the cadence today
    for (const s of STATUSES) expect(HOUSE_LABELS.status(s)).toBe(want[`status.${s}`]);
    for (const item of CHECKLIST) expect(HOUSE_LABELS.check(item.key)).toBe(want[`check.${item.key}`]);
    expect(HOUSE_LABELS.check('playground')).toBeUndefined();
    expect(() => HOUSE_LABELS.status('OPEN')).toThrow();
    expect(HOUSE_LABELS.section('cost')).toEqual({ key: 'house.cost', visible: true });
    expect(HOUSE_LABELS.field('money', 'month')).toEqual({ key: 'house.price', visible: true });
    expect(kindLabels('house')).toBe(HOUSE_LABELS);
  });

  it('a hidden section hides its fields, a hidden field keeps its label, a cadence key wins only for its cadence', () => {
    const kind = new KindLabels({
      kind: 'test',
      keys: { 'section.cost': 'house.cost', 'section.details': null, 'field.deposit': 'cost.deposit', 'field.label': 'house.name',
        'field.notes': 'house.notes', 'field.money': 'house.price', 'status.NEW': 'status.NEW' },
      byCadence: { 'field.money@month': 'price.rent' },
      sections: { deposit: 'cost', label: 'details', notes: 'details', money: 'details' },
      hidden: ['section.cost', 'field.notes'],
    });
    expect(kind.section('cost')).toEqual({ key: 'house.cost', visible: false });
    expect(kind.field('deposit')).toEqual({ key: 'cost.deposit', visible: false });
    expect(kind.field('notes')).toEqual({ key: 'house.notes', visible: false });
    expect(kind.field('label')).toEqual({ key: 'house.name', visible: true });
    expect(kind.section('details')).toEqual({ key: null, visible: true });
    expect(kind.field('money', 'month')).toEqual({ key: 'price.rent', visible: true });
    expect(kind.field('money', 'once')).toEqual({ key: 'house.price', visible: true });
    expect(kind.field('money')).toEqual({ key: 'house.price', visible: true });
    expect(kind.status('NEW')).toBe('status.NEW');
    expect(kind.field('toString')).toBeUndefined();
    expect(kind.isVisible('field.areaSqft')).toBe(false);
    expect(kind.isVisible('section.photos')).toBe(false);
  });
});

// S4b-BL-256: the screens that list statuses read the kind's table (the map's legend, filter and rows; Compare; Ask; the page).
describe('statusKeys (a kind’s status words by name)', () => {
  it('is the house’s STATUS_KEY for the house, and the school’s own table for a school (the same five keys)', () => {
    expect(statusKeys(HOUSE_LABELS)).toEqual(STATUS_KEY);
    expect(statusKeys(SCHOOL_LABELS)).toEqual({
      NEW: 'status.NEW', SHORTLISTED: 'status.SHORTLISTED', REJECTED: 'status.REJECTED', TAKEN: 'status.TAKEN', NOT_CHOSEN: 'status.NOT_CHOSEN',
    });
  });

  it('is built once per kind', () => {
    expect(statusKeys(HOUSE_LABELS)).toBe(statusKeys(HOUSE_LABELS));
    expect(statusKeys(SCHOOL_LABELS)).not.toBe(statusKeys(HOUSE_LABELS));
  });
});

describe('labelKey (a screen’s own word for the house, the kind’s label for another kind)', () => {
  it('keeps the house’s hand-written key for the house, even where the kind has a label for the place', () => {
    expect(labelKey(HOUSE_LABELS, 'field.money', 'compare.price')).toBe('compare.price');
    expect(labelKey(HOUSE_LABELS, 'field.rating', 'compare.rating')).toBe('compare.rating');
  });

  it('reads a school’s label through the resolver, and keeps the house’s key where the school has none', () => {
    expect(labelKey(SCHOOL_LABELS, 'field.listingUrl', 'house.listingUrl')).toBe('school.website');
    expect(labelKey(SCHOOL_LABELS, 'section.cost', 'house.cost')).toBe('school.fees');
    expect(labelKey(SCHOOL_LABELS, 'field.rating', 'compare.rating')).toBe('house.rating');
    expect(labelKey(SCHOOL_LABELS, 'field.money', 'compare.price')).toBe('compare.price');
  });
});
