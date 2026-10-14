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
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';

/**
 * S4b-BL-249: *Your data* did not move on load. The export card is a placeholder until the saved options are read, then
 * the form or the empty state, which is taller; the cards under it were on the screen and jumped down (a layout shift of
 * 0.09-0.10 on a phone, found by the layout gate). They are now held back (`display: none` under `.page.loading`) until
 * the card has its content, so they are painted once.
 */
afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

const UNDER_THE_EXPORT_CARD = ['app-import-backup', 'app-drive-connect', 'app-offline-areas'];

describe('Your data on load (S4b-BL-249)', () => {
  it('shows only the heading and the export placeholder until the card has its content, then every card once', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/data');
    const root = harness.routeNativeElement as HTMLElement;
    const page = root.querySelector('.page') as HTMLElement;
    const display = (selector: string) => getComputedStyle(root.querySelector(selector) as HTMLElement).display;

    // Before the saved options are read: the placeholder is there, the cards under it are not painted.
    expect(page.classList.contains('loading')).toBe(true);
    expect(root.querySelector('#export-heading')).not.toBeNull();
    for (const card of UNDER_THE_EXPORT_CARD) expect(display(card), card).toBe('none');

    for (let i = 0; i < 20 && page.classList.contains('loading'); i++) {
      await harness.fixture.whenStable();
      await new Promise((resolve) => setTimeout(resolve, 10));
      harness.detectChanges();
    }

    expect(page.classList.contains('loading')).toBe(false);
    for (const card of UNDER_THE_EXPORT_CARD) expect(display(card), card).not.toBe('none');
  });
});
