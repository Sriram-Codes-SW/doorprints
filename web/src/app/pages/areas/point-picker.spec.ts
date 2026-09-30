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

import { By } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TranslationService } from '../../i18n/translation.service';
import { LocationMap } from '../../shared/location-map';
import type { LatLon } from '../../shared/location-map';
import { PointPicker } from './point-picker';

afterEach(() => {
  vi.unstubAllGlobals();
  TestBed.resetTestingModule();
  localStorage.clear();
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

async function render(lat: number | null = null, lon: number | null = null, showRequired = false) {
  TestBed.resetTestingModule();
  await TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(PointPicker);
  fixture.componentRef.setInput('lat', lat);
  fixture.componentRef.setInput('lon', lon);
  fixture.componentRef.setInput('showRequired', showRequired);
  const picked: LatLon[] = [];
  fixture.componentInstance.picked.subscribe((p) => picked.push(p));
  fixture.detectChanges();
  await fixture.whenStable();
  return { fixture, picked, host: fixture.nativeElement as HTMLElement };
}

const change = (host: HTMLElement, selector: string, value: string) => {
  const el = host.querySelector<HTMLInputElement>(selector)!;
  el.value = value;
  el.dispatchEvent(new Event('change'));
};

describe('PointPicker', () => {
  it('shows the map, labelled, with the coordinates and Use my location as real controls', async () => {
    vi.stubGlobal('navigator', { ...navigator, geolocation: {} });
    const { host } = await render(13.0067, 80.2574);
    expect(host.querySelector('app-location-map')?.getAttribute('aria-label')).toBe('Map of the chosen spot');
    expect(host.querySelector('label[for="point-lat"]')?.textContent?.trim()).toBe('Latitude');
    expect((host.querySelector('#point-lat') as HTMLInputElement).value).toBe('13.0067');
    expect((host.querySelector('#point-lon') as HTMLInputElement).value).toBe('80.2574');
    expect(host.querySelector('button')?.textContent?.trim()).toBe('Use my location');
  });

  it('emits the point from a tap or drag on the map, rounded to six decimals', async () => {
    const { fixture, picked } = await render();
    const map = fixture.debugElement.query(By.directive(LocationMap)).componentInstance as LocationMap;
    map.moved.emit({ lat: 13.00670049, lon: 80.25740049 });
    expect(picked).toEqual([{ lat: 13.0067, lon: 80.2574 }]);
  });

  it('emits typed coordinates, accepts a comma and a minus sign, and refuses a value out of range', async () => {
    const { host, picked, fixture } = await render(13, 80);
    change(host, '#point-lat', '12,5');
    expect(picked).toEqual([{ lat: 12.5, lon: 80 }]);
    change(host, '#point-lon', '−77.6');
    expect(picked[1]).toEqual({ lat: 13, lon: -77.6 });
    change(host, '#point-lat', '91');
    fixture.detectChanges();
    expect(picked).toHaveLength(2);
    expect(host.querySelector('.field-error')?.textContent).toContain('Latitude must be between');
    expect(host.querySelector('#point-lat')?.getAttribute('aria-invalid')).toBe('true');
  });

  it('says to choose the spot first when the parent asks', async () => {
    const { host } = await render(null, null, true);
    expect(host.querySelector('.field-error')?.textContent).toContain('Choose the spot first');
  });

  it('uses the current location, and says so when the browser refuses', async () => {
    const ok = { getCurrentPosition: (success: (p: unknown) => void) => success({ coords: { latitude: 12.97161234, longitude: 77.59461234 } }) };
    vi.stubGlobal('navigator', { ...navigator, geolocation: ok });
    const first = await render();
    (first.host.querySelector('button') as HTMLButtonElement).click();
    expect(first.picked).toEqual([{ lat: 12.971612, lon: 77.594612 }]);

    const denied = { getCurrentPosition: (_s: unknown, fail: (e: { code: number }) => void) => fail({ code: 1 }) };
    vi.stubGlobal('navigator', { ...navigator, geolocation: denied });
    const second = await render();
    (second.host.querySelector('button') as HTMLButtonElement).click();
    second.fixture.detectChanges();
    await flush();
    second.fixture.detectChanges();
    expect(second.picked).toEqual([]);
    expect(second.host.querySelector('[role="alert"]')?.textContent?.trim()).not.toBe('');
  });

  it('offers no Use my location button where the browser has no geolocation', async () => {
    const copy = { ...navigator } as Record<string, unknown>;
    delete copy['geolocation'];
    vi.stubGlobal('navigator', copy);
    const { host } = await render();
    expect(host.querySelector('button')).toBeNull();
  });
});
