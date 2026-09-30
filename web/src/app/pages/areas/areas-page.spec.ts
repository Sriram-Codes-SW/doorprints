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
import { TranslationService } from '../../i18n/translation.service';
import type { Area, AreaNoteRow } from '../../shared/area';
import { AreasPage } from './areas-page';

const area = (id: string, over: Partial<Area> = {}): Area => ({ id, name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500, enabled: true, ...over });
const ADYAR = area('a_1f2e3d4c');
const INDIRANAGAR = area('a_5b6c7d8e', { name: 'Indiranagar 2nd stage', radiusM: 1200, enabled: false });
const note = (id: string, updatedAt: string, n: AreaNoteRow['note']): AreaNoteRow => ({ id, updatedAt, note: n });
const NOTES: AreaNoteRow[] = [
  note('n_55667788', '2026-09-05T00:00:00.000Z', { id: 'n_55667788', street: 'MG Road', text: 'Noisy after 9 pm' }),
  note('n_11223344', '2026-09-04T00:00:00.000Z', { id: 'n_11223344', areaId: 'a_1f2e3d4c', text: 'Water tanker every morning' }),
  note('n_99999999', '2026-09-03T00:00:00.000Z', { id: 'n_99999999', areaId: 'a_gone', text: 'Orphan' }),
];

// A later spec file in the same worker must not start in Tamil or with a stubbed global.
afterEach(() => {
  vi.unstubAllGlobals();
  TestBed.resetTestingModule();
  localStorage.clear();
});

interface Fakes {
  areas: Area[];
  notes: AreaNoteRow[];
  saveArea: ReturnType<typeof vi.fn>;
  deleteArea: ReturnType<typeof vi.fn>;
  saveAreaNote: ReturnType<typeof vi.fn>;
  deleteAreaNote: ReturnType<typeof vi.fn>;
  newAreaId: ReturnType<typeof vi.fn>;
}

function fakes(areas: Area[] = [ADYAR, INDIRANAGAR], notes: AreaNoteRow[] = NOTES): Fakes {
  return {
    areas,
    notes,
    saveArea: vi.fn((a: Area) => of(a)),
    deleteArea: vi.fn(() => of(undefined)),
    saveAreaNote: vi.fn((n: unknown) => of(n)),
    deleteAreaNote: vi.fn(() => of(undefined)),
    newAreaId: vi.fn(() => of('a_0badf00d')),
  };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

async function render(f: Fakes, confirm = true, lang: 'en' | 'ta' = 'en') {
  const ask = vi.fn(() => Promise.resolve(confirm));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [AreasPage],
    providers: [
      provideRouter([]),
      { provide: ConfirmService, useValue: { ask } },
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          areas: () => of(f.areas),
          areaNotes: () => of(f.notes),
          newAreaId: f.newAreaId,
          saveArea: f.saveArea,
          deleteArea: f.deleteArea,
          saveAreaNote: f.saveAreaNote,
          deleteAreaNote: f.deleteAreaNote,
        },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(AreasPage);
  fixture.detectChanges();
  await fixture.whenStable();
  await flush();
  fixture.detectChanges();
  const settle = async () => {
    fixture.detectChanges();
    await fixture.whenStable();
    await flush();
    fixture.detectChanges();
  };
  return { host: fixture.nativeElement as HTMLElement, f, ask, fixture, settle };
}

const click = (host: HTMLElement, selector: string) => (host.querySelector(selector) as HTMLElement).click();
const setValue = (host: HTMLElement, selector: string, value: string, event = 'input') => {
  const el = host.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event(event));
};
const text = (host: HTMLElement, selector: string) => host.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();

