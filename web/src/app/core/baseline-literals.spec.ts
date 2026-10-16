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
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { CAPABILITY_KINDS, capabilitiesOf, isBaselineKind } from './kind-capabilities';
import houseJson from '../../../../docs/schemas/kinds/house.json';
import schoolJson from '../../../../docs/schemas/kinds/school.json';

// Kinds PR 30 (docs/03 §18.24): the paths, the AI wire's `kind`, the copies' file names and the tool pages of "the house" are the
// BASELINE kind's (`capabilities.baseline` of the kind file), asked through `isBaselineKind`, never compared with the id `house`
// in these files. A comparison with the literal is what this scan refuses, so a second built-in kind never meets a house check.
/** The source with block and line comments removed, so a sentence that explains the rule is not read as a breach of it. */
const code = (source: string): string => source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1');
const found = (source: string, tokens: readonly string[]): string[] => tokens.filter((t) => code(source).includes(t));
const FILES = ['core/space-scope.ts', 'core/space.service.ts', 'export/kind-copy.ts', 'app.routes.ts', 'core/ai.service.ts'].map((f) => `src/app/${f}`);
const COMPARISONS = ["=== 'house'", "!== 'house'", "== 'house'", "!= 'house'"];

describe('the baseline kind is a capability, not an id compared in the tool, route, copy and AI files', () => {
  it('has the files it must scan', () => {
    for (const file of FILES) expect(readFileSync(file, 'utf8').length, file).toBeGreaterThan(1000);
  });

  it.each(FILES)('%s compares no kind with the literal house', (file) => {
    expect(found(readFileSync(file, 'utf8'), COMPARISONS), file).toEqual([]);
  });

  it('is only the house, in the kind files and in the resolver, and a kind this build does not know is not it', () => {
    expect(CAPABILITY_KINDS.filter((id) => isBaselineKind(id))).toEqual(['house']);
    expect((houseJson as { capabilities: { baseline?: boolean } }).capabilities.baseline).toBe(true);
    expect((schoolJson as { capabilities: { baseline?: boolean } }).capabilities.baseline).toBeUndefined();
    for (const id of ['', 'villa', 'House', '__proto__', null, undefined]) expect(isBaselineKind(id as string), String(id)).toBe(false);
    expect(capabilitiesOf('house').filters).toEqual([]);
  });
});
