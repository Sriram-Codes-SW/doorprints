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

import { describe, expect, it } from 'vitest';
import type { TracePoint, TraceWalk } from './trace-geo';
import { walkSamples } from './trace-repeats';
import {
  TRACK_CHECK_HALO_LAYER,
  TRACK_CHECK_LINE_LAYER,
  TRACK_CHECK_SOURCE,
  TRACK_COLOR,
  TRACK_LAYER,
  TRACK_REPEAT_COLOR,
  TRACK_REPEAT_DASH,
  TRACK_REPEAT_LAYER,
  TRACK_SOURCE,
  baseLines,
  checkGeoJson,
  checkLayersJson,
  MAX_DRAWN_POINTS,
  legendDashArray,
  repeatFactor,
  repeatVisibility,
  repeatWidthExpression,
  thinLine,
  trackGeoJson,
  trackLayerJson,
  trackRepeatLayerJson,
} from './trace-style';

const p = (lat: number, lon: number, atMs: number, resumed = false): TracePoint => ({ lat, lon, atMs, walkId: 1, ...(resumed ? { resumed } : {}) });
const walk = (key: string, points: TracePoint[]): TraceWalk => ({ key, points });

describe('trace style: the widths and colours of docs/11 5.27.4', () => {
  it('uses the orange #E65100 for the repeat and the purple #8E24AA for the base, with the dash [3, 2]', () => {
    expect(TRACK_REPEAT_COLOR).toBe('#E65100');
    expect(TRACK_COLOR).toBe('#8E24AA');
    expect(TRACK_REPEAT_DASH).toEqual([3, 2]);
  });

  it('scales the legend dash by the sample width: the map\'s [3, 2] line widths at 3 px is "9 6" and at 1 px "3 2"', () => {
    expect(legendDashArray(3)).toBe('9 6');
    expect(legendDashArray(1)).toBe('3 2');
    expect(legendDashArray(2.5)).toBe('7.5 5');
  });

  it('pins the overlay widths: Clear 2.7 / 5.4 / 9.0 and Subtle 1.5 / 3.0 / 5.0 at zoom 10 / 14 / 18', () => {
    expect(repeatWidthExpression('CLEAR')).toEqual(['interpolate', ['linear'], ['zoom'], 10, 2.7, 14, 5.4, 18, 9]);
    expect(repeatWidthExpression('SUBTLE')).toEqual(['interpolate', ['linear'], ['zoom'], 10, 1.5, 14, 3, 18, 5]);
  });

  it('has the factor 1.8 for Clear, 1.0 for Subtle and none for Off', () => {
    expect(repeatFactor('CLEAR')).toBe(1.8);
    expect(repeatFactor('SUBTLE')).toBe(1);
    expect(repeatFactor('OFF')).toBeNull();
  });

  it('hides the overlay for Off only, and the Off look never changes what is drawn for the other two', () => {
    expect(repeatVisibility('OFF')).toBe('none');
    expect(repeatVisibility('CLEAR')).toBe('visible');
    expect(repeatVisibility('SUBTLE')).toBe('visible');
  });

  it('builds the base layer: solid, round caps, 0.85 opacity, the base widths', () => {
    const layer = trackLayerJson();
    expect(layer.id).toBe(TRACK_LAYER);
    expect(layer.source).toBe(TRACK_SOURCE);
    expect(layer.filter).toEqual(['==', ['get', 'kind'], 'base']);
    expect(layer.paint['line-color']).toBe('#8E24AA');
    expect(layer.paint['line-opacity']).toBe(0.85);
    expect(layer.paint['line-width']).toEqual(['interpolate', ['linear'], ['zoom'], 10, 1.5, 14, 3, 18, 5]);
    expect(layer.layout['line-cap']).toBe('round');
  });

  it('builds the repeat layer: dashed [3, 2], butt caps, fades in from zoom 10.5 to 11, hidden for Off', () => {
    const clear = trackRepeatLayerJson('CLEAR');
    expect(clear.id).toBe(TRACK_REPEAT_LAYER);
    expect(clear.filter).toEqual(['==', ['get', 'kind'], 'repeat']);
    expect(clear.paint['line-dasharray']).toEqual([3, 2]);
    expect(clear.paint['line-color']).toBe('#E65100');
    expect(clear.paint['line-opacity']).toEqual(['interpolate', ['linear'], ['zoom'], 10.5, 0, 11, 0.95]);
    expect(clear.layout['line-cap']).toBe('butt');
    expect(clear.layout.visibility).toBe('visible');
    expect(trackRepeatLayerJson('OFF').layout.visibility).toBe('none');
  });

  it('keeps the dash for Clear and Subtle (never colour alone)', () => {
    for (const look of ['CLEAR', 'SUBTLE'] as const) expect(trackRepeatLayerJson(look).paint['line-dasharray']).toEqual([3, 2]);
  });
});

