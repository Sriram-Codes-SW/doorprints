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

// The conformance flows for a kind a person adds (docs/03 ADR-39, docs/06 TC-U-293): the gym of user-kind-vectors.json, stored as a person's import
// stores it (no development-only seam), runs through the registry, the three layers, the words, the routes, search and filters, saving, deleting and
// restoring, the copies, the backup and its import, the AI (which never reads it) and India's boundaries, as the built-in kinds do in
// kind-conformance.spec.ts.
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import type { StyleSpecification } from 'maplibre-gl';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { routesFor } from './app.routes';
import { houseOf, kindOfCandidate, type CandidateDto, type ExtraValue } from './core/candidate';
import { capabilitiesOf, isBaselineKind } from './core/kind-capabilities';
import {
  CUSTOM_KINDS_KEY, DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, availableKinds, drawnKind, enabledKinds, kindById, offeredKinds, setCustomKinds, setKindEnabled,
  unreleasedKinds,
} from './core/kind-setting';
import { kindWordIds, kindWordKey } from './core/kind-word-table';
import { SpaceService } from './core/space.service';
import { aiShownIn, spaceFileName } from './core/space-scope';
import { spaceWords } from './core/space-words';
import { toolWord } from './core/space-tool-words';
import { USER_KINDS_KEY, adoptUserKinds, reloadUserKinds, removeUserKind, saveUserKind, userKindFiles } from './core/user-kinds/user-kinds';
import { readUserKind } from './core/user-kinds/user-kind-file';
import type { HouseDto } from './core/models';
import { LocalStore } from './data/local-store.service';
import { tryHouseFromDto, type HouseRecord } from './data/records';
import { checkData } from './export/backup-check';
import { backupDataJson } from './export/backup-export';
import { userKindsOf } from './export/backup-family';
import { buildCsvTables } from './export/csv-export';
import { DEFAULT_EXPORT_OPTIONS, collect } from './export/export-model';
import { buildHtml } from './export/html-export';
import { EMPTY_LOCAL, plan } from './export/import-plan';
import { buildMarkdown } from './export/markdown-export';
import { DICTIONARIES } from './i18n/all-dictionaries';
import { applyIndiaBoundaries, indiaBoundaryStyle, type BoundaryStyleTarget } from './shared/india-boundaries';
import { libertyExcerpt } from './shared/testing/liberty-style.fixture';
import { USER_KIND_VECTORS, userKindSample } from './shared/testing/user-kind-vectors';
import { extraFilterMatches, filterableExtras } from './shared/extra-filter';
import { questionKindOf, questionScopesOf } from './shared/question';
import { searchText } from './pages/map/map-list';

const LANGS = ['en', 'hi', 'ta', 'te'] as const;
const ID = 'u_gym';
const T1 = Date.parse('2026-10-01T00:00:00.000Z');
const T2 = Date.parse('2026-10-02T00:00:00.000Z');

function store(files: unknown[]): void {
  localStorage.setItem(USER_KINDS_KEY, JSON.stringify(files));
  reloadUserKinds();
}

type FileExtra = { id: string; type: string; min?: number; max?: number; cadences?: string[]; options?: { id: string }[] };
function extraValueOf(e: FileExtra, n: number): ExtraValue {
  switch (e.type) {
    case 'number': return Math.min(e.max ?? 1000, (e.min ?? 0) + 2 + n);
    case 'money': return { amount: 2000 - n * 500, cadence: (e.cadences?.[0] ?? 'month') as never };
    case 'text': return n === 0 ? 'Six to ten' : 'Seven to nine';
    case 'enum': return e.options![Math.min(n, e.options!.length - 1)].id;
    case 'date': return n === 0 ? '2026-10-01' : '2026-11-15';
    default: return n === 0;
  }
}

