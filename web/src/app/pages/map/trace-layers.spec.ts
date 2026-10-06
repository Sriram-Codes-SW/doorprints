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
import type { Map as MlMap } from 'maplibre-gl';
import { TRACK_CHECK_SOURCE, TRACK_REPEAT_LAYER, TRACK_SOURCE, checkGeoJson, trackGeoJson } from '../../shared/trace-style';
import { TraceLayers } from './trace-layers';

/** The part of a MapLibre map that TraceLayers touches, recording what it was asked. */
class FakeMap {
  sources = new Map<string, { data: unknown }>();
  layers: { id: string; before: string | undefined; spec: Record<string, unknown> }[] = [];
  paint: [string, string, unknown][] = [];
  layout: [string, string, unknown][] = [];
  fits: unknown[] = [];
  moved: string[] = [];
  constructor(public houseLayer = true) {
    if (houseLayer) this.layers.push({ id: 'houses-circles', before: undefined, spec: {} });
  }
  getSource(id: string) {
    const s = this.sources.get(id);
    return s ? { setData: (d: unknown) => ((s.data = d), Promise.resolve()) } : undefined;
  }
  addSource(id: string, spec: { data: unknown }) {
    this.sources.set(id, { data: spec.data });
  }
  getLayer(id: string) {
    return this.layers.find((l) => l.id === id);
  }
  addLayer(spec: Record<string, unknown>, before?: string) {
    this.layers.push({ id: spec['id'] as string, before, spec });
  }
  setPaintProperty(layer: string, name: string, value: unknown) {
    this.paint.push([layer, name, value]);
  }
  setLayoutProperty(layer: string, name: string, value: unknown) {
    this.layout.push([layer, name, value]);
  }
  fitBounds(bounds: unknown, options: unknown) {
    this.fits.push([bounds, options]);
  }
  moveLayer(id: string) {
    this.moved.push(id);
  }
  /** The style was replaced (offline, then online): sources and layers are gone, the house layer comes back first. */
  reload() {
    this.sources.clear();
    this.layers = this.houseLayer ? [{ id: 'houses-circles', before: undefined, spec: {} }] : [];
  }
}

const make = (m: FakeMap) => new TraceLayers(m as unknown as MlMap);
const ids = (m: FakeMap) => m.layers.map((l) => l.id);