describe('trace style: the GeoJSON', () => {
  it('cuts the base line at every resumed point: no line across a pause', () => {
    const lines = baseLines([p(1, 1, 1), p(1, 2, 2), p(2, 1, 3, true), p(2, 2, 4)]);
    expect(lines).toEqual([
      [[1, 1], [2, 1]],
      [[1, 2], [2, 2]],
    ]);
  });

  it('drops a piece of one point (a resumed point that nothing follows)', () => {
    expect(baseLines([p(1, 1, 1), p(1, 2, 2), p(2, 1, 3, true)])).toEqual([[[1, 1], [2, 1]]]);
  });

  it('draws every walk whole as kind base and each shown stretch as kind repeat, tagged with the walk key', () => {
    const w = walk('t:1', [p(0, 0, 1), p(0, 0.001, 2), p(0, 0.002, 3)]);
    const stretch = { fromM: 0, toM: walkSamples(w.points).arc[3] };
    const fc = trackGeoJson([w], [{ repeated: [stretch], shown: [stretch] }]);
    const kinds = fc.features.map((f) => f.properties!['kind']);
    expect(kinds).toContain('base');
    expect(kinds).toContain('repeat');
    expect(fc.features.every((f) => f.properties!['key'] === 't:1')).toBe(true);
    const repeat = fc.features.find((f) => f.properties!['kind'] === 'repeat')!;
    expect(repeat.geometry.type).toBe('LineString');
  });

  it('draws the SHOWN stretches as repeats, not every repeated one (a walk draws only its own part)', () => {
    const w = walk('t:1', [p(0, 0, 1), p(0, 0.001, 2), p(0, 0.002, 3)]);
    const arc = walkSamples(w.points).arc;
    const shown = { fromM: 0, toM: arc[3] };
    const repeated = { fromM: arc[3], toM: arc[6] };
    const fc = trackGeoJson([w], [{ repeated: [shown, repeated], shown: [shown] }]);
    expect(fc.features.filter((f) => f.properties!['kind'] === 'repeat')).toHaveLength(1);
  });

  it('draws a walk with no repeats as base only, and an empty list as an empty collection', () => {
    const w = walk('s:a', [p(0, 0, 1), p(0, 0.001, 2)]);
    expect(trackGeoJson([w], [{ repeated: [], shown: [] }]).features.map((f) => f.properties!['kind'])).toEqual(['base']);
    expect(trackGeoJson([], [])).toEqual({ type: 'FeatureCollection', features: [] });
  });

  it('draws a walk the detection left out (no result) whole', () => {
    const w = walk('t:9', [p(0, 0, 1), p(0, 0.001, 2)]);
    expect(trackGeoJson([w], []).features).toHaveLength(1);
  });
});

describe('trace style: the place check layers', () => {
  it('has the halo (a wide white casing) under the line, both in the source track-check', () => {
    const [halo, line] = checkLayersJson();
    expect(halo.id).toBe(TRACK_CHECK_HALO_LAYER);
    expect(line.id).toBe(TRACK_CHECK_LINE_LAYER);
    expect(halo.source).toBe(TRACK_CHECK_SOURCE);
    expect(halo.paint['line-color']).toBe('#FFFFFF');
    expect(JSON.stringify(halo.paint['line-width'])).not.toBe(JSON.stringify(line.paint['line-width']));
    // Not the repeat look: no orange and no dash.
    expect(JSON.stringify(checkLayersJson())).not.toContain('E65100');
    expect(JSON.stringify(checkLayersJson())).not.toContain('dasharray');
  });

  it('turns the matched stretches into line features and nothing else', () => {
    const fc = checkGeoJson([
      [[1, 1], [1, 2]],
      [[3, 3]],
    ]);
    expect(fc.features).toHaveLength(1);
    expect(fc.features[0].geometry.type).toBe('LineString');
    expect(checkGeoJson([])).toEqual({ type: 'FeatureCollection', features: [] });
  });
});

