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
import { STATUS_COLOR, newHouse } from '../../core/models';
import { HOUSE_PAINT, houseFeatures } from './house-markers';

/** The hollow marker of an approximate location (FR-068): the feature says so, and the paint reads it. */
describe('house markers', () => {
  it('marks a feature approx only for an APPROX house', () => {
    const precise = { ...newHouse(13, 80, 'MAP'), id: 'a' };
    const rough = { ...newHouse(13.1, 80.1, 'APPROX'), id: 'b' };
    const old = { ...newHouse(13.2, 80.2), id: 'c' };
    expect(houseFeatures([precise, rough, old]).map((f) => f.properties.approx)).toEqual([false, true, false]);
    expect(houseFeatures([rough])[0].geometry.coordinates).toEqual([80.1, 13.1]);
  });

  it('draws an approx house with no fill and its status colour as the stroke', () => {
    const approx = ['==', ['get', 'approx'], true];
    expect(HOUSE_PAINT['circle-opacity']).toEqual(['case', approx, 0, ['match', ['get', 'status'], 'REJECTED', 0.75, 'NOT_CHOSEN', 0.6, 1]]);
    expect(HOUSE_PAINT['circle-stroke-color']).toEqual(['case', approx, HOUSE_PAINT['circle-color'], '#ffffff']);
    expect(HOUSE_PAINT['circle-color']).toContain(STATUS_COLOR.SHORTLISTED);
  });

  /** WCAG relative luminance contrast of two #rrggbb colours. */
  const contrast = (a: string, b: string): number => {
    const lum = (hex: string) => {
      const [r, g, bl] = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    };
    const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
    return (hi + 0.05) / (lo + 0.05);
  };

  it('gives Taken and Not chosen their own marker colours, distinct from the other statuses and from the path trace (slice 5)', () => {
    const colours = Object.values(STATUS_COLOR);
    expect(new Set(colours).size).toBe(5);
    expect(HOUSE_PAINT['circle-color']).toEqual(expect.arrayContaining(['TAKEN', STATUS_COLOR.TAKEN, 'NOT_CHOSEN', STATUS_COLOR.NOT_CHOSEN]));
    expect(colours).not.toContain('#8E24AA');
  });

  it('keeps every status marker colour at 5:1 or better against white (the light map tiles)', () => {
    for (const [status, colour] of Object.entries(STATUS_COLOR)) expect(contrast(colour, '#ffffff'), status).toBeGreaterThanOrEqual(5);
  });

  it('draws Taken large with the thick stroke like Shortlisted, and Not chosen small and faded', () => {
    expect(HOUSE_PAINT['circle-stroke-width']).toEqual(['match', ['get', 'status'], 'SHORTLISTED', 3, 'TAKEN', 3, 2]);
    const radius = JSON.stringify(HOUSE_PAINT['circle-radius']);
    expect(radius).toContain('"TAKEN",7');
    expect(radius).toContain('"NOT_CHOSEN",5');
  });

  it('carries the status of a Taken house on its feature', () => {
    const h = { ...newHouse(13, 80), status: 'TAKEN' as const };
    expect(houseFeatures([h])[0].properties.status).toBe('TAKEN');
  });
});
