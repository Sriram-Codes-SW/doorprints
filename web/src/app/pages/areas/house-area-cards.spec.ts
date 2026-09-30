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
import { LocalDataService } from '../../core/local-data.service';
import { LocalDataError } from '../../core/local-error';
import { TranslationService } from '../../i18n/translation.service';
import type { Area, AreaNote, AreaNoteRow, HousePoint, Place } from '../../shared/area';
import { HouseAreaNotesCard, HouseDistancesCard } from './house-area-cards';

const ADYAR: Area = { id: 'a_1f2e3d4c', name: 'Adyar', lat: 13.0067, lon: 80.2574, radiusM: 500, enabled: true };
const FAR: Area = { id: 'a_5b6c7d8e', name: 'Indiranagar', lat: 12.9784, lon: 77.6408, radiusM: 1200, enabled: true };
const NOTES: AreaNoteRow[] = [
  { id: 'n_11223344', updatedAt: '2026-09-04T00:00:00.000Z', note: { id: 'n_11223344', areaId: 'a_1f2e3d4c', text: 'Water tanker every morning' } },
  { id: 'n_55667788', updatedAt: '2026-09-05T00:00:00.000Z', note: { id: 'n_55667788', street: 'MG Road', text: 'Noisy after 9 pm' } },
  { id: 'n_99999999', updatedAt: '2026-09-06T00:00:00.000Z', note: { id: 'n_99999999', street: 'Other Road', text: 'Not here' } },
];
const OFFICE: Place = { id: 'p_0a1b2c3d', name: 'Office', lat: 13.0827, lon: 80.2707 };
const HOUSE: HousePoint = { lat: 13.006, lon: 80.2574, street: 'MG Road', locationSource: 'GPS' };

