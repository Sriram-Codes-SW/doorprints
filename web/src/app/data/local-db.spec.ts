import { describe, expect, it } from 'vitest';
import { MemoryDb, STORE_KEY_PATH, STORE_NAMES, openLocalDb } from './local-db';
import { houseFromDto, isRecordId, isoNow, millis, tryHouseFromDto, tryVisitFromDto, visitFromDto } from './records';
import { LocalDataError } from '../core/local-error';
import type { HouseDto, VisitDto } from '../core/models';

describe('MemoryDb', () => {
  it('stores and reads back by the store’s key path', async () => {
    const db = new MemoryDb();
    await db.put('houses', { id: 'a', label: 'One' });
    await db.put('settings', { key: 'cursor.house', value: '7' });
    expect(await db.get<{ label: string }>('houses', 'a')).toEqual({ id: 'a', label: 'One' });
    expect(await db.get<{ value: string }>('settings', 'cursor.house')).toEqual({
      key: 'cursor.house',
      value: '7',
    });
  });

  it('copies on write, so a later edit of the caller’s object does not change the store', async () => {
    const db = new MemoryDb();
    const row = { id: 'a', label: 'One' };
    await db.put('houses', row);
    row.label = 'Changed';
    expect((await db.get<{ label: string }>('houses', 'a'))?.label).toBe('One');
  });

  it('replaces a row with the same key and deletes by key', async () => {
    const db = new MemoryDb();
    await db.putAll('visits', [
      { id: 'v1', lat: 1 },
      { id: 'v2', lat: 2 },
    ]);
    await db.put('visits', { id: 'v1', lat: 9 });
    expect(await db.getAll('visits')).toHaveLength(2);
    await db.delete('visits', 'v2');
    expect(await db.getAll('visits')).toHaveLength(1);
    expect((await db.get<{ lat: number }>('visits', 'v1'))?.lat).toBe(9);
  });

  it('clears one store or all of them', async () => {
    const db = new MemoryDb();
    await db.put('houses', { id: 'a' });
    await db.put('settings', { key: 'k', value: 'v' });
    await db.clear('houses');
    expect(await db.getAll('houses')).toHaveLength(0);
    expect(await db.getAll('settings')).toHaveLength(1);
    await db.clear();
    expect(await db.getAll('settings')).toHaveLength(0);
  });

  it('returns undefined for a key it does not have', async () => {
    expect(await new MemoryDb().get('houses', 'missing')).toBeUndefined();
  });
});

describe('openLocalDb', () => {
  it('falls back to memory and reports why when IndexedDB is unavailable', async () => {
    // The test environment (jsdom) has no IndexedDB, which is exactly the private-browsing case.
    const opened = await openLocalDb();
    expect(opened.db.kind).toBe('memory');
    expect(opened.problem).toBe('unavailable');
  });

  it('knows a key path for every store', () => {
    for (const name of STORE_NAMES) expect(typeof STORE_KEY_PATH[name]).toBe('string');
  });
});

describe('records', () => {
  const wire: HouseDto = {
    id: 'h1',
    label: 'One',
    lat: 13,
    lon: 80,
    status: 'SHORTLISTED',
    checklist: { water: 4, bad: Number.NaN as unknown as number },
    deleted: false,
    syncVersion: 3,
  };

  it('keeps only sane values from the wire', () => {
    const record = houseFromDto(wire);
    expect(record.checklist).toEqual({ water: 4 });
    expect(record.status).toBe('SHORTLISTED');
    expect(record.address).toBeNull();
    expect(record.dirty).toBe(false);
  });

  it('falls back to NEW for an unknown status', () => {
    const record = houseFromDto({ ...wire, status: 'WHATEVER' as HouseDto['status'] });
    expect(record.status).toBe('NEW');
  });

  it('sorts checklist keys, so the same scores always serialise the same way', () => {
    const record = houseFromDto({ ...wire, checklist: { zinc: 1, alpha: 2 } });
    expect(Object.keys(record.checklist)).toEqual(['alpha', 'zinc']);
  });

  it('normalises visits and defaults an unknown source to MANUAL', () => {
    const visit: VisitDto = {
      id: 'v1',
      lat: 1,
      lon: 2,
      arrivedAt: '2026-09-05T11:00:00.000Z',
      source: 'ROBOT' as VisitDto['source'],
      deleted: false,
      syncVersion: 0,
    };
    expect(visitFromDto(visit).source).toBe('MANUAL');
    expect(visitFromDto(visit, true).dirty).toBe(true);
  });

  it('parses timestamps to epoch milliseconds and survives rubbish', () => {
    expect(millis('1970-01-01T00:00:01.000Z')).toBe(1000);
    expect(millis('nonsense')).toBe(0);
    expect(millis(null)).toBe(0);
    expect(isoNow(1000)).toBe('1970-01-01T00:00:01.000Z');
  });

  /**
   * The id is the one field that may not be coerced: `String(undefined)` is the literal id "undefined", so a
   * malformed row would become a real house on the map and two of them would collide on that key. The server
   * refuses a blank id (`BackupValidation.checkData`) and S4-04's import reuses these mappers, so both the sync
   * pull and the importer are held to it here.
   */
  it('accepts only a non-empty string id', () => {
    expect(isRecordId('h1')).toBe(true);
    for (const bad of [undefined, null, '', '   ', 5, {}, []]) expect(isRecordId(bad), JSON.stringify(bad)).toBe(false);
  });

  it('returns null for an untrusted row with no usable id, rather than inventing one', () => {
    expect(tryHouseFromDto({ ...wire, id: undefined as unknown as string })).toBeNull();
    expect(tryHouseFromDto({ ...wire, id: '  ' })).toBeNull();
    expect(tryHouseFromDto({ ...wire, id: 7 as unknown as string })).toBeNull();
    expect(tryHouseFromDto(null)).toBeNull();
    expect(tryHouseFromDto(wire)?.id).toBe('h1');
    expect(tryVisitFromDto(null)).toBeNull();
  });

  it('throws a translated error from the strict mapper, which local writes use', () => {
    let thrown: unknown = null;
    try {
      houseFromDto({ ...wire, id: '' });
    } catch (err: unknown) {
      thrown = err;
    }
    expect(thrown).toBeInstanceOf(LocalDataError);
    expect((thrown as LocalDataError).key).toBe('error.badRecord');
  });
});
