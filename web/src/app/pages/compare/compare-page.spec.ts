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
import type { BrokerRow } from '../../shared/broker';
import { LocalStore } from '../../data/local-store.service';
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
  async function render(brokers: BrokerRow[] = [], unit: 'FT' | 'M' = 'FT', list: HouseDto[] = [RENT, SALE]) {
    TestBed.configureTestingModule({
      imports: [ComparePage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: { settled: signal(0), houses: () => of(list), visitCounts: () => of(new Map()), brokers: () => of(brokers) } },
        { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve(unit) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ ids: 'a,b' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(ComparePage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  async function rows(): Promise<Row[]> {
    return ((await render()).componentInstance as unknown as { rows: () => Row[] }).rows();
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

  /** Slice 1b: a linked house shows its broker's name and agency in the Contact row, the phone as before. */
  it('shows the broker and agency in the Contact row of a linked house', async () => {
    const linked = { ...RENT, brokerId: 'b-1', contactName: 'Ravi Kumar', contactPhone: '+91 98400 11111' };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ComparePage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: { settled: signal(0), houses: () => of([linked, SALE]), visitCounts: () => of(new Map()), brokers: () => of([{ id: 'b-1', updatedAt: null, broker: { name: 'Ravi Kumar', agency: 'Adyar Homes' } }]) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ ids: 'a,b' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(ComparePage);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const cells = [...(fixture.nativeElement as HTMLElement).querySelectorAll('tr')]
      .find((tr) => tr.querySelector('th')?.textContent?.includes('Contact'))!
      .querySelectorAll('td');
    expect(cells[0].textContent).toContain('Ravi Kumar (Adyar Homes)');
    expect(cells[0].querySelector('a')?.getAttribute('href')).toContain('tel:');
    expect(cells[1].textContent).toContain('–');
  });

  /** Slice 1c: the Rooms row shows the count and the total area in the length unit, a dash without rooms. */
  describe('Rooms row', () => {
    const withRooms: HouseDto = {
      ...RENT,
      rooms: [
        { id: 'r1', type: 'HALL', lengthCm: 396, widthCm: 366, sort: 0 },
        { id: 'r2', type: 'BEDROOM', sort: 1 },
      ],
    };
    const cells = async (unit: 'FT' | 'M') => {
      const fixture = await render([], unit, [withRooms, SALE]);
      const row = (fixture.componentInstance as unknown as { rows: () => Row[] }).rows().find((r) => r.id === 'rooms')!;
      return { label: row.label, texts: row.cells.map((c) => c.text) };
    };

    it('shows the number of rooms and their total area in sq ft, and a dash for a house without rooms', async () => {
      expect(await cells('FT')).toEqual({ label: 'Rooms', texts: ['2 · 156 sq ft', '–'] });
    });

    it('shows the total area in m² when the length unit is Metres', async () => {
      expect((await cells('M')).texts).toEqual(['2 · 14.5 m²', '–']);
    });

    it('shows only the count when no room has both sizes', async () => {
      const fixture = await render([], 'FT', [{ ...RENT, rooms: [{ id: 'r1', type: 'HALL', sort: 0 }] }, SALE]);
      const row = (fixture.componentInstance as unknown as { rows: () => Row[] }).rows().find((r) => r.id === 'rooms')!;
      expect(row.cells.map((c) => c.text)).toEqual(['1', '–']);
    });
  });
});
