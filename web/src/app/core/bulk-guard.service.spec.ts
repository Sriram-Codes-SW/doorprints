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

import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { BULK_MS, BulkGuard } from './bulk-guard.service';

describe('BulkGuard: one bulk action per 10 seconds per device', () => {
  afterEach(() => {
    localStorage.clear();
    TestBed.resetTestingModule();
  });

  it('lets the first through and holds the next for 10 seconds, then lets one through again', () => {
    const guard = TestBed.inject(BulkGuard);
    expect(guard.tryStart(1_000_000)).toBe(true);
    expect(guard.tryStart(1_000_000 + 1)).toBe(false);
    expect(guard.waitSeconds(1_000_000 + 4_500)).toBe(6);
    expect(guard.tryStart(1_000_000 + BULK_MS - 1)).toBe(false);
    expect(guard.tryStart(1_000_000 + BULK_MS)).toBe(true);
  });

  it('is shared with another tab through local storage, and a refused try starts nothing', () => {
    localStorage.setItem('doorprints.bulkAt', '5000000');
    const guard = TestBed.inject(BulkGuard);
    expect(guard.waitSeconds(5_002_000)).toBe(8);
    expect(guard.tryStart(5_002_000)).toBe(false);
    expect(localStorage.getItem('doorprints.bulkAt')).toBe('5000000');
  });

  it('does not lock for ever when the clock went back', () => {
    localStorage.setItem('doorprints.bulkAt', '9000000');
    expect(TestBed.inject(BulkGuard).tryStart(1_000)).toBe(true);
  });

  it('still holds in memory when storage is blocked', () => {
    const guard = TestBed.inject(BulkGuard);
    const real = Storage.prototype.getItem;
    Storage.prototype.getItem = () => { throw new Error('blocked'); };
    const set = Storage.prototype.setItem;
    Storage.prototype.setItem = () => { throw new Error('blocked'); };
    try {
      expect(guard.tryStart(2_000_000)).toBe(true);
      expect(guard.tryStart(2_003_000)).toBe(false);
    } finally {
      Storage.prototype.getItem = real;
      Storage.prototype.setItem = set;
    }
  });
});