afterEach(() => {
  vi.unstubAllGlobals();
  TestBed.resetTestingModule();
  localStorage.clear();
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

async function renderNotes(house: HousePoint = HOUSE, areas: Area[] = [ADYAR, FAR], notes: AreaNoteRow[] = NOTES, saveAreaNote = vi.fn((n: AreaNote) => of(n)), lang: 'en' | 'ta' = 'en') {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: { settled: signal(0), areas: () => of(areas), areaNotes: () => of(notes), newAreaNoteId: () => of('n_0badf00d'), saveAreaNote },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(HouseAreaNotesCard);
  fixture.componentRef.setInput('house', house);
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
  return { host: fixture.nativeElement as HTMLElement, saveAreaNote, settle };
}

const buttons = (host: HTMLElement) => [...host.querySelectorAll('button')].map((b) => b.textContent?.trim());
const clickButton = (host: HTMLElement, label: string) => [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === label)!.click();
const setValue = (host: HTMLElement, selector: string, value: string) => {
  const el = host.querySelector<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input'));
};

describe('HouseAreaNotesCard', () => {
  it('lists the notes that reach the house, newest first, each with where it comes from', async () => {
    const { host } = await renderNotes();
    const items = [...host.querySelectorAll('li')].map((li) => [...li.querySelectorAll('p')].map((p) => p.textContent?.trim()));
    expect(items).toEqual([['Noisy after 9 pm', 'Street: MG Road'], ['Water tanker every morning', 'Area: Adyar']]);
    expect(host.querySelector('h2')?.textContent?.trim()).toBe('Area notes');
  });

  it('says so when no note reaches the house', async () => {
    const { host } = await renderNotes({ lat: 0, lon: 0, street: '' }, [ADYAR], NOTES);
    expect(host.querySelector('#house-area-notes-none')?.textContent?.trim()).toBe('No area notes reach this house.');
  });

  it('adds a note for this street: the street is the house\'s, trimmed, and the text is required', async () => {
    const { host, saveAreaNote, settle } = await renderNotes({ ...HOUSE, street: '  MG Road ' });
    clickButton(host, 'Add a note for this street');
    await settle();
    expect(host.querySelector('form')?.textContent).toContain('Street: MG Road');
    (host.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(host.querySelector('#house-note-text-error')?.textContent?.trim()).toBe('Write a note.');
    expect(saveAreaNote).not.toHaveBeenCalled();
    setValue(host, '#house-note-text', '  Bus depot on the corner  ');
    await settle();
    (host.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(saveAreaNote).toHaveBeenCalledWith({ id: 'n_0badf00d', street: 'MG Road', text: 'Bus depot on the corner' });
    expect(host.querySelector('form')).toBeNull();
  });

  it('hides Add a note for this street when the house has no street', async () => {
    const { host } = await renderNotes({ ...HOUSE, street: '   ' });
    expect(buttons(host)).toEqual(['Add a note for an area']);
    const none = await renderNotes({ ...HOUSE, street: null });
    expect(buttons(none.host)).toEqual(['Add a note for an area']);
  });

  it('adds a note for an area, offering the areas the house is in first and then the others', async () => {
    const { host, saveAreaNote, settle } = await renderNotes();
    clickButton(host, 'Add a note for an area');
    await settle();
    const groups = [...host.querySelectorAll('optgroup')].map((g) => [g.getAttribute('label'), [...g.querySelectorAll('option')].map((o) => o.textContent?.trim())]);
    expect(groups).toEqual([['Areas this house is in', ['Adyar']], ['Other areas', ['Indiranagar']]]);
    (host.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(host.querySelector('#house-note-area-error')?.textContent?.trim()).toBe('Choose an area.');
    setValue(host, '#house-note-area', 'a_5b6c7d8e');
    setValue(host, '#house-note-text', 'Quiet lanes');
    await settle();
    (host.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(saveAreaNote).toHaveBeenCalledWith({ id: 'n_0badf00d', areaId: 'a_5b6c7d8e', text: 'Quiet lanes' });
  });

  it('sends the person to My areas when there is no area to write on', async () => {
    const { host, settle } = await renderNotes(HOUSE, [], []);
    clickButton(host, 'Add a note for an area');
    await settle();
    expect(host.querySelector('#house-note-no-areas')?.textContent).toContain('Add an area first');
    expect(host.querySelector('#house-note-no-areas a')?.getAttribute('href')).toBe('/areas');
    expect(host.querySelector('form')).toBeNull();
  });

  it('shows the cap message the store sends ("At most 200 notes") and keeps the form open', async () => {
    const { host, settle } = await renderNotes(HOUSE, [ADYAR], [], vi.fn(() => throwError(() => new LocalDataError('areaNotes.max'))));
    clickButton(host, 'Add a note for this street');
    await settle();
    setValue(host, '#house-note-text', 'One more');
    await settle();
    (host.querySelector('form button[type="submit"]') as HTMLButtonElement).click();
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent?.trim()).toBe('At most 200 notes');
    expect(host.querySelector('form')).not.toBeNull();
  });

  it('cancel closes the form without saving, and the card is labelled in Tamil', async () => {
    const { host, saveAreaNote, settle } = await renderNotes();
    clickButton(host, 'Add a note for this street');
    await settle();
    clickButton(host, 'Cancel');
    await settle();
    expect(host.querySelector('form')).toBeNull();
    expect(saveAreaNote).not.toHaveBeenCalled();
    const ta = await renderNotes(HOUSE, [ADYAR], NOTES, undefined, 'ta');
    expect(ta.host.querySelector('h2')?.textContent?.trim()).toBe('பகுதிக் குறிப்புகள்');
  });
});

async function renderDistances(house: HousePoint, places: Place[]) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: LocalDataService, useValue: { settled: signal(0), places: () => of(places) } }] });
  await TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(HouseDistancesCard);
  fixture.componentRef.setInput('house', house);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('HouseDistancesCard', () => {
  it('shows per place the name, the kilometres with one decimal and the Plan estimate on foot', async () => {
    const host = await renderDistances({ lat: 13.0067, lon: 80.2574 }, [OFFICE]);
    expect(host.querySelector('h2')?.textContent?.trim()).toBe('Distances');
    // 8 572.757 m straight line: 8.6 km; on foot ceil(8572.757 x 1.3 / 80) = 140 minutes.
    expect(host.querySelector('li')?.textContent?.trim()).toBe('Office: 8.6 km, about 140 min on foot');
  });

  it('is hidden without places and for a house with no point', async () => {
    expect((await renderDistances({ lat: 13.0067, lon: 80.2574 }, [])).querySelector('section')).toBeNull();
    expect((await renderDistances({ lat: 0, lon: 0 }, [OFFICE])).querySelector('section')).toBeNull();
  });
});