/** A gym entry, `n` 0 or 1 for two different ones. */
function gym(n = 0): HouseDto {
  const file = userKindSample(ID);
  const extras: Record<string, ExtraValue> = {};
  for (const e of file['extras'] as FileExtra[]) extras[e.id] = extraValueOf(e, n);
  const c = {
    id: `66666666-6666-4666-8666-00000000000${n}`, kind: ID, label: `${n === 0 ? 'Alpha' : 'Beta'} Fitness`, address: `${n + 2} MG Road, Pune`,
    street: 'MG Road', locality: 'Camp', lat: 18.51 + n * 0.01, lon: 73.88, status: n === 0 ? 'SHORTLISTED' : 'NEW', rating: 4 - n, notes: `Note ${n}`,
    contactName: 'Front desk', contactPhone: '+91 98765 43210', checklist: { clean: 5 - n, equipment: 4 - n }, extras, deleted: false, syncVersion: 0,
    createdAt: '2026-10-01T10:00:00.000Z', updatedAt: '2026-10-01T10:00:00.000Z',
  } as unknown as CandidateDto;
  return houseOf(c);
}
const rec = (h: HouseDto): HouseRecord => tryHouseFromDto(h)!;

describe('conformance of a kind a person adds (u_gym)', () => {
  beforeEach(() => {
    localStorage.clear();
    store([userKindSample(ID)]);
  });
  afterEach(() => {
    localStorage.clear();
    reloadUserKinds();
    TestBed.resetTestingModule();
  });

  it('is read from the store as its file says, and is a kind of its own (not the baseline)', () => {
    const kind = kindById(ID);
    const v = USER_KIND_VECTORS.valid.find((x) => x.resolved.id === ID)!;
    expect(kind.id).toBe(ID);
    expect(drawnKind(ID).id).toBe(ID);
    expect(kind.fields.extras.map((e) => e.id)).toEqual(v.resolved.extras);
    expect(kind.fields.search).toEqual(v.resolved.search);
    expect(capabilitiesOf(ID)).toEqual(v.resolved.capabilities);
    expect(isBaselineKind(ID)).toBe(false);
    expect(kind.labels.checklistKeys()).toEqual(v.resolved.checklist);
    expect(kind.extracts).toBe(false);
    expect(userKindFiles().map((f) => f.id)).toEqual([ID]);
  });

  it('has the three layers: available once imported, offered only with *Kinds you add* on, shown only once turned on', () => {
    expect(availableKinds(true)).toContain(ID);
    expect(availableKinds(false, null)).toEqual(['house', ID]);
    expect(unreleasedKinds(availableKinds(true))).not.toContain(ID);
    expect(offeredKinds(availableKinds(true))).not.toContain(ID);
    setKindEnabled(ID, true, availableKinds(true));
    expect(enabledKinds(availableKinds(true))).not.toContain(ID);
    setCustomKinds(true);
    expect(localStorage.getItem(CUSTOM_KINDS_KEY)).toBe('1');
    expect(offeredKinds(availableKinds(true))).toContain(ID);
    expect(enabledKinds(availableKinds(true))).not.toContain(ID);
    setKindEnabled(ID, true, availableKinds(true));
    expect(enabledKinds(availableKinds(true))).toContain(ID);
    // The switch off hides it again and deletes nothing; on again, it is back as it was.
    setCustomKinds(false);
    expect(enabledKinds(availableKinds(true))).not.toContain(ID);
    expect(userKindFiles().map((f) => f.id)).toEqual([ID]);
    setCustomKinds(true);
    expect(enabledKinds(availableKinds(true))).toContain(ID);
  });

  for (const lang of LANGS) {
    it(`shows the person's own words as written in ${lang}, and a neutral word everywhere else`, () => {
      const dict = DICTIONARIES[lang] as Record<string, string>;
      const { labels } = kindById(ID);
      for (const ref of labels.refs) {
        const key = labels.resolve(ref)!.key!;
        expect(typeof dict[key], `${lang} ${ref}`).toBe('string');
      }
      expect(dict[labels.resolve('kind.many')!.key!]).toBe('Gyms');
      expect(dict[labels.resolve('status.TAKEN')!.key!]).toBe('Joined');
      expect(dict[labels.resolve('field.walkMinutes')!.key!]).toBe('Walk from home (minutes)');
      for (const f of kindById(ID).fields.extras) for (const o of f.options ?? []) expect(typeof dict[o.label], `${lang} ${o.label}`).toBe('string');
      const words = spaceWords(ID);
      expect(dict[words.heading]).toBe('Gyms');
      expect(dict[words.add]).toBe('New gym');
      for (const w of Object.values(words)) expect(typeof dict[w], `${lang} ${w}`).toBe('string');
      for (const id of kindWordIds()) {
        const key = kindWordKey(ID, id);
        if (key) expect(typeof dict[key], `${lang} word ${id}`).toBe('string');
      }
    });
  }

  it('says no house or school in any English word of its space', () => {
    const en = DICTIONARIES.en as Record<string, string>;
    for (const id of kindWordIds()) {
      const key = kindWordKey(ID, id);
      if (key) expect(en[key].replace(/\{\w+\}/g, ''), id).not.toMatch(/\bhouses?\b|school|BHK/i);
    }
    expect(toolWord(ID, 'compare.title')).not.toBe('compare.title');
    expect(en[toolWord(ID, 'compare.title')]).toBe('Compare');
  });

  it('has its pages on the addresses its capabilities give', async () => {
    setCustomKinds(true);
    localStorage.setItem(ENABLED_KINDS_KEY, JSON.stringify(['house', ID]));
    localStorage.setItem(DEFAULT_KIND_KEY, ID);
    const routes = routesFor(true, ['house', ID]);
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const space = TestBed.inject(SpaceService);
    const paths = new Set(routes.map((r) => r.path));
    await TestBed.inject(Router).navigateByUrl(space.home(ID));
    expect(space.kind()).toBe(ID);
    for (const p of [`k/${ID}`, `k/${ID}/new`, `k/${ID}/:id`, `k/${ID}/compare`]) expect(paths.has(p), p).toBe(true);
    expect(space.detailLink('x', ID)).toEqual(['/k', ID, 'x']);
  });

  it('is found by every field the person defined, and filtered by its own filters', () => {
    const { fields } = kindById(ID);
    const h0 = gym(0);
    const h1 = gym(1);
    const text = searchText(h0, '', [], undefined, (key) => DICTIONARIES.en[key], fields).toLowerCase();
    for (const part of [h0.label, h0.locality, h0.notes, h0.contactName, 'six to ten', 'weights']) expect(text, part!).toContain(String(part).toLowerCase());
    // Every typed extra is searched, whether the file's `search` names it or not (CLAUDE.md, search grows with the values).
    expect(fields.others(true).map((e) => e.id)).toEqual(fields.extras.map((e) => e.id));
    const rows = filterableExtras(fields);
    expect(rows.map((r) => r.id)).toEqual(['focus', 'trainer']);
    for (const row of rows) {
      const filter = { [row.id]: row.type === 'bool' ? 'yes' : row.options![0].id };
      expect(extraFilterMatches(filter, fields, h0), row.id).toBe(true);
      expect(extraFilterMatches(filter, fields, h1), row.id).toBe(false);
    }
  });

  it('is saved, deleted and restored with its extras, and keeps its kind', async () => {
    const local = new LocalStore();
    await local.ready();
    const saved = await local.saveHouse(gym(0), T1);
    expect(kindOfCandidate(saved)).toBe(ID);
    const read = (await local.getHouse(saved.id))!;
    expect(read.extrasOther).toEqual(gym(0).extrasOther);
    await local.deleteHouse(saved.id, T2);
    expect((await local.deletedHouses()).map((h) => h.id)).toContain(saved.id);
    await local.restoreHouse(saved.id, T2 + 1000);
    const again = (await local.getHouse(saved.id))!;
    expect(kindOfCandidate(again)).toBe(ID);
    expect(again.extrasOther).toEqual(read.extrasOther);
  });

  it('is copied in its own words, as text', () => {
    store([userKindSample(ID), userKindSample('u_gym_two')]);
    const dict = DICTIONARIES.en;
    for (const id of [ID, 'u_gym_two']) {
      const kind = kindById(id);
      const h = { ...gym(0), kind: id };
      const bundle = collect({
        houses: [rec(h)], visits: [], photos: [], exportedAt: '2026-10-14T10:00:00.000Z',
        kinds: { fieldsOf: (kd) => drawnKind(kd).fields, dict }, options: { ...DEFAULT_EXPORT_OPTIONS, lang: 'en', space: id },
        inSpace: (x: HouseRecord) => kindOfCandidate(x) === id,
      });
      const md = buildMarkdown(bundle, dict);
      const html = buildHtml(bundle, dict, new Map());
      const csv = Object.values(buildCsvTables(bundle)).join('\n');
      for (const text of [md, html, csv]) expect(text, `${id} label`).toContain(h.label);
      for (const e of kind.fields.others()) expect(md, `${id} ${e.id}`).toContain(dict[kind.fields.label(e)]);
      expect(spaceFileName('Doorprints-2026-10-14.md', id, dict[kind.keys.ref('kind.many')])).toMatch(/^Doorprints-[a-z0-9-]+-2026-10-14\.md$/);
    }
    // The look-alike's words reach the copies as text: no script, an escaped ampersand in the HTML.
    const html = buildHtml(collect({
      houses: [rec({ ...gym(0), kind: 'u_gym_two' })], visits: [], photos: [], exportedAt: '2026-10-14T10:00:00.000Z',
      kinds: { fieldsOf: (kd) => drawnKind(kd).fields, dict }, options: { ...DEFAULT_EXPORT_OPTIONS, lang: 'en', space: 'u_gym_two' },
      inSpace: () => true,
    }), dict, new Map());
    expect(html).not.toContain('<script');
  });

  it('travels in a backup (search.userKinds) and comes back on import, its entries with it', () => {
    const originals = [rec(gym(0)), rec(gym(1))];
    const bundle = collect({
      houses: originals, visits: [], photos: [], exportedAt: '2026-10-14T10:00:00.000Z',
      kinds: { fieldsOf: (kd) => drawnKind(kd).fields, dict: DICTIONARIES.en }, options: { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' },
      userKinds: userKindFiles(),
    });
    const root = JSON.parse(backupDataJson(bundle));
    expect(root.search.userKinds.map((k: { id: string }) => k.id)).toEqual([ID]);
    const checked = checkData(root);
    if (!checked.ok) throw new Error(`refused: ${checked.problem}`);
    let n = 0;
    const actions = plan(checked.data, new Set(), EMPTY_LOCAL, { mode: 'MERGE' }, () => `new-${n++}`);
    expect(actions.houses.map((h) => kindOfCandidate(tryHouseFromDto(h as never)!))).toEqual([ID, ID]);
    // A browser without the kind takes it from the file, once, checked as an import is.
    localStorage.clear();
    reloadUserKinds();
    expect(availableKinds(true)).not.toContain(ID);
    expect(adoptUserKinds(userKindsOf(root.search))).toEqual([ID]);
    expect(availableKinds(true)).toContain(ID);
    expect(adoptUserKinds(userKindsOf(root.search))).toEqual([]);
  });

  it('takes nothing hostile from a backup: a broken, polluting or map-carrying kind is left out, and at most eight are read', () => {
    localStorage.clear();
    reloadUserKinds();
    const bad = [
      JSON.parse('{"__proto__":{"polluted":1}}'),
      { ...userKindSample(ID), style: 'https://tiles.example/style.json' },
      { ...userKindSample(ID), id: 'house' },
      { ...userKindSample(ID), labels: { new: { text: '<img src=x onerror=alert(1)>' }, many: { text: 'x' }, delete: { text: 'y' } } },
      'not a kind',
    ];
    expect(adoptUserKinds(bad)).toEqual([]);
    expect(({} as Record<string, unknown>)['polluted']).toBeUndefined();
    const many = Array.from({ length: 12 }, (_, i) => ({ ...userKindSample('u_cafe'), id: `u_cafe${String.fromCharCode(97 + i)}` }));
    expect(adoptUserKinds(many)).toHaveLength(8);
    expect(userKindsOf({ userKinds: 'x' })).toEqual([]);
  });

  it('is read by no AI: its space has no assistant, whatever the key or the server', () => {
    expect(aiShownIn(ID, availableKinds(true), true, true, [ID])).toBe(false);
    expect(aiShownIn('house', availableKinds(true), true, true)).toBe(true);
  });

  it('draws India’s boundaries as the house does, byte for byte', () => {
    const url = 'https://doorprints.web.app/geo/in-boundaries.geojson';
    const drawn = () => {
      let style = libertyExcerpt();
      const target = { getStyle: () => style, setStyle: (s: StyleSpecification) => { style = s; } } as unknown as BoundaryStyleTarget;
      applyIndiaBoundaries(target, url, () => undefined);
      return JSON.stringify({ style, built: indiaBoundaryStyle(libertyExcerpt(), url).style });
    };
    const withKind = drawn();
    localStorage.clear();
    reloadUserKinds();
    expect(drawn()).toBe(withKind);
    expect(capabilitiesOf(ID).map).toBe(true);
  });

  it('has a question bank of its own, scoped BOTH, its ids carrying the kind', () => {
    expect(questionScopesOf(ID)).toEqual(['BOTH']);
    expect(questionKindOf(`q_${ID}_ab12cd34`)).toBe(ID);
    store([userKindSample(ID), { ...userKindSample('u_cafe'), id: 'u_gym_hall' }]);
    expect(questionKindOf('q_u_gym_hall_ab12cd34')).toBe('u_gym_hall');
    expect(questionKindOf('q_u_gym_ab12cd34')).toBe(ID);
  });

  it('is removed without touching its entries, and replaced in place by a newer file', async () => {
    const local = new LocalStore();
    await local.ready();
    const saved = await local.saveHouse(gym(0), T1);
    const v2 = userKindSample(ID);
    v2['version'] = 2;
    v2['labels']['many']['text'] = 'Gyms and pools';
    const read = readUserKind(JSON.stringify(v2));
    if (!read.ok) throw new Error('v2');
    expect(saveUserKind(read.file)).toBe('replaced');
    expect(DICTIONARIES.hi[kindById(ID).keys.ref('kind.many')]).toBe('Gyms and pools');
    expect(removeUserKind(ID)).toBe(true);
    expect(availableKinds(true)).not.toContain(ID);
    expect(Object.keys(DICTIONARIES.en).some((k) => k.startsWith(`uk.${ID}.`))).toBe(false);
    const still = (await local.getHouse(saved.id))!;
    expect(kindOfCandidate(still)).toBe(ID);
    expect(still.extrasOther).toEqual(gym(0).extrasOther);
    expect(drawnKind(ID).id).toBe('house');
  });

  it('keeps the store to eight kinds and says when it is full', () => {
    localStorage.clear();
    reloadUserKinds();
    for (let i = 0; i < 8; i++) {
      const r = readUserKind(JSON.stringify({ ...userKindSample('u_cafe'), id: `u_k${i}x` }));
      expect(r.ok && saveUserKind(r.file)).toBe('added');
    }
    const ninth = readUserKind(JSON.stringify({ ...userKindSample('u_cafe'), id: 'u_k9x' }));
    expect(ninth.ok && saveUserKind(ninth.file)).toBe('full');
    expect(userKindFiles()).toHaveLength(8);
  });

  it('draws nothing from a stored file it cannot read, and keeps it for a newer version', () => {
    store([{ ...userKindSample(ID), capabilities: { teleport: true } }, userKindSample('u_cafe')]);
    expect(availableKinds(true)).not.toContain(ID);
    expect(availableKinds(true)).toContain('u_cafe');
    expect(JSON.parse(localStorage.getItem(USER_KINDS_KEY)!)).toHaveLength(2);
  });
});
