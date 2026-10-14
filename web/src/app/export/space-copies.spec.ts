import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import schoolVectors from '../../../../docs/schemas/kinds/school-vectors.json';
import { houseOf, type CandidateDto } from '../core/candidate';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../core/kind-setting';
import { LocalStore } from '../data/local-store.service';
import { tryHouseFromDto } from '../data/records';
import type { AreaNoteStore } from '../data/area-note-store';
import type { AreaStore } from '../data/area-store';
import type { BrokerStore } from '../data/broker-store';
import type { CriteriaStore } from '../data/criteria-store';
import type { PhotoStore } from '../data/photo-store';
import type { PlaceStore } from '../data/place-store';
import type { QuestionStore } from '../data/question-store';
import type { ViewingStore } from '../data/viewing-store';
import { DEFAULT_EXPORT_OPTIONS } from './export-model';
import type { ExportFormat } from './export-model';
import { ExportService } from './export.service';
import { FIXTURE_BROKERS, FIXTURE_HOUSES, FIXTURE_VISITS } from './golden/fixture';

/**
 * Copies per space (kinds PR B, S4b-BL-279; docs/03 §18.13, `space-scope-vectors.json` `fileName`): a copy of the school
 * space holds the schools only and its file name carries the plural; the house's copy and the backup are as they were.
 * docs/06 TC-U-216.
 */
const NOW = new Date('2026-09-22T10:00:00.000Z');
const OPTIONS = { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' as const };
const school = () => tryHouseFromDto(houseOf((schoolVectors as unknown as { candidate: CandidateDto }).candidate))!;

function service(): ExportService {
  TestBed.resetTestingModule();
  const store: Partial<LocalStore> = {
    allHouses: () => Promise.resolve([...FIXTURE_HOUSES.map((h) => ({ ...h })), school()]),
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

function twoKinds(): void {
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, 'house');
}

describe('the copies of a space (S4b-BL-279)', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('the school space holds the schools only; the house space the houses only; no space everything', async () => {
    twoKinds();
    const s = service();
    const ids = async (space?: string) => (await s.bundle({ ...OPTIONS, ...(space ? { space } : {}) })).houses.map((h) => h.house.kind ?? 'house');
    expect(new Set(await ids('school'))).toEqual(new Set(['school']));
    expect((await ids('school')).length).toBe(1);
    expect((await ids('house')).includes('school')).toBe(false);
    expect((await ids()).length).toBe((await ids('house')).length + 1);
  });

  it('what belongs to the other space stays out: brokers and viewings of a space’s copy are its own', async () => {
    twoKinds();
    const b = await service().bundle({ ...OPTIONS, space: 'school' });
    expect(b.brokers).toEqual([]);
    expect(b.viewings).toEqual([]);
    expect(b.counts.houses).toBe(1);
  });

  it('the file names carry the plural noun before the date; the house’s and the backup’s do not change', async () => {
    twoKinds();
    const s = service();
    const names = async (space?: string) => {
      const out: Record<string, string> = {};
      for (const f of ['html', 'markdown', 'csv', 'xlsx', 'backup'] as ExportFormat[]) out[f] = (await s.build(f, { ...OPTIONS, ...(space ? { space } : {}) }, NOW)).fileName;
      return out;
    };
    expect(await names('school')).toEqual({
      html: 'Doorprints-copy-schools-2026-09-22.html',
      markdown: 'Doorprints-schools-2026-09-22.md',
      csv: 'Doorprints-schools-2026-09-22-csv.zip',
      xlsx: 'Doorprints-schools-2026-09-22.xlsx',
      backup: 'Doorprints-backup-2026-09-22.zip',
    });
    expect(await names('house')).toEqual(await names());
  });

  it('the backup holds everything even when a space is asked for', async () => {
    twoKinds();
    const s = service();
    const all = await s.build('backup', OPTIONS, NOW);
    const asked = await s.build('backup', { ...OPTIONS, space: 'school' }, NOW);
    expect(asked.counts).toEqual(all.counts);
    expect(asked.fileName).toBe(all.fileName);
  });

  it('with one kind enabled a space is no filter and no name', async () => {
    const s = service();
    const r = await s.build('markdown', { ...OPTIONS, space: 'school' }, NOW);
    expect(r.fileName).toBe('Doorprints-2026-09-22.md');
    expect(r.counts.houses).toBe(FIXTURE_HOUSES.filter((h) => !h.deleted).length + 1);
  });
});
