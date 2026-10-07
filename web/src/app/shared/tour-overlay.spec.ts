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
import { Router, provideRouter } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TOUR_KEY } from '../core/storage-keys';
import { TourService } from '../core/tour.service';
import { en } from '../i18n/en';
import { TranslationService } from '../i18n/translation.service';
import { TourOverlay } from './tour-overlay';

beforeEach(() => localStorage.clear());
afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
  document.getElementById('add-toggle')?.remove();
});

async function setup() {
  TestBed.configureTestingModule({ imports: [TourOverlay], providers: [provideRouter([{ path: '**', children: [] }])] });
  await TestBed.inject(TranslationService).setLang('en');
  await TestBed.inject(Router).navigateByUrl('/');
  const fixture = TestBed.createComponent(TourOverlay);
  const el = fixture.nativeElement as HTMLElement;
  const settle = async (ms = 0) => {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((r) => setTimeout(r, ms));
    fixture.detectChanges();
  };
  await settle();
  return { fixture, el, settle, tour: TestBed.inject(TourService) };
}

describe('TourOverlay', () => {
  it('offers the tour on a first visit and "Not now" is remembered', async () => {
    const { el, settle } = await setup();
    expect(el.querySelector('#tour-offer-title')?.textContent).toContain(en['tour.offer.title']);
    el.querySelector<HTMLButtonElement>('#tour-offer-skip')!.click();
    await settle();
    expect(el.querySelector('.offer')).toBeNull();
    expect(localStorage.getItem(TOUR_KEY)).toBe('skipped');
  });

  it('starts from the offer: a labelled dialog with the step, what to try, and focus on its heading', async () => {
    const { el, settle } = await setup();
    el.querySelector<HTMLButtonElement>('#tour-offer-start')!.click();
    await settle();
    const dialog = el.querySelector('[role="dialog"]')!;
    expect(dialog.getAttribute('aria-labelledby')).toBe('tour-title');
    expect(el.querySelector('#tour-title')?.textContent).toContain(en['tour.welcome.title']);
    expect(el.textContent).toContain(en['tour.tryIt']);
    expect(el.textContent).toContain('Step 1 of 12');
    expect(document.activeElement?.id).toBe('tour-title');
    expect(el.querySelector('#tour-back')).toBeNull();
    expect(el.querySelector('.offer')).toBeNull();
  });

  it('highlights the target of a step and leaves the page clickable under the highlight', async () => {
    const target = document.createElement('button');
    target.id = 'add-toggle';
    // jsdom has no layout and no scrollIntoView.
    target.scrollIntoView = () => undefined;
    target.getBoundingClientRect = () => ({ top: 100, left: 20, width: 120, height: 40, right: 140, bottom: 140, x: 20, y: 100, toJSON: () => ({}) });
    document.body.appendChild(target);
    const { el, settle, tour } = await setup();
    tour.start();
    tour.next(); // the "add" step
    await settle(150);
    const spot = el.querySelector<HTMLElement>('.spot');
    expect(spot).not.toBeNull();
    expect(spot!.getAttribute('aria-hidden')).toBe('true');
    expect(spot!.style.top).toBe('94px');
    expect(spot!.style.width).toBe('132px');
    expect(el.querySelector('.card')?.classList.contains('top')).toBe(false);
  });

  it('shows a step without the highlight when its target is not on the page', async () => {
    const { el, settle, tour } = await setup();
    tour.start();
    tour.next();
    await settle(50);
    expect(el.querySelector('.spot')).toBeNull();
    expect(el.querySelector('.card')?.classList.contains('middle')).toBe(true);
    expect(el.querySelector('#tour-title')?.textContent).toContain(en['tour.add.title']);
  });

  it('goes back and forward with the buttons, names the last button Finish, and Escape skips', async () => {
    const { el, settle, tour } = await setup();
    tour.start();
    await settle();
    el.querySelector<HTMLButtonElement>('#tour-next')!.click();
    await settle();
    expect(el.querySelector('#tour-title')?.textContent).toContain(en['tour.add.title']);
    el.querySelector<HTMLButtonElement>('#tour-back')!.click();
    await settle();
    expect(el.querySelector('#tour-title')?.textContent).toContain(en['tour.welcome.title']);
    for (let i = 0; i < 11; i++) tour.next();
    await settle();
    expect(el.querySelector('#tour-next')?.textContent).toContain(en['tour.finish']);
    el.querySelector<HTMLElement>('[role="dialog"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle();
    expect(el.querySelector('[role="dialog"]')).toBeNull();
    expect(localStorage.getItem(TOUR_KEY)).toBe('skipped');
  });
});
