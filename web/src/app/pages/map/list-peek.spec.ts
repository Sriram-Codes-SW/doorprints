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
import { listPeek } from './list-peek';

describe('listPeek (the list under the phone map: heading and counters above the bottom bar)', () => {
  it('runs from the top of the heading to the foot of the counters', () => {
    // 384x615 phone at 130% text: heading 470-512, counters two rows to 628.
    expect(listPeek({ top: 470, bottom: 512 }, { bottom: 628 })).toBe(158);
  });

  it('is the heading alone while there are no counters yet', () => {
    expect(listPeek({ top: 400, bottom: 441.2 }, null)).toBe(42);
  });

  it('rounds a fraction up, so no caption is left a part of a pixel under the bar', () => {
    expect(listPeek({ top: 100.4, bottom: 140 }, { bottom: 260.1 })).toBe(160);
  });

  it('is never negative, and never shorter than the heading', () => {
    expect(listPeek({ top: 10, bottom: 5 }, null)).toBe(0);
    expect(listPeek({ top: 0, bottom: 50 }, { bottom: 20 })).toBe(50);
  });
});
