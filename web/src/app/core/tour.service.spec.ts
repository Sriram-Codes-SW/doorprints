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
import { TOUR_STEPS } from '../shared/tour-steps';
import { TOUR_KEY } from './storage-keys';
import { TourService } from './tour.service';

function make(): { tour: TourService; router: Router } {
  TestBed.configureTestingModule({ providers: [provideRouter([{ path: '**', children: [] }])] });
  return { tour: TestBed.inject(TourService), router: TestBed.inject(Router) };
}

beforeEach(() => localStorage.clear());
afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

describe('TourService', () => {
  it('offers the tour on the Map to someone who has not seen it, and nowhere else', () => {
    const { tour } = make();
    expect(tour.offered('/')).toBe(true);
    expect(tour.offered('/?x=1')).toBe(true);
    expect(tour.offered('/compare')).toBe(false);
    expect(tour.offered('/houses/new')).toBe(false);
  });

  it('is not offered while it runs, and not again once skipped', () => {
    const { tour } = make();
    tour.start();
    expect(tour.offered('/')).toBe(false);
    tour.skip();
    expect(tour.active()).toBe(false);
    expect(localStorage.getItem(TOUR_KEY)).toBe('skipped');
    expect(tour.offered('/')).toBe(false);
  });

  it('is not offered to someone who finished it before (a new service reads the stored answer)', () => {
    localStorage.setItem(TOUR_KEY, 'done');
    expect(make().tour.offered('/')).toBe(false);
  });

  it('can still be started by hand after it was skipped', () => {
    localStorage.setItem(TOUR_KEY, 'skipped');
    const { tour } = make();
    tour.start();
    expect(tour.step()?.id).toBe('welcome');
  });

  it('walks forward and back, never before the first step, and finishes after the last', () => {
    const { tour } = make();
    tour.start();
    expect(tour.isFirst()).toBe(true);
    tour.back();
    expect(tour.position()).toBe(0);
    for (let i = 1; i < TOUR_STEPS.length; i++) {
      tour.next();
      expect(tour.step()?.id).toBe(TOUR_STEPS[i].id);
    }
    expect(tour.isLast()).toBe(true);
    tour.back();
    expect(tour.position()).toBe(TOUR_STEPS.length - 2);
    tour.next();
    tour.next();
    expect(tour.active()).toBe(false);
    expect(localStorage.getItem(TOUR_KEY)).toBe('done');
  });

  it('moves to the route of each step', async () => {
    const { tour, router } = make();
    await router.navigateByUrl('/');
    tour.start();
    for (let i = 0; i < TOUR_STEPS.findIndex((s) => s.id === 'compare'); i++) tour.next();
    expect(tour.step()?.id).toBe('compare');
    await new Promise((r) => setTimeout(r, 0));
    expect(router.url).toBe('/compare');
  });

  it('ignores next and back when no tour is running', () => {
    const { tour } = make();
    tour.next();
    tour.back();
    expect(tour.active()).toBe(false);
    expect(localStorage.getItem(TOUR_KEY)).toBeNull();
  });

  it('every step is a distinct id and names only strings that exist', async () => {
    const { en } = await import('../i18n/en');
    expect(new Set(TOUR_STEPS.map((s) => s.id)).size).toBe(TOUR_STEPS.length);
    for (const s of TOUR_STEPS) {
      for (const k of [s.title, s.body, s.action]) expect(en[k], k).toBeTruthy();
    }
  });
});
