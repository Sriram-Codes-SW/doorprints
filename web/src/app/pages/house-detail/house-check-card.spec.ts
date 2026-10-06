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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Announcer } from '../../core/announcer.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { GEOLOCATION, TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import type { MapOverlay } from '../../shared/location-map';
import { audit } from '../../shared/testing/a11y';
import { FakeRecorder, settle } from '../../shared/testing/trace-fakes';
import { PlaceCheckState } from '../map/place-check-state';
import { HouseCheckCard } from './house-check-card';

const DEG = 1 / 111_194.9266;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
const WALK = NOW - 2 * 86_400_000;
const house = (over: Partial<HouseDto> = {}): HouseDto => ({ ...newHouse(100 * DEG, 4.5 * DEG, 'MAP'), id: 'h1', label: 'Blue gate', ...over });

describe('HouseCheckCard (Did I walk past this house?)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true });
    vi.setSystemTime(NOW);
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  async function render(opts: { lang?: Lang; house?: HouseDto; isNew?: boolean; locationSet?: boolean; walk?: boolean } = {}) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: TraceRecorderService, useValue: new FakeRecorder() },
        { provide: GEOLOCATION, useValue: null },
      ],
    });
    await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
    const store = TestBed.inject(TraceStore);
    if (opts.walk !== false) for (let i = 0; i < 11; i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: WALK + i * 30_000, walkId: WALK }, 10);
    const fixture = TestBed.createComponent(HouseCheckCard);
    fixture.componentRef.setInput('house', opts.house ?? house());
    fixture.componentRef.setInput('isNew', opts.isNew ?? false);
    fixture.componentRef.setInput('locationSet', opts.locationSet ?? true);
    const overlays: (MapOverlay | null)[] = [];
    fixture.componentInstance.overlay.subscribe((o) => overlays.push(o));
    document.body.appendChild(fixture.nativeElement);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const $ = <T extends HTMLElement>(s: string) => host.querySelector<T>(s);
    const click = async (sel: string) => {
      $<HTMLButtonElement>(sel)!.click();
      await settle(fixture);
    };
    return { fixture, host, $, click, overlays, state: TestBed.inject(PlaceCheckState) };
  }

  it('is a card with the title and one button, until it is pressed', async () => {
    const r = await render();
    expect(r.$('section.card')!.getAttribute('aria-labelledby')).toBe('house-check-heading');
    expect(r.$('#house-check-heading')!.textContent).toBe('Did I walk past this house?');
    expect(r.$('#house-check-open')!.textContent!.trim()).toBe('Did I walk past this house?');
    expect(r.$('#check-title')).toBeNull();
  });

  it('is hidden for a house not saved yet', async () => {
    const r = await render({ isNew: true });
    expect(r.$('section')).toBeNull();
  });

  it('is hidden for a house with no location', async () => {
    const r = await render({ locationSet: false });
    expect(r.$('section')).toBeNull();
  });

  it('compares nothing until the button is pressed', async () => {
    const r = await render();
    const read = vi.spyOn(TestBed.inject(TraceStore), 'placeWalks');
    await settle(r.fixture);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(read).not.toHaveBeenCalled();
  });

  it('answers in place under the title, with the headline, and draws the halo, the ring and the box on the page\'s map', async () => {
    const r = await render();
    await r.click('#house-check-open');
    expect(r.$('#check-title')!.textContent).toBe('Did I walk past this house?');
    expect(r.$('#check-headline')!.textContent).toMatch(/^You walked within 5 m of this house on /);
    expect(r.$('section.card')!.getAttribute('aria-labelledby')).toBe('check-title');
    expect(r.overlays).toHaveLength(1);
    const o = r.overlays[0]!;
    expect(o.check!.features).toHaveLength(1);
    expect(o.ring).toEqual({ lat: 100 * DEG, lon: 4.5 * DEG, label: 'This house' });
    expect(o.fit![0][1]).toBeLessThanOrEqual(100 * DEG);
    expect(o.walks.features).toEqual([]);
  });

  it('moves focus to the answer\'s title and says the headline once through the app live region', async () => {
    const r = await render();
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    await r.click('#house-check-open');
    expect(document.activeElement).toBe(r.$('#check-title'));
    expect(announce).toHaveBeenCalledTimes(1);
    expect(announce.mock.calls[0][0].key).toBe('trace.here.announce');
    expect((announce.mock.calls[0][0].params as { text: string }).text).toBe(r.$('#check-headline')!.textContent);
  });

  it('says CLOSE and NONE in words, with nothing to draw', async () => {
    const close = await render({ house: house({ lon: 40 * DEG }) });
    await close.click('#house-check-open');
    expect(close.$('#check-headline')!.textContent).toContain('but one came within');
    expect(close.overlays[0]!.check!.features).toEqual([]);
    const none = await render({ house: house({ lon: 400 * DEG }) });
    await none.click('#house-check-open');
    expect(none.$('#check-headline')!.textContent).toContain('No walk of yours passed within 25 m of this house in the last 30 days.');
  });

  it('says there are no walks yet when none is stored', async () => {
    const r = await render({ walk: false });
    await r.click('#house-check-open');
    expect(r.$('#check-headline')!.textContent).toContain('There are no walks to compare yet.');
  });

  it('a house whose spot is only an area says so, compares nothing and draws nothing', async () => {
    const r = await render({ house: house({ locationSource: 'APPROX' }) });
    const compute = vi.spyOn(TestBed.inject(PlaceCheckState), 'compute');
    const read = vi.spyOn(TestBed.inject(TraceStore), 'placeWalks');
    await r.click('#house-check-open');
    expect(r.$('#check-headline')!.textContent).toBe('This house has no exact spot yet, only an area. Place it on the map first, then check.');
    expect(compute).not.toHaveBeenCalled();
    expect(read).not.toHaveBeenCalled();
    expect(r.overlays).toEqual([null]);
    expect(r.$('#check-rows')).toBeNull();
  });

  it('Show on map hands the overlay to the page again, framed', async () => {
    const r = await render();
    await r.click('#house-check-open');
    await r.click('#check-show');
    expect(r.overlays).toHaveLength(2);
    expect(r.overlays[1]!.fit).not.toBeNull();
  });

  it('Close withdraws the answer, the map overlay and the announcement, and returns focus to the button', async () => {
    const r = await render();
    const cancel = vi.spyOn(TestBed.inject(Announcer), 'cancel');
    await r.click('#house-check-open');
    await r.click('#check-close');
    expect(r.$('#check-title')).toBeNull();
    expect(r.overlays[r.overlays.length - 1]).toBeNull();
    expect(cancel).toHaveBeenCalledWith({ key: 'trace.here.announce' });
    expect(document.activeElement).toBe(r.$('#house-check-open'));
  });

  it('withdraws its announcement when the page is left with an answer on show, and says nothing when there is none', async () => {
    const r = await render();
    const cancel = vi.spyOn(TestBed.inject(Announcer), 'cancel');
    r.fixture.destroy();
    expect(cancel).not.toHaveBeenCalled();
    const s = await render();
    const cancel2 = vi.spyOn(TestBed.inject(Announcer), 'cancel');
    await s.click('#house-check-open');
    s.fixture.destroy();
    expect(cancel2).toHaveBeenCalledWith({ key: 'trace.here.announce' });
  });

  it('forgets the answer when the house moves: it was about another spot', async () => {
    const r = await render();
    await r.click('#house-check-open');
    r.fixture.componentRef.setInput('house', house({ lat: 300 * DEG }));
    await settle(r.fixture);
    expect(r.$('#check-title')).toBeNull();
    expect(r.overlays[r.overlays.length - 1]).toBeNull();
  });

  it('keeps an answer when only the house\'s name changes', async () => {
    const r = await render();
    await r.click('#house-check-open');
    r.fixture.componentRef.setInput('house', house({ label: 'Renamed' }));
    await settle(r.fixture);
    expect(r.$('#check-title')).not.toBeNull();
  });

  it('re-draws the ring with the new language\'s label when the language changes', async () => {
    const r = await render();
    await r.click('#house-check-open');
    await TestBed.inject(TranslationService).setLang('hi');
    await settle(r.fixture);
    expect(r.overlays[r.overlays.length - 1]!.ring!.label).toBe('यह मकान');
  });

  it('keeps the answer to itself: the Map\'s shared answer is untouched, and nothing goes to a URL or a storage', async () => {
    const r = await render();
    const set = vi.spyOn(Storage.prototype, 'setItem');
    const url = location.href;
    await r.click('#house-check-open');
    expect(r.state.answer()).toBeNull();
    expect(set).not.toHaveBeenCalled();
    expect(location.href).toBe(url);
    expect(history.state ?? {}).not.toHaveProperty('placeCheck');
  });

  it('is not run twice by a double press while it is working', async () => {
    const r = await render();
    const compute = vi.spyOn(TestBed.inject(PlaceCheckState), 'compute');
    const b = r.$<HTMLButtonElement>('#house-check-open')!;
    b.click();
    b.click();
    await settle(r.fixture);
    expect(compute).toHaveBeenCalledTimes(1);
  });

  describe.each(['en', 'hi', 'ta', 'te'] as const)('in %s', (lang) => {
    it('shows no raw key before and after the answer and passes the accessibility rules', async () => {
      const r = await render({ lang });
      expect(r.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(r.host)).toEqual([]);
      await r.click('#house-check-open');
      expect(r.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(r.host.textContent).not.toContain('{');
      expect(audit(r.host)).toEqual([]);
    });
  });
});
