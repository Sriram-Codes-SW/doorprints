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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmService } from '../../core/confirm.service';
import { LocalDataService } from '../../core/local-data.service';
import { LocalDataError } from '../../core/local-error';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import { CriterionRow, scoringOf } from '../../shared/scoring';
import type { Criterion, Scoring } from '../../shared/scoring';
import { CriteriaPage } from './criteria-page';

const PETS: CriterionRow = {
  key: 'c_1a2b3c4d',
  updatedAt: null,
  criterion: { key: 'c_1a2b3c4d', label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 },
};
const NOISE: CriterionRow = {
  key: 'noise',
  updatedAt: null,
  criterion: { key: 'noise', weight: 0, mustHave: false, minScore: 3, sort: 5, archived: true },
};
const WATER: CriterionRow = {
  key: 'water',
  updatedAt: null,
  criterion: { key: 'water', weight: 3, mustHave: true, minScore: 4, sort: 0 },
};

function scored(key: string): HouseDto {
  return { ...newHouse(13, 80), id: 'h-' + key, checklist: { [key]: 4 } };
}

interface Fakes {
  scoring: Scoring;
  houses: HouseDto[];
  saveCriteria: ReturnType<typeof vi.fn>;
  addCriterion: ReturnType<typeof vi.fn>;
  deleteCriterion: ReturnType<typeof vi.fn>;
  setRatingShare: ReturnType<typeof vi.fn>;
  resetCriteria: ReturnType<typeof vi.fn>;
}

function fakes(rows: CriterionRow[] = [], share = '0.5', houses: HouseDto[] = []): Fakes {
  return {
    scoring: scoringOf(rows, share === '0.5' ? [] : [{ key: 'score.ratingShare', value: share, updatedAt: null }]),
    houses,
    saveCriteria: vi.fn(() => of(undefined)),
    addCriterion: vi.fn(() => of({} as Criterion)),
    deleteCriterion: vi.fn(() => of(undefined)),
    setRatingShare: vi.fn(() => of(undefined)),
    resetCriteria: vi.fn(() => of(undefined)),
  };
}

afterEach(() => TestBed.resetTestingModule());

async function render(f: Fakes, confirm = true): Promise<{ host: HTMLElement; f: Fakes; ask: ReturnType<typeof vi.fn> }> {
  const ask = vi.fn(() => Promise.resolve(confirm));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [CriteriaPage],
    providers: [
      provideRouter([]),
      { provide: ConfirmService, useValue: { ask } },
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          scoring: () => of(f.scoring),
          houses: () => of(f.houses),
          saveCriteria: f.saveCriteria,
          addCriterion: f.addCriterion,
          deleteCriterion: f.deleteCriterion,
          setRatingShare: f.setRatingShare,
          resetCriteria: f.resetCriteria,
        },
      },
    ],
  });
  TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(CriteriaPage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, f, ask };
}

const change = (el: Element, value: string): void => {
  (el as HTMLSelectElement).value = value;
  el.dispatchEvent(new Event('change'));
};
const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const names = (host: HTMLElement) => [...host.querySelectorAll('li.criterion .name')].map((n) => n.textContent?.trim());

