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
import { describe, expect, it } from 'vitest';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import { DataPage } from './data-page';

/** Slice 1c: the *Length units* switch on Your data reads and writes the local setting (never synced). */
describe('DataPage: Length units', () => {
  async function open() {
    TestBed.configureTestingModule({ imports: [DataPage], providers: [provideRouter([])] });
    TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(DataPage);
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    fixture.detectChanges();
    return { fixture, host: fixture.nativeElement as HTMLElement };
  }

  const radio = (host: HTMLElement, value: string) => host.querySelector<HTMLInputElement>(`input[name="lengthUnit"][value="${value}"]`)!;

  it('offers Feet and Metres, with Feet chosen by default', async () => {
    const { host } = await open();
    expect(host.querySelector('#units-heading')?.textContent).toContain('Length units');
    expect(radio(host, 'FT').checked).toBe(true);
    expect(radio(host, 'M').checked).toBe(false);
    expect(host.querySelector('input[name="lengthUnit"]')!.closest('label')?.textContent).toContain('Feet');
  });

  it('persists the choice of Metres in the local store and shows it on the next visit', async () => {
    const first = await open();
    radio(first.host, 'M').click();
    await first.fixture.whenStable();
    first.fixture.detectChanges();
    expect(radio(first.host, 'M').checked).toBe(true);
    expect(await TestBed.inject(LocalStore).lengthUnit()).toBe('M');

    const store = TestBed.inject(LocalStore);
    first.fixture.destroy();
    // A new page over the same store: the switch reads it back.
    const again = TestBed.createComponent(DataPage);
    again.detectChanges();
    await again.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
    again.detectChanges();
    expect(await store.lengthUnit()).toBe('M');
    expect(radio(again.nativeElement as HTMLElement, 'M').checked).toBe(true);

    radio(again.nativeElement as HTMLElement, 'FT').click();
    await again.whenStable();
    expect(await store.lengthUnit()).toBe('FT');
  });
});
