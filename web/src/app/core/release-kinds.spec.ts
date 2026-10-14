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

// The release gate is the build (S4b-BL-309, docs/02 T-E13, docs/13 part J1): a production build ships the house alone.
// Cheap checks on the build's own configuration, so a change that would ship another kind fails here and not at a review:
// the default build defines no DOORPRINTS_KINDS, and with no list the website offers the house alone.
import { describe, expect, it } from 'vitest';
import angularJson from '../../../angular.json';
import packageJson from '../../../package.json';
import { availableKinds } from './kind-setting';

describe('a release build ships the house alone (S4b-BL-309)', () => {
  it('the default and production builds define no kinds', () => {
    const build = (angularJson as { projects: { web: { architect: { build: { options: object; configurations: Record<string, object>; defaultConfiguration: string } } } } }).projects.web.architect.build;
    expect(build.defaultConfiguration).toBe('production');
    for (const config of [build.options, build.configurations['production']]) expect(JSON.stringify(config)).not.toMatch(/DOORPRINTS_KINDS|"define"/);
  });

  it('the build scripts pass no kinds define', () => {
    expect(JSON.stringify((packageJson as { scripts: Record<string, string> }).scripts)).not.toMatch(/DOORPRINTS_KINDS|--define/);
  });

  it('a production build with no list offers the house alone, in every doubt', () => {
    expect(availableKinds(false, null)).toEqual(['house']);
    expect(availableKinds(false, '')).toEqual(['house']);
  });
});
