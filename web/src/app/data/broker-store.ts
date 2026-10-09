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

// The brokers of the browser's store (docs/11 5.25, slice 1b): records of type `broker`, read as typed rows, saved and
// deleted with the copies of the contact that every linked house keeps, the link a saved house gets, and the one-off
// move of the contacts of older houses into brokers. It is separate from LocalStore (S4b-BL-168) because the broker
// pages, the compare and map pages, the house page, the export and the store's own house save each reach the brokers;
// it keeps no rows of its own and goes through RecordStore. The only other kind it writes is the houses (the contact
// copies and the link).
import { LocalDataError } from '../core/local-error';
import { uuid } from '../core/models';
import type { HouseDto } from '../core/models';
import type { LocalDb } from './local-db';
import { SETTING_KEYS, isoNow, millis, recordFromDto, sortByCreated } from './records';
import type { HouseRecord, RecordRecord, SettingRecord } from './records';
import { sortRecords } from './record-store';
import type { RecordStore } from './record-store';
import { BROKER_TYPE, MAX_BROKER_NAME, MAX_BROKER_PHONE, brokerFromPayload, brokerToPayload, phoneKey, samePhone } from '../shared/broker';
import type { Broker, BrokerRow } from '../shared/broker';

export class BrokerStore {
  /**
   * @param records the record rows every broker goes through
   * @param database the opened database, once the store is ready (the houses are read and written through it)
   * @param rawDatabase the opened database without waiting for the contacts migration, which this class runs
   * @param changed called after a write to the houses, so views and the sync engine see a new revision
   */
  constructor(
    private readonly records: RecordStore,
    private readonly database: () => Promise<LocalDb>,
    private readonly rawDatabase: () => Promise<LocalDb>,
    private readonly changed: () => void,
  ) {}

  /** The live brokers, oldest edit first; a row whose payload is not a broker (a blank name) is skipped. */
  async rows(): Promise<BrokerRow[]> {
    return brokerRows(await this.records.ofType(BROKER_TYPE));
  }

  /**
   * Saves a broker (create or update) and rewrites the name and phone copies on every live house linked to it, so the
   * exports, the search and an old app keep showing the contact.
   *
   * @throws LocalDataError when the name is blank or too long.
   */
  async save(id: string, broker: Broker, now: number = Date.now()): Promise<BrokerRow> {
    const clean = brokerFromPayload({ ...broker });
    if (!clean) throw new LocalDataError('error.badRecord');
    const record = await this.records.save(BROKER_TYPE, id, brokerToPayload(clean), now);
    const db = await this.database();
    for (const house of await db.getAll<HouseRecord>('houses')) {
      if (house.deleted || house.brokerId !== id) continue;
      if (house.contactName === clean.name && house.contactPhone === (clean.phone ?? null)) continue;
      await db.put('houses', { ...house, contactName: clean.name, contactPhone: clean.phone ?? null, dirty: true, updatedAt: isoNow(now) });
    }
    this.changed();
    return { id, updatedAt: record.updatedAt ?? null, broker: clean };
  }

  /** Deletes a broker (a tombstone); its houses lose the link and keep the contact details they hold. */
  async delete(id: string, now: number = Date.now()): Promise<void> {
    await this.records.delete(BROKER_TYPE, id, now);
    const db = await this.database();
    for (const house of await db.getAll<HouseRecord>('houses')) {
      if (house.brokerId === id) await db.put('houses', { ...house, brokerId: null, dirty: true, updatedAt: isoNow(now) });
    }
    this.changed();
  }

  /** The live houses linked to a broker, in list order, for the broker's page. */
  async housesOf(id: string): Promise<HouseRecord[]> {
    const db = await this.database();
    return sortByCreated((await db.getAll<HouseRecord>('houses')).filter((h) => !h.deleted && h.brokerId === id));
  }

