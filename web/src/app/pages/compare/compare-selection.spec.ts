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
import { idsFromQuery } from './compare-selection';

describe('the compared houses in the URL (?ids=)', () => {
  const allowed = new Set(['a', 'b', 'c', 'd', 'e']);

  it('keeps the order, drops duplicates and houses that cannot be compared, and stops at four', () => {
    expect(idsFromQuery('c,a,c,x,b', allowed)).toEqual(['c', 'a', 'b']);
    expect(idsFromQuery('a,b,c,d,e', allowed)).toEqual(['a', 'b', 'c', 'd']);
  });

  it('is null without the parameter (the page then picks a default), and empty for an empty one', () => {
    expect(idsFromQuery(null, allowed)).toBeNull();
    expect(idsFromQuery('', allowed)).toEqual([]);
  });
});