describe('AreasPage', () => {
  it('lists the areas with name and radius, marks a switched-off one, and gives every row an accessible name', async () => {
    const { host } = await render(fakes());
    const rows = [...host.querySelectorAll('section:first-of-type .row-btn')];
    expect(rows.map((r) => r.textContent?.replace(/\s+/g, ' ').trim())).toEqual(['Adyar 500 m', 'Indiranagar 2nd stage 1,200 m · Off']);
    expect(rows[0].getAttribute('aria-label')).toBe('Edit Adyar, radius 500 metres');
    expect(rows.every((r) => r.tagName === 'BUTTON')).toBe(true);
  });

  it('says so when there are no areas and no notes', async () => {
    const { host } = await render(fakes([], []));
    expect(text(host, '#areas-empty')).toBe('No areas yet. Add the neighbourhoods you are hunting in.');
    expect(text(host, '#notes-empty')).toBe('No area notes yet. Add one from a house.');
  });

  it('adds an area: the name and the point are required, then it saves with a new a_ id, 100 m steps and the switch', async () => {
    const { host, f, settle } = await render(fakes([]));
    click(host, 'section .btn-primary');
    await settle();
    expect(host.querySelector('#area-radius')?.getAttribute('step')).toBe('100');
    expect(host.querySelector('#area-radius')?.getAttribute('min')).toBe('200');
    expect(host.querySelector('#area-radius')?.getAttribute('max')).toBe('2000');
    expect(text(host, '#area-enabled-hint')).toBe('Only stored for now; it takes effect in a later update.');
    expect(host.querySelector('#area-enabled')?.getAttribute('role')).toBe('switch');
    click(host, 'form button[type="submit"]');
    await settle();
    expect(text(host, '#area-name-error')).toBe('Enter a name.');
    expect(host.textContent).toContain('Choose the spot first');
    expect(f.saveArea).not.toHaveBeenCalled();

    setValue(host, '#area-name', '  Adyar  ');
    setValue(host, '#area-point-lat', '13.0067', 'change');
    await settle();
    setValue(host, '#area-point-lon', '80.2574', 'change');
    setValue(host, '#area-radius', '1200');
    click(host, '#area-enabled');
    await settle();
    click(host, 'form button[type="submit"]');
    await settle();
    expect(f.newAreaId).toHaveBeenCalledTimes(1);
    expect(f.saveArea).toHaveBeenCalledWith({ id: 'a_0badf00d', name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 1200, enabled: false });
    expect(host.querySelector('form')).toBeNull();
  });

  it('refuses a coordinate that is not a number, and keeps the form open', async () => {
    const { host, f, settle } = await render(fakes([]));
    click(host, 'section .btn-primary');
    await settle();
    setValue(host, '#area-point-lat', 'north', 'change');
    await settle();
    expect(host.querySelector('form .field-error')?.textContent).toContain('Latitude must be between');
    expect(f.saveArea).not.toHaveBeenCalled();
  });

  it('edits an area: the form holds its values and saving keeps its id', async () => {
    const { host, f, settle } = await render(fakes());
    click(host, '.row-btn');
    await settle();
    expect((host.querySelector('#area-name') as HTMLInputElement).value).toBe('Adyar');
    expect((host.querySelector('#area-radius') as HTMLInputElement).value).toBe('500');
    setValue(host, '#area-name', 'Adyar East');
    await settle();
    click(host, 'form button[type="submit"]');
    await settle();
    expect(f.newAreaId).not.toHaveBeenCalled();
    expect(f.saveArea).toHaveBeenCalledWith(expect.objectContaining({ id: 'a_1f2e3d4c', name: 'Adyar East', radiusM: 500 }));
  });

  it('deletes an area after a confirmation, and not when the answer is no', async () => {
    const yes = await render(fakes());
    click(yes.host, '.row-btn');
    await yes.settle();
    click(yes.host, 'form .btn-danger');
    await yes.settle();
    expect(yes.ask).toHaveBeenCalledWith({ key: 'areas.confirmDelete' }, expect.objectContaining({ danger: true }));
    expect(yes.f.deleteArea).toHaveBeenCalledWith('a_1f2e3d4c');
    const no = await render(fakes(), false);
    click(no.host, '.row-btn');
    await no.settle();
    click(no.host, 'form .btn-danger');
    await no.settle();
    expect(no.f.deleteArea).not.toHaveBeenCalled();
  });

  it('hides Add and says "At most 20 areas" at 20', async () => {
    const twenty = Array.from({ length: 20 }, (_, i) => area('a_' + String(i).padStart(8, '0'), { name: 'Area ' + i }));
    const { host } = await render(fakes(twenty));
    expect(text(host, '#areas-cap')).toBe('At most 20 areas');
    expect(host.querySelector('section .btn-primary')).toBeNull();
  });

  it('shows the cap message the store sends when a save is refused', async () => {
    const f = fakes([]);
    f.saveArea = vi.fn(() => throwError(() => new LocalDataError('areas.max')));
    const { host, settle } = await render(f);
    click(host, 'section .btn-primary');
    await settle();
    setValue(host, '#area-name', 'One');
    setValue(host, '#area-point-lat', '13', 'change');
    await settle();
    setValue(host, '#area-point-lon', '80', 'change');
    await settle();
    click(host, 'form button[type="submit"]');
    await settle();
    expect(host.querySelector('.error')?.textContent).toContain('At most 20 areas');
  });

  it('lists the area notes with where each comes from, newest first, and a note of a deleted area says so', async () => {
    const { host } = await render(fakes());
    const items = [...host.querySelectorAll('li.note')].map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(items[0]).toContain('Noisy after 9 pm');
    expect(items[0]).toContain('Street: MG Road');
    expect(items[1]).toContain('Area: Adyar');
    expect(items[2]).toContain('Area: An area that is gone');
  });

  it('filters the notes by area or by street, and by the street typed', async () => {
    const { host, settle } = await render(fakes());
    const count = () => host.querySelectorAll('li.note').length;
    setValue(host, '#notes-filter', 'a_1f2e3d4c', 'change');
    await settle();
    expect(count()).toBe(1);
    setValue(host, '#notes-filter', '!streets', 'change');
    await settle();
    expect(count()).toBe(1);
    setValue(host, '#notes-filter', '', 'change');
    setValue(host, '#notes-search', 'mg r');
    await settle();
    expect(count()).toBe(1);
    setValue(host, '#notes-search', 'nowhere');
    await settle();
    expect(count()).toBe(0);
    expect(text(host, '#notes-no-match')).toBe('No notes match.');
  });

  it('edits a note, keeping its target, and refuses a blank text', async () => {
    const { host, f, settle } = await render(fakes());
    click(host, 'li.note button.btn-sm');
    await settle();
    setValue(host, '#note-text', '   ');
    await settle();
    click(host, 'li.note .btn-primary');
    await settle();
    expect(f.saveAreaNote).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Write a note.');
    setValue(host, '#note-street', 'MG Road 2');
    setValue(host, '#note-text', 'Quiet now');
    await settle();
    click(host, 'li.note .btn-primary');
    await settle();
    expect(f.saveAreaNote).toHaveBeenCalledWith({ id: 'n_55667788', street: 'MG Road 2', text: 'Quiet now' });
  });

  it('deletes a note after a confirmation', async () => {
    const { host, f, ask, settle } = await render(fakes());
    click(host, 'li.note .btn-danger');
    await settle();
    expect(ask).toHaveBeenCalledWith({ key: 'areaNotes.confirmDelete' }, expect.objectContaining({ danger: true }));
    expect(f.deleteAreaNote).toHaveBeenCalledWith('n_55667788');
  });

  it('shows the page in Tamil with a labelled name field and accessible names', async () => {
    const { host, settle } = await render(fakes(), true, 'ta');
    expect(text(host, 'h1')).toBe('என் பகுதிகள்');
    click(host, 'section .btn-primary');
    await settle();
    expect(host.querySelector('label[for="area-name"]')?.textContent?.trim()).toBe('பெயர்');
    expect(host.querySelector('#area-radius')).not.toBeNull();
    expect(host.querySelector('.row-btn')?.getAttribute('aria-label')).toContain('ஐத் திருத்து');
  });

  it('shows a loading card first and an error card when the areas cannot be read', async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AreasPage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: { settled: signal(0), areas: () => throwError(() => new LocalDataError('error.badRecord')), areaNotes: () => of([]) } },
      ],
    });
    const fixture = TestBed.createComponent(AreasPage);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('[role="status"]')).not.toBeNull();
    await fixture.whenStable();
    await flush();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.error')?.textContent).toContain('could not read');
  });
});
