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

// Spaces (kinds PR 8, S4b-BL-212; docs/03 §18.12) against docs/schemas/kinds/search-kind-vectors.json: what the
// resolver answers for the build's kinds and the stored settings, the setters' rules, the backup's `search` bytes, and
// the `search` values every reader reads past without refusing the file or changing a candidate. docs/06 TC-U-214.
import { afterEach, describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/kinds/search-kind-vectors.json';
import sampleJson from '../../../../docs/schemas/backup-sample.json';
import { checkData } from '../export/backup-check';
import { currentOf, legacyOf, spacesOf } from '../export/backup-family';
import { backupDataJson, buildBackupZip } from '../export/backup-export';
import { FIXTURE_PHOTO_MAP, fixtureBundle } from '../export/golden/fixture';
import { DEV_KINDS_KEY } from './dev-kinds';
import {
  DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY, RELEASED_KINDS, adoptSpaces, availableKinds, defaultKind, drawnKind,
  enabledKinds, experimentalKindsOn, kindsOn, offeredKinds, ofKind, setDefaultKind, setExperimentalKinds, setKindEnabled,
  spacesOn, unreleasedKinds,
} from './kind-setting';
import { newHouse } from './models';

type Obj = Record<string, unknown>;
interface Setting {
  name: string; available: string[]; experimental: string | null; enabled: string | null; default: string | null;
  offered: string[]; enabledKinds: string[]; defaultKind: string; spaces: boolean;
}
interface Vectors {
  format: string;
  setting: Setting[];
  write: { name: string; kinds: string[]; defaultKind: string; search: string }[];
  read: { name: string; search?: unknown; absent?: boolean }[];
}
const V = vectorsJson as unknown as Vectors;
const SAMPLE = JSON.stringify(sampleJson);
const BOTH = ['house', 'school'];

/** A storage holding only what it is given. */
function store(values: Record<string, string | null> = {}) {
  const m = new Map<string, string>();
  for (const [k, v] of Object.entries(values)) if (v !== null) m.set(k, v);
  return { getItem: (k: string) => m.get(k) ?? null, setItem: (k: string, v: string) => void m.set(k, v), removeItem: (k: string) => void m.delete(k), m };
}
const blocked = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('full'); }, removeItem: () => { throw new Error('blocked'); } };

afterEach(() => localStorage.clear());

describe('the kinds this build offers (S4b-BL-212)', () => {
  it('a production build lists only the house, a development build every built-in kind, a build flag the kinds it names', () => {
    expect(availableKinds(false, null)).toEqual(['house']);
    expect(availableKinds(true, null)).toEqual(BOTH);
    expect(availableKinds(false, 'school')).toEqual(BOTH);
    expect(availableKinds(false, ' school , clinic')).toEqual(BOTH);
    expect(availableKinds(false, 'clinic')).toEqual(['house']);
    expect(availableKinds(false, '')).toEqual(['house']);
    expect(RELEASED_KINDS).toEqual(['house']);
    expect([unreleasedKinds(['house']), unreleasedKinds(BOTH)]).toEqual([[], ['school']]);
  });
});

describe('the settings of the kinds (search-kind-vectors.json `setting`)', () => {
  it('answers the vectors for each build and stored setting; every doubt is the house alone', () => {
    expect(V.format).toBe('doorprints-search-kind-vectors/2');
    for (const v of V.setting) {
      const s = store({ [EXPERIMENTAL_KINDS_KEY]: v.experimental, [ENABLED_KINDS_KEY]: v.enabled, [DEFAULT_KIND_KEY]: v.default });
      expect(offeredKinds(v.available, s), v.name).toEqual(v.offered);
      expect(enabledKinds(v.available, s), v.name).toEqual(v.enabledKinds);
      expect(defaultKind(v.available, s), v.name).toBe(v.defaultKind);
      expect(spacesOn(v.available, s), v.name).toBe(v.spaces);
    }
  });

  it('a storage that throws or is missing reads as the house alone', () => {
    for (const s of [blocked, null]) {
      expect([experimentalKindsOn(s), offeredKinds(BOTH, s), enabledKinds(BOTH, s), defaultKind(BOTH, s), spacesOn(BOTH, s)])
        .toEqual([false, ['house'], ['house'], 'house', false]);
    }
    expect(() => { setExperimentalKinds(true, blocked); setKindEnabled('school', true, BOTH, blocked); setDefaultKind('house', BOTH, blocked); }).not.toThrow();
  });

  it('is off by default in this browser; the developer flag or an offered second kind turns the AI’s kinds on', () => {
    expect([experimentalKindsOn(), spacesOn(), kindsOn()]).toEqual([false, false, false]);
    setExperimentalKinds(true);
    expect([localStorage.getItem(EXPERIMENTAL_KINDS_KEY), kindsOn()]).toEqual(['1', true]);
    setExperimentalKinds(false);
    expect([localStorage.getItem(EXPERIMENTAL_KINDS_KEY), kindsOn()]).toEqual([null, false]);
    localStorage.setItem(DEV_KINDS_KEY, 'school');
    expect(kindsOn()).toBe(true);
  });
});

