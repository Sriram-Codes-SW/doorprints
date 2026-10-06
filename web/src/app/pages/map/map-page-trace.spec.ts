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
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import { SyncService } from '../../data/sync.service';
import { TranslationService } from '../../i18n/translation.service';
import { scoringOf } from '../../shared/scoring';
import { FakeRecorder, settle } from '../../shared/testing/trace-fakes';
import { TRACK_REPEAT_DASH, checkGeoJson } from '../../shared/trace-style';
import { MapPage } from './map-page';
import { PlaceCheckState } from './place-check-state';
import type { CheckAnswer } from './place-check-state';
import { TraceView } from './trace-view';

/** The walks on the Map page (docs/11 5.27.1, 5.27.4): the card, the legend, the description and the layers' feed. The map itself is not made (no WebGL in jsdom). */
async function render(lang: 'en' | 'hi' | 'ta' | 'te' = 'en') {
  vi.spyOn(MapPage.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
  TestBed.configureTestingModule({
    imports: [MapPage],
    providers: [
      provideRouter([]),
      { provide: TraceRecorderService, useValue: new FakeRecorder() },
      {
        provide: LocalDataService,
        useValue: {
          settled: signal(0),
          houses: () => of([]),
          brokers: () => of([]),
          areas: () => of([]),
          areaNotes: () => of([]),
          scoring: () => of(scoringOf([], [])),
          stats: () => of({ houses: 0, shortlisted: 0, rejected: 0, visits: 0, streets: 0 }),
        },
      },
      { provide: SyncService, useValue: { migration: signal('done'), enabled: signal(false), downloadToThisBrowser: vi.fn() } },
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({}) } } },
    ],
  });
  await TestBed.inject(TranslationService).setLang(lang);
  const fixture = TestBed.createComponent(MapPage);
  await settle(fixture);
  return { fixture, host: fixture.nativeElement as HTMLElement, view: TestBed.inject(TraceView) };
}

afterEach(() => {
  vi.restoreAllMocks();
  TestBed.resetTestingModule();
  localStorage.clear();
});

const FAKE_WALKS = { type: 'FeatureCollection' as const, features: [] };

