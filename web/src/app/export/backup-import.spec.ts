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
import vectorsJson from '../../../../docs/schemas/import-vectors.json';
import updateSample from '../../../../docs/schemas/update-sample.json';
import { checkData } from './backup-check';
import type { BackupData } from './backup-export';
import { isUpdate, openBackup } from './backup-reader';
import { EMPTY_LOCAL, copyDuplicates, plan, preview } from './import-plan';
import type { ImportMode, LocalVersions } from './import-plan';
import { inflateRaw } from './zip-read';

interface Vectors {
  format: string;
  checks: { name: string; data: unknown; problem: string | null }[];
  merges: {
    name: string;
    mode: ImportMode;
    update: boolean;
    restoreDeleted: boolean;
    skipUpdates: boolean;
    photoEntries: string[];
    local: Record<string, Record<string, number> | string[]>;
    data: BackupData;
    expect: Record<string, number | boolean>;
    duplicates?: number;
  }[];
  archives: { name: string; base64: string; problem: string | null; houses?: number; photoEntries?: string[]; photoOk?: boolean; sharedTo?: string; update?: boolean }[];
}

const vectors = vectorsJson as unknown as Vectors;

/** The vectors' photo: byte `i` is `i * 31 mod 256`, 300 bytes (as `ImportVectorsTest`). */
const PHOTO = Uint8Array.from({ length: 300 }, (_, i) => (i * 31) % 256);

function bytesOf(base64: string): Uint8Array {
  return Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
}

function localOf(raw: Vectors['merges'][number]['local']): LocalVersions {
  const map = (key: string) => new Map(Object.entries((raw[key] as Record<string, number> | undefined) ?? {}));
  const set = (key: string) => new Set((raw[key] as string[] | undefined) ?? []);
  return {
    ...EMPTY_LOCAL,
    houses: map('houses'),
    visits: map('visits'),
    photoIds: set('photoIds'),
    deletedHouseIds: set('deletedHouses'),
    scoredHouseIds: set('scoredHouses'),
    unlinkedVisitIds: set('unlinkedVisits'),
    brokers: map('brokers'),
    viewings: map('viewings'),
  };
}

/**
 * The website's backup reader (S4b-BL-75) against the shared vectors of `docs/schemas/import-vectors.json`, which
 * `android/shared`'s `ImportVectorsTest` and `android/app`'s `BackupReaderParityTest` run too: the same answer for every
 * `data.json` check, merge preview and archive, an update file's deletions (S4b-BL-82) included.
 */
describe('backup import (shared vectors)', () => {
  it('reads the vectors file it was written for', () => {
    expect(vectors.format).toBe('doorprints-import-vectors/1');
    expect(vectors.checks.length).toBeGreaterThanOrEqual(20);
  });

  for (const c of vectors.checks) {
    it(`checks: ${c.name}`, () => {
      const checked = checkData(c.data);
      expect(checked.ok ? null : checked.problem).toBe(c.problem);
    });
  }

  for (const m of vectors.merges) {
    it(`merges: ${m.name}`, () => {
      const local = localOf(m.local);
      const flags = { mode: m.mode, restoreDeleted: m.restoreDeleted, skipUpdates: m.skipUpdates, applyDeletions: m.update };
      const entries = new Set(m.photoEntries);
      expect(checkData(m.data).ok).toBe(true);
      const p = preview(m.data, entries, local, flags);
      for (const [key, value] of Object.entries(m.expect)) expect(p[key as keyof typeof p], key).toBe(value);
      let n = 0;
      const actions = plan(m.data, entries, local, flags, () => `n${n++}`);
      expect(actions.removedHouseIds.length).toBe(p.removedHouses);
      expect(actions.houses.length).toBe(p.newHouses + p.updatedHouses + p.restoredHouses);
      expect(actions.photos.length).toBe(p.newPhotos);
      if (m.duplicates !== undefined) expect(copyDuplicates(m.data, local)).toBe(m.duplicates);
    });
  }

  for (const a of vectors.archives) {
    it(`archives: ${a.name}`, async () => {
      const opened = await openBackup(new Blob([bytesOf(a.base64) as BlobPart]));
      if (!opened.ok) {
        expect(opened.problem).toBe(a.problem);
        return;
      }
      expect(a.problem).toBeNull();
      const archive = opened.archive;
      expect(archive.data.houses.length).toBe(a.houses);
      expect([...archive.photoEntries].sort()).toEqual([...(a.photoEntries ?? [])].sort());
      if (a.photoOk !== undefined) {
        const photo = await archive.photoBytes('photos/p1.jpg');
        if (a.photoOk) expect(photo).toEqual(PHOTO);
        else expect(photo).toBeNull();
      }
      if (a.sharedTo !== undefined) expect(archive.manifest?.sharedTo).toBe(a.sharedTo);
      expect(isUpdate(archive.manifest)).toBe(a.update ?? false);
    });
  }
});

describe('backup import (format)', () => {
  it('reads the /3 update sample and its deletion', () => {
    const checked = checkData(updateSample);
    expect(checked.ok).toBe(true);
    if (!checked.ok) return;
    expect(checked.data.format).toBe('doorprints-backup/3');
    expect(checked.data.deleted).toEqual([{ kind: 'house', id: '6f1d1c2e-0000-4000-8000-000000000009', updatedAt: 1780000045000 }]);
  });

  it('stops inflating at the limit, whatever the entry claims', async () => {
    const zeros = new Uint8Array(100_000);
    const deflater = new CompressionStream('deflate-raw');
    const writer = deflater.writable.getWriter();
    void writer.write(zeros);
    void writer.close();
    const packed = new Uint8Array(await new Response(deflater.readable).arrayBuffer());
    expect((await inflateRaw(packed, 200_000))?.length).toBe(100_000);
    expect(await inflateRaw(packed, 1_000)).toBeNull();
    await expect(inflateRaw(packed.subarray(0, 5), 200_000)).rejects.toThrow();
  });
});
