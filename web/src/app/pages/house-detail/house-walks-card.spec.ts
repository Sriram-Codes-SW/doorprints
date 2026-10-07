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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { GeocodeService } from '../../core/geocode.service';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { TraceStore } from '../../data/trace-store';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import type { MapOverlay } from '../../shared/location-map';
import { audit } from '../../shared/testing/a11y';
import { settle } from '../../shared/testing/trace-fakes';
import { DEFAULT_SCORING } from '../../shared/scoring';
import { HouseWalksCard, walkBounds } from './house-walks-card';
import { HouseDetailPage } from './house-detail-page';

const DEG = 1 / 111_194.9266;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
const HOUSE = 'house-1';

describe('walkBounds', () => {
  it('is the box of the points, west south east north', () => {
    expect(walkBounds({ key: 's:a', points: [{ lat: 2, lon: 5, atMs: 1 }, { lat: 1, lon: 7, atMs: 2 }, { lat: 3, lon: 6, atMs: 3 }] })).toEqual([[5, 1], [7, 3]]);
  });
});

describe('HouseWalksCard', () => {
  let store: TraceStore;

  /** A walk of `n` points 20 m apart, one minute apart, saved to `house`. */
  async function saveWalk(startedAt: number, n = 6, house = HOUSE, stepMs = 60_000): Promise<string> {
    for (let i = 0; i < n; i++) await store.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: startedAt + i * stepMs, walkId: startedAt }, 10);
    const r = await store.saveWalk(startedAt, house, NOW);
    if (!r.ok) throw new Error('not saved');
    return r.id;
  }

  async function render(opts: { lang?: Lang; isNew?: boolean; house?: string } = {}) {
    TestBed.resetTestingModule();
    await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
    store = TestBed.inject(TraceStore);
    return {
      mount: async () => {
        const fixture = TestBed.createComponent(HouseWalksCard);
        fixture.componentRef.setInput('houseId', opts.house ?? HOUSE);
        fixture.componentRef.setInput('isNew', opts.isNew ?? false);
        const overlays: (MapOverlay | null)[] = [];
        fixture.componentInstance.overlay.subscribe((o) => overlays.push(o));
        await settle(fixture);
        const host = fixture.nativeElement as HTMLElement;
        return { fixture, host, overlays, $: <T extends HTMLElement>(s: string) => host.querySelector<T>(s), rows: () => [...host.querySelectorAll('li')] };
      },
    };
  }

  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true }));
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  it('is named by its heading and says there are no saved walks, with the stays-in-this-browser sentence', async () => {
    const { mount } = await render();
    const r = await mount();
    expect(r.$('section')!.getAttribute('aria-labelledby')).toBe('walks-heading');
    expect(r.$('#walks-heading')!.textContent).toBe('Saved walks');
    expect(r.$('#walks-empty')!.textContent).toContain('When you finish a walk, you can link it to this house.');
    expect(r.host.textContent).toContain('Saved walks stay in this browser only.');
    expect(r.rows()).toEqual([]);
  });

  it('lists the house\'s saved walks newest first as date, distance and minutes, and no other house\'s', async () => {
    const { mount } = await render();
    await saveWalk(Date.UTC(2026, 9, 1, 9, 0), 6);
    await saveWalk(Date.UTC(2026, 9, 5, 9, 0), 11);
    await saveWalk(Date.UTC(2026, 9, 3, 9, 0), 6, 'other-house');
    const r = await mount();
    const texts = r.rows().map((x) => x.querySelector('.row-text')!.textContent!.replace(/\s+/g, ' ').trim());
    expect(texts).toHaveLength(2);
    expect(texts[0]).toMatch(/200 m, 10 min$/);
    expect(texts[1]).toMatch(/100 m, 5 min$/);
    expect(texts[0]).toContain('5 Oct');
    expect(texts[1]).toContain('1 Oct');
  });

  it('shows at least 1 minute for a walk that lasted seconds (S4b-FR-35: max(1, rounded), as on the phones)', async () => {
    const { mount } = await render();
    await saveWalk(Date.UTC(2026, 9, 1, 9, 0), 6, HOUSE, 1_000);
    const r = await mount();
    expect(r.rows()[0].querySelector('.row-text')!.textContent!.replace(/\s+/g, ' ').trim()).toMatch(/100 m, 1 min$/);
  });

  it('each row has Show on map and Delete walk, described by the row so a screen reader knows which walk', async () => {
    const { mount } = await render();
    await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
    const r = await mount();
    const [show, del] = [...r.host.querySelectorAll<HTMLButtonElement>('li button')];
    expect(show.textContent!.trim()).toBe('Show on map');
    expect(del.textContent!.trim()).toBe('Delete walk');
    const id = show.getAttribute('aria-describedby')!;
    expect(document.getElementById(id)!.textContent).toContain('100 m');
    expect(del.getAttribute('aria-describedby')).toBe(id);
  });

  it('is not shown for a house that is not saved yet, and reads nothing', async () => {
    const { mount } = await render({ isNew: true });
    const read = vi.spyOn(TestBed.inject(TraceStore), 'savedWalksOf');
    const r = await mount();
    expect(r.$('section')).toBeNull();
    expect(read).not.toHaveBeenCalled();
  });

  it('says so when the walks cannot be read', async () => {
    const { mount } = await render();
    vi.spyOn(TestBed.inject(TraceStore), 'savedWalksOf').mockRejectedValue(new Error('db'));
    const r = await mount();
    expect(r.$('#walks-error')!.textContent).toContain('could not be read');
    expect(r.$('#walks-error')!.closest('[role="alert"]') ?? r.$('#walks-error')!.getAttribute('role')).toBeTruthy();
  });

  describe('Show on map', () => {
    it('hands the walk to the page\'s own map: the line, the halo and the box to frame; the page is not left', async () => {
      const { mount } = await render();
      await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
      const r = await mount();
      r.host.querySelector<HTMLButtonElement>('li button')!.click();
      await settle(r.fixture);
      expect(r.overlays).toHaveLength(1);
      const o = r.overlays[0]!;
      expect(o.walks.features.map((f) => f.properties['kind'])).toEqual(['base']);
      expect(o.check!.features).toHaveLength(1);
      expect(o.fit![0][1]).toBeCloseTo(0, 6);
      expect(o.fit![1][1]).toBeCloseTo(100 * DEG, 6);
    });

    it('keeps the halo for 3 seconds, then keeps the line without it and does not frame again', async () => {
      const { mount } = await render();
      await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
      const r = await mount();
      r.host.querySelector<HTMLButtonElement>('li button')!.click();
      await settle(r.fixture);
      await vi.advanceTimersByTimeAsync(2_900);
      expect(r.overlays).toHaveLength(1);
      await vi.advanceTimersByTimeAsync(200);
      expect(r.overlays).toHaveLength(2);
      expect(r.overlays[1]!.check).toBeNull();
      expect(r.overlays[1]!.fit).toBeNull();
      expect(r.overlays[1]!.walks.features).toHaveLength(1);
    });

    it('showing another walk before the halo ends replaces it (the first halo does not clear the second)', async () => {
      const { mount } = await render();
      await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
      await saveWalk(Date.UTC(2026, 9, 2, 9, 0));
      const r = await mount();
      const [first, second] = [...r.host.querySelectorAll<HTMLButtonElement>('li button:first-child')];
      second.click();
      await settle(r.fixture);
      await vi.advanceTimersByTimeAsync(2000);
      first.click();
      await settle(r.fixture);
      await vi.advanceTimersByTimeAsync(2000);
      expect(r.overlays.filter((o) => o?.check === null)).toHaveLength(0);
      await vi.advanceTimersByTimeAsync(1500);
      expect(r.overlays.filter((o) => o?.check === null)).toHaveLength(1);
    });
  });

  describe('Delete walk', () => {
    it('asks first; Cancel changes nothing', async () => {
      const { mount } = await render();
      await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
      const r = await mount();
      const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(false);
      r.host.querySelector<HTMLButtonElement>('li button.btn-danger')!.click();
      await settle(r.fixture);
      expect(ask.mock.calls[0][0]).toEqual({ key: 'trace.house.deleteConfirm' });
      expect(r.rows()).toHaveLength(1);
      expect(await store.savedCount(HOUSE)).toBe(1);
    });

    it('deletes the walk on a yes, says so, clears the map and moves focus to the heading', async () => {
      const { mount } = await render();
      await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
      const r = await mount();
      r.host.querySelector<HTMLButtonElement>('li button')!.click();
      await settle(r.fixture);
      vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValue(true);
      const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
      r.host.querySelector<HTMLButtonElement>('li button.btn-danger')!.click();
      await settle(r.fixture);
      expect(await store.savedCount(HOUSE)).toBe(0);
      expect(r.rows()).toEqual([]);
      expect(r.overlays[r.overlays.length - 1]).toBeNull();
      expect(announce.mock.calls.map((c) => c[0].key)).toContain('trace.deleted.snack');
      expect(r.$('#walks-empty')).not.toBeNull();
      expect(document.activeElement).toBe(r.$('#walks-heading'));
    });
  });

  describe.each(['en', 'hi', 'ta', 'te'] as const)('in %s', (lang) => {
    it('shows no raw key, with a walk and when empty, and passes the accessibility rules', async () => {
      const { mount } = await render({ lang });
      const empty = await mount();
      expect(empty.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(empty.host)).toEqual([]);
      await saveWalk(Date.UTC(2026, 9, 1, 9, 0));
      const withWalk = await mount();
      expect(withWalk.host.textContent).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(audit(withWalk.host)).toEqual([]);
    });
  });
});

