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

import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import vectorsJson from '../../../../docs/schemas/delete-scope-vectors.json';
import { DeleteUndoService } from '../core/delete-undo.service';
import { LocalStore } from './local-store.service';
import { StorageService } from './storage.service';
import { SyncService } from './sync.service';
import { binRows } from './house-bin';
import type { HouseRecord } from './records';

/**
 * The blast-radius invariant of deleting ONE candidate, as a spec and as the first cut of the shared vector file
 * `docs/schemas/delete-scope-vectors.json` (deletion safeguards PR 1, docs/02 T-D, docs/06 TC-U-215, S4b-BL-287): an
 * action in one space (delete with Undo, undo, restore from *Recently deleted*, the list) writes only that candidate's
 * row, and every other row, of any kind, is byte-identical before and after. The phones and the server read the same
 * file in their own pull requests. The vector file is never edited to make this pass.
 */
interface SeedRow {
  id: string;
  kind?: string;
  label: string;
  deleted: boolean;
  dirty: boolean;
  updatedAt: string;
}
interface Step {
  op: 'delete' | 'undo' | 'restore';
  id: string;
  at: string;
}
interface Case {
  name: string;
  space?: string;
  now?: string;
  multi?: boolean;
  default?: string;
  available?: string[];
  seed: SeedRow[];
  steps: Step[];
  expect: { rows?: (SeedRow & { kind: string })[]; unchanged: string[]; bin?: Record<string, string[]> };
}
const cases = (vectorsJson as unknown as { cases: Case[] }).cases;

const record = (r: SeedRow): HouseRecord =>
  ({ ...r, lat: 13, lon: 80, status: 'NEW', checklist: {}, createdAt: '2026-09-01T00:00:00.000Z', syncVersion: 0 }) as unknown as HouseRecord;
const shape = (h: HouseRecord) => ({ id: h.id, kind: h.kind ?? 'house', label: h.label, deleted: h.deleted, dirty: h.dirty, updatedAt: h.updatedAt });
const byId = (a: { id: string }, b: { id: string }) => (a.id < b.id ? -1 : 1);

describe('the delete-scope vectors', () => {
  let store: LocalStore;
  let service: DeleteUndoService;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: SyncService, useValue: { syncSoon: () => undefined } },
        { provide: StorageService, useValue: { requestPersistence: () => Promise.resolve() } },
      ],
    });
    store = TestBed.inject(LocalStore);
    service = TestBed.inject(DeleteUndoService);
  });

  afterEach(() => {
    service.dismiss();
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  it('holds nine cases', () => {
    expect(cases).toHaveLength(9);
  });

  describe.each(cases)('$name', (c) => {
    it('writes only the named rows and leaves every other row byte-identical', async () => {
      await store.putImported({ houses: c.seed.map(record) });
      const before = new Map((await store.allHouses()).map((h) => [h.id, JSON.stringify(h)]));
      for (const step of c.steps) {
        vi.setSystemTime(Date.parse(step.at));
        if (step.op === 'delete') await service.remove(step.id, step.id);
        else if (step.op === 'undo') await service.undo();
        else await service.restore({ id: step.id, name: step.id, deletedAt: '', photos: 0 });
      }
      const after = await store.allHouses();
      for (const id of c.expect.unchanged) expect(JSON.stringify(after.find((h) => h.id === id)), id).toBe(before.get(id));
      if (c.expect.rows) {
        const want = c.expect.rows.map((r) => ({ ...r, label: c.seed.find((s) => s.id === r.id)!.label })).sort(byId);
        expect(after.map(shape).sort(byId)).toEqual(want);
      }
      if (c.expect.bin) {
        const now = Date.parse(c.now!);
        for (const [kind, ids] of Object.entries(c.expect.bin)) {
          const space = { kind, multi: c.multi!, dflt: c.default!, available: c.available! };
          expect(binRows(after, space, now).map((h) => h.id), kind).toEqual(ids);
        }
      }
    });
  });
});
