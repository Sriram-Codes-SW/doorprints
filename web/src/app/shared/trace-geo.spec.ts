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
import { K, TRACE, distanceToSegmentM, haversineM, interpolatedAtMs, isValidLatLon, localXY, nearestOnSegment, segmentLengthM } from './trace-geo';

describe('trace-geo: the one plane', () => {
  it('measures a degree of latitude as K metres and uses the same earth radius as the haversine', () => {
    expect(K).toBeCloseTo(111_194.9266, 3);
    expect(TRACE.earthRadiusM).toBe(6_371_000);
    expect(haversineM(0, 0, 1, 0)).toBeCloseTo(K, 6);
  });

  it('puts a point east of the place at x = dLon * cos(lat of the place) * K', () => {
    const p = localXY({ lat: 60, lon: 10 }, { lat: 60.001, lon: 10.002 });
    expect(p.x).toBeCloseTo(0.002 * 0.5 * K, 6);
    expect(p.y).toBeCloseTo(0.001 * K, 6);
  });

  it('measures a segment on the plane of its mean latitude', () => {
    expect(segmentLengthM({ lat: 0, lon: 0 }, { lat: 0.001, lon: 0 })).toBeCloseTo(0.001 * K, 6);
    // An east-west street at latitude 60 is half as long, per degree, as at the equator.
    expect(segmentLengthM({ lat: 60, lon: 0 }, { lat: 60, lon: 0.001 })).toBeCloseTo(0.0005 * K, 4);
  });

  it('uses the mean latitude of the two ends for a segment that changes latitude', () => {
    const a = { lat: 0, lon: 0 };
    const b = { lat: 60, lon: 0.001 };
    const dx = 0.001 * Math.cos((30 * Math.PI) / 180) * K;
    const dy = 60 * K;
    expect(segmentLengthM(a, b)).toBeCloseTo(Math.sqrt(dx * dx + dy * dy), 6);
  });

  it('uses cos(latitude) of the point asked from when measuring sideways to a street (latitude 60: half the metres per degree)', () => {
    const a = { lat: 60, lon: 0 };
    const b = { lat: 60.001, lon: 0 };
    // 0.0004 degrees of longitude at latitude 60 is 0.0002 * K = about 22.2 m, not 44.5 m.
    expect(distanceToSegmentM({ lat: 60.0005, lon: 0.0004 }, a, b)).toBeCloseTo(0.0002 * K, 3);
  });

  it('clamps a point past a segment end to a round cap at that end', () => {
    const a = { lat: 0, lon: 0 };
    const b = { lat: 100 / K, lon: 0 };
    expect(distanceToSegmentM({ lat: 130 / K, lon: 0 }, a, b)).toBeCloseTo(30, 6);
    expect(distanceToSegmentM({ lat: -40 / K, lon: 0 }, a, b)).toBeCloseTo(40, 6);
    expect(nearestOnSegment({ lat: 130 / K, lon: 0 }, a, b).t).toBe(1);
    expect(nearestOnSegment({ lat: 50 / K, lon: 30 / K }, a, b)).toEqual({ distanceM: expect.closeTo(30, 6), t: expect.closeTo(0.5, 9) });
  });

  it('takes t = 0 for a segment of length 0', () => {
    const a = { lat: 0, lon: 0 };
    expect(nearestOnSegment({ lat: 3 / K, lon: 4 / K }, a, a)).toEqual({ distanceM: expect.closeTo(5, 6), t: 0 });
  });

  it('interpolates the time at the nearest point, rounding half up', () => {
    expect(interpolatedAtMs({ atMs: 1000 }, { atMs: 61_000 }, 0.5)).toBe(31_000);
    expect(interpolatedAtMs({ atMs: 0 }, { atMs: 3 }, 0.5)).toBe(2); // floor(1.5 + 0.5)
    expect(interpolatedAtMs({ atMs: 5 }, { atMs: 9 }, 0)).toBe(5);
  });

  it('knows a valid latitude and longitude', () => {
    expect(isValidLatLon(90, 180)).toBe(true);
    expect(isValidLatLon(-90, -180)).toBe(true);
    expect(isValidLatLon(90.0001, 0)).toBe(false);
    expect(isValidLatLon(0, Number.NaN)).toBe(false);
  });
});
