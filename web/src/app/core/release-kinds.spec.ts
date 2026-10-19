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

// The release gate is the build and two switches (S4b-BL-309, docs/02 T-E13, docs/13 part J1). Owner decision 2026-10-15: the
// production build ships the school compiled in; owner decision 2026-10-19 (docs/03 ADR-13b): the school is released on the
// web, after the named gates. Three layers: *available* (the build lists the kind: the production configuration defines
// DOORPRINTS_KINDS = house,school), *offered* (available and released, RELEASED_KINDS = house and school; a kind still
// unreleased is offered only while the person turns on Experimental: more than houses, a switch shown only while such a kind
// is listed) and *enabled* (switched on in What you track; the house alone until the person shows the school). Cheap checks
// on the build's own configuration, so a change that lists another kind, or drops the school, fails here and not at a review.
import { describe, expect, it } from 'vitest';
import angularJson from '../../../angular.json';
import packageJson from '../../../package.json';
import { RELEASED_KINDS, availableKinds, offeredKinds, unreleasedKinds } from './kind-setting';

type Config = { define?: Record<string, string> };
const build = (angularJson as { projects: { web: { architect: { build: { options: Config; configurations: Record<string, Config>; defaultConfiguration: string } } } } }).projects.web.architect.build;

describe('a release build ships the school, released but not enabled (S4b-BL-309, ADR-13b)', () => {
  it('the production build lists exactly house and school, and it is the default build', () => {
    expect(build.defaultConfiguration).toBe('production');
    expect(build.configurations['production'].define).toEqual({ DOORPRINTS_KINDS: "'house,school'" });
    expect(build.options.define).toBeUndefined();
    expect(build.configurations['development']?.define).toBeUndefined();
  });

  it('the build scripts (start, test) pass no kinds define', () => {
    expect(JSON.stringify((packageJson as { scripts: Record<string, string> }).scripts)).not.toMatch(/DOORPRINTS_KINDS|--define/);
  });

  it('that list makes the school available and released: offered with no switch, and no kind is left for the switch', () => {
    const flag = "'house,school'".replace(/'/g, '');
    expect(availableKinds(false, flag)).toEqual(['house', 'school']);
    expect(RELEASED_KINDS).toEqual(['house', 'school']);
    expect(unreleasedKinds(availableKinds(false, flag))).toEqual([]);
    expect(offeredKinds(['house', 'school'], { getItem: () => null })).toEqual(['house', 'school']);
    expect(offeredKinds(['house', 'school'], { getItem: (k: string) => (k.endsWith('kinds.experimental') ? '1' : null) })).toEqual(['house', 'school']);
  });

  it('a kind that is listed but not released still needs the switch (the mechanism is kept for the next kind)', () => {
    const storage = (on: boolean) => ({ getItem: (k: string) => (on && k.endsWith('kinds.experimental') ? '1' : null) });
    expect(unreleasedKinds(['house', 'school', 'clinic'])).toEqual(['clinic']);
    expect(offeredKinds(['house', 'school', 'clinic'], storage(false))).toEqual(['house', 'school']);
    expect(offeredKinds(['house', 'school', 'clinic'], storage(true))).toEqual(['house', 'school', 'clinic']);
  });

  it('a build with no list, or an empty one, still offers the house alone, in every doubt', () => {
    expect(availableKinds(false, null)).toEqual(['house']);
    expect(availableKinds(false, '')).toEqual(['house']);
  });

  it('the synthetic conformance kind is never in a release list, and an unlisted id is not available (kinds PR 32)', () => {
    expect(build.configurations['production'].define?.['DOORPRINTS_KINDS']).not.toContain('zz_all');
    expect(availableKinds(false, 'house,school')).not.toContain('zz_all');
    expect(RELEASED_KINDS).not.toContain('zz_all');
    expect(RELEASED_KINDS).toEqual(['house', 'school']);
  });
});
