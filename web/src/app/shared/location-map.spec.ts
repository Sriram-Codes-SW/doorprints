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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TranslationService } from '../i18n/translation.service';
import { LocationMap } from './location-map';

/**
 * The panel over the house location map when the map has no tiles. The map itself is not made (jsdom has no WebGL):
 * `ngAfterViewInit` is stubbed and the two signals that `watchMapStyle` drives are set directly.
 */
type Panel = { available: { set(v: boolean): void }; workerFailed: { set(v: boolean): void } };

function render() {
  vi.spyOn(LocationMap.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
  TestBed.configureTestingModule({ imports: [LocationMap] });
  TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(LocationMap);
  fixture.componentRef.setInput('lat', 13);
  fixture.componentRef.setInput('lon', 80);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, panel: fixture.componentInstance as unknown as Panel };
}

const tryAgain = (host: HTMLElement) => [...host.querySelectorAll('.offline button')].find((b) => b.textContent?.trim() === 'Try again');

afterEach(() => {
  vi.restoreAllMocks();
  TestBed.resetTestingModule();
});

describe('LocationMap: the panel shown when the map has no tiles', () => {
  it('offers Try again while offline', () => {
    const { fixture, host, panel } = render();
    panel.available.set(false);
    fixture.detectChanges();
    expect(host.querySelector('.offline')).toBeTruthy();
    expect(tryAgain(host)).toBeTruthy();
  });

  it('says the map helper failed and offers no Try again, which could not bring it back', () => {
    const { fixture, host, panel } = render();
    panel.available.set(false);
    panel.workerFailed.set(true);
    fixture.detectChanges();
    expect(host.querySelector('.offline')?.textContent).toContain('The map could not start its helper');
    expect(tryAgain(host)).toBeUndefined();
  });

  it('shows no panel while the map is available', () => {
    const { host } = render();
    expect(host.querySelector('.offline')).toBeNull();
  });
});
