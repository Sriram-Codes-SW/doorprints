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
import vectorsJson from '../../../../../docs/schemas/photo-sweep-vectors.json';
import { cleanMeta } from '../../shared/photo-tags';
import { RESTORE_WINDOW_DAYS } from './photo-restore-rules';
import type { GonePhoto } from './photo-restore-rules';
import { photoFilesToSweep, SWEEP_WINDOW_DAYS } from './photo-sweep-rules';

/** The rules of the 30-day clean-up: which deleted photos' Drive files leave the person's Drive (S4b-BL-293; docs/15 §5.1). */
const DAY = 24 * 60 * 60 * 1000;
const NOW = 2_000 * DAY;

/** A deleted photo whose bytes are gone here, with the Drive file of its bytes (null when none is known). */
function gone(id: string, deletedAt: number, file: string | null = `file-${id}`): GonePhoto {
  return { id, deletedAt, contentType: null, createdAt: null, meta: cleanMeta({}), ref: file == null ? null : { driveFileId: file, sha256: 'a'.repeat(64) } };
}

const sweep = (photos: readonly GonePhoto[], oldestKeptBackupAt: number | null) => photoFilesToSweep({ photos, oldestKeptBackupAt, now: NOW });

interface SweepCase {
  name: string;
  oldestStayingBackupAgoMs: number | null;
  photos: { id: string; deletedAgoMs: number; driveFileId: string | null }[];
  expect: string[];
}
const vectors = vectorsJson as unknown as { format: string; windowDays: number; now: number; cases: SweepCase[] };

describe('photo sweep rules', () => {
  it('uses the shared vectors (docs/schemas/photo-sweep-vectors.json, read by the phones too)', () => {
    expect(vectors.format).toBe('doorprints-photo-sweep-vectors/1');
    expect(SWEEP_WINDOW_DAYS).toBe(vectors.windowDays);
    expect(vectors.cases.length).toBeGreaterThan(0);
    for (const c of vectors.cases) {
      const photos = c.photos.map((p) => gone(p.id, vectors.now - p.deletedAgoMs, p.driveFileId));
      const kept = c.oldestStayingBackupAgoMs == null ? null : vectors.now - c.oldestStayingBackupAgoMs;
      expect(photoFilesToSweep({ photos, oldestKeptBackupAt: kept, now: vectors.now }), c.name).toEqual(c.expect);
    }
  });

  it('is the restore window itself, so the clean-up cannot take a file Restore still promises', () => {
    expect(SWEEP_WINDOW_DAYS).toBe(RESTORE_WINDOW_DAYS);
    expect(SWEEP_WINDOW_DAYS).toBe(30);
  });

  it('takes a file older than the window when no backup is kept', () => {
    expect(sweep([gone('p1', NOW - 31 * DAY)], null)).toEqual(['file-p1']);
  });

  it('keeps a file at exactly the window and takes it one millisecond older', () => {
    expect(sweep([gone('p1', NOW - 30 * DAY)], null)).toEqual([]);
    expect(sweep([gone('p1', NOW - 30 * DAY - 1)], null)).toEqual(['file-p1']);
  });

  it('keeps a file a kept backup could still hold: not older than the oldest kept backup', () => {
    // The oldest kept backup was made after the deletion, so no backup holds the file.
    expect(sweep([gone('p1', NOW - 40 * DAY)], NOW - 39 * DAY)).toEqual(['file-p1']);
    // The oldest kept backup predates the deletion, so it may hold the file.
    expect(sweep([gone('p1', NOW - 40 * DAY)], NOW - 41 * DAY)).toEqual([]);
    // Exactly at the oldest kept backup's time: still not strictly older, so keep it.
    expect(sweep([gone('p1', NOW - 40 * DAY)], NOW - 40 * DAY)).toEqual([]);
  });

  it('needs both conditions: old enough and older than every kept backup', () => {
    expect(sweep([gone('p1', NOW - 5 * DAY)], NOW - 4 * DAY)).toEqual([]);
    expect(sweep([gone('p1', NOW - 5 * DAY)], null)).toEqual([]);
    expect(sweep([gone('p1', NOW - 5 * DAY)], NOW - 6 * DAY)).toEqual([]);
  });

  it('skips a photo with no Drive file named: there is nothing to take', () => {
    expect(sweep([gone('p1', NOW - 40 * DAY, null)], null)).toEqual([]);
  });

  it('returns each file once, even when two photos name it', () => {
    const both = [gone('p1', NOW - 40 * DAY, 'shared'), gone('p2', NOW - 50 * DAY, 'shared')];
    expect(sweep(both, null)).toEqual(['shared']);
  });

  it('sweeps only the photos that qualify, in order, and leaves the rest', () => {
    const photos = [gone('old', NOW - 90 * DAY), gone('young', NOW - 2 * DAY), gone('mid', NOW - 31 * DAY, null)];
    expect(sweep(photos, NOW - 80 * DAY)).toEqual(['file-old']);
  });
});
