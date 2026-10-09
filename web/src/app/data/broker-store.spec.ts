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

import { beforeEach, describe, expect, it } from 'vitest';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS } from './records';
import type { HouseDto } from '../core/models';

const T1 = Date.parse('2026-09-01T00:00:00.000Z');
const T2 = Date.parse('2026-09-02T00:00:00.000Z');
const T3 = Date.parse('2026-09-03T00:00:00.000Z');

const house = (id: string, over: Partial<HouseDto> = {}): HouseDto => ({
  id,
  label: `House ${id}`,
  lat: 13,
  lon: 80,
  status: 'NEW',
  checklist: {},
  deleted: false,
  syncVersion: 0,
  ...over,
});

const RAVI = { contactName: 'Ravi Kumar', contactPhone: '+91 98400 11111' };

/**
 * The brokers' rows, saves and links, and the one-off move of the contacts into brokers, in the detail that
 * `local-store.spec.ts` (the flows through `saveHouse`) leaves out: the rows with their edit times, what a save leaves
 * alone, the limits of a made broker, the numbers too short to compare, and when the migration bumps the revision.
 */
describe('brokers (LocalStore.brokers)', () => {
  let store: LocalStore;

  beforeEach(async () => {
    store = new LocalStore();
    await store.ready();
  });

  describe('rows and saves', () => {
    it('gives each row its record id, its edit time and the typed broker, oldest edit first', async () => {
      await store.saveBroker('b2', { name: 'Later' }, T2);
      await store.saveBroker('b1', { name: 'Earlier', agency: 'Adyar Homes' }, T1);
      expect(await store.brokers()).toEqual([
        { id: 'b1', updatedAt: '2026-09-01T00:00:00.000Z', broker: { name: 'Earlier', agency: 'Adyar Homes' } },
        { id: 'b2', updatedAt: '2026-09-02T00:00:00.000Z', broker: { name: 'Later' } },
      ]);
    });

    it('returns the saved row with the cleaned broker and stores only the set keys', async () => {
      const saved = await store.saveBroker('b1', { name: 'A', phone: '  ', agency: '', rating: 4.4 }, T1);
      expect(saved).toEqual({ id: 'b1', updatedAt: '2026-09-01T00:00:00.000Z', broker: { name: 'A', rating: 4 } });
      expect((await store.records.get('broker', 'b1'))?.payload).toEqual({ name: 'A', rating: 4 });
    });

    it('bumps the revision on a save and on a delete', async () => {
      const start = store.revision();
      await store.saveBroker('b1', { name: 'A' }, T1);
      const afterSave = store.revision();
      expect(afterSave).toBeGreaterThan(start);
      await store.deleteBroker('b1', T2);
      expect(store.revision()).toBeGreaterThan(afterSave);
    });

    it('leaves a linked house alone when its copies already match the saved broker', async () => {
      const h1 = await store.saveHouse(house('h1', RAVI), T1);
      await store.markHouseClean('h1', h1.updatedAt);
      await store.saveBroker(h1.brokerId!, { name: 'Ravi Kumar', phone: '+91 98400 11111', agency: 'New agency' }, T2);
      const after = (await store.getHouse('h1'))!;
      expect(after.dirty).toBe(false);
      expect(after.updatedAt).toBe('2026-09-01T00:00:00.000Z');
    });

    it('rewrites the name alone when the phone already matches, and the phone alone when the name does', async () => {
      const h1 = await store.saveHouse(house('h1', RAVI), T1);
      await store.saveBroker(h1.brokerId!, { name: 'Renamed', phone: '+91 98400 11111' }, T2);
      expect((await store.getHouse('h1'))?.contactName).toBe('Renamed');
      expect((await store.getHouse('h1'))?.dirty).toBe(true);
      await store.markHouseClean('h1', (await store.getHouse('h1'))!.updatedAt);
      await store.saveBroker(h1.brokerId!, { name: 'Renamed', phone: '5551234567' }, T3);
      expect((await store.getHouse('h1'))?.contactPhone).toBe('5551234567');
      expect((await store.getHouse('h1'))?.updatedAt).toBe('2026-09-03T00:00:00.000Z');
    });

    it('does not rewrite the copies on a deleted house', async () => {
      const h1 = await store.saveHouse(house('h1', RAVI), T1);
      await store.deleteHouse('h1', T1);
      await store.saveBroker(h1.brokerId!, { name: 'Renamed' }, T2);
      const row = (await store.allHouses()).find((h) => h.id === 'h1')!;
      expect(row.contactName).toBe('Ravi Kumar');
      expect(row.deleted).toBe(true);
    });

    it('unlinks only the houses of the deleted broker, and a deleted house as well', async () => {
      const h1 = await store.saveHouse(house('h1', RAVI), T1);
      const h2 = await store.saveHouse(house('h2', { contactName: 'Meena', contactPhone: '9000000001' }), T1);
      await store.deleteHouse('h1', T1);
      await store.deleteBroker(h1.brokerId!, T2);
      const all = await store.allHouses();
      expect(all.find((h) => h.id === 'h1')?.brokerId).toBeNull();
      expect(all.find((h) => h.id === 'h2')?.brokerId).toBe(h2.brokerId);
      expect((await store.brokers()).map((b) => b.id)).toEqual([h2.brokerId]);
    });

    it('lists the houses of a broker in the order they were created, and none for an unknown broker', async () => {
      const first = await store.saveHouse(house('h-b', RAVI), T1);
      await store.saveHouse(house('h-a', RAVI), T2);
      expect((await store.brokerHouses(first.brokerId!)).map((h) => h.id)).toEqual(['h-b', 'h-a']);
      expect(await store.brokerHouses('nobody')).toEqual([]);
    });
  });

  describe('the broker a saved house makes', () => {
    it('trims the name and the phone, and stamps the broker with the time of the save', async () => {
      await store.saveHouse(house('h1', { contactName: '  Ravi  ', contactPhone: '  98400 11111 ' }), T1);
      expect(await store.brokers()).toEqual([
        { id: expect.any(String), updatedAt: '2026-09-01T00:00:00.000Z', broker: { name: 'Ravi', phone: '98400 11111' } },
      ]);
    });

    it('cuts a name to 200 characters and a phone to 50', async () => {
      await store.saveHouse(house('h1', { contactName: 'N'.repeat(250), contactPhone: '9'.repeat(60) }), T1);
      const broker = (await store.brokers())[0].broker;
      expect(broker.name).toBe('N'.repeat(200));
      expect(broker.phone).toBe('9'.repeat(50));
    });

    it('names the broker by the phone when the name is only spaces', async () => {
      await store.saveHouse(house('h1', { contactName: '   ', contactPhone: '98400 11111' }), T1);
      expect((await store.brokers())[0].broker.name).toBe('98400 11111');
    });

    it('does not match a number with fewer than six digits to another, so each house makes its own broker', async () => {
      const a = await store.saveHouse(house('h1', { contactName: 'A', contactPhone: '12345' }), T1);
      const b = await store.saveHouse(house('h2', { contactName: 'B', contactPhone: '12345' }), T1);
      expect(await store.brokers()).toHaveLength(2);
      expect(a.brokerId).not.toBe(b.brokerId);
    });

    it('links to the broker with the same last ten digits and keeps the broker unchanged', async () => {
      await store.saveBroker('b1', { name: 'Existing', phone: '+91 98400 11111', agency: 'Adyar Homes' }, T1);
      const saved = await store.saveHouse(house('h1', { contactName: 'Typed', contactPhone: '9840011111' }), T2);
      expect(saved.brokerId).toBe('b1');
      expect(saved.contactName).toBe('Existing');
      expect(await store.brokers()).toEqual([
        { id: 'b1', updatedAt: '2026-09-01T00:00:00.000Z', broker: { name: 'Existing', phone: '+91 98400 11111', agency: 'Adyar Homes' } },
      ]);
    });

    it('gives a linked house a null phone when its broker has none', async () => {
      await store.saveBroker('b1', { name: 'No phone' }, T1);
      const saved = await store.saveHouse(house('h1', { brokerId: 'b1', contactName: 'x', contactPhone: '5551234567' }), T2);
      expect(saved.contactName).toBe('No phone');
      expect(saved.contactPhone).toBeNull();
    });
  });

  describe('the one-off migration of the contacts', () => {
    async function seed(...houses: HouseDto[]): Promise<void> {
      for (const h of houses) await store.putHouseFromServer({ updatedAt: '2026-09-01T00:00:00.000Z', ...h });
      await store.removeSetting(SETTING_KEYS.brokersMigrated);
    }

    it('sets the flag and leaves the revision alone when no house needs a broker', async () => {
      await seed(house('e', { contactName: 'No phone' }));
      const start = store.revision();
      expect(await store.migrateContactsToBrokers()).toBe(0);
      expect(await store.setting(SETTING_KEYS.brokersMigrated)).toBe('1');
      expect(store.revision()).toBe(start);
      expect(await store.brokers()).toEqual([]);
    });

    it('bumps the revision once it has linked a house, and saves the new broker as unsent', async () => {
      await seed(house('a', RAVI));
      const start = store.revision();
      expect(await store.migrateContactsToBrokers()).toBe(1);
      expect(store.revision()).toBeGreaterThan(start);
      const row = (await store.brokers())[0];
      expect((await store.records.get('broker', row.id))?.dirty).toBe(true);
    });

    it('leaves a house that is already linked alone', async () => {
      await seed(house('a', { ...RAVI, brokerId: 'kept' }));
      expect(await store.migrateContactsToBrokers()).toBe(0);
      expect((await store.getHouse('a'))?.brokerId).toBe('kept');
      expect(await store.brokers()).toEqual([]);
    });

    it('groups numbers with fewer than six digits by their trimmed text, not by digits', async () => {
      await seed(
        house('a', { contactName: 'A', contactPhone: '12345' }),
        house('b', { contactName: 'B', contactPhone: ' 12345 ' }),
        house('c', { contactName: 'C', contactPhone: '1-2345' }),
      );
      expect(await store.migrateContactsToBrokers()).toBe(3);
      const linked = async (id: string) => (await store.getHouse(id))?.brokerId;
      expect(await linked('a')).toBe(await linked('b'));
      expect(await linked('a')).not.toBe(await linked('c'));
      expect(await store.brokers()).toHaveLength(2);
    });

    it('reuses an existing broker with a short number by its trimmed text', async () => {
      await store.saveBroker('short', { name: 'Short', phone: ' 12345 ' }, T1);
      await seed(house('a', { contactName: 'A', contactPhone: '12345' }));
      await store.migrateContactsToBrokers();
      expect((await store.getHouse('a'))?.brokerId).toBe('short');
      expect(await store.brokers()).toHaveLength(1);
    });

    it('does not reuse a deleted broker', async () => {
      await store.saveBroker('gone', { name: 'Gone', phone: '9840011111' }, T1);
      await store.deleteBroker('gone', T2);
      await seed(house('a', RAVI));
      await store.migrateContactsToBrokers();
      const brokerId = (await store.getHouse('a'))?.brokerId;
      expect(brokerId).not.toBe('gone');
      expect((await store.brokers()).map((b) => b.id)).toEqual([brokerId]);
    });

    it('names the broker by the later house when two were edited at the same time', async () => {
      await seed(house('a', { contactName: 'First', contactPhone: '9840011111' }), house('b', { contactName: 'Second', contactPhone: '9840011111' }));
      await store.migrateContactsToBrokers();
      expect((await store.brokers())[0].broker.name).toBe('Second');
    });

    it('trims the name and the phone, names a blank name by the phone, and cuts to the limits', async () => {
      await seed(
        house('a', { contactName: '  Ravi ', contactPhone: ' 98400 11111 ' }),
        house('b', { contactName: ' ', contactPhone: '90000 00001' }),
        house('c', { contactName: 'N'.repeat(250), contactPhone: `1${'9'.repeat(59)}` }),
      );
      await store.migrateContactsToBrokers();
      const brokers = (await store.brokers()).map((b) => b.broker);
      expect(brokers).toContainEqual({ name: 'Ravi', phone: '98400 11111' });
      expect(brokers).toContainEqual({ name: '90000 00001', phone: '90000 00001' });
      expect(brokers).toContainEqual({ name: 'N'.repeat(200), phone: `1${'9'.repeat(49)}` });
    });
  });
});