describe('the house page and its saved walks', () => {
  const house: HouseDto = {
    id: HOUSE,
    label: 'Blue gate',
    lat: 12.97,
    lon: 77.59,
    status: 'NEW',
    checklist: {},
    deleted: false,
    createdAt: '2026-09-20T10:00:00.000Z',
    updatedAt: '2026-09-20T10:00:00.000Z',
    syncVersion: 0,
  } as HouseDto;

  async function open() {
    TestBed.resetTestingModule();
    const api = {
      houses: () => of([]),
      brokers: () => of([]),
      scoring: () => of(DEFAULT_SCORING),
      questions: () => of([]),
      house: () => of(house),
      visits: () => of([]),
      viewingsOf: () => of([]),
      areas: () => of([]),
      areaNotes: () => of([]),
      places: () => of([]),
      settled: signal(0),
      photos: () => of([]),
      photo: () => throwError(() => new Error('no bytes')),
      deleteHouse: () => of(true),
    };
    TestBed.configureTestingModule({
      imports: [HouseDetailPage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: HOUSE }), queryParamMap: convertToParamMap({}) } } },
        { provide: GeocodeService, useValue: { reverse: () => of({}) } },
        { provide: AiService, useValue: { enabled: signal(false), usesOwnKey: signal(false), ownHost: signal(''), extractListing: () => of() } },
      ],
    });
    await TestBed.inject(TranslationService).setLang('en');
    const traces = TestBed.inject(TraceStore);
    const asked: unknown[] = [];
    vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockImplementation((message) => {
      asked.push(message);
      return Promise.resolve(false);
    });
    const fixture = TestBed.createComponent(HouseDetailPage);
    await settle(fixture);
    return { fixture, host: fixture.nativeElement as HTMLElement, traces, asked, page: fixture.componentInstance as unknown as { deleteHouse(): Promise<void>; overlay(): MapOverlay | null; showOverlay(o: MapOverlay | null): void } };
  }

  afterEach(() => {
    vi.restoreAllMocks();
    TestBed.resetTestingModule();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('puts Did I walk past this house? and then the Saved walks card after the location card, before the checklist', async () => {
    const { host } = await open();
    const order = [...host.querySelectorAll('section.card[aria-labelledby]')].map((s) => s.getAttribute('aria-labelledby'));
    expect(order.indexOf('house-check-heading')).toBe(order.indexOf('location-heading') + 1);
    expect(order.indexOf('walks-heading')).toBe(order.indexOf('house-check-heading') + 1);
    expect(order.indexOf('checklist-heading')).toBe(order.indexOf('walks-heading') + 1);
  });

  it('passes the page\'s overlay to its own map, and an overlay from the card scrolls the map into view', async () => {
    const { page, fixture, host } = await open();
    const scroll = vi.fn();
    host.querySelector('app-location-map')!.scrollIntoView = scroll;
    const overlay: MapOverlay = { walks: { type: 'FeatureCollection', features: [] }, check: null, fit: [[1, 1], [2, 2]] };
    page.showOverlay(overlay);
    await settle(fixture);
    expect(page.overlay()).toBe(overlay);
    expect(scroll).toHaveBeenCalledTimes(1);
    page.showOverlay(null);
    expect(page.overlay()).toBeNull();
  });

  it('asks the plain delete question for a house with no saved walk, and says the walks go too when it has some', async () => {
    const plain = await open();
    await plain.page.deleteHouse();
    expect(plain.asked[0]).toMatchObject({ key: 'confirm.deleteHouse' });
    const withWalks = await open();
    for (let i = 0; i < 6; i++) await withWalks.traces.putPoint({ lat: i * 20 * DEG, lon: 0, atMs: NOW + i * 60_000, walkId: NOW }, 10);
    await withWalks.traces.saveWalk(NOW, HOUSE, NOW);
    await withWalks.page.deleteHouse();
    expect(withWalks.asked[0]).toMatchObject({ key: 'trace.houseDelete.confirm' });
  });
});

