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
import { DEFAULT_SCORING, scoringOf } from '../../shared/scoring';
import type { Scoring } from '../../shared/scoring';
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
  async function render(brokers: BrokerRow[] = [], unit: 'FT' | 'M' = 'FT', list: HouseDto[] = [RENT, SALE], scoring: Scoring = DEFAULT_SCORING) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ComparePage],
      providers: [
        provideRouter([]),
        { provide: LocalDataService, useValue: { settled: signal(0), scoring: () => of(scoring), houses: () => of(list), visitCounts: () => of(new Map()), brokers: () => of(brokers) } },
        { provide: LocalStore, useValue: { lengthUnit: () => Promise.resolve(unit) } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ ids: list.map((h) => h.id).join(',') }) } } },
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
        { provide: LocalDataService, useValue: { settled: signal(0), scoring: () => of(DEFAULT_SCORING), houses: () => of([linked, SALE]), visitCounts: () => of(new Map()), brokers: () => of([{ id: 'b-1', updatedAt: null, broker: { name: 'Ravi Kumar', agency: 'Adyar Homes' } }]) } },
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
  /** Slice 2 (docs/11 5.4): the ranking order, the Must-haves row and the person's criteria. */
  describe('criteria and ranking (slice 2)', () => {
    const scoring = scoringOf(
      [
        { key: 'security', updatedAt: null, criterion: { key: 'security', weight: 2, mustHave: true, minScore: 4, sort: 6 } },
        { key: 'noise', updatedAt: null, criterion: { key: 'noise', weight: 0, mustHave: false, minScore: 3, sort: 5, archived: true } },
        { key: 'c_1a2b3c4d', updatedAt: null, criterion: { key: 'c_1a2b3c4d', label: 'Pets allowed', weight: 2, mustHave: false, minScore: 3, sort: 10 } },
      ],
      [],
    );
    const failing: HouseDto = { ...RENT, id: 'f', label: 'Missed', rating: 5, checklist: { security: 2, water: 5 } };
    const passing: HouseDto = { ...SALE, id: 'p', label: 'Met', rating: 3, checklist: { security: 5, c_1a2b3c4d: 4 } };
    const unchecked: HouseDto = { ...SALE, id: 'u', label: 'Unchecked', rating: 1, checklist: { water: 1 } };

    async function pageRows(list: HouseDto[]) {
      const fixture = await render([], 'FT', list, scoring);
      return (fixture.componentInstance as unknown as { rows: () => Row[]; candidates: () => { house: HouseDto }[] });
    }

    it('orders the picker by the ranking: a house that misses a must-have comes after the others, whatever its score', async () => {
      const page = await pageRows([failing, unchecked, passing]);
      // Both have status NEW or SHORTLISTED: RENT is SHORTLISTED (first), then the ranking among the rest is by score.
      expect(page.candidates().map((c) => c.house.id)).toEqual(['f', 'p', 'u']);
      const both = await pageRows([{ ...failing, status: 'NEW' }, unchecked, passing]);
      expect(both.candidates().map((c) => c.house.id)).toEqual(['p', 'u', 'f']);
    });

    it('has a Must-haves row: Missed, Not checked yet or All met, with the names of what was missed', async () => {
      const page = await pageRows([failing, passing, unchecked]);
      const row = page.rows().find((r) => r.id === 'mustHaves')!;
      expect(row.label).toBe('Must-haves');
      expect(row.cells.map((c) => c.text)).toEqual(['✕ Missed: Safety and security', '✓ All met', 'Not checked yet']);
    });

    it('has no Must-haves row when no criterion is a must-have', async () => {
      const fixture = await render();
      const rows = (fixture.componentInstance as unknown as { rows: () => Row[] }).rows();
      expect(rows.some((r) => r.id === 'mustHaves')).toBe(false);
    });

    it('lists the person\'s criteria as rows: a custom one by its label, an archived one left out', async () => {
      const page = await pageRows([failing, passing]);
      const checks = page.rows().filter((r) => r.id.startsWith('check-'));
      expect(checks.map((r) => r.label)).toContain('Pets allowed');
      expect(checks.map((r) => r.label)).not.toContain('Quiet (low noise)');
      expect(checks.find((r) => r.id === 'check-c_1a2b3c4d')!.cells.map((c) => c.text)).toEqual(['–', '4']);
      expect(page.rows()[0].cells.map((c) => c.text)).toEqual(['4.3', '3.8']);
    });
  });
});