describe('the drawing cap (S4b-FR-31): the points drawn are thinned, never the points the detection reads', () => {
  const line = (n: number): [number, number][] => Array.from({ length: n }, (_, i) => [i, 0]);
  /** A walk of `n` points 10 m apart going north, one id per walk. */
  const north = (key: string, n: number, east = 0): TraceWalk => walk(key, Array.from({ length: n }, (_, i) => p(i * 1e-4, east, i * 10_000)));
  const baseCount = (fc: ReturnType<typeof trackGeoJson>) => fc.features.filter((f) => f.properties['kind'] === 'base').reduce((n, f) => n + f.geometry.coordinates.length, 0);

  it('thinLine keeps every stride-th point and always the first and the last, without a repeated last', () => {
    expect(thinLine(line(10), 3).map((c) => c[0])).toEqual([0, 3, 6, 9]);
    expect(thinLine(line(11), 3).map((c) => c[0])).toEqual([0, 3, 6, 9, 10]);
    expect(thinLine(line(10), 1)).toEqual(line(10));
    expect(thinLine(line(2), 5)).toEqual(line(2));
    expect(thinLine(line(7), 100).map((c) => c[0])).toEqual([0, 6]);
  });

  it('draws every point up to the cap, exactly at the cap included', () => {
    const w = north('a', 100);
    expect(baseCount(trackGeoJson([w], [], 100))).toBe(100);
    expect(baseCount(trackGeoJson([w], [], 101))).toBe(100);
  });

  it('one point over the cap thins by 2 and keeps the first and the last point of the line', () => {
    const w = north('a', 101);
    const fc = trackGeoJson([w], [], 100);
    const coords = fc.features[0].geometry.coordinates;
    expect(coords).toHaveLength(51); // indexes 0, 2, ..., 100: the last is already a multiple of the stride, so it is not added twice
    expect(coords[0]).toEqual([0, 0]);
    expect(coords[coords.length - 1]).toEqual([0, 100e-4]);
  });

  it('thins every walk by the same stride from the total, and never drops a walk or its ends', () => {
    const walks = [north('a', 60, 0), north('b', 60, 1), north('c', 60, 2)];
    const fc = trackGeoJson(walks, [], 90); // 180 points: stride 2
    expect(fc.features.map((f) => f.properties['key'])).toEqual(['a', 'b', 'c']);
    for (const [i, f] of fc.features.entries()) {
      expect(f.geometry.coordinates).toHaveLength(31); // 0, 2, ..., 58 and the last, 59
      expect(f.geometry.coordinates[0]).toEqual([i, 0]);
      expect(f.geometry.coordinates[30]).toEqual([i, 59e-4]);
    }
  });

  it('cuts at a resumed point as before, and each piece keeps its own first and last point', () => {
    const pts = [...north('a', 50).points, ...north('a', 50).points.map((q, i) => p(q.lat + 1, 0, 1_000_000 + i * 10_000, i === 0))];
    const fc = trackGeoJson([walk('a', pts)], [], 30);
    expect(fc.features).toHaveLength(2);
    expect(fc.features[0].geometry.coordinates[0]).toEqual([0, 0]);
    expect(fc.features[0].geometry.coordinates.at(-1)).toEqual([0, 49e-4]);
    expect(fc.features[1].geometry.coordinates[0]).toEqual([0, 1]);
    expect(fc.features[1].geometry.coordinates.at(-1)).toEqual([0, 1 + 49e-4]);
  });

  it('thins a repeat stretch with the same stride, keeping its ends, and leaves the stretch the detection found alone', () => {
    const w = north('a', 100);
    const arc = walkSamples(w.points).arc;
    const stretch = { fromM: arc[10], toM: arc[arc.length - 10] };
    const repeats = [{ repeated: [stretch], shown: [stretch] }];
    const whole = trackGeoJson([w], repeats, 1_000).features.find((f) => f.properties['kind'] === 'repeat')!;
    const thin = trackGeoJson([w], repeats, 25).features.find((f) => f.properties['kind'] === 'repeat')!;
    expect(thin.geometry.coordinates.length).toBeLessThan(whole.geometry.coordinates.length / 3);
    expect(thin.geometry.coordinates[0]).toEqual(whole.geometry.coordinates[0]);
    expect(thin.geometry.coordinates.at(-1)).toEqual(whole.geometry.coordinates.at(-1));
    expect(repeats[0].shown[0]).toEqual({ fromM: arc[10], toM: arc[arc.length - 10] });
  });

  it('does not change the walks it is given', () => {
    const w = north('a', 100);
    const before = JSON.stringify(w);
    trackGeoJson([w], [], 10);
    expect(JSON.stringify(w)).toBe(before);
  });

  it('the worst case, 200 saved walks of 5000 points, is drawn within the cap plus the two ends of each line', () => {
    const walks = Array.from({ length: 200 }, (_, k) => north(`s:${k}`, 5_000, k));
    const fc = trackGeoJson(walks, []);
    expect(fc.features).toHaveLength(200);
    expect(baseCount(fc)).toBeLessThanOrEqual(MAX_DRAWN_POINTS + 2 * 200);
    expect(baseCount(fc)).toBeGreaterThan(MAX_DRAWN_POINTS / 2);
    for (const f of fc.features) {
      expect(f.geometry.coordinates[0][1]).toBe(0);
      expect(f.geometry.coordinates.at(-1)![1]).toBeCloseTo(4_999e-4, 9);
    }
  });

  it('keeps the cap at 40 000 points: twice what the detection reads', () => {
    expect(MAX_DRAWN_POINTS).toBe(40_000);
  });
});
