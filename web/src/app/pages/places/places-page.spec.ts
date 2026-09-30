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
import type { Place } from '../../shared/area';
import { PlacesPage } from './places-page';

const place = (id: string, name: string): Place => ({ id, name, lat: 13.0827, lon: 80.2707 });
const OFFICE = place('p_0a1b2c3d', 'Office');
const AMMA = place('p_4e5f6a7b', "Amma's home");

afterEach(() => {
  vi.unstubAllGlobals();
  TestBed.resetTestingModule();
  localStorage.clear();
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

async function render(places: Place[] = [OFFICE, AMMA], confirm = true, lang: 'en' | 'ta' = 'en', savePlace = vi.fn((p: Place) => of(p))) {
  const ask = vi.fn(() => Promise.resolve(confirm));
  const deletePlace = vi.fn(() => of(undefined));
  const newPlaceId = vi.fn(() => of('p_0badf00d'));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [PlacesPage],
    providers: [
      provideRouter([]),
      { provide: ConfirmService, useValue: { ask } },
      { provide: LocalDataService, useValue: { settled: signal(0), places: () => of(places), newPlaceId, savePlace, deletePlace } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(PlacesPage);
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
  return { host: fixture.nativeElement as HTMLElement, ask, savePlace, deletePlace, newPlaceId, settle };
}

const click = (host: HTMLElement, selector: string) => (host.querySelector(selector) as HTMLElement).click();
const setValue = (host: HTMLElement, selector: string, value: string, event = 'input') => {
  const el = host.querySelector<HTMLInputElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event(event));
};
const text = (host: HTMLElement, selector: string) => host.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim();

describe('PlacesPage', () => {
  it('lists the places by name with an accessible name on each row, and says so when there are none', async () => {
    const { host } = await render();
    const rows = [...host.querySelectorAll('.row-btn')];
    expect(rows.map((r) => r.textContent?.trim())).toEqual(['Office', "Amma's home"]);
    expect(rows[0].getAttribute('aria-label')).toBe('Edit Office');
    const empty = await render([]);
    expect(text(empty.host, '#places-empty')).toContain('No places yet.');
  });

  it('adds a place: name and point are required, then it saves with a new p_ id', async () => {
    const { host, savePlace, newPlaceId, settle } = await render([]);
    click(host, 'section .btn-primary');
    await settle();
    click(host, 'form button[type="submit"]');
    await settle();
    expect(text(host, '#place-name-error')).toBe('Enter a name.');
    expect(host.textContent).toContain('Choose the spot first');
    expect(savePlace).not.toHaveBeenCalled();
    setValue(host, '#place-name', ' Office ');
    setValue(host, '#place-point-lat', '13.0827', 'change');
    await settle();
    setValue(host, '#place-point-lon', '80.2707', 'change');
    await settle();
    click(host, 'form button[type="submit"]');
    await settle();
    expect(newPlaceId).toHaveBeenCalledTimes(1);
    expect(savePlace).toHaveBeenCalledWith({ id: 'p_0badf00d', name: 'Office', lat: 13.0827, lon: 80.2707 });
  });

  it('edits and deletes a place, deleting only after a yes', async () => {
    const yes = await render();
    click(yes.host, '.row-btn');
    await yes.settle();
    expect((yes.host.querySelector('#place-name') as HTMLInputElement).value).toBe('Office');
    click(yes.host, 'form .btn-danger');
    await yes.settle();
    expect(yes.ask).toHaveBeenCalledWith({ key: 'places.confirmDelete' }, expect.objectContaining({ danger: true }));
    expect(yes.deletePlace).toHaveBeenCalledWith('p_0a1b2c3d');
    const no = await render(undefined, false);
    click(no.host, '.row-btn');
    await no.settle();
    click(no.host, 'form .btn-danger');
    await no.settle();
    expect(no.deletePlace).not.toHaveBeenCalled();
  });

  it('hides Add and says "At most 10 places" at 10, and shows the store\'s refusal otherwise', async () => {
    const ten = Array.from({ length: 10 }, (_, i) => place('p_' + String(i).padStart(8, '0'), 'Place ' + i));
    const full = await render(ten);
    expect(text(full.host, '#places-cap')).toBe('At most 10 places');
    expect(full.host.querySelector('section .btn-primary')).toBeNull();
    const refused = await render([], true, 'en', vi.fn(() => throwError(() => new LocalDataError('places.max'))));
    click(refused.host, 'section .btn-primary');
    await refused.settle();
    setValue(refused.host, '#place-name', 'Office');
    setValue(refused.host, '#place-point-lat', '13', 'change');
    await refused.settle();
    setValue(refused.host, '#place-point-lon', '80', 'change');
    await refused.settle();
    click(refused.host, 'form button[type="submit"]');
    await refused.settle();
    expect(refused.host.querySelector('.error')?.textContent).toContain('At most 10 places');
  });

  it('shows the page in Tamil', async () => {
    const { host } = await render(undefined, true, 'ta');
    expect(text(host, 'h1')).toBe('என் இடங்கள்');
  });
});
