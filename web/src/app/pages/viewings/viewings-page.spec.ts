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
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { TranslationService } from '../../i18n/translation.service';
import type { Viewing } from '../../shared/viewing';
import { ViewingsPage } from './viewings-page';

const NOW = Date.now();
const HOUR = 3_600_000;
const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({
  id, label: `House ${id}`, lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0, ...over,
});
const H1 = house('h1', { label: 'Green View 2BHK', street: 'MG Road', locality: 'Adyar' });
const H2 = house('h2', { label: 'Blue Gate', street: '5th Cross', locality: 'Indiranagar' });
const v = (id: string, over: Partial<Viewing> = {}): Viewing => ({
  id, houseId: 'h1', startsAt: NOW + HOUR, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60, ...over,
});
const UPCOMING = v('v_00000001', { startsAt: NOW + 5 * HOUR, kind: 'SECOND', withWhom: 'Meena Iyer', notes: 'Bring a tape measure' });
const MISSED = v('v_00000002', { startsAt: NOW - 10 * HOUR, houseId: 'h2' });
const DONE = v('v_00000003', { startsAt: NOW - 30 * HOUR, status: 'DONE', notes: 'n'.repeat(120) });
const CANCELLED = v('v_00000004', { startsAt: NOW + 2 * HOUR, status: 'CANCELLED', houseId: 'h-gone' });

interface Fakes {
  viewings: Viewing[];
  houses: HouseDto[];
  markViewingDone: ReturnType<typeof vi.fn>;
  saveViewing: ReturnType<typeof vi.fn>;
  remind: boolean;
  setViewingsRemind: ReturnType<typeof vi.fn>;
  load?: () => unknown;
}

function fakes(viewings: Viewing[] = [UPCOMING, MISSED, DONE, CANCELLED], houses: HouseDto[] = [H1, H2]): Fakes {
  return {
    viewings,
    houses,
    markViewingDone: vi.fn((id: string) => of({ ...viewings.find((x) => x.id === id)!, status: 'DONE' as const })),
    saveViewing: vi.fn((x: Viewing) => of(x)),
    remind: true,
    setViewingsRemind: vi.fn(() => of(undefined)),
  };
}

// A Tamil test saves its language in localStorage; a later spec file in the same worker (the compare page) would start
// in Tamil and fail order-dependently, so every test ends with a clean store.
afterEach(() => {
  vi.unstubAllGlobals();
  TestBed.resetTestingModule();
  localStorage.clear();
});

async function render(f: Fakes, lang: 'en' | 'ta' = 'en', query: Record<string, string> = {}) {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    imports: [ViewingsPage],
    providers: [
      provideRouter([]),
      { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({}), queryParamMap: convertToParamMap(query) } } },
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          viewings: f.load ?? (() => of(f.viewings)),
          houses: () => of(f.houses),
          house: () => of(house('h2', { answers: null })),
          scoring: () => of(DEFAULT_SCORING),
          markViewingDone: f.markViewingDone,
          saveViewing: f.saveViewing,
          viewingsRemind: () => of(f.remind),
          setViewingsRemind: f.setViewingsRemind,
        },
      },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(ViewingsPage);
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  return { host: fixture.nativeElement as HTMLElement, f, fixture };
}

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));
const type = async (fixture: { detectChanges: () => void; whenStable: () => Promise<unknown> }, el: Element, value: string) => {
  (el as HTMLInputElement | HTMLSelectElement).value = value;
  el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? 'change' : 'input'));
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
};
const rows = (host: HTMLElement) => [...host.querySelectorAll('li.viewing')];
const ids = (host: HTMLElement) => rows(host).map((r) => r.querySelector('a.row-link')?.getAttribute('href'));

