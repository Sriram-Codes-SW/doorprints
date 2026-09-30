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
import { DEFAULT_SCORING } from '../shared/scoring';
import { houseScore } from './models';

describe('houseScore', () => {
  it('is null when neither checklist nor rating is set', () => {
    expect(houseScore({ checklist: {}, rating: null }, DEFAULT_SCORING)).toBeNull();
    expect(houseScore({ checklist: {} }, DEFAULT_SCORING)).toBeNull();
  });

  it('averages the checklist when there is no rating', () => {
    expect(houseScore({ checklist: { water: 5, power: 3 }, rating: null }, DEFAULT_SCORING)).toBe(4);
    expect(houseScore({ checklist: { water: 1, power: 2, parking: 4 } }, DEFAULT_SCORING)).toBeCloseTo(7 / 3, 10);
  });

  it('uses the star rating alone when the checklist is empty', () => {
    expect(houseScore({ checklist: {}, rating: 3 }, DEFAULT_SCORING)).toBe(3);
  });

  it('blends checklist average and rating 50/50 when both exist', () => {
    // checklist average 4, rating 2 -> 3
    expect(houseScore({ checklist: { water: 5, power: 3 }, rating: 2 }, DEFAULT_SCORING)).toBe(3);
    expect(houseScore({ checklist: { water: 5, noise: 4, security: 3 }, rating: 5 }, DEFAULT_SCORING)).toBe(4.5);
  });

  it('treats a rating of 0 as a real value, not as missing', () => {
    expect(houseScore({ checklist: { water: 4 }, rating: 0 }, DEFAULT_SCORING)).toBe(2);
    expect(houseScore({ checklist: {}, rating: 0 }, DEFAULT_SCORING)).toBe(0);
  });

  it('is the old rule under the default scoring, whatever the keys (V7 compatibility)', () => {
    expect(houseScore({ checklist: { water: 4, power: 2 }, rating: 5 }, DEFAULT_SCORING)).toBe(4);
  });

  it('ignores NaN checklist values', () => {
    expect(houseScore({ checklist: { water: Number.NaN, power: 4 }, rating: null }, DEFAULT_SCORING)).toBe(4);
    expect(houseScore({ checklist: { water: Number.NaN }, rating: null }, DEFAULT_SCORING)).toBeNull();
  });

  it('copes with a missing checklist object from older API responses', () => {
    const legacy = { checklist: null as unknown as Record<string, number>, rating: 4 };
    expect(houseScore(legacy, DEFAULT_SCORING)).toBe(4);
  });

  it('stays within 0..5 for in-range inputs', () => {
    expect(houseScore({ checklist: { a: 0, b: 0 }, rating: 0 }, DEFAULT_SCORING)).toBe(0);
    expect(houseScore({ checklist: { a: 5, b: 5 }, rating: 5 }, DEFAULT_SCORING)).toBe(5);
  });
});
