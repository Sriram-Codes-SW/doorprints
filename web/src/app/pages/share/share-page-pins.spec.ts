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
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TranslationService } from '../../i18n/translation.service';
import { SharePage } from './share-page';

/**
 * The share target with only houses, pinned before kinds PR B (S4b-BL-279; docs/03 §18.12): the same words, no kind
 * choice, and the text goes to the map in add mode as it always did. Written first, green on `main`, kept green by the
 * change (docs/06 TC-U-216).
 */
async function open(query: Record<string, string>) {
  TestBed.resetTestingModule();
  sessionStorage.clear();
  TestBed.configureTestingModule({
    imports: [SharePage],
    providers: [provideRouter([]), { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(query) } } }],
  });
  await TestBed.inject(TranslationService).setLang('en');
  const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
  const fixture = TestBed.createComponent(SharePage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { fixture, navigate, host: fixture.nativeElement as HTMLElement };
}

const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('the share target with only houses, pinned (S4b-BL-279)', () => {
  afterEach(() => {
    sessionStorage.clear();
    TestBed.resetTestingModule();
  });

  it('shows the heading, the text and the three buttons, and no choice of what it is', async () => {
    const { host } = await open({ title: 'Flat for rent', text: 'Call 98450 12345' });
    expect(text(host.querySelector('h1'))).toBe('Add a shared listing');
    expect((host.querySelector('#shared') as HTMLTextAreaElement).value).toBe('Flat for rent\nCall 98450 12345');
    expect([...host.querySelectorAll('.actions .btn')].map(text)).toEqual(['Add a house from this', 'Copy', 'Back to the map']);
    expect(host.querySelector('select')).toBeNull();
    expect(text(host.querySelector('#shared-hint'))).toContain('Next, choose where the house is on the map.');
  });

  it('Add a house from this goes to the map in add mode with the text in the navigation state, not the address', async () => {
    const { fixture, navigate } = await open({ text: 'Call 98450 12345' });
    (fixture.componentInstance as unknown as { createHouse(): void }).createHouse();
    expect(navigate).toHaveBeenCalledWith(['/'], { state: { shared: 'Call 98450 12345', pickLocation: true }, replaceUrl: true });
  });
});
