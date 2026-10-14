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
import { RouterTestingHarness } from '@angular/router/testing';
import { provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { routes } from '../../app.routes';
import { BulkGuard } from '../../core/bulk-guard.service';
import { ConfirmService } from '../../core/confirm.service';
import { LocalStore } from '../../data/local-store.service';
import { TranslationService } from '../../i18n/translation.service';
import type { HouseDto } from '../../core/models';

const h = (id: string, kind?: string): HouseDto => ({ id, kind, label: id, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 }) as unknown as HouseDto;

/** Remove all data lists what this browser holds per kind, in the card and in its question (deletion safeguards PR 2, docs/02 T-D15). */
describe('Your data: Remove all data names what it removes', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  async function open() {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    await TestBed.inject(TranslationService).setLang('en');
    const store = TestBed.inject(LocalStore);
    await store.saveHouse(h('a'));
    await store.saveHouse(h('b', 'house'));
    await store.saveHouse(h('c', 'clinic'));
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/data');
    for (let i = 0; i < 4; i++) {
      await harness.fixture.whenStable();
      await new Promise((r) => setTimeout(r, 0));
      harness.detectChanges();
    }
    return harness;
  }

  it('lists the count of every kind, a kind with no space of its own too', async () => {
    const harness = await open();
    const text = (harness.routeNativeElement as HTMLElement).querySelector('#clear-counts')?.textContent?.trim();
    expect(text).toBe('On this device: Houses: 2, clinic: 1.');
  });

  it('puts the same list in the question, and a bulk action started a moment ago holds the removal back', async () => {
    const harness = await open();
    const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(true);
    const page = harness.routeDebugElement!.componentInstance as { clearBrowser(): Promise<void> };
    TestBed.inject(BulkGuard).tryStart();
    await page.clearBrowser();
    expect(ask).toHaveBeenCalledTimes(1);
    expect(ask.mock.calls[0][0]).toEqual({ key: 'confirm.clearBrowser', params: { list: 'On this device: Houses: 2, clinic: 1.' } });
    // Nothing was removed: the pause was running.
    expect((await TestBed.inject(LocalStore).liveHouses()).length).toBe(3);
  });
});
