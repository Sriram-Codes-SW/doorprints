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

// The rules that scope Compare, Ask, Plan, the copies and the share target to one space (kinds PR B, S4b-BL-279;
// docs/03 §18.13) against docs/schemas/kinds/space-scope-vectors.json. docs/06 TC-U-216.
import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/kinds/space-scope-vectors.json';
import { inTheRunning } from '../shared/house-status';
import type { HouseStatus } from './models';
import { COMPARE_MAX, aiShownIn, comparePick, fileSlug, holdsFor, shareKind, spaceFileName } from './space-scope';

type Cand = { id: string; kind?: string; status?: HouseStatus; deleted?: boolean };
interface Vectors {
  format: string;
  contains: { name: string; multi: boolean; space: string; default: string; available: string[]; candidates: Cand[]; holds: string[] }[];
  compare: { name: string; space: string; multi: boolean; default: string; available: string[]; candidates: Cand[]; picked: string[]; kept: string[]; refused: string[] }[];
  fileName: { name: string; space: string | null; many: string; in: string; out: string }[];
  assistant: { name: string; kind: string; available: string[]; on: boolean; ownKey: boolean; shown: boolean }[];
  shareKind: { name: string; chosen: string | null; enabled: string[]; default: string; kind: string }[];
}
const V = vectorsJson as unknown as Vectors;

describe('the space-scope vectors (S4b-BL-279)', () => {
  it('has the format the schemas README names', () => {
    expect(V.format).toBe('doorprints-space-scope-vectors/1');
  });

  it('what a space contains', () => {
    for (const v of V.contains) {
      const holds = holdsFor(v.space, v.multi, v.default, v.available);
      expect(v.candidates.filter(holds).map((c) => c.id), v.name).toEqual(v.holds);
    }
  });

  it('Compare keeps the picks of the space in the running and refuses the picks of another space', () => {
    for (const v of V.compare) {
      const pick = comparePick(v.picked, v.candidates as never, holdsFor(v.space, v.multi, v.default, v.available), (h) => inTheRunning(h.status as HouseStatus));
      expect({ kept: pick.kept, refused: pick.refused }, v.name).toEqual({ kept: v.kept, refused: v.refused });
    }
    expect(COMPARE_MAX).toBe(4);
  });

  it('the file name of a space’s copy', () => {
    for (const v of V.fileName) expect(spaceFileName(v.in, v.space ?? undefined, v.many), v.name).toBe(v.out);
    expect([fileSlug('Schools'), fileSlug(' Health  clinics! '), fileSlug('स्कूल')]).toEqual(['schools', 'health-clinics', '']);
  });

  it('when a space shows the assistant', () => {
    for (const v of V.assistant) expect(aiShownIn(v.kind, v.available, v.on, v.ownKey, "serverKinds" in v ? (v.serverKinds as string[]) : undefined), v.name).toBe(v.shown);
  });

  it('the kind a shared listing lands in', () => {
    for (const v of V.shareKind) expect(shareKind(v.chosen, v.enabled, v.default), v.name).toBe(v.kind);
    expect(shareKind(undefined, ['house'], 'house')).toBe('house');
  });
});
