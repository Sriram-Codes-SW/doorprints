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

import { describe, expect, it, vi } from 'vitest';
import { ensureMapStyles, foldAttribution, mapErrorMessageKey, watchMapStyle } from './map-style';

/** A map container with MapLibre's credits control in the given state, `width` px wide. */
function mapRoot(width: number, classes: string): HTMLElement {
  const root = document.createElement('div');
  Object.defineProperty(root, 'offsetWidth', { value: width });
  const credits = document.createElement('details');
  credits.className = `maplibregl-ctrl maplibregl-ctrl-attrib ${classes}`;
  root.append(credits);
  return root;
}

const creditsOf = (root: HTMLElement) => root.querySelector('.maplibregl-ctrl-attrib')!;

describe('foldAttribution (owner report 2026-09-24: credits open over a phone map for good)', () => {
  it('folds open compact credits on a phone-width map into the (i) button', () => {
    const root = mapRoot(384, 'maplibregl-compact maplibregl-compact-show');
    expect(foldAttribution(root)).toBe(true);
    expect(creditsOf(root).classList).toContain('maplibregl-compact');
    expect(creditsOf(root).classList).not.toContain('maplibregl-compact-show');
  });

  it('folds at MapLibre own compact width, 640px, and leaves a wider (desktop) map as it is', () => {
    expect(foldAttribution(mapRoot(640, 'maplibregl-compact maplibregl-compact-show'))).toBe(true);
    const wide = mapRoot(641, 'maplibregl-compact maplibregl-compact-show');
    expect(foldAttribution(wide)).toBe(false);
    expect(creditsOf(wide).classList).toContain('maplibregl-compact-show');
  });

  it('does nothing when the credits are already folded, or not compact', () => {
    expect(foldAttribution(mapRoot(360, 'maplibregl-compact'))).toBe(false);
    const full = mapRoot(360, '');
    expect(foldAttribution(full)).toBe(false);
    expect(creditsOf(full).className).toBe('maplibregl-ctrl maplibregl-ctrl-attrib ');
  });

  it('does nothing on a map with no credits control (map unavailable)', () => {
    expect(foldAttribution(document.createElement('div'))).toBe(false);
  });
});

describe('ensureMapStyles (MapLibre CSS out of the render-blocking stylesheet)', () => {
  it('adds maplibre.css once, and hides the controls only until it has loaded', () => {
    const doc = document.implementation.createHTMLDocument('t');
    const base = doc.createElement('base');
    base.href = 'https://doorprints.example/';
    doc.head.append(base);
    ensureMapStyles(doc);
    ensureMapStyles(doc);
    const links = doc.querySelectorAll<HTMLLinkElement>('link[data-maplibre-css]');
    expect(links).toHaveLength(1);
    expect(links[0].rel).toBe('stylesheet');
    expect(links[0].href).toBe('https://doorprints.example/maplibre.css');
    expect(doc.documentElement.classList.contains('maplibre-css-loading')).toBe(true);
    links[0].dispatchEvent(new Event('load'));
    expect(doc.documentElement.classList.contains('maplibre-css-loading')).toBe(false);
  });
});

describe('mapErrorMessageKey (distinguish worker failure from offline)', () => {
  it('workerFailedOnline: worker error + online -> map.workerFailed', () => {
    const error = new Error('Worker failed to load. Check that the worker URL is correct.');
    expect(mapErrorMessageKey(error, true)).toBe('map.workerFailed');
  });

  it('workerFailedOffline: worker error + offline -> map.offline', () => {
    const error = new Error('Worker failed to load. Check that the worker URL is correct.');
    expect(mapErrorMessageKey(error, false)).toBe('map.offline');
  });

  it('tileErrorOnline: tile error + online -> map.offline (unchanged)', () => {
    const error = new Error('Failed to load tile');
    expect(mapErrorMessageKey(error, true)).toBe('map.offline');
  });
});

/** A map that records its listeners, so a test can fire MapLibre's events at watchMapStyle. */
function fakeMap() {
  const handlers = new Map<string, (event?: unknown) => void>();
  const map = {
    on: (name: string, handler: (event?: unknown) => void) => handlers.set(name, handler),
    setStyle: () => undefined,
  };
  return { map: map as unknown as Parameters<typeof watchMapStyle>[0], fire: (name: string, event?: unknown) => handlers.get(name)!(event) };
}

describe('watchMapStyle (what the map panel is told when the style does not load)', () => {
  const workerError = { error: new Error('Worker failed to load. Check that the worker URL is correct.') };
  const tileError = { error: new Error('Failed to fetch') };

  it('reports a worker that failed to load, while online, as map.workerFailed', () => {
    const { map, fire } = fakeMap();
    const calls: unknown[][] = [];
    watchMapStyle(map, (ok, key) => calls.push([ok, key]));
    fire('error', workerError);
    expect(calls).toEqual([[false, 'map.workerFailed']]);
  });

  it('reports any other error as the offline message, as before', () => {
    const { map, fire } = fakeMap();
    const calls: unknown[][] = [];
    watchMapStyle(map, (ok, key) => calls.push([ok, key]));
    fire('error', tileError);
    expect(calls).toEqual([[false, 'map.offline']]);
  });

  it('keeps the offline message for a worker error while the browser is offline', () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    try {
      const { map, fire } = fakeMap();
      const calls: unknown[][] = [];
      watchMapStyle(map, (ok, key) => calls.push([ok, key]));
      calls.length = 0; // the call made at start because the browser is offline
      fire('error', workerError);
      expect(calls).toEqual([[false, 'map.offline']]);
    } finally {
      online.mockRestore();
    }
  });

  it('says nothing about a tile error after the style has loaded', () => {
    const { map, fire } = fakeMap();
    const calls: unknown[][] = [];
    watchMapStyle(map, (ok, key) => calls.push([ok, key]));
    fire('style.load');
    calls.length = 0;
    fire('error', tileError);
    expect(calls).toEqual([]);
  });

  it('keeps saying the worker failed when the style loads after the worker error', () => {
    const { map, fire } = fakeMap();
    const calls: unknown[][] = [];
    watchMapStyle(map, (ok, key) => calls.push([ok, key]));
    fire('error', workerError);
    fire('style.load');
    expect(calls).toEqual([
      [false, 'map.workerFailed'],
      [false, 'map.workerFailed'],
    ]);
  });

  it('reports a worker error that arrives after the style has loaded', () => {
    const { map, fire } = fakeMap();
    const calls: unknown[][] = [];
    watchMapStyle(map, (ok, key) => calls.push([ok, key]));
    fire('style.load');
    fire('error', workerError);
    expect(calls).toEqual([
      [true, undefined],
      [false, 'map.workerFailed'],
    ]);
  });

  it('still reports the map available when the style loads and nothing failed', () => {
    const { map, fire } = fakeMap();
    const calls: unknown[][] = [];
    watchMapStyle(map, (ok, key) => calls.push([ok, key]));
    fire('style.load');
    expect(calls).toEqual([[true, undefined]]);
  });
});