describe('ViewingsPage', () => {
  it('groups the timeline into Upcoming, Missed?, Done and Cancelled, in that order', async () => {
    const { host } = await render(fakes());
    expect([...host.querySelectorAll('h2[id^="viewings-group-"]')].map((h) => h.textContent?.trim())).toEqual(['Upcoming', 'Missed?', 'Done', 'Cancelled']);
    expect(ids(host)).toEqual(['/viewings/v_00000001', '/viewings/v_00000002', '/viewings/v_00000003', '/viewings/v_00000004']);
  });

  it('shows on each row the date and time, the house, the kind, the status, with whom and a cut notes preview', async () => {
    const { host } = await render(fakes());
    const [upcoming, , done, cancelled] = rows(host);
    expect(upcoming.querySelector('.house')?.textContent?.trim()).toBe('Green View 2BHK');
    expect(upcoming.textContent).toContain('Second viewing');
    expect(upcoming.textContent).toContain('Planned');
    expect(upcoming.textContent).toContain('With Meena Iyer');
    expect(upcoming.textContent).toContain('Bring a tape measure');
    expect(upcoming.querySelector('.when')?.textContent?.trim()).not.toBe('');
    expect(done.querySelector('.notes')?.textContent?.trim()).toHaveLength(78);
    expect(done.querySelector('.notes')?.textContent?.trim().endsWith('…')).toBe(true);
    // A viewing of a house that is not there says so, and keeps its row.
    expect(cancelled.querySelector('.house')?.textContent?.trim()).toBe('A house that is gone');
  });

  it('opens the form when a row is tapped: the whole row is one link named with the house and the time', async () => {
    const { host } = await render(fakes());
    const link = rows(host)[0].querySelector<HTMLAnchorElement>('a.row-link')!;
    expect(link.getAttribute('href')).toBe('/viewings/v_00000001');
    expect(link.getAttribute('aria-label')).toMatch(/^Open the viewing: Green View 2BHK, /);
  });

  it('shows the empty state, "No viewings yet. Plan one from a house.", and a Plan a viewing button', async () => {
    const { host } = await render(fakes([]));
    expect(host.querySelector('#viewings-empty')?.textContent).toContain('No viewings yet. Plan one from a house.');
    expect(host.querySelector('a.btn-primary')?.getAttribute('href')).toBe('/viewings/new');
  });

  it('shows a skeleton first, an error with Try again when loading fails, and the list when it works again', async () => {
    const f = fakes();
    let fail = true;
    f.load = () => (fail ? throwError(() => new Error('boom')) : of(f.viewings));
    const { host, fixture } = await render(f);
    expect(host.querySelector('.error [role="alert"]')).not.toBeNull();
    fail = false;
    host.querySelector<HTMLButtonElement>('.err-actions button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(host.querySelector('.error')).toBeNull();
    expect(rows(host)).toHaveLength(4);
  });

  it('searches the house label, street and locality, with whom and notes, ignoring case', async () => {
    const { host, fixture } = await render(fakes());
    const search = host.querySelector<HTMLInputElement>('#viewings-search')!;
    const found = async (q: string) => {
      await type(fixture, search, q);
      return ids(host);
    };
    expect(await found('GREEN view')).toEqual(['/viewings/v_00000001', '/viewings/v_00000003']);
    expect(await found('5th cross')).toEqual(['/viewings/v_00000002']);
    expect(await found('adyar')).toEqual(['/viewings/v_00000001', '/viewings/v_00000003']);
    expect(await found('meena')).toEqual(['/viewings/v_00000001']);
    expect(await found('TAPE MEASURE')).toEqual(['/viewings/v_00000001']);
    expect(await found('nothing like this')).toEqual([]);
    expect(host.querySelector('#viewings-empty')?.textContent).toContain('No viewings match.');
    expect(await found('')).toHaveLength(4);
  });

  it('filters by kind, by status and by date range, and clears the filters', async () => {
    const day = (ms: number) => {
      const d = new Date(ms);
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    };
    const DAY = 24 * HOUR;
    const old = v('v_00000010', { startsAt: NOW - 40 * DAY, status: 'DONE' });
    const recent = v('v_00000011', { startsAt: NOW - 5 * DAY, status: 'DONE' });
    const { host, fixture } = await render(fakes([UPCOMING, old, recent]));
    await type(fixture, host.querySelector('#viewings-kind')!, 'SECOND');
    expect(ids(host)).toEqual(['/viewings/v_00000001']);
    await type(fixture, host.querySelector('#viewings-kind')!, '');
    await type(fixture, host.querySelector('#viewings-status')!, 'DONE');
    expect(ids(host)).toEqual(['/viewings/v_00000011', '/viewings/v_00000010']);
    await type(fixture, host.querySelector('#viewings-status')!, '');
    await type(fixture, host.querySelector('#viewings-from')!, day(NOW - 45 * DAY));
    await type(fixture, host.querySelector('#viewings-to')!, day(NOW - 20 * DAY));
    expect(ids(host)).toEqual(['/viewings/v_00000010']);
    // The last day counts whole: a viewing later that day is still in.
    await type(fixture, host.querySelector('#viewings-from')!, day(NOW - 5 * DAY));
    await type(fixture, host.querySelector('#viewings-to')!, day(NOW - 5 * DAY));
    expect(ids(host)).toEqual(['/viewings/v_00000011']);
    await type(fixture, host.querySelector('#viewings-from')!, day(NOW + 400 * DAY));
    await type(fixture, host.querySelector('#viewings-to')!, '');
    expect(ids(host)).toEqual([]);
    host.querySelector<HTMLButtonElement>('.filters button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(ids(host)).toHaveLength(3);
    expect(host.querySelector('.filters button')).toBeNull();
  });

  it('labels every filter control', async () => {
    const { host } = await render(fakes());
    for (const id of ['viewings-search', 'viewings-from', 'viewings-to', 'viewings-kind', 'viewings-status']) {
      expect(host.querySelector(`label[for="${id}"]`)?.textContent?.trim(), id).not.toBe('');
    }
  });

  it('gives a Missed? row the buttons It happened and Cancel, and no other row', async () => {
    const { host } = await render(fakes());
    const [upcoming, missed] = rows(host);
    expect(upcoming.querySelector('button')).toBeNull();
    const names = [...missed.querySelectorAll('button')].map((b) => b.textContent?.trim());
    expect(names).toEqual(['It happened', 'Cancel']);
    expect([...missed.querySelectorAll('button')].map((b) => b.getAttribute('aria-label'))).toEqual([
      expect.stringMatching(/^It happened: Blue Gate, /),
      expect.stringMatching(/^Cancel the viewing: Blue Gate, /),
    ]);
  });

  it('marks a missed viewing done with It happened and offers Book a second viewing?', async () => {
    const { host, f, fixture } = await render(fakes());
    host.querySelector<HTMLButtonElement>('li.viewing[data-status="PLANNED"] .actions button')!.click();
    await flush();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(f.markViewingDone).toHaveBeenCalledWith('v_00000002');
    const dialog = host.querySelector('dialog')!;
    expect(dialog.hasAttribute('open')).toBe(true);
    expect(dialog.querySelector('h2')?.textContent).toContain('Book a second viewing?');
    expect([...dialog.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual(['Not now', 'Book a second viewing']);
  });

  it('gives no second-viewing prompt for a house that is gone', async () => {
    const gone = v('v_00000005', { startsAt: NOW - 10 * HOUR, houseId: 'h-gone' });
    const { host, f, fixture } = await render(fakes([gone]));
    host.querySelector<HTMLButtonElement>('.actions button')!.click();
    await flush();
    fixture.detectChanges();
    expect(f.markViewingDone).toHaveBeenCalledWith('v_00000005');
    expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(false);
  });

  it('cancels a missed viewing with Cancel: the same viewing with the status CANCELLED', async () => {
    const { host, f } = await render(fakes());
    host.querySelectorAll<HTMLButtonElement>('li.viewing[data-status="PLANNED"] .actions button')[1].click();
    await flush();
    expect(f.saveViewing).toHaveBeenCalledWith({ ...MISSED, status: 'CANCELLED' });
    expect(f.markViewingDone).not.toHaveBeenCalled();
  });

  it('shows a refused change next to the list', async () => {
    const f = fakes();
    f.markViewingDone = vi.fn(() => throwError(() => new Error('nope')));
    const { host } = await render(f);
    host.querySelector<HTMLButtonElement>('.actions button')!.click();
    await flush();
    expect(host.querySelector('.error [role="alert"]')).not.toBeNull();
  });

  it('shows only the viewings of one house when opened from that house, and says so', async () => {
    const { host } = await render(fakes(), 'en', { houseId: 'h1' });
    expect(ids(host)).toEqual(['/viewings/v_00000001', '/viewings/v_00000003']);
    expect(host.querySelector('#viewings-house-filter')?.textContent).toContain('Viewings of Green View 2BHK');
    expect(host.querySelector('a.btn-primary')?.getAttribute('href')).toBe('/viewings/new?houseId=h1');
  });

  it('shows the screen in Tamil when the app is in Tamil', async () => {
    const { host } = await render(fakes(), 'ta');
    expect(host.querySelector('h1')?.textContent).toContain('வீடு பார்வையிடல்');
    expect(host.querySelector('#viewings-group-upcoming')?.textContent).toContain('வரவிருப்பவை');
    expect(rows(host)).toHaveLength(4);
  });

  describe('the reminder switch', () => {
    function stubNotification(permission: string, after = permission) {
      const requestPermission = vi.fn(async () => {
        fake.permission = after;
        return after;
      });
      const fake = { permission, requestPermission };
      vi.stubGlobal('Notification', fake);
      return requestPermission;
    }
    const sw = (host: HTMLElement) => host.querySelector<HTMLInputElement>('#viewings-remind')!;
    const tap = async (fixture: { detectChanges: () => void; whenStable: () => Promise<unknown> }, el: HTMLInputElement) => {
      el.click();
      await flush();
      fixture.detectChanges();
      await fixture.whenStable();
      fixture.detectChanges();
    };

    it('is on by default, says the website only reminds while it is open, and never asks the browser at load', async () => {
      const ask = stubNotification('default');
      const { host } = await render(fakes());
      expect(sw(host).checked).toBe(true);
      expect(host.querySelector('#viewings-remind-note')?.textContent).toContain('only work while it is open. Add to calendar');
      expect(ask).not.toHaveBeenCalled();
    });

    it('reflects the stored setting', async () => {
      stubNotification('default');
      const f = fakes();
      f.remind = false;
      const { host } = await render(f);
      expect(sw(host).checked).toBe(false);
    });

    it('asks the browser only on the tap that turns it on, and saves the setting', async () => {
      const ask = stubNotification('default', 'granted');
      const f = fakes();
      f.remind = false;
      const { host, fixture } = await render(f);
      await tap(fixture, sw(host));
      expect(ask).toHaveBeenCalledTimes(1);
      expect(f.setViewingsRemind).toHaveBeenCalledWith(true);
      expect(host.querySelector('#viewings-remind-denied')).toBeNull();
    });

    it('turning it off does not ask the browser', async () => {
      const ask = stubNotification('default');
      const f = fakes();
      const { host, fixture } = await render(f);
      await tap(fixture, sw(host));
      expect(ask).not.toHaveBeenCalled();
      expect(f.setViewingsRemind).toHaveBeenCalledWith(false);
    });

    it('says once that the browser blocks notifications when it is denied, and still turns on', async () => {
      const ask = stubNotification('default', 'denied');
      const f = fakes();
      f.remind = false;
      const { host, fixture } = await render(f);
      await tap(fixture, sw(host));
      expect(f.setViewingsRemind).toHaveBeenCalledWith(true);
      expect(host.querySelectorAll('#viewings-remind-denied')).toHaveLength(1);
      expect(host.querySelector('#viewings-remind-denied')?.textContent).toContain('you will see a banner instead');
      expect(ask).toHaveBeenCalledTimes(1);
    });

    it('works in a browser without notifications', async () => {
      vi.stubGlobal('Notification', undefined);
      const f = fakes();
      f.remind = false;
      const { host, fixture } = await render(f);
      await tap(fixture, sw(host));
      expect(f.setViewingsRemind).toHaveBeenCalledWith(true);
    });

    it('has its words in Tamil', async () => {
      stubNotification('default');
      const { host } = await render(fakes(), 'ta');
      expect(host.querySelector('.remind-label')?.textContent).toContain('Doorprints திறந்திருக்கும்போது');
    });
  });
});
