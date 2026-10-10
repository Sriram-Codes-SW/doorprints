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
import { ciCell, ciLabel, wilson } from './ai-eval-stats';

/** The expected bounds were computed apart from the code under test (Python, z = 1.959964, four decimals). */
describe('the Wilson 95% interval (S4b-BL-236)', () => {
  it('gives the lower bounds the consult stated for the golden set at a perfect score', () => {
    expect(wilson(25, 25)![0]).toBeCloseTo(0.8668, 4);
    expect(wilson(10, 10)![0]).toBeCloseTo(0.7225, 4);
    expect(wilson(5, 5)![0]).toBeCloseTo(0.5655, 4);
    expect(wilson(100, 100)![0]).toBeCloseTo(0.963, 4);
    expect(wilson(217, 217)![0]).toBeCloseTo(0.9826, 4);
    expect(wilson(25, 25)![1]).toBe(1);
  });

  it('bounds zero of 35 at ten percent and a middling score on both sides', () => {
    expect(wilson(0, 35)).toEqual([0, expect.closeTo(0.0989, 4)]);
    const [lo, hi] = wilson(8, 10)!;
    expect(lo).toBeCloseTo(0.4902, 4);
    expect(hi).toBeCloseTo(0.9433, 4);
    expect(wilson(0, 0)).toBeNull();
  });

  it('labels the value and the interval the way the server scorecard does, and a column cell without the value', () => {
    expect(ciLabel(31, 34)).toBe('0.91 (95% CI 0.77-0.97)');
    expect(ciLabel(25, 25)).toBe('1.00 (95% CI 0.87-1.00)');
    expect(ciLabel(0, 0)).toBe('n/a');
    expect(ciCell(1, 2)).toBe('0.09-0.91');
    expect(ciCell(0, 0)).toBe('n/a');
  });
});
