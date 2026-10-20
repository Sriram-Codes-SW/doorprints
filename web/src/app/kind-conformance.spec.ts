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


// The conformance suite, part 1: the data flows of every kind (kinds PR 32, docs/03 §18.26; docs/06 TC-U-281). The same tests run for the
// house (as today), the school and the synthetic kind of `docs/schemas/kinds/test-all.json`, which has every capability on and is registered for
// the run only: the registry reads the kind file, the routes exist, a list is searched and filtered by the file's own ids, a candidate is saved,
// deleted and restored with its extras, a copy and a backup carry it, and the AI reads the file's slots. A feature that works only for the two
// built-in kinds fails against the third. Part 2 (`kind-conformance-pages.spec.ts`) renders the pages.
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import Ajv2020 from 'ajv/dist/2020';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import schemaJson from '../../../docs/schemas/kinds/kind.schema.json';
import { routesFor } from './app.routes';
import { aiText, aiKindOf } from './core/ai/kind-ai';
import { kindOfCandidate } from './core/candidate';
import { capabilitiesOf, isBaselineKind, parseCapabilities } from './core/kind-capabilities';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY, availableKinds, drawnKind } from './core/kind-setting';
import { SpaceService } from './core/space.service';
import { spaceFileName } from './core/space-scope';
import { spaceWords } from './core/space-words';
import { toolWord } from './core/space-tool-words';
import { LocalStore } from './data/local-store.service';
import { tryHouseFromDto, type HouseRecord } from './data/records';
import { checkData } from './export/backup-check';
import { backupDataJson } from './export/backup-export';
import { buildCsvTables } from './export/csv-export';
import { DEFAULT_EXPORT_OPTIONS, collect } from './export/export-model';
import { buildHtml } from './export/html-export';
import { EMPTY_LOCAL, plan } from './export/import-plan';
import { buildMarkdown } from './export/markdown-export';
import { DICTIONARIES } from './i18n/all-dictionaries';
import type { Lang } from './i18n/languages';
import { extraFilterMatches, filterableExtras, isRangeExtra } from './shared/extra-filter';
import { searchText } from './pages/map/map-list';
import { FLOW_KINDS, forKind, type FlowKindId } from './shared/testing/kind-flows';

const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const T1 = Date.parse('2026-10-01T00:00:00.000Z');
const T2 = Date.parse('2026-10-02T00:00:00.000Z');
const rec = (h: ReturnType<ReturnType<typeof forKind>['house']>): HouseRecord => tryHouseFromDto(h)!;

