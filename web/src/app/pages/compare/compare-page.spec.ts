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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { LocalDataService } from '../../core/local-data.service';
import { newHouse } from '../../core/models';
import type { HouseDto } from '../../core/models';
import { ComparePage } from './compare-page';

const RENT: HouseDto = {
  ...newHouse(13, 80),
  id: 'a',
  label: 'Green View',
  status: 'SHORTLISTED',
  price: 32000,
  priceType: 'RENT',
  areaSqft: 1150,
  cost: { deposit: 64000, maintenance: 2500, maintenanceIncluded: false, brokerageMonths: 1, availableFrom: '2026-10-15', agreedPrice: 31000 },
};
const SALE: HouseDto = { ...newHouse(13.1, 80.1), id: 'b', label: 'Beach Road', price: 1250000, priceType: 'SALE', areaSqft: 1450 };

interface Row {
  id: string;
  label: string;
  cells: { text: string }[];
}

/** The rows slice 1a adds to Compare: the carpet area and what a house really costs, from `costSummary`. */
describe('ComparePage', () => {
  async function rows(): Promise<Row[]> {
    TestBed.configureTestingModule({
      imports: [ComparePage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: { settled: signal(0), houses: () => of([RENT, SALE]), visitCounts: () => of(new Map()) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ ids: 'a,b' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(ComparePage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return (fixture.componentInstance as unknown as { rows: () => Row[] }).rows();
  }

  it('shows the carpet area, the monthly cost, the money to move in, the price per sq ft and the agreed price', async () => {
    const byId = new Map((await rows()).map((r) => [r.id, r.cells.map((c) => c.text)]));
    expect([...byId.keys()].slice(0, 9)).toEqual(['score', 'price', 'bhk', 'area', 'monthlyCost', 'moveIn', 'perSqFt', 'availableFrom', 'agreedPrice']);
    expect(byId.get('area')).toEqual(['1,150 sq ft', '1,450 sq ft']);
    expect(byId.get('monthlyCost')).toEqual(['₹34,500', '–']);
    expect(byId.get('moveIn')).toEqual(['₹1,28,000', '–']);
    expect(byId.get('perSqFt')).toEqual(['₹27', '₹862']);
    expect(byId.get('availableFrom')).toEqual(['2026-10-15', '–']);
    expect(byId.get('agreedPrice')).toEqual(['₹31,000', '–']);
  });
});
