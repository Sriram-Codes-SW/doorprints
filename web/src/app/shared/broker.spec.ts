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
import { BROKER_TYPE, brokerFromPayload, brokerLine, brokerToPayload, phoneKey, samePhone } from './broker';

describe('a broker payload', () => {
  it('round-trips with the keys in the contract order, only the set ones', () => {
    const full = {
      name: 'Ravi Kumar',
      phone: '+91 98400 11111',
      agency: 'Adyar Homes',
      feeTerms: "15 days' rent, once",
      notes: 'Replies fast',
      rating: 4,
    };
    const payload = brokerToPayload(full);
    expect(Object.keys(payload)).toEqual(['name', 'phone', 'agency', 'feeTerms', 'notes', 'rating']);
    expect(brokerFromPayload(payload)).toEqual(full);
    expect(brokerToPayload({ name: 'Meena' })).toEqual({ name: 'Meena' });
    expect(BROKER_TYPE).toBe('broker');
  });

  it('reads an out-of-range value as unknown and drops the key, never null', () => {
    const read = brokerFromPayload({ name: 'A', rating: 6, phone: 'x'.repeat(51), agency: '  ', feeTerms: 5, notes: null });
    expect(read).toEqual({ name: 'A' });
    expect(brokerFromPayload({ name: 'A', rating: 0 })).toEqual({ name: 'A' });
    expect(brokerFromPayload({ name: 'A', rating: 3.6 })).toEqual({ name: 'A', rating: 4 });
    expect(brokerFromPayload({ name: 'A', rating: 1 })).toEqual({ name: 'A', rating: 1 });
  });

  it('skips a row with a blank, missing or oversized name as untrusted', () => {
    expect(brokerFromPayload({ name: '' })).toBeNull();
    expect(brokerFromPayload({ name: '   ' })).toBeNull();
    expect(brokerFromPayload({ phone: '123456' })).toBeNull();
    expect(brokerFromPayload({ name: 'x'.repeat(201) })).toBeNull();
    expect(brokerFromPayload({ name: 'x'.repeat(200) })).not.toBeNull();
    expect(brokerFromPayload(null)).toBeNull();
    expect(brokerFromPayload({ name: 7 })).toBeNull();
  });

  it('writes the name and, when there is one, the agency in brackets', () => {
    expect(brokerLine({ name: 'Ravi Kumar', agency: 'Adyar Homes' })).toBe('Ravi Kumar (Adyar Homes)');
    expect(brokerLine({ name: 'Meena' })).toBe('Meena');
  });
});

describe('the phone key', () => {
  it('is the last ten digits, so the ways of writing one number are one key', () => {
    for (const written of ['+91 98400 11111', '098400-11111', '9840011111', '(91) 98400-11111', '0091 9840011111']) {
      expect(phoneKey(written), written).toBe('9840011111');
    }
  });

  it('is null under six digits and for nothing', () => {
    expect(phoneKey('12345')).toBeNull();
    expect(phoneKey('ext. 12')).toBeNull();
    expect(phoneKey('')).toBeNull();
    expect(phoneKey(null)).toBeNull();
    expect(phoneKey(undefined)).toBeNull();
    expect(phoneKey('123456')).toBe('123456');
  });

  it('tells two numbers apart, and never matches a number too short to compare', () => {
    expect(samePhone('+91 98400 11111', '098400-11111')).toBe(true);
    expect(samePhone('98400 11111', '98400 22222')).toBe(false);
    expect(samePhone('12345', '12345')).toBe(false);
    expect(samePhone(null, null)).toBe(false);
    // The last ten only: a different country code in front of the same ten digits is the same number here.
    expect(samePhone('+1 984 001 1111', '9840011111')).toBe(true);
  });
});