describe('CriteriaPage', () => {
  it('lists the criteria that are not archived in the person\'s order, a custom one by its label', async () => {
    const { host } = await render(fakes([PETS, NOISE, WATER]));
    expect(names(host)).toEqual([
      'Water supply',
      'Power backup',
      'Parking',
      'Sunlight',
      'Ventilation',
      'Safety and security',
      'Building condition',
      'Neighbourhood',
      'Commute',
      'Pets allowed',
    ]);
  });

  it('lists an archived criterion apart, with Restore, and puts it back at the end when restored', async () => {
    const { host, f } = await render(fakes([NOISE]));
    expect(host.querySelector('#criteria-archived-heading')?.textContent).toContain('Archived');
    const restore = host.querySelector<HTMLButtonElement>('[aria-label="Restore Quiet (low noise)"]')!;
    restore.click();
    await flush();
    expect(f.saveCriteria).toHaveBeenCalledWith([{ key: 'noise', weight: 0, mustHave: false, minScore: 3, sort: 10, archived: false }]);
  });

  it('archives a criterion', async () => {
    const { host, f } = await render(fakes());
    host.querySelector<HTMLButtonElement>('[aria-label="Archive Parking"]')!.click();
    await flush();
    expect(f.saveCriteria).toHaveBeenCalledWith([expect.objectContaining({ key: 'parking', archived: true })]);
  });

  it('sets the weight from Ignore, Low, Medium, High, with the criterion in the accessible name', async () => {
    const { host, f } = await render(fakes([WATER]));
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Weight of Water supply"]')!;
    expect([...select.options].map((o) => o.textContent?.trim())).toEqual(['Ignore', 'Low', 'Medium', 'High']);
    expect(select.value).toBe('3');
    change(select, '1');
    await flush();
    expect(f.saveCriteria).toHaveBeenCalledWith([expect.objectContaining({ key: 'water', weight: 1 })]);
  });

  it('shows the minimum score only for a must-have, and saves the switch and the minimum', async () => {
    const { host, f } = await render(fakes([WATER]));
    expect(host.querySelector('select[aria-label="Minimum score for Water supply"]')).not.toBeNull();
    expect(host.querySelector('select[aria-label="Minimum score for Power backup"]')).toBeNull();
    change(host.querySelector('select[aria-label="Minimum score for Water supply"]')!, '5');
    await flush();
    expect(f.saveCriteria).toHaveBeenCalledWith([expect.objectContaining({ key: 'water', minScore: 5 })]);
    const box = host.querySelector<HTMLInputElement>('input[aria-label="Must-have: Power backup"]')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await flush();
    expect(f.saveCriteria).toHaveBeenCalledWith([expect.objectContaining({ key: 'power', mustHave: true })]);
  });

  it('moves a criterion down and renumbers the visible ones 0..n, saving only the rows that changed', async () => {
    const { host, f } = await render(fakes());
    host.querySelector<HTMLButtonElement>('[aria-label="Move Water supply down"]')!.click();
    await flush();
    expect(f.saveCriteria).toHaveBeenCalledWith([
      expect.objectContaining({ key: 'power', sort: 0 }),
      expect.objectContaining({ key: 'water', sort: 1 }),
    ]);
    expect(host.querySelector<HTMLButtonElement>('[aria-label="Move Water supply up"]')!.disabled).toBe(true);
    expect(host.querySelector<HTMLButtonElement>('[aria-label="Move Commute down"]')!.disabled).toBe(true);
  });

  it('adds a criterion by name, and asks for a name when it is blank', async () => {
    const { host, f } = await render(fakes());
    const input = host.querySelector<HTMLInputElement>('#criteria-new')!;
    expect(input.getAttribute('maxlength')).toBe('60');
    host.querySelector('form.add')!.dispatchEvent(new Event('submit'));
    await flush();
    expect(f.addCriterion).not.toHaveBeenCalled();
    expect(host.querySelector('#criteria-new-error')?.textContent).toContain('Enter a name.');
    input.value = 'Lift';
    input.dispatchEvent(new Event('input'));
    host.querySelector('form.add')!.dispatchEvent(new Event('submit'));
    await flush();
    expect(f.addCriterion).toHaveBeenCalledWith('Lift');
  });

  it('disables Add criterion at 40 criteria and says "At most 40 criteria"', async () => {
    const rows: CriterionRow[] = Array.from({ length: 30 }, (_, i) => {
      const key = `c_${i.toString(16).padStart(8, '0')}`;
      return { key, updatedAt: null, criterion: { key, label: `Custom ${i}`, weight: 2, mustHave: false, minScore: 3, sort: 10 + i } };
    });
    const { host } = await render(fakes(rows));
    expect(host.querySelector<HTMLButtonElement>('form.add button[type="submit"]')!.disabled).toBe(true);
    expect(host.querySelector<HTMLInputElement>('#criteria-new')!.disabled).toBe(true);
    expect(host.textContent).toContain('At most 40 criteria');
    const few = await render(fakes([PETS]));
    expect(few.host.textContent).not.toContain('At most 40 criteria');
  });

  it('shows a refused add as an error next to the control', async () => {
    const f = fakes();
    f.addCriterion = vi.fn(() => throwError(() => new LocalDataError('criteria.max')));
    const { host } = await render(f);
    const input = host.querySelector<HTMLInputElement>('#criteria-new')!;
    input.value = 'Lift';
    input.dispatchEvent(new Event('input'));
    host.querySelector('form.add')!.dispatchEvent(new Event('submit'));
    await flush();
    expect(f.addCriterion).toHaveBeenCalled();
    expect(host.querySelector('.error [role="alert"]')?.textContent).toContain('At most 40 criteria');
  });

  it('offers Delete only for a custom criterion no house has scored, and asks first', async () => {
    const { host, f, ask } = await render(fakes([PETS], '0.5', [scored('water')]));
    expect(host.querySelector('[aria-label="Delete Water supply"]')).toBeNull();
    host.querySelector<HTMLButtonElement>('[aria-label="Delete Pets allowed"]')!.click();
    await flush();
    expect(ask).toHaveBeenCalled();
    expect(f.deleteCriterion).toHaveBeenCalledWith('c_1a2b3c4d');
    // A custom criterion a house scored can only be archived, and the page says so.
    const used = await render(fakes([PETS], '0.5', [scored('c_1a2b3c4d')]));
    expect(used.host.querySelector('[aria-label="Delete Pets allowed"]')).toBeNull();
    expect(used.host.textContent).toContain('can only be archived');
  });

  it('sets the rating share from 0%, 25%, 50%, 75%, 100%, 50% by default, and keeps an odd stored value', async () => {
    const { host, f } = await render(fakes());
    const select = host.querySelector<HTMLSelectElement>('#criteria-share')!;
    expect([...select.options].map((o) => o.textContent?.trim())).toEqual(['0%', '25%', '50%', '75%', '100%']);
    expect(select.value).toBe('50');
    expect(host.textContent).toContain('How much your star rating counts against the checklist');
    change(select, '75');
    await flush();
    expect(f.setRatingShare).toHaveBeenCalledWith(0.75);
    const odd = await render(fakes([], '0.4'));
    const oddSelect = odd.host.querySelector<HTMLSelectElement>('#criteria-share')!;
    expect([...oddSelect.options].map((o) => o.textContent?.trim())).toEqual(['0%', '25%', '40%', '50%', '75%', '100%']);
    expect(oddSelect.value).toBe('40');
  });

  it('resets to the defaults only after the person confirms', async () => {
    const yes = await render(fakes([WATER]));
    yes.host.querySelector<HTMLButtonElement>('.card.danger button')!.click();
    await flush();
    expect(yes.ask).toHaveBeenCalledWith({ key: 'criteria.confirmReset' }, expect.objectContaining({ danger: true }));
    expect(yes.f.resetCriteria).toHaveBeenCalled();
    const no = await render(fakes([WATER]), false);
    no.host.querySelector<HTMLButtonElement>('.card.danger button')!.click();
    await flush();
    expect(no.f.resetCriteria).not.toHaveBeenCalled();
  });

  it('marks a criterion that is ignored, in words', async () => {
    const { host } = await render(fakes([{ key: 'power', updatedAt: null, criterion: { key: 'power', weight: 0, mustHave: false, minScore: 3, sort: 1 } }]));
    expect(host.textContent).toContain('(ignored)');
  });
});
