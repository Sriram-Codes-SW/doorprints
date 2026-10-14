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
import { afterEach, describe, expect, it } from 'vitest';
import { LocalStore } from '../data/local-store.service';
import type { PhotoStore } from '../data/photo-store';
import type { AreaNoteStore } from '../data/area-note-store';
import type { AreaStore } from '../data/area-store';
import type { PlaceStore } from '../data/place-store';
import type { QuestionStore } from '../data/question-store';
import type { BrokerStore } from '../data/broker-store';
import type { CriteriaStore } from '../data/criteria-store';
import type { ViewingStore } from '../data/viewing-store';
import { DEFAULT_EXPORT_OPTIONS } from './export-model';
import type { ExportFormat } from './export-model';
import { ExportService } from './export.service';
import { FIXTURE_BROKERS, FIXTURE_HOUSES, FIXTURE_VISITS } from './golden/fixture';

/**
 * The house's copies keep their file names and counts byte for byte (kinds PR B, S4b-BL-279; docs/03 §18.12), whatever the
 * copy learns about spaces: the six names a person has always got. Written first, green on `main`, kept green by the
 * change (docs/06 TC-U-216).
 */
const NOW = new Date('2026-09-22T10:00:00.000Z');
const NAMES: Record<ExportFormat, string> = {
  html: 'Doorprints-copy-2026-09-22.html',
  pdf: 'Doorprints-copy-2026-09-22.html',
  markdown: 'Doorprints-2026-09-22.md',
  csv: 'Doorprints-2026-09-22-csv.zip',
  xlsx: 'Doorprints-2026-09-22.xlsx',
  backup: 'Doorprints-backup-2026-09-22.zip',
};

function service(): ExportService {
  TestBed.resetTestingModule();
  const store: Partial<LocalStore> = {
    allHouses: () => Promise.resolve(FIXTURE_HOUSES.map((h) => ({ ...h }))),
    allVisits: () => Promise.resolve(FIXTURE_VISITS.map((v) => ({ ...v }))),
    photos: { all: () => Promise.resolve([]) } as unknown as PhotoStore,
    brokers: { rows: () => Promise.resolve(FIXTURE_BROKERS) } as unknown as BrokerStore,
    criteria: { rows: () => Promise.resolve([]), preferenceRows: () => Promise.resolve([]) } as unknown as CriteriaStore,
    questions: { rows: () => Promise.resolve([]) } as unknown as QuestionStore,
    viewings: { rows: () => Promise.resolve([]) } as unknown as ViewingStore,
    areas: { rows: () => Promise.resolve([]) } as unknown as AreaStore,
    places: { rows: () => Promise.resolve([]) } as unknown as PlaceStore,
    areaNotes: { rows: () => Promise.resolve([]) } as unknown as AreaNoteStore,
    lengthUnit: () => Promise.resolve('FT' as const),
  };
  TestBed.configureTestingModule({ providers: [{ provide: LocalStore, useValue: store }] });
  return TestBed.inject(ExportService);
}

describe('the house’s copies, pinned (S4b-BL-279)', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  const OPTIONS = { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' as const };

  it.each(Object.entries(NAMES))('the %s copy is named %s', async (format, name) => {
    const result = await service().build(format as ExportFormat, OPTIONS, NOW);
    expect(result.fileName).toBe(name);
    expect(result.counts).toEqual({ houses: FIXTURE_HOUSES.filter((h) => !h.deleted).length, visits: expect.any(Number), photos: 0 });
  });

  it('the bundle of the house holds every live candidate, as it always did', async () => {
    const bundle = await service().bundle(OPTIONS, NOW.toISOString());
    expect(bundle.houses.map((h) => h.house.id)).toEqual(FIXTURE_HOUSES.filter((h) => !h.deleted).map((h) => h.id).sort());
  });
});
