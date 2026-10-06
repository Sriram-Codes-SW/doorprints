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
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import { SyncService } from '../../data/sync.service';
import { TranslationService } from '../../i18n/translation.service';
import { scoringOf } from '../../shared/scoring';
import { FakeRecorder, settle } from '../../shared/testing/trace-fakes';
import { checkGeoJson } from '../../shared/trace-style';
import { MapPage } from './map-page';
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
    expect(dashed.getAttribute('stroke-dasharray')).toBe('3 2');
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

  it('does not feed the layers before the map is ready', async () => {
    const { fixture, view } = await render();
    const layers = { setWalks: vi.fn(), setLook: vi.fn(), setCheck: vi.fn(), attach: vi.fn() };
    (fixture.componentInstance as unknown as { traceLayers: unknown }).traceLayers = layers;
    view.look.set('OFF');
    await settle(fixture);
    expect(layers.setLook).not.toHaveBeenCalled();
  });
});
