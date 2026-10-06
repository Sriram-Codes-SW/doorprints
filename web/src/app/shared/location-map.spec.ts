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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TranslationService } from '../i18n/translation.service';
import { LocationMap } from './location-map';

/**
 * The panel over the house location map when the map has no tiles. The map itself is not made (jsdom has no WebGL):
 * `ngAfterViewInit` is stubbed and the two signals that `watchMapStyle` drives are set directly.
 */
type Panel = { available: { set(v: boolean): void }; workerFailed: { set(v: boolean): void } };

function render() {
  vi.spyOn(LocationMap.prototype, 'ngAfterViewInit').mockImplementation(() => undefined);
  TestBed.configureTestingModule({ imports: [LocationMap] });
  TestBed.inject(TranslationService).setLang('en');
  const fixture = TestBed.createComponent(LocationMap);
  fixture.componentRef.setInput('lat', 13);
  fixture.componentRef.setInput('lon', 80);
  fixture.detectChanges();
  return { fixture, host: fixture.nativeElement as HTMLElement, panel: fixture.componentInstance as unknown as Panel };
}

const tryAgain = (host: HTMLElement) => [...host.querySelectorAll('.offline button')].find((b) => b.textContent?.trim() === 'Try again');

afterEach(() => {
  vi.restoreAllMocks();
  TestBed.resetTestingModule();
});

describe('LocationMap: the panel shown when the map has no tiles', () => {
  it('offers Try again while offline', () => {
    const { fixture, host, panel } = render();
    panel.available.set(false);
    fixture.detectChanges();
    expect(host.querySelector('.offline')).toBeTruthy();
    expect(tryAgain(host)).toBeTruthy();
  });

  it('says the map helper failed and offers no Try again, which could not bring it back', () => {
    const { fixture, host, panel } = render();
    panel.available.set(false);
    panel.workerFailed.set(true);
    fixture.detectChanges();
    expect(host.querySelector('.offline')?.textContent).toContain('The map could not start its helper');
    expect(tryAgain(host)).toBeUndefined();
  });

  it('shows no panel while the map is available', () => {
    const { host } = render();
    expect(host.querySelector('.offline')).toBeNull();
  });
});

describe('LocationMap: the overlay (walks and the place check halo, docs/03 section 6.2b row 11)', () => {
  const line = (x: number) => ({ type: 'Feature' as const, properties: { kind: 'base' }, geometry: { type: 'LineString' as const, coordinates: [[x, 1] as [number, number], [x, 2] as [number, number]] } });
  const walks = { type: 'FeatureCollection' as const, features: [line(1)] };
  const halo = { type: 'FeatureCollection' as const, features: [line(2)] };
  const fakeLayers = () => ({ attach: vi.fn(), setWalks: vi.fn(), setCheck: vi.fn(), setLook: vi.fn(), fitTo: vi.fn() });

  function withLayers() {
    const r = render();
    const layers = fakeLayers();
    (r.fixture.componentInstance as unknown as { layers: unknown }).layers = layers;
    return { ...r, layers };
  }

  it('draws nothing and fits nothing by default (the house form and the point picker are unchanged)', () => {
    const { fixture, layers } = withLayers();
    fixture.detectChanges();
    fixture.componentRef.setInput('lat', 14);
    fixture.detectChanges();
    expect(layers.fitTo).not.toHaveBeenCalled();
    expect(layers.setCheck).not.toHaveBeenCalledWith(expect.objectContaining({ features: [expect.anything()] }));
  });

  it('draws the walks and the halo and frames the box with 40 px padding when the overlay is set', () => {
    const { fixture, layers } = withLayers();
    fixture.componentRef.setInput('overlay', { walks, check: halo, fit: [[1, 1], [2, 2]] });
    fixture.detectChanges();
    expect(layers.setWalks).toHaveBeenLastCalledWith(walks);
    expect(layers.setCheck).toHaveBeenLastCalledWith(halo);
    expect(layers.fitTo).toHaveBeenLastCalledWith([[1, 1], [2, 2]], 40);
  });

  it('clears the walks and the halo when the overlay goes back to null, without framing again', () => {
    const { fixture, layers } = withLayers();
    fixture.componentRef.setInput('overlay', { walks, check: halo, fit: [[1, 1], [2, 2]] });
    fixture.detectChanges();
    layers.fitTo.mockClear();
    fixture.componentRef.setInput('overlay', null);
    fixture.detectChanges();
    expect(layers.setWalks).toHaveBeenLastCalledWith({ type: 'FeatureCollection', features: [] });
    expect(layers.setCheck).toHaveBeenLastCalledWith(null);
    expect(layers.fitTo).not.toHaveBeenCalled();
  });

  it('does not frame an overlay that has no box', () => {
    const { fixture, layers } = withLayers();
    fixture.componentRef.setInput('overlay', { walks, check: null, fit: null });
    fixture.detectChanges();
    expect(layers.setWalks).toHaveBeenLastCalledWith(walks);
    expect(layers.fitTo).not.toHaveBeenCalled();
  });

  it('is quiet when the map is not made (no WebGL): setting an overlay does nothing and throws nothing', () => {
    const { fixture } = render();
    expect(() => {
      fixture.componentRef.setInput('overlay', { walks, check: halo, fit: [[1, 1], [2, 2]] });
      fixture.detectChanges();
    }).not.toThrow();
  });

  it('puts the ring of a check at its place with its label, replaces it on the next overlay and removes it with the overlay', () => {
    const { fixture } = withLayers();
    const page = fixture.componentInstance as unknown as { map: unknown; addRing(map: unknown, ring: unknown): unknown };
    page.map = { remove: () => undefined };
    const removed: string[] = [];
    const add = vi.spyOn(page, 'addRing').mockImplementation((_map, ring) => {
      const label = (ring as { label: string }).label;
      return { remove: () => removed.push(label) };
    });
    const ring = { lat: 13, lon: 80, label: 'This house' };
    fixture.componentRef.setInput('overlay', { walks, check: halo, fit: null, ring });
    fixture.detectChanges();
    expect(add).toHaveBeenCalledWith(page.map, ring);
    fixture.componentRef.setInput('overlay', { walks, check: halo, fit: null, ring: { ...ring, label: 'यह मकान' } });
    fixture.detectChanges();
    expect(removed).toEqual(['This house']);
    expect(add).toHaveBeenCalledTimes(2);
    fixture.componentRef.setInput('overlay', null);
    fixture.detectChanges();
    expect(removed).toEqual(['This house', 'यह मकान']);
    expect(add).toHaveBeenCalledTimes(2);
  });

  it('draws no ring for an overlay without one', () => {
    const { fixture } = withLayers();
    const page = fixture.componentInstance as unknown as { map: unknown; addRing(map: unknown, ring: unknown): unknown };
    page.map = { remove: () => undefined };
    const add = vi.spyOn(page, 'addRing');
    fixture.componentRef.setInput('overlay', { walks, check: halo, fit: null });
    fixture.detectChanges();
    expect(add).not.toHaveBeenCalled();
  });
});
