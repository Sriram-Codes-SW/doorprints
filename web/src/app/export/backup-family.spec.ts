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
import sampleJson from '../../../../docs/schemas/backup-sample.json';
import updateJson from '../../../../docs/schemas/update-sample.json';
import housesJson from '../../../../docs/schemas/backup-sample-houses.json';
import { checkData } from './backup-check';
import { CURRENT_FORMAT, NEWER_FORMAT, currentManifestOf, currentOf, isCurrentFamily, legacyManifestOf, legacyOf } from './backup-family';
import { tryHouseFromDto } from '../data/records';
import type { HouseDto } from '../core/models';

type Obj = Record<string, unknown>;

/**
 * The backup family of the format reset (docs/03 ADR-36 §18.4 doors 1 and 2; S4b-BL-206, kinds step 3) against the
 * shared sample, which the website, Android and the server all write byte for byte: the new family reads back as the
 * family before and is written again unchanged, and a file of the family before is still read. docs/06 TC-U-203.
 */
describe('the backup family (kinds step 3)', () => {
  const sample = sampleJson as unknown as Obj;

  it('tells the two families apart by their shape: candidates, not houses, whatever the number', () => {
    expect(isCurrentFamily(sample)).toBe(true);
    expect(isCurrentFamily({ format: 'doorprints-backup/1', houses: [] })).toBe(false);
    expect(isCurrentFamily(null)).toBe(false);
  });

  it('reads the sample as the family before and writes it again byte for byte', () => {
    const legacy = legacyOf(sample);
    expect(legacy['format']).toBe('doorprints-backup/2');
    expect('candidates' in legacy || 'search' in legacy).toBe(false);
    const house1 = (legacy['houses'] as Obj[])[0];
    expect([house1['price'], house1['priceType'], house1['bedrooms'], house1['areaSqft'], house1['floor']]).toEqual([32000, 'RENT', 2, 1150, 3]);
    expect(house1['cost']).toEqual({ deposit: 64000, maintenance: 2500, maintenanceIncluded: false, brokerageMonths: 1, lockInMonths: 11, noticeMonths: 2, availableFrom: '2026-10-15', myOffer: 30000, agreedPrice: 31000 });
    expect((legacy['visits'] as Obj[])[0]['houseId']).toBe('11111111-1111-4111-8111-111111111111');
    expect((legacy['viewings'] as Obj[])[1]['houseId']).toBe('11111111-1111-4111-8111-111111111111');
    expect(JSON.stringify(currentOf(legacy))).toBe(JSON.stringify(sample));
  });

  it('is a lossless re-shape: the sample read as the family before is that family\'s sample, byte for byte, and back', () => {
    // `backup-sample-houses.json` is the sample as it was before the reset (a `/2` file of the family before).
    expect(JSON.stringify(legacyOf(sample))).toBe(JSON.stringify(housesJson));
    expect(JSON.stringify(currentOf(housesJson as unknown as Obj))).toBe(JSON.stringify(sample));
    // And an older Doorprints' file is still read, as the family before.
    expect(checkData(housesJson)).toMatchObject({ ok: true });
  });

  it('reads the update sample as a /3 file with its deletion of a house, and writes it again unchanged', () => {
    const legacy = legacyOf(updateJson as unknown as Obj);
    expect(legacy['format']).toBe('doorprints-backup/3');
    expect(legacy['deleted']).toEqual([{ kind: 'house', id: '6f1d1c2e-0000-4000-8000-000000000009', updatedAt: 1780000045000 }]);
    expect(JSON.stringify(currentOf(legacy))).toBe(JSON.stringify(updateJson));
  });

  it('writes a house of the family before with kind after the id, money where the price was and extras before the rooms', () => {
    const legacy = { format: 'doorprints-backup/2', exportedAt: 1, houses: [{ id: 'k1', label: 'Kochi', lat: 9.93, lon: 76.26, status: 'NEW', price: 15000, priceType: 'RENT', bedrooms: 1, notes: 'n', locationSource: 'MAP', cost: { deposit: 45000, myOffer: 14000 }, rooms: [], floor: 2, checklist: {}, createdAt: 1, updatedAt: 2 }], visits: [], photos: [] };
    const c = (currentOf(legacy)['candidates'] as Obj[])[0];
    expect(Object.keys(c)).toEqual(['id', 'kind', 'label', 'lat', 'lon', 'status', 'money', 'notes', 'locationSource', 'extras', 'rooms', 'checklist', 'createdAt', 'updatedAt']);
    expect(c['extras']).toEqual({ bedrooms: 1, floor: 2, deposit: { amount: 45000, cadence: 'once' }, myOffer: { amount: 14000, cadence: 'month' } });
    // A rent not known yet keeps its cadence; a price without a type is monthly; neither writes no money.
    const money = (h: Obj) => (currentOf({ houses: [{ id: 'x', ...h, checklist: {} }] })['candidates'] as Obj[])[0]['money'];
    expect(money({ priceType: 'SALE' })).toEqual({ cadence: 'once' });
    expect(money({ price: 9000 })).toEqual({ amount: 9000, cadence: 'month' });
    expect(money({})).toBeUndefined();
  });

  it('keeps the extras a newer app wrote through a read and a write, after the known ones', () => {
    const file = { ...sample, candidates: [{ ...(sample['candidates'] as Obj[])[1], extras: { zLast: 'a', fromNewerApp: 4, bedrooms: 1 } }] };
    const checked = checkData(file);
    expect(checked.ok).toBe(true);
    const row = checked.ok ? (checked.data.houses[0] as unknown as Obj) : {};
    expect(row['extrasOther']).toEqual({ zLast: 'a', fromNewerApp: 4 });
    expect(tryHouseFromDto(row as unknown as HouseDto)?.extrasOther).toEqual({ zLast: 'a', fromNewerApp: 4 });
    const written = (currentOf(legacyOf(file))['candidates'] as Obj[])[0];
    expect(Object.keys(written['extras'] as Obj)).toEqual(['bedrooms', 'fromNewerApp', 'zLast']);
  });

  it('reads a newer number of the new family as newer, so the reader asks to update the app', () => {
    expect(legacyOf({ ...sample, format: 'doorprints-backup/2' })['format']).toBe(NEWER_FORMAT);
    expect(checkData({ ...sample, format: 'doorprints-backup/2' })).toEqual({ ok: false, problem: 'UNSUPPORTED_VERSION' });
  });

  it('refuses what only the new family can break', () => {
    const one = (c: Obj): unknown => checkData({ format: CURRENT_FORMAT, exportedAt: 1, search: { kind: 'house' }, candidates: [{ id: 'h1', kind: 'house', label: '', lat: 0, lon: 0, status: 'NEW', checklist: {}, createdAt: 1, updatedAt: 1, ...c }], visits: [], photos: [] });
    const broken = { ok: false, problem: 'BROKEN_DATA' };
    expect(one({ kind: undefined })).toEqual(broken);
    expect(one({ money: { amount: 5, cadence: 'week' } })).toEqual(broken);
    expect(one({ money: { amount: -1, cadence: 'month' } })).toEqual(broken);
    expect(one({ money: 25000 })).toEqual(broken);
    expect(one({ extras: [] })).toEqual(broken);
    expect(one({ extras: { maintenance: { amount: 2500, cadence: 'once' } } })).toEqual(broken);
    expect(one({ extras: { myOffer: { cadence: 'month' } } })).toEqual(broken);
    expect(one({ extras: { Bad: 1 } })).toEqual(broken);
    expect(one({ extras: { note: 'a'.repeat(2001) } })).toEqual(broken);
    expect(one({ extras: Object.fromEntries(Array.from({ length: 31 }, (_, i) => [`x${i}`, i])) })).toEqual(broken);
    expect(one({ extras: Object.fromEntries(Array.from({ length: 30 }, (_, i) => [`x${i}`, i])) })).toMatchObject({ ok: true });
    // Another kind may have another cadence; the agreed price follows the money, whatever its cadence.
    expect(one({ kind: 'school', money: { amount: 90000, cadence: 'year' }, extras: { agreedPrice: { amount: 1, cadence: 'year' } } })).toMatchObject({ ok: true });
    expect(checkData({ format: CURRENT_FORMAT, exportedAt: 1, candidates: [], houses: [], visits: [], photos: [] })).toEqual(broken);
  });

  it('counts candidates in the manifest of the new family and reads either count as houses', () => {
    const written = currentManifestOf({ format: 'doorprints-backup/2', counts: { houses: 3, visits: 1, photos: 0 } });
    expect(written).toEqual({ format: CURRENT_FORMAT, counts: { candidates: 3, visits: 1, photos: 0 } });
    expect(legacyManifestOf(written as unknown as Obj)['counts']).toEqual({ houses: 3, visits: 1, photos: 0 });
    const before = { format: 'doorprints-backup/1', counts: { houses: 2, visits: 0, photos: 0 } };
    expect(legacyManifestOf(before)).toBe(before);
  });
});
