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
import { Router, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto, VisitDto } from '../../core/models';
import { TranslationService } from '../../i18n/translation.service';
import { DEFAULT_SCORING } from '../../shared/scoring';
import type { Viewing } from '../../shared/viewing';
import { HouseViewingsCard } from './house-viewings-card';
import { SecondViewingPrompt } from './second-viewing-prompt';

const HOUR = 3_600_000;
const NOW = Date.now();
const v = (id: string, over: Partial<Viewing> = {}): Viewing => ({
  id, houseId: 'h1', startsAt: NOW + HOUR, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60, ...over,
});
const visit = (id: string, arrivedAt: number): VisitDto => ({
  id, houseId: 'h1', lat: 13, lon: 80, arrivedAt: new Date(arrivedAt).toISOString(), source: 'MANUAL', deleted: false, syncVersion: 0,
});
const HOUSE: HouseDto = {
  id: 'h1', label: 'Green View 2BHK', lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0,
};

// A Tamil test saves its language in localStorage; a later spec file in the same worker (the compare page) would start
// in Tamil and fail order-dependently, so every test ends with a clean store.
afterEach(() => {
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function renderCard(viewings: Viewing[], visits: VisitDto[] = [], isNew = false, lang: 'en' | 'ta' = 'en') {
  const markViewingDone = vi.fn((id: string) => of({ ...viewings.find((x) => x.id === id)!, status: 'DONE' as const }));
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [HouseViewingsCard],
    providers: [
      provideRouter([]),
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          viewingsOf: () => of(viewings),
          visits: () => of(visits),
          house: () => of({ ...HOUSE, answers: [{ id: 'a1', text: 'Q', status: 'OPEN', sort: 0 }] }),
          scoring: () => of(DEFAULT_SCORING),
          markViewingDone,
        },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(HouseViewingsCard);
  fixture.componentRef.setInput('houseId', 'h1');
  fixture.componentRef.setInput('houseName', 'Green View 2BHK');
  fixture.componentRef.setInput('isNew', isNew);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, markViewingDone, fixture };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

describe('HouseViewingsCard', () => {
  it('says "No viewing planned" and offers Plan a viewing with this house', async () => {
    const { host } = await renderCard([]);
    expect(host.querySelector('#house-viewings-next')?.textContent).toContain('No viewing planned');
    const plan = [...host.querySelectorAll('a')].find((a) => a.textContent?.trim() === 'Plan a viewing')!;
    expect(plan.getAttribute('href')).toBe('/viewings/new?houseId=h1');
    expect(host.querySelector('#house-viewings-heading')?.textContent).toBe('Viewings');
  });

  it('shows the next PLANNED viewing, its date and its kind, not a cancelled or past one', async () => {
    const { host } = await renderCard([
      v('v_00000001', { startsAt: NOW + 5 * HOUR, kind: 'SECOND' }),
      v('v_00000002', { startsAt: NOW + 2 * HOUR, kind: 'FOLLOW_UP' }),
      v('v_00000003', { startsAt: NOW + HOUR, status: 'CANCELLED' }),
      v('v_00000004', { startsAt: NOW - 3 * HOUR }),
    ]);
    const text = host.querySelector('#house-viewings-next')?.textContent ?? '';
    expect(text).toMatch(/^Next: .+, Follow-up$/);
    expect(text).not.toContain('Second viewing');
  });

  it('links to all the viewings of this house', async () => {
    const { host } = await renderCard([]);
    const all = [...host.querySelectorAll('a')].find((a) => a.textContent?.trim() === 'All viewings of this house')!;
    expect(all.getAttribute('href')).toBe('/viewings?houseId=h1');
  });

  it('offers Mark viewing done only when a visit was saved within two hours of a PLANNED viewing', async () => {
    const planned = v('v_00000001', { startsAt: NOW - HOUR });
    const without = await renderCard([planned], [visit('vis-far', NOW - 5 * HOUR)]);
    expect(without.host.textContent).not.toContain('Mark viewing done');
    const withVisit = await renderCard([planned], [visit('vis-1', NOW - 30 * 60_000)]);
    const button = [...withVisit.host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Mark viewing done')!;
    expect(button).toBeDefined();
    expect(withVisit.host.textContent).toContain('A visit was saved at');
    const done = await renderCard([{ ...planned, status: 'DONE' }], [visit('vis-1', NOW - 30 * 60_000)]);
    expect(done.host.textContent).not.toContain('Mark viewing done');
  });

  it('marks the viewing done with the visit, then offers Book a second viewing?', async () => {
    const { host, markViewingDone, fixture } = await renderCard([v('v_00000001', { startsAt: NOW - HOUR })], [visit('vis-1', NOW - 30 * 60_000)]);
    [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Mark viewing done')!.click();
    await flush();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(markViewingDone).toHaveBeenCalledWith('v_00000001', 'vis-1');
    const dialog = host.querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.querySelector('h2')?.textContent).toContain('Book a second viewing?');
    expect(dialog.textContent).toContain('Green View 2BHK');
  });

  it('asks to save the house first when it is new', async () => {
    const { host } = await renderCard([], [], true);
    expect(host.textContent).toContain('Save the house first to plan a viewing.');
    expect(host.querySelector('a')).toBeNull();
  });

  it('shows the card in Tamil', async () => {
    const { host } = await renderCard([], [], false, 'ta');
    expect(host.querySelector('#house-viewings-heading')?.textContent).toContain('வீடு பார்வையிடல்');
    expect(host.querySelector('#house-viewings-next')?.textContent).toContain('பார்வையிடல் திட்டமிடப்படவில்லை');
  });
});

describe('SecondViewingPrompt', () => {
  async function renderPrompt(house: Partial<HouseDto>, open = true) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SecondViewingPrompt],
      providers: [
        provideRouter([]),
        {
          provide: LocalDataService,
          useValue: { house: () => of({ ...HOUSE, ...house }), scoring: () => of(DEFAULT_SCORING) },
        },
      ],
    });
    // The language is kept between tests, so say which one this test is in.
    await TestBed.inject(TranslationService).setLang('en');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(SecondViewingPrompt);
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);
    fixture.componentRef.setInput('houseId', 'h1');
    fixture.componentRef.setInput('houseName', 'Green View 2BHK');
    fixture.componentRef.setInput('open', open);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return { host: fixture.nativeElement as HTMLElement, navigate, closed, fixture };
  }

  it('lists the open questions and the criteria scored 2 or less, by name', async () => {
    const { host } = await renderPrompt({
      answers: [
        { id: 'a1', text: 'Q1', status: 'OPEN', sort: 0 },
        { id: 'a2', text: 'Q2', status: 'OPEN', sort: 1 },
        { id: 'a3', text: 'Q3', status: 'ANSWERED', answer: 'Yes', sort: 2 },
        { id: 'a4', text: 'Q4', status: 'SKIPPED', sort: 3 },
      ],
      checklist: { water: 2, power: 1, parking: 3, noise: 5, sunlight: 0 },
    });
    const items = [...host.querySelectorAll('#second-viewing-recheck li')].map((li) => li.textContent?.trim());
    expect(items).toHaveLength(2);
    expect(items[0]).toBe('Questions still open: 2');
    expect(items[1]).toMatch(/^Scored 2 or less: /);
    expect(items[1]).toContain('Water supply');
    expect(items[1]).toContain('Power backup');
    expect(items[1]).not.toContain('Parking');
    expect(host.textContent).not.toContain('PROBLEM');
  });

  it('says nothing is open when there is nothing to re-check', async () => {
    const { host } = await renderPrompt({ answers: null, checklist: { water: 5 } });
    expect(host.querySelector('#second-viewing-recheck')?.textContent).toContain('Nothing open.');
  });

  it('opens the form with kind SECOND and this house on Book a second viewing', async () => {
    const { host, navigate, closed } = await renderPrompt({});
    [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Book a second viewing')!.click();
    expect(navigate).toHaveBeenCalledWith(['/viewings/new'], { queryParams: { houseId: 'h1', kind: 'SECOND' } });
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('closes on Not now without going anywhere', async () => {
    const { host, navigate, closed } = await renderPrompt({});
    [...host.querySelectorAll('button')].find((b) => b.textContent?.trim() === 'Not now')!.click();
    expect(navigate).not.toHaveBeenCalled();
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('shows nothing while closed, and an unreadable house still lets the person book', async () => {
    const closedOne = await renderPrompt({}, false);
    expect(closedOne.host.querySelector('dialog')?.hasAttribute('open')).toBe(false);
    expect(closedOne.host.querySelector('h2')).toBeNull();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SecondViewingPrompt],
      providers: [provideRouter([]), { provide: LocalDataService, useValue: { house: () => throwError(() => new Error('x')), scoring: () => of(DEFAULT_SCORING) } }],
    });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(SecondViewingPrompt);
    fixture.componentRef.setInput('houseId', 'h1');
    fixture.componentRef.setInput('open', true);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#second-viewing-recheck')?.textContent).toContain('Nothing open.');
    expect(fixture.nativeElement.querySelectorAll('button')).toHaveLength(2);
  });
});
