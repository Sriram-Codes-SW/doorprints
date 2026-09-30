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
import { of } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { TranslationService } from '../i18n/translation.service';
import type { Viewing } from '../shared/viewing';
import { LocalDataService } from './local-data.service';
import { REMINDER_TICK_MS, ViewingReminderService } from './viewing-reminder.service';

const MIN = 60_000;
const START = new Date(2026, 9, 1, 10, 0).getTime();
const v = (id: string, over: Partial<Viewing> = {}): Viewing => ({
  id, houseId: 'h-secret', startsAt: START, durationMin: 30, kind: 'FIRST', status: 'PLANNED', remindMin: 60, ...over,
});
const FIRE = START - 60 * MIN;

let viewings: Viewing[];
let remind: boolean;
let showNotification: ReturnType<typeof vi.fn>;
let registration: { showNotification: typeof showNotification } | undefined;

function stubNotification(permission: string): void {
  vi.stubGlobal('Notification', { permission, requestPermission: vi.fn() });
}

function make(): ViewingReminderService {
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [{ provide: LocalDataService, useValue: { viewings: () => of(viewings), viewingsRemind: () => of(remind) } }],
  });
  return TestBed.inject(ViewingReminderService);
}

beforeEach(() => {
  viewings = [v('v_00000001', { withWhom: 'Meena Iyer', notes: 'Flat 4B, 12 MG Road' })];
  remind = true;
  showNotification = vi.fn(async () => undefined);
  registration = { showNotification };
  vi.stubGlobal('navigator', { serviceWorker: { getRegistration: async () => registration } });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
  TestBed.resetTestingModule();
  localStorage.clear();
});

describe('ViewingReminderService', () => {
  it('shows a browser notification through the service worker when permission is granted, once per viewing', async () => {
    stubNotification('granted');
    const s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE + 1);
    expect(showNotification).toHaveBeenCalledTimes(1);
    expect(s.banners()).toEqual([]);
    const [title, options] = showNotification.mock.calls[0];
    expect(title).toBe('Doorprints');
    expect(options.data).toEqual({ url: 'viewings' });
    // The same viewing is not shown again, even when a later tick's span would include it.
    await s.tick(FIRE + 2);
    (s as unknown as { lastTick: number }).lastTick = FIRE - MIN;
    await s.tick(FIRE + 3);
    expect(showNotification).toHaveBeenCalledTimes(1);
  });

  it('shows an in-page banner when permission is not granted', async () => {
    for (const permission of ['default', 'denied']) {
      stubNotification(permission);
      const s = make();
      s.start(FIRE - MIN);
      await s.tick(FIRE);
      expect(s.banners().map((b) => b.id)).toEqual(['v_00000001']);
    }
    expect(showNotification).not.toHaveBeenCalled();
  });

  it('shows a banner when there is no Notification API or no worker registration', async () => {
    vi.stubGlobal('Notification', undefined);
    let s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE);
    expect(s.banners()).toHaveLength(1);
    stubNotification('granted');
    registration = undefined;
    s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE);
    expect(s.banners()).toHaveLength(1);
  });

  it('says only the time: no house, address, notes or who the viewing is with', async () => {
    stubNotification('granted');
    const s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE);
    const body: string = showNotification.mock.calls[0][1].body;
    const time = new Intl.DateTimeFormat(TestBed.inject(TranslationService).locale(), { timeStyle: 'short' }).format(new Date(START));
    expect(body).toBe('A viewing at ' + time + '. Open Doorprints for the place.');
    for (const secret of ['Meena', 'Iyer', 'MG Road', '4B', 'h-secret']) expect(body).not.toContain(secret);
    stubNotification('default');
    const t = make();
    t.start(FIRE - MIN);
    await t.tick(FIRE);
    expect(t.bannerText(t.banners()[0])).toBe(body);
  });

  it('shows nothing for a past, DONE, CANCELLED or no-reminder viewing', async () => {
    stubNotification('granted');
    viewings = [
      v('v_00000001', { status: 'DONE' }),
      v('v_00000002', { status: 'CANCELLED' }),
      v('v_00000003', { remindMin: 0 }),
      v('v_00000004', { startsAt: START - 5 * 60 * MIN }),
    ];
    const s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE + MIN);
    expect(showNotification).not.toHaveBeenCalled();
    expect(s.banners()).toEqual([]);
  });

  it('does not fire a reminder that came due before the app started', async () => {
    stubNotification('granted');
    const s = make();
    s.start(FIRE + MIN);
    await s.tick(FIRE + 2 * MIN);
    expect(showNotification).not.toHaveBeenCalled();
  });

  it('shows nothing while the setting is off', async () => {
    stubNotification('granted');
    remind = false;
    const s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE);
    expect(showNotification).not.toHaveBeenCalled();
    expect(s.banners()).toEqual([]);
  });

  it('dismiss removes a banner', async () => {
    stubNotification('default');
    const s = make();
    s.start(FIRE - MIN);
    await s.tick(FIRE);
    s.dismiss('v_00000001');
    expect(s.banners()).toEqual([]);
  });

  it('looks every 60 seconds on a timer that stop clears, and start twice makes one timer', async () => {
    vi.useFakeTimers();
    stubNotification('granted');
    const s = make();
    const spy = vi.spyOn(s, 'tick');
    s.start(FIRE - MIN);
    s.start(FIRE - MIN);
    expect(vi.getTimerCount()).toBe(1);
    await vi.advanceTimersByTimeAsync(REMINDER_TICK_MS * 2);
    expect(spy).toHaveBeenCalledTimes(2);
    s.stop();
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(REMINDER_TICK_MS * 2);
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it('clears the timer when the injector is destroyed', () => {
    vi.useFakeTimers();
    stubNotification('granted');
    const s = make();
    s.start(0);
    expect(vi.getTimerCount()).toBe(1);
    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
    expect(s).toBeTruthy();
  });

  it('has the reminder words in all four languages with the time placeholder', () => {
    for (const lang of ['en', 'hi', 'ta', 'te'] as const) {
      const d = DICTIONARIES[lang] as Readonly<Record<string, string>>;
      for (const key of ['viewings.remind.label', 'viewings.remind.note', 'viewings.remind.denied', 'viewings.remind.text', 'viewings.remind.region', 'viewings.remind.open', 'viewings.remind.dismiss']) {
        expect(d[key], lang + ' ' + key).toBeTruthy();
      }
      expect(d['viewings.remind.text']).toContain('{time}');
    }
  });
});