describe('TraceLayers', () => {
  it('adds the track and track-check sources and the four layers, every one under the houses', () => {
    const m = new FakeMap();
    make(m).attach();
    expect([...m.sources.keys()].sort()).toEqual([TRACK_CHECK_SOURCE, TRACK_SOURCE].sort());
    expect(ids(m)).toEqual(['houses-circles', 'track-line', 'track-repeat-line', 'track-check-halo', 'track-check-line']);
    for (const l of m.layers.filter((x) => x.id !== 'houses-circles')) expect(l.before, l.id).toBe('houses-circles');
  });

  it('adds the layers without a before-layer when the map has no house layer (the house page map)', () => {
    const m = new FakeMap(false);
    make(m).attach();
    expect(ids(m)).toContain('track-repeat-line');
    expect(m.layers.every((l) => l.before === undefined)).toBe(true);
  });

  it('is idempotent: a second attach on the same style adds nothing', () => {
    const m = new FakeMap();
    const layers = make(m);
    layers.attach();
    layers.attach();
    expect(ids(m).filter((i) => i === 'track-line')).toHaveLength(1);
  });

  it('puts everything back when the style is replaced, with the walks, the look and the check it had', async () => {
    const m = new FakeMap();
    const layers = make(m);
    layers.attach();
    const walks = trackGeoJson([{ key: 't:1', points: [{ lat: 1, lon: 1, atMs: 1 }, { lat: 1, lon: 1.001, atMs: 2 }] }], []);
    layers.setWalks(walks);
    layers.setLook('OFF');
    layers.setCheck(checkGeoJson([[[1, 1], [1, 2]]]));
    m.reload();
    layers.attach();
    expect(m.sources.get(TRACK_SOURCE)!.data).toEqual(walks);
    expect((m.sources.get(TRACK_CHECK_SOURCE)!.data as { features: unknown[] }).features).toHaveLength(1);
    expect(m.getLayer(TRACK_REPEAT_LAYER)!.spec['layout']).toMatchObject({ visibility: 'none' });
  });

  it('pushes new walks to the source without rebuilding anything', () => {
    const m = new FakeMap();
    const layers = make(m);
    layers.attach();
    const walks = trackGeoJson([{ key: 't:1', points: [{ lat: 1, lon: 1, atMs: 1 }, { lat: 1, lon: 1.001, atMs: 2 }] }], []);
    layers.setWalks(walks);
    expect(m.sources.get(TRACK_SOURCE)!.data).toEqual(walks);
    expect(m.layers).toHaveLength(5);
  });

  it('applies the look live: the width and the visibility of the repeat layer, and no other layer', () => {
    const m = new FakeMap();
    const layers = make(m);
    layers.attach();
    layers.setLook('SUBTLE');
    expect(m.paint).toEqual([[TRACK_REPEAT_LAYER, 'line-width', ['interpolate', ['linear'], ['zoom'], 10, 1.5, 14, 3, 18, 5]]]);
    expect(m.layout).toEqual([[TRACK_REPEAT_LAYER, 'visibility', 'visible']]);
    layers.setLook('OFF');
    expect(m.layout[m.layout.length - 1]).toEqual([TRACK_REPEAT_LAYER, 'visibility', 'none']);
  });

  it('clears the check with null', () => {
    const m = new FakeMap();
    const layers = make(m);
    layers.attach();
    layers.setCheck(checkGeoJson([[[1, 1], [1, 2]]]));
    expect((m.sources.get(TRACK_CHECK_SOURCE)!.data as { features: unknown[] }).features).toHaveLength(1);
    layers.setCheck(null);
    expect(m.sources.get(TRACK_CHECK_SOURCE)!.data).toEqual({ type: 'FeatureCollection', features: [] });
  });

  it('is quiet before attach (no style yet): setters remember, nothing throws', () => {
    const m = new FakeMap();
    const layers = make(m);
    expect(() => {
      layers.setWalks({ type: 'FeatureCollection', features: [] });
      layers.setLook('CLEAR');
      layers.setCheck(null);
    }).not.toThrow();
    expect(m.sources.size).toBe(0);
  });

  it('never touches a layer it did not add: no moveLayer, no change to any other layer id (India boundary, ADR-22)', () => {
    const m = new FakeMap();
    m.layers.unshift({ id: 'in-boundary-line', before: undefined, spec: {} });
    const layers = make(m);
    layers.attach();
    layers.setLook('OFF');
    layers.fitTo([[1, 1], [2, 2]], 40);
    expect(m.moved).toEqual([]);
    expect([...m.paint, ...m.layout].every(([layer]) => layer === TRACK_REPEAT_LAYER)).toBe(true);
  });

  it('fits to bounds with the padding, without animation when the person prefers reduced motion', () => {
    const m = new FakeMap();
    const layers = make(m);
    vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('reduce'), addEventListener() {}, removeEventListener() {} }));
    layers.fitTo([[1, 2], [3, 4]], { top: 10, bottom: 20, left: 30, right: 40 });
    expect(m.fits[0]).toEqual([[[1, 2], [3, 4]], { padding: { top: 10, bottom: 20, left: 30, right: 40 }, maxZoom: 18, duration: 0 }]);
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener() {}, removeEventListener() {} }));
    layers.fitTo([[1, 2], [3, 4]], 40);
    expect((m.fits[1] as [unknown, { duration: number }])[1].duration).toBeGreaterThan(0);
    vi.unstubAllGlobals();
  });
});