describe.each(FLOW_KINDS)('conformance of the kind %s: data flows', (id: FlowKindId) => {
  let k: ReturnType<typeof forKind>;
  beforeEach(() => {
    k = forKind(id);
  });
  afterEach(() => {
    k.release();
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('is a valid kind file, and the registry reads exactly what it says', () => {
    const validate = new Ajv2020({ strict: false, allErrors: true }).compile(schemaJson);
    expect(validate(k.file), JSON.stringify(validate.errors?.slice(0, 3))).toBe(true);
    const kind = k.kind();
    expect(kind.id).toBe(id);
    expect(kind.fields.extras.map((e) => e.id)).toEqual(k.file.extras.map((e) => e.id));
    expect(capabilitiesOf(id)).toEqual(parseCapabilities(k.file.capabilities));
    expect(isBaselineKind(id)).toBe(id === 'house');
    expect(kind.labels.checklistKeys()).toEqual(k.file.checklist.map((c) => c.key));
    for (const s of k.file.statuses) expect(kind.labels.status(s.id)).toBe(s.label.web);
    expect(kind.keys.ref('kind.many')).toBe(k.file.labels.many.web);
    expect(drawnKind(id).id).toBe(id);
    expect(availableKinds(true)).toContain(id);
  });

  it('shows a place of the candidate only when the kind has it and its capability is on', () => {
    const { labels } = k.kind();
    const caps = k.caps;
    for (const c of k.file.core) {
      const gated = c.id === 'brokerId' ? caps.contacts?.role === 'broker' : true;
      const section = k.file.sections.find((s) => s.id === c.section)!;
      const sectionOn = section.visible !== false && (c.section !== 'rooms' || caps.rooms) && (c.section !== 'moveIn' || caps.moveIn);
      expect(labels.isVisible(`field.${c.id}`), `${id} field.${c.id}`).toBe(c.visible !== false && gated && sectionOn);
    }
    for (const e of k.file.extras) expect(labels.field(e.id)?.key, `${id} ${e.id}`).toBe(e.label.web);
    expect(labels.isVisible('section.rooms')).toBe(caps.rooms && k.file.sections.some((s) => s.id === 'rooms'));
    expect(labels.isVisible('section.moveIn')).toBe(caps.moveIn && k.file.sections.some((s) => s.id === 'moveIn'));
    expect(labels.isVisible('section.viewings')).toBe(caps.viewings !== null && k.file.sections.some((s) => s.id === 'viewings'));
    expect(labels.isVisible('section.questions')).toBe(caps.viewings !== null && k.file.sections.some((s) => s.id === 'questions'));
    expect(labels.isVisible('field.brokerId')).toBe(caps.contacts?.role === 'broker' && k.file.core.some((c) => c.id === 'brokerId'));
  });

  for (const lang of LANGS) {
    it(`has text in ${lang} for every key the kind and its words use`, () => {
      const dict = DICTIONARIES[lang] as Record<string, string>;
      const { labels } = k.kind();
      const keys = new Set<string>(labels.refs.map((r) => labels.resolve(r)?.key).filter((x): x is NonNullable<typeof x> => !!x));
      const words = spaceWords(id);
      for (const w of Object.values(words)) keys.add(w);
      for (const key of keys) expect(typeof dict[key], `${lang} ${key}`).toBe('string');
      for (const f of k.kind().fields.extras) for (const o of f.options ?? []) expect(typeof dict[o.label], `${lang} ${o.label}`).toBe('string');
    });
  }

  it('has its pages: the list, the new form, a candidate and the tools, on the addresses its capabilities give', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, JSON.stringify(['house', id].filter((x, i, a) => a.indexOf(x) === i)));
    localStorage.setItem(DEFAULT_KIND_KEY, id);
    const routes = routesFor(true, ['house', id].filter((x, i, a) => a.indexOf(x) === i));
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const space = TestBed.inject(SpaceService);
    const paths = new Set(routes.map((r) => r.path));
    await TestBed.inject(Router).navigateByUrl(space.home(id));
    expect(space.kind()).toBe(id);
    const own = id === 'house' ? [] : [`k/${id}`, `k/${id}/new`, `k/${id}/:id`, `k/${id}/compare`, `k/${id}/ask`, `k/${id}/plan`];
    for (const p of own) expect(paths.has(p), `${id} ${p}`).toBe(true);
    for (const t of ['compare', 'ask', 'plan'] as const) {
      const link = space.toolLink(t, id);
      expect(paths.has(link.slice(1)), `${id} ${link}`).toBe(true);
    }
    expect(space.detailLink('x', id)).toEqual(id === 'house' ? ['/houses', 'x'] : ['/k', id, 'x']);
    expect(paths.has(space.newLink(id).slice(1)) || space.newLink(id) === '/houses/new').toBe(true);
  });

  it('is found by the list search through the kind file’s own ids, and filtered by the file’s `filters`', () => {
    const { fields } = k.kind();
    const h0 = k.house(0);
    const h1 = k.house(1);
    const text = searchText(h0, '', [], undefined, (key) => DICTIONARIES.en[key], fields).toLowerCase();
    for (const part of [h0.label, h0.locality, h0.notes, h0.contactName]) expect(text, `${id} ${part}`).toContain(String(part).toLowerCase());
    for (const e of fields.others(true)) {
      const v = (h0.extrasOther as Record<string, unknown>)[e.id];
      if (typeof v === 'string' && e.type === 'text') expect(text, `${id} ${e.id}`).toContain(v.toLowerCase());
    }
    const rows = filterableExtras(fields);
    expect(rows.map((r) => r.id)).toEqual(k.caps.filters.filter((f) => fields.others().some((o) => o.id === f && ['enum', 'bool', 'number', 'money'].includes(o.type))));
    for (const row of rows) {
      // A number or an amount filters by a range (docs/03 §18.27): from the first candidate's value towards the side the second one is not on.
      const [v0, v1] = [h0, h1].map((h) => (h.extrasOther as Record<string, unknown>)[row.id]).map((v) => (typeof v === 'object' ? (v as { amount: number }).amount : v)) as number[];
      const choice = isRangeExtra(row) ? (v0 < v1 ? `:${v0}` : `${v0}:`) : row.type === 'bool' ? 'yes' : row.options![0].id;
      const filter = { [row.id]: choice };
      expect(extraFilterMatches(filter, fields, h0), `${id} ${row.id} matches the first`).toBe(true);
      expect(extraFilterMatches(filter, fields, h1), `${id} ${row.id} drops the second`).toBe(false);
    }
  });

  it('is saved, deleted and restored with its extras, and the store keeps its kind', async () => {
    const store = new LocalStore();
    await store.ready();
    const saved = await store.saveHouse(k.house(0), T1);
    expect(kindOfCandidate(saved)).toBe(id);
    const read = (await store.getHouse(saved.id))!;
    expect(read.label).toBe(k.house(0).label);
    expect(read.status).toBe('SHORTLISTED');
    expect(read.checklist).toEqual(k.house(0).checklist);
    if (id === 'house') expect(read.bedrooms).toBe(k.house(0).bedrooms);
    else expect(read.extrasOther).toEqual(k.house(0).extrasOther);
    await store.deleteHouse(saved.id, T2);
    expect(await store.getHouse(saved.id)).toBeUndefined();
    expect((await store.deletedHouses()).map((h) => h.id)).toContain(saved.id);
    const back = await store.restoreHouse(saved.id, T2 + 1000);
    expect(back).toBeDefined();
    const again = (await store.getHouse(saved.id))!;
    expect(kindOfCandidate(again)).toBe(id);
    expect(id === 'house' ? again.bedrooms : again.extrasOther).toEqual(id === 'house' ? read.bedrooms : read.extrasOther);
    expect(again.updatedAt! > read.updatedAt!).toBe(true);
  });

  it('is copied in its own words: the file name, the label and every extra the kind shows', () => {
    const kind = k.kind();
    const dict = DICTIONARIES.en;
    const bundle = collect({
      houses: [rec(k.house(0)), rec(k.house(1))], visits: [], photos: [], exportedAt: '2026-10-14T10:00:00.000Z',
      kinds: { fieldsOf: (kd) => drawnKind(kd).fields, dict },
      options: { ...DEFAULT_EXPORT_OPTIONS, lang: 'en', ...(id === 'house' ? {} : { space: id }) },
      ...(id === 'house' ? {} : { inSpace: (h: HouseRecord) => kindOfCandidate(h) === id }),
    });
    const md = buildMarkdown(bundle, dict);
    const html = buildHtml(bundle, dict, new Map());
    const csv = Object.values(buildCsvTables(bundle)).join('\n');
    for (const text of [md, html, csv]) expect(text, `${id} label`).toContain(k.house(0).label);
    for (const e of kind.fields.others()) {
      const label = dict[kind.fields.label(e)];
      expect(md, `${id} ${e.id} in Markdown`).toContain(label);
      expect(html, `${id} ${e.id} in HTML`).toContain(label);
    }
    const name = spaceFileName('Doorprints-2026-10-14.md', id, dict[kind.keys.ref('kind.many')]);
    expect(name === 'Doorprints-2026-10-14.md').toBe(isBaselineKind(id));
    if (!isBaselineKind(id)) expect(name).toMatch(/^Doorprints-[a-z]+-2026-10-14\.md$/);
  });

  it('survives a backup and its import: the kind, the extras, the money and the checklist come back as they went', () => {
    const originals = [rec(k.house(0)), rec(k.house(1))];
    const bundle = collect({
      houses: originals, visits: [], photos: [], exportedAt: '2026-10-14T10:00:00.000Z',
      kinds: { fieldsOf: (kd) => drawnKind(kd).fields, dict: DICTIONARIES.en },
      options: { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' },
    });
    const checked = checkData(JSON.parse(backupDataJson(bundle)));
    if (!checked.ok) throw new Error(`refused: ${checked.problem}`);
    let n = 0;
    const actions = plan(checked.data, new Set(), EMPTY_LOCAL, { mode: 'MERGE' }, () => `new-${n++}`);
    expect(actions.houses).toHaveLength(2);
    for (const original of originals) {
      const got = actions.houses.find((h) => h.id === original.id)!;
      const back = tryHouseFromDto(got as never)!;
      expect(kindOfCandidate(back), `${id} kind`).toBe(id);
      expect(back.label).toBe(original.label);
      expect(back.price).toBe(original.price);
      expect(back.priceType).toBe(original.priceType);
      expect(back.checklist).toEqual(original.checklist);
      if (id === 'house') expect(back.bedrooms).toBe(original.bedrooms);
      else expect(back.extrasOther).toEqual(original.extrasOther);
    }
  });

  it('is read by the AI through the file: the document names the kind’s noun and the slot’s extras in their words', () => {
    const h = k.house(0);
    const ai = { id: h.id, kind: id === 'house' ? undefined : id, label: h.label, address: h.address, street: h.street, locality: h.locality, lat: h.lat, lon: h.lon,
      status: h.status, rating: h.rating, notes: h.notes, price: h.price, priceType: h.priceType, extras: h.extrasOther, contactName: h.contactName, contactPhone: h.contactPhone,
      areaNotes: null, distances: null, visits: [] } as never;
    const doc = aiText(ai, true);
    if (id === 'house') {
      expect(aiKindOf(id, true)).toBeNull();
      expect(doc.startsWith('House: ')).toBe(true);
      return;
    }
    const kind = aiKindOf(id, true)!;
    expect(kind.noun).toBe(k.file.ai.words!.noun);
    expect(doc.startsWith(`${kind.noun}: ${h.label}`)).toBe(true);
    for (const e of kind.extras.filter((x) => kind.ask.includes(x.id) && !kind.never.includes(x.id))) {
      expect(doc, `${id} ${e.id}`).toContain(`${e.words}:`);
    }
    for (const never of kind.never) expect(doc, `${id} never ${never}`).not.toContain('98765');
    expect(toolWord(id, 'compare.title')).toBe(id === 'school' ? 'school.compareTitle' : 'compare.title');
  });
});