describe('the Map page with the path trace', () => {
  it('puts the Trace my path card above the list heading', async () => {
    const { host } = await render();
    const card = host.querySelector('app-trace-card')!;
    const head = host.querySelector('.panel-head')!;
    expect(card.compareDocumentPosition(head) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(card.textContent).toContain('Trace my path');
  });

  it('has the Save this walk sheet, shut until a walk is to be asked about, after the card', async () => {
    const { host } = await render();
    const card = host.querySelector('app-trace-card')!;
    const sheet = host.querySelector('app-walk-end-sheet')!;
    expect(sheet).not.toBeNull();
    expect(card.compareDocumentPosition(sheet) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(sheet.querySelector('dialog')!.hasAttribute('open')).toBe(false);
  });

  it('shows no walk legend while the trace is empty, and Walked once / Walked more than once when there are walks', async () => {
    const { fixture, host, view } = await render();
    expect(host.querySelector('.legend')!.textContent).not.toContain('Walked once');
    view.walkCount.set(2);
    await settle(fixture);
    const legend = host.querySelector('.legend')!.textContent!;
    expect(legend).toContain('Walked once');
    expect(legend).toContain('Walked more than once');
  });

  it('draws the repeat legend sample with the dash, in the repeat colour, and hides it with the look Off', async () => {
    const { fixture, host, view } = await render();
    view.walkCount.set(1);
    await settle(fixture);
    const dashed = host.querySelector('.legend svg line[stroke-dasharray]')!;
    expect(dashed.getAttribute('stroke')).toBe('#E65100');
    // The map's dash is [3, 2] in LINE WIDTHS: at the sample's width the pattern is the same ratio, not 3 px / 2 px.
    const width = Number(dashed.getAttribute('stroke-width'));
    expect(width).toBe(3);
    expect(dashed.getAttribute('stroke-dasharray')).toBe('9 6');
    expect(dashed.getAttribute('stroke-dasharray')!.split(' ').map((n) => Number(n) / width)).toEqual([...TRACK_REPEAT_DASH]);
    view.look.set('OFF');
    await settle(fixture);
    expect(host.querySelector('.legend')!.textContent).not.toContain('Walked more than once');
    expect(host.querySelector('.legend')!.textContent).toContain('Walked once');
  });

  it('says that repeated paths are dashed in the map region description only when some are drawn and the look is not Off', async () => {
    const { fixture, host, view } = await render();
    const region = () => host.querySelector('[role="region"]')!;
    expect(region().getAttribute('aria-describedby')).toBe('map-hint');
    view.repeatCount.set(3);
    await settle(fixture);
    expect(region().getAttribute('aria-describedby')).toBe('map-hint trace-map-hint');
    expect(host.querySelector('#trace-map-hint')!.textContent).toBe('Paths you walked more than once are dashed.');
    view.look.set('OFF');
    await settle(fixture);
    expect(region().getAttribute('aria-describedby')).toBe('map-hint');
  });

  it('feeds the layers with the walks, the look and the check once the map is ready, and again when they change', async () => {
    const { fixture, view } = await render();
    const layers = { setWalks: vi.fn(), setLook: vi.fn(), setCheck: vi.fn(), attach: vi.fn() };
    const page = fixture.componentInstance as unknown as { traceLayers: unknown; mapReady: { set(v: boolean): void } };
    page.traceLayers = layers;
    page.mapReady.set(true);
    await settle(fixture);
    expect(layers.setLook).toHaveBeenLastCalledWith('CLEAR');
    expect(layers.setCheck).toHaveBeenLastCalledWith(null);
    view.look.set('SUBTLE');
    view.walks.set(FAKE_WALKS);
    const halo = checkGeoJson([[[1, 1], [1, 2]]]);
    view.check.set(halo);
    await settle(fixture);
    expect(layers.setLook).toHaveBeenLastCalledWith('SUBTLE');
    expect(layers.setWalks).toHaveBeenLastCalledWith(FAKE_WALKS);
    expect(layers.setCheck).toHaveBeenLastCalledWith(halo);
  });

  it('a look change sends only the look: the walks are not re-sent (setData re-tiles the source), and the check alone sends only the check', async () => {
    const { fixture, view } = await render();
    const layers = { setWalks: vi.fn(), setLook: vi.fn(), setCheck: vi.fn(), attach: vi.fn() };
    const page = fixture.componentInstance as unknown as { traceLayers: unknown; mapReady: { set(v: boolean): void } };
    page.traceLayers = layers;
    page.mapReady.set(true);
    await settle(fixture);
    expect(layers.setWalks).toHaveBeenCalledTimes(1);
    layers.setWalks.mockClear();
    layers.setLook.mockClear();
    layers.setCheck.mockClear();
    view.look.set('SUBTLE');
    await settle(fixture);
    expect(layers.setLook).toHaveBeenCalledTimes(1);
    expect(layers.setWalks).not.toHaveBeenCalled();
    expect(layers.setCheck).not.toHaveBeenCalled();
    view.check.set(checkGeoJson([[[1, 1], [1, 2]]]));
    await settle(fixture);
    expect(layers.setCheck).toHaveBeenCalledTimes(1);
    expect(layers.setWalks).not.toHaveBeenCalled();
    expect(layers.setLook).toHaveBeenCalledTimes(1);
    view.walks.set(FAKE_WALKS);
    await settle(fixture);
    expect(layers.setWalks).toHaveBeenCalledTimes(1);
    expect(layers.setLook).toHaveBeenCalledTimes(1);
  });

  it('does not feed the layers before the map is ready', async () => {
    const { fixture, view } = await render();
    const layers = { setWalks: vi.fn(), setLook: vi.fn(), setCheck: vi.fn(), attach: vi.fn() };
    (fixture.componentInstance as unknown as { traceLayers: unknown }).traceLayers = layers;
    view.look.set('OFF');
    await settle(fixture);
    expect(layers.setLook).not.toHaveBeenCalled();
  });

  describe('Have I been here? on the Map page', () => {
    const page = (fixture: { componentInstance: unknown }) =>
      fixture.componentInstance as unknown as {
        checkMode: { (): boolean; set(v: boolean): void };
        addMode: { (): boolean; set(v: boolean): void };
        map: unknown;
        placeView: unknown;
        mapReady: { set(v: boolean): void };
        onEscape(): void;
        ngOnDestroy(): void;
      };
    const answer = (over: Partial<CheckAnswer> = {}): CheckAnswer => ({ kind: 'house', place: { lat: 13, lon: 80 }, summary: null, stretches: [], bounds: [[79, 12], [81, 14]], ...over });
    const fakeMap = (lat = 13.5, lng = 80.5) => ({ getCenter: () => ({ lat, lng }), getCanvas: () => ({ style: { cursor: '' } }), remove: () => undefined });

    it('puts the Have I been here? button among the map actions, shown with the trace off and no walks', async () => {
      const { host } = await render();
      const button = host.querySelector('.map-actions #place-check-open')!;
      expect(button.textContent).toContain('Have I been here?');
    });

    it('hides it while a house is being placed, and while a spot is being chosen', async () => {
      const { fixture, host } = await render();
      page(fixture).addMode.set(true);
      await settle(fixture);
      expect(host.querySelector('#place-check-open')).toBeNull();
      page(fixture).addMode.set(false);
      page(fixture).checkMode.set(true);
      await settle(fixture);
      expect(host.querySelector('#place-check-open')).toBeNull();
    });

    it('A spot on the map: the crosshair, the check\'s own hint, Cancel checking and Check this spot instead of Add house', async () => {
      const { fixture, host } = await render();
      page(fixture).checkMode.set(true);
      await settle(fixture);
      expect(host.querySelector('.crosshair')).not.toBeNull();
      expect(host.querySelector('#add-hint')!.textContent).toContain('Move the map so the cross is on the spot, then press Check this spot.');
      expect(host.querySelector('#check-this-spot')!.textContent).toContain('Check this spot');
      expect(host.querySelector('#check-cancel-pick')!.textContent).toContain('Cancel checking');
      expect(host.querySelector('#add-toggle')).toBeNull();
      expect(host.querySelector('#place-here')).toBeNull();
      expect(host.querySelector('.legend')).toBeNull();
    });

    it('Check this spot asks the answer about the centre under the cross, leaves the mode, and navigates nowhere', async () => {
      const { fixture, host } = await render();
      const run = vi.spyOn(TestBed.inject(PlaceCheckState), 'run').mockResolvedValue();
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate');
      const p = page(fixture);
      p.map = fakeMap(13.25, 80.75);
      p.checkMode.set(true);
      await settle(fixture);
      host.querySelector<HTMLButtonElement>('#check-this-spot')!.click();
      await settle(fixture);
      expect(run).toHaveBeenCalledWith('spot', { lat: 13.25, lon: 80.75 });
      expect(p.checkMode()).toBe(false);
      expect(navigate).not.toHaveBeenCalled();
      expect(host.querySelector('#place-check-open')).not.toBeNull();
    });

    it('Cancel checking and Esc leave the mode and run nothing; Esc puts focus back on Have I been here?', async () => {
      const { fixture, host } = await render();
      const run = vi.spyOn(TestBed.inject(PlaceCheckState), 'run').mockResolvedValue();
      const p = page(fixture);
      p.checkMode.set(true);
      await settle(fixture);
      host.querySelector<HTMLButtonElement>('#check-cancel-pick')!.click();
      await settle(fixture);
      expect(p.checkMode()).toBe(false);
      p.checkMode.set(true);
      await settle(fixture);
      p.onEscape();
      await settle(fixture);
      expect(p.checkMode()).toBe(false);
      expect(run).not.toHaveBeenCalled();
    });

    it('gives the ring and the framing to the map view when an answer arrives, with the label in the app language', async () => {
      const { fixture } = await render('hi');
      const view = { sync: vi.fn(), fit: vi.fn(), dispose: vi.fn() };
      const p = page(fixture);
      p.placeView = view;
      p.mapReady.set(true);
      await settle(fixture);
      const state = TestBed.inject(PlaceCheckState);
      const a = answer({ kind: 'house' });
      state.present(a);
      await settle(fixture);
      expect(view.sync).toHaveBeenLastCalledWith(a, 'यह मकान');
      expect(view.fit).toHaveBeenCalledWith([[79, 12], [81, 14]]);
      state.close();
      await settle(fixture);
      expect(view.sync).toHaveBeenLastCalledWith(null, '');
    });

    it('shows the answer at the top of the list section, above the trace card', async () => {
      const { fixture, host } = await render();
      TestBed.inject(PlaceCheckState).present(answer());
      await settle(fixture);
      const panel = host.querySelector('app-place-check-panel')!;
      expect(panel.querySelector('#check-title')).not.toBeNull();
      expect(panel.compareDocumentPosition(host.querySelector('app-trace-card')!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('leaving the page withdraws the answer, the halo and the announcement', async () => {
      const { fixture, view } = await render();
      const state = TestBed.inject(PlaceCheckState);
      state.present(answer());
      page(fixture).ngOnDestroy();
      expect(state.answer()).toBeNull();
      expect(view.check()).toBeNull();
    });
  });
});
