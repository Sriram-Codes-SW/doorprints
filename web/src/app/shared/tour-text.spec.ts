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

// Kinds PR 31 (docs/03 section 18.25): the tour in the space of another kind. The house tour is word for word what it was; another
// kind gets its own words where a step names the Add button, the search, the details, Compare or the viewings, and one generic
// sentence (with the kind's plural) where the house's text says "house" (owner decision of 2026-10-15: generic, not a text per kind).
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { afterEach, describe, expect, it } from 'vitest';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../core/kind-setting';
import { TourService } from '../core/tour.service';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { en } from '../i18n/en';
import type { Lang } from '../i18n/languages';
import { TranslationService } from '../i18n/translation.service';
import { TOUR_STEPS, tourText } from './tour-steps';

afterEach(() => {
  localStorage.clear();
  TestBed.resetTestingModule();
});

const step = (id: string) => TOUR_STEPS.find((s) => s.id === id)!;

describe('the tour text of a step in a space (kinds PR 31)', () => {
  it('the house tour is the steps’ own keys, every step', () => {
    for (const s of TOUR_STEPS) {
      expect(tourText(s, 'house'), s.id).toMatchObject({ title: s.title, body: s.body, action: s.action });
    }
  });

  it('the school names its own Add button, search, details, Compare and viewings, and the generic sentence elsewhere', () => {
    expect(tourText(step('add'), 'school')).toMatchObject({ title: 'school.add', body: 'school.addHint', action: 'school.addHintShort' });
    expect(tourText(step('find'), 'school')).toMatchObject({ title: 'school.search', body: 'tour.kind.body', action: 'tour.kind.action' });
    expect(tourText(step('details'), 'school')).toMatchObject({ title: 'school.titleDetails', body: 'tour.kind.body' });
    expect(tourText(step('compare'), 'school')).toMatchObject({ title: 'school.compareTitle', body: 'tour.kind.body' });
    expect(tourText(step('viewings'), 'school')).toMatchObject({ title: 'school.viewings.heading' });
    expect(tourText(step('welcome'), 'school')).toMatchObject({ title: 'tour.welcome.title', body: 'tour.kind.welcome', many: 'school.many' });
    expect(tourText(step('done'), 'school')).toMatchObject({ title: 'tour.done.title', body: 'tour.kind.doneBody', action: 'tour.kind.doneAction' });
    // A step that says nothing about the kind is the same everywhere.
    for (const id of ['language', 'offline', 'save', 'drive', 'install', 'help']) {
      expect(tourText(step(id), 'school'), id).toMatchObject({ title: step(id).title, body: step(id).body, action: step(id).action });
    }
  });

  it('every generic sentence has text in all four languages with its {many}', () => {
    for (const lang of ['en', 'hi', 'ta', 'te'] as Lang[]) {
      const dict = DICTIONARIES[lang] as Record<string, string>;
      for (const k of ['tour.kind.welcome', 'tour.kind.body', 'tour.kind.action', 'tour.kind.doneBody']) {
        expect(dict[k], `${lang} ${k}`).toContain('{many}');
      }
    }
  });

  it('Compare is on the address the space has for it', async () => {
    localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
    localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
    localStorage.setItem(DEFAULT_KIND_KEY, 'school');
    TestBed.configureTestingModule({ providers: [provideRouter([{ path: '**', children: [] }])] });
    await TestBed.inject(TranslationService).setLang('en');
    const tour = TestBed.inject(TourService);
    expect(tour.routeOf(step('compare'))).toBe('/k/school/compare');
    expect(tour.routeOf(step('viewings'))).toBe('/viewings');
    await TestBed.inject(Router).navigateByUrl('/k/school');
    tour.start();
    while (tour.step()?.id !== 'add') tour.next();
    expect(tour.text()?.title).toBe('school.add');
    expect(en['school.add']).toBeTruthy();
  });
});
