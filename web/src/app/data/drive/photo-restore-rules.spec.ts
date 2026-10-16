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
import vectorsJson from '../../../../../docs/schemas/photo-restore-vectors.json';
import { cleanMeta } from '../../shared/photo-tags';
import { deletedWithCandidate, planRestore, RESTORE_WINDOW_DAYS, summarize, WITH_CANDIDATE_MS } from './photo-restore-rules';
import type { GonePhoto, PhotoRestoreReason, RestoreConnection } from './photo-restore-rules';
import { syncRow } from './sync-file';

/** The rules of getting a restored candidate's photos back from Google Drive, against the shared vectors (S4b-BL-293; Kotlin `PhotoRestoreVectorsTest`). */
interface PlanCase {
  name: string;
  houseId: string;
  houseDeletedAt: string;
  now: string;
  local: { id: string; deletedAt: string }[];
  file: { id: string; houseId?: string; candidateId?: string; deleted: boolean; deletedAt: string; driveFileId?: string; sha256?: string }[];
  refs: Record<string, { driveFileId: string; sha256: string }>;
  expect: { id: string; action: string; driveFileId?: string }[];
}
interface SummaryCase {
  name: string;
  noted: number;
  back: number;
  failed: PhotoRestoreReason[];
  connection: RestoreConnection;
  expect: { total: number; restored: number; reasons: string[] };
}
const vectors = vectorsJson as unknown as { format: string; windowDays: number; withCandidateMs: number; plan: PlanCase[]; summary: SummaryCase[] };
const ms = (iso: string) => Date.parse(iso);
const DEVICE = 'device-aaaa-0001';

describe('photo restore rules', () => {
  it('uses the vectors\' window and tolerance', () => {
    expect(vectors.format).toBe('doorprints-photo-restore-vectors/1');
    expect(RESTORE_WINDOW_DAYS).toBe(vectors.windowDays);
    expect(WITH_CANDIDATE_MS).toBe(vectors.withCandidateMs);
  });

  it('a photo deleted a minute before its candidate or later was deleted with it', () => {
    expect(deletedWithCandidate(1_000_000 - 60_000, 1_000_000)).toBe(true);
    expect(deletedWithCandidate(1_000_000 - 60_001, 1_000_000)).toBe(false);
    expect(deletedWithCandidate(1_000_001, 1_000_000)).toBe(true);
  });

  describe('plan', () => {
    for (const c of vectors.plan) {
      it(c.name, () => {
        const local: GonePhoto[] = c.local.map((p) => ({ id: p.id, deletedAt: ms(p.deletedAt), contentType: 'image/jpeg', createdAt: null, meta: cleanMeta(null), ref: null }));
        const file = c.file.map((r) =>
          syncRow('photos', {
            id: r.id,
            ...(r.candidateId ? { candidateId: r.candidateId } : { houseId: r.houseId }),
            contentType: 'image/jpeg',
            updatedAt: r.deletedAt,
            deleted: r.deleted,
            by: DEVICE,
            ...(r.driveFileId ? { driveFileId: r.driveFileId, sha256: r.sha256 } : {}),
          }),
        );
        const plan = planRestore({ houseId: c.houseId, houseDeletedAt: ms(c.houseDeletedAt), now: ms(c.now), local, file, refs: c.refs });
        expect(plan.map((i) => ({ id: i.photo.id, action: i.action, ...(i.photo.ref && c.expect.some((e) => e.driveFileId) ? { driveFileId: i.photo.ref.driveFileId } : {}) }))).toEqual(c.expect);
      });
    }
  });

  describe('summary', () => {
    for (const c of vectors.summary) {
      it(c.name, () => {
        expect(summarize(c.noted, c.back, c.failed, c.connection)).toEqual(c.expect);
      });
    }
  });
});