  /**
   * What the house save does about the broker. A house linked to a broker that exists gets that broker's name and
   * phone as its contact. A house with a phone and no link is linked to the broker with the same number, or to a new
   * one made from it; a blank phone never makes a broker. An id that names no broker is left alone.
   */
  async link(house: HouseDto, now: number): Promise<HouseDto> {
    if (house.brokerId) {
      const known = (await this.rows()).find((row) => row.id === house.brokerId);
      return known ? linked(house, known) : house;
    }
    const phone = (house.contactPhone ?? '').trim();
    if (phone === '') return house;
    const found = (await this.rows()).find((row) => samePhone(row.broker.phone, phone));
    if (found) return linked(house, found);
    const name = (house.contactName ?? '').trim() || phone;
    const broker: Broker = { name: name.slice(0, MAX_BROKER_NAME), phone: phone.slice(0, MAX_BROKER_PHONE) };
    return linked(house, await this.save(uuid(), broker, now));
  }

  /**
   * The one-off migration (slice 1b): each live house with a phone and no broker is linked to a broker made from its
   * contact, one broker per distinct number (the last ten digits; "+91 98400 11111", "098400-11111" and
   * "9840011111" are one). A broker that already has the number is reused. The flag `brokers.migrated` is set at the
   * end, so a person who later unlinks a house on purpose is never re-linked, and a run that stopped half way
   * resumes. Works on the raw database because the store's `db()` waits for it; does nothing once the flag is set and
   * returns the number of houses it linked.
   */
  async migrate(now: number = Date.now()): Promise<number> {
    const db = await this.rawDatabase();
    if (await db.get<SettingRecord>('settings', SETTING_KEYS.brokersMigrated)) return 0;
    const houses = sortByCreated(
      (await db.getAll<HouseRecord>('houses')).filter((h) => !h.deleted && !h.brokerId && (h.contactPhone ?? '').trim() !== ''),
    );
    const groups = new Map<string, HouseRecord[]>();
    for (const house of houses) {
      const phone = (house.contactPhone ?? '').trim();
      // A number too short to compare stands only for itself.
      const key = phoneKey(phone) ?? `raw:${phone}`;
      groups.set(key, [...(groups.get(key) ?? []), house]);
    }
    const existing = brokerRows(
      (await db.getAllByIndex<RecordRecord>('records', 'type', BROKER_TYPE)).filter((r) => !r.deleted),
    );
    let linkedHouses = 0;
    for (const [key, members] of groups) {
      // The newest edit names the broker.
      const latest = members.reduce((a, b) => (millis(b.updatedAt) >= millis(a.updatedAt) ? b : a));
      let row = existing.find((r) => (phoneKey(r.broker.phone) ?? `raw:${(r.broker.phone ?? '').trim()}`) === key);
      if (!row) {
        const phone = (latest.contactPhone ?? '').trim();
        const name = (latest.contactName ?? '').trim() || phone;
        const broker: Broker = { name: name.slice(0, MAX_BROKER_NAME), phone: phone.slice(0, MAX_BROKER_PHONE) };
        const record = recordFromDto(
          { type: BROKER_TYPE, id: uuid(), payload: brokerToPayload(broker), updatedAt: isoNow(now), deleted: false, syncVersion: 0 },
          true,
        );
        await db.put('records', record);
        row = { id: record.id, updatedAt: record.updatedAt ?? null, broker };
      }
      for (const house of members) {
        await db.put('houses', { ...house, brokerId: row.id, dirty: true, updatedAt: isoNow(now) });
        linkedHouses += 1;
      }
    }
    await db.put<SettingRecord>('settings', { key: SETTING_KEYS.brokersMigrated, value: '1' });
    if (linkedHouses > 0) this.changed();
    return linkedHouses;
  }
}

/** The brokers among some `broker` records, as rows; a payload that is not a broker is skipped as untrusted. */
function brokerRows(rows: readonly RecordRecord[]): BrokerRow[] {
  const out: BrokerRow[] = [];
  for (const row of sortRecords(rows)) {
    const broker = brokerFromPayload(row.payload);
    if (broker) out.push({ id: row.id, updatedAt: row.updatedAt ?? null, broker });
  }
  return out;
}

/** The house linked to a broker: its id, and the broker's name and phone as the contact copies. */
function linked(house: HouseDto, row: BrokerRow): HouseDto {
  return { ...house, brokerId: row.id, contactName: row.broker.name, contactPhone: row.broker.phone ?? null };
}