describe('Settings > Kinds’ rules', () => {
  it('enables and disables a kind; the last one stays; the house alone is stored as no key', () => {
    const s = store({ [EXPERIMENTAL_KINDS_KEY]: '1' });
    setKindEnabled('school', true, BOTH, s);
    expect([enabledKinds(BOTH, s), s.m.get(ENABLED_KINDS_KEY)]).toEqual([BOTH, '["house","school"]']);
    setKindEnabled('house', false, BOTH, s);
    expect(enabledKinds(BOTH, s)).toEqual(['school']);
    setKindEnabled('school', false, BOTH, s);
    expect(enabledKinds(BOTH, s)).toEqual(['school']);
    setKindEnabled('house', true, BOTH, s);
    setKindEnabled('school', false, BOTH, s);
    expect([enabledKinds(BOTH, s), s.m.has(ENABLED_KINDS_KEY)]).toEqual([['house'], false]);
    setKindEnabled('clinic', true, BOTH, s);
    expect(enabledKinds(BOTH, s)).toEqual(['house']);
  });

  it('a kind the switch does not offer cannot be turned on', () => {
    const s = store();
    setKindEnabled('school', true, BOTH, s);
    expect([enabledKinds(BOTH, s), s.m.has(ENABLED_KINDS_KEY)]).toEqual([['house'], false]);
  });

  it('the default is an enabled kind; turning the default off makes the next enabled kind the default', () => {
    const s = store({ [EXPERIMENTAL_KINDS_KEY]: '1' });
    setDefaultKind('school', BOTH, s);
    expect(defaultKind(BOTH, s)).toBe('house');
    setKindEnabled('school', true, BOTH, s);
    setDefaultKind('school', BOTH, s);
    expect([defaultKind(BOTH, s), s.m.get(DEFAULT_KIND_KEY)]).toEqual(['school', 'school']);
    setKindEnabled('school', false, BOTH, s);
    expect([defaultKind(BOTH, s), s.m.has(DEFAULT_KIND_KEY)]).toEqual(['house', false]);
    setKindEnabled('school', true, BOTH, s);
    setDefaultKind('school', BOTH, s);
    setKindEnabled('house', false, BOTH, s);
    expect(defaultKind(BOTH, s)).toBe('school');
    setDefaultKind('house', BOTH, s);
    expect(s.m.get(DEFAULT_KIND_KEY)).toBe('school');
  });

  it('a new candidate takes its kind: a school with no price type, a house unchanged; a stored one is drawn as its kind', () => {
    const h = newHouse(26.1, 91.7);
    expect(ofKind(h, 'house')).toBe(h);
    expect([ofKind(h, 'school').kind, ofKind(h, 'school').priceType]).toEqual(['school', null]);
    expect([drawnKind('school').id, drawnKind('clinic').id, drawnKind(null).id]).toEqual(['school', 'house', 'house']);
  });
});

describe('the backup’s search object (search-kind-vectors.json `write` and `read`)', () => {
  it('is written for the enabled kinds and the default, the rest of the file byte for byte the house’s', () => {
    for (const w of V.write) {
      const written = JSON.stringify(currentOf(legacyOf(JSON.parse(SAMPLE) as Obj), { kinds: w.kinds, defaultKind: w.defaultKind }));
      expect(written, w.name).toBe(SAMPLE.replace('"search":{"kind":"house"}', `"search":${w.search}`));
    }
    expect(JSON.stringify(currentOf(legacyOf(JSON.parse(SAMPLE) as Obj)))).toBe(SAMPLE);
  });

  it('the backup ZIP and its data.json carry the bundle’s kinds; a bundle without them says the house', () => {
    const b = fixtureBundle();
    const spaces = { kinds: BOTH, defaultKind: 'school' };
    const want = ',"search":{"kinds":["house","school"],"defaultKind":"school"},';
    expect(backupDataJson({ ...b, spaces })).toContain(want);
    expect(backupDataJson(b)).toContain(',"search":{"kind":"house"},');
    const zip = new TextDecoder().decode(buildBackupZip({ ...b, spaces }, FIXTURE_PHOTO_MAP, 'x', new Date('2026-10-13T00:00:00Z')));
    expect(zip).toContain(want);
  });

  it('is read past, never refused or applied: each value reads as the sample does', () => {
    const want = JSON.stringify(legacyOf(JSON.parse(SAMPLE) as Obj));
    for (const r of V.read) {
      const file = JSON.parse(SAMPLE) as Obj;
      if (r.absent) delete file['search'];
      else file['search'] = r.search;
      expect(JSON.stringify(legacyOf(file)), r.name).toBe(want);
      expect(checkData(file), r.name).toMatchObject({ ok: true });
    }
  });

  it('names its kinds and default as a hint only when well formed; a browser that never chose takes it, one that chose keeps its own', () => {
    const hints = V.read.map((r) => spacesOf(r.search));
    expect(hints).toEqual([{ kinds: ['house', 'school'], defaultKind: 'school' }, null, { kinds: ['clinic'], defaultKind: 'clinic' }, null, null, null, null, null, null]);
    const fresh = store();
    expect(adoptSpaces(hints[0], fresh)).toBe(true);
    expect([fresh.m.get(ENABLED_KINDS_KEY), fresh.m.get(DEFAULT_KIND_KEY)]).toEqual(['["house","school"]', 'school']);
    for (const chose of [store({ [ENABLED_KINDS_KEY]: '["house"]' }), store({ [DEFAULT_KIND_KEY]: 'house' })]) {
      const before = [...chose.m];
      expect(adoptSpaces(hints[0], chose)).toBe(false);
      expect([...chose.m]).toEqual(before);
    }
    expect(adoptSpaces(null, store())).toBe(false);
    // A kind the build does not offer stays stored but unread.
    const s = store({ [EXPERIMENTAL_KINDS_KEY]: '1' });
    adoptSpaces(hints[2], s);
    expect([enabledKinds(BOTH, s), defaultKind(BOTH, s)]).toEqual([['house'], 'house']);
  });
});
