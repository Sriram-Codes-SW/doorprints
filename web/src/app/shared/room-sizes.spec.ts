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

import { areaSqFt, areaSqM, cmToFeetInches, feetInchesToCm } from './room-sizes';

describe('room sizes', () => {
  describe('cmToFeetInches', () => {
    // The four test vectors: 13 ft 0 in = 396 cm; 12 ft 0 in = 366 cm.
    it('converts 396 cm to 13 ft 0 in', () => {
      expect(cmToFeetInches(396)).toEqual({ feet: 13, inches: 0 });
    });

    it('converts 366 cm to 12 ft 0 in', () => {
      expect(cmToFeetInches(366)).toEqual({ feet: 12, inches: 0 });
    });
  });

  describe('feetInchesToCm', () => {
    it('converts 13 ft 0 in to 396 cm', () => {
      expect(feetInchesToCm(13, 0)).toBe(396);
    });

    it('converts 12 ft 0 in to 366 cm', () => {
      expect(feetInchesToCm(12, 0)).toBe(366);
    });
  });

  describe('areaSqFt', () => {
    it('calculates 396×366 cm = 156 sq ft', () => {
      expect(areaSqFt(396, 366)).toBe(156);
    });
  });

  describe('areaSqM', () => {
    it('calculates 396×366 cm = 14.5 m²', () => {
      // 396 cm = 3.96 m, 366 cm = 3.66 m, 3.96 × 3.66 = 14.4936 ≈ 14.5 m²
      expect(areaSqM(396, 366)).toBe(14.5);
    });
  });
});
