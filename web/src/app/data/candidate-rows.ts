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

import { candidateOf, houseOf } from '../core/candidate';
import type { CandidateDto } from '../core/candidate';
import type { HouseDto } from '../core/models';
import type { BatchGuard, LocalDb, StoreKey, StoreName, WriteOp } from './local-db';
import { tryHouseFromDto } from './records';
import type { HouseRecord } from './records';

/**
 * The `houses` store holds candidates (IndexedDB version 4, kinds step 3, S4b-BL-206): `kind`, `money` and `extras`
 * as first-class fields, as the server's `/api/candidates` and the backup format hold them (docs/03 ADR-36 §18.1).
 * The rest of the app still reads and writes a {@link HouseRecord}, so this wrapper turns one into the other at the
 * store, in both directions, and nothing above it changes: {@link LocalStore} and the stores beside it see houses,
 * the database sees candidates.
 */

/** A row of the `houses` store: the candidate plus the local `dirty` flag. */
export type CandidateRow = Omit<CandidateDto, 'distanceMeters'> & { dirty: boolean };

/** The stored row of a house: its candidate, the `dirty` flag kept, no search distance. */
export function storedRowOf(house: HouseRecord): CandidateRow {
  const { distanceMeters: _d, ...row } = candidateOf(house as HouseDto) as CandidateDto & { distanceMeters?: number };
  return { ...row, dirty: house.dirty === true };
}

/**
 * The house of a stored row. A row without `kind` is one a version-3 database wrote (the upgrade rewrites every one,
 * `upgradeLocalDb`), so it is read as the house it already is: an interrupted or partial upgrade loses nothing.
 */
export function houseOfRow(row: unknown): HouseRecord {
  const r = row as Record<string, unknown>;
  if (typeof r['kind'] !== 'string') return row as HouseRecord;
  // Read back with every absent value as `null`, as a house was stored before (the same coercion as any stored row).
  const house = houseOf(row as CandidateDto);
  return tryHouseFromDto(house, r['dirty'] === true) ?? ({ ...house, dirty: r['dirty'] === true } as HouseRecord);
}

/** The row of a version-3 database's house, as version 4 stores it (the upgrade step): kept as it is when it is one already. */
export function upgradeHouseRow(row: unknown): unknown {
  if (typeof row !== 'object' || row === null) return row;
  const r = row as Record<string, unknown>;
  return typeof r['kind'] === 'string' || 'money' in r || 'extras' in r ? row : storedRowOf(r as unknown as HouseRecord);
}

/** `db` with the `houses` store's rows turned into candidates on the way in and into houses on the way out. */
export function withCandidateRows(db: LocalDb): LocalDb {
  return new CandidateRowsDb(db);
}

class CandidateRowsDb implements LocalDb {
  constructor(private readonly db: LocalDb) {}

  get kind(): LocalDb['kind'] {
    return this.db.kind;
  }

  async get<T>(store: StoreName, key: StoreKey): Promise<T | undefined> {
    const row = await this.db.get<unknown>(store, key);
    return (row === undefined || store !== 'houses' ? row : houseOfRow(row)) as T | undefined;
  }

  async getAll<T>(store: StoreName): Promise<T[]> {
    return this.out<T>(store, await this.db.getAll<unknown>(store));
  }

  async getMany<T>(store: StoreName, keys: readonly StoreKey[]): Promise<T[]> {
    return this.out<T>(store, await this.db.getMany<unknown>(store, keys));
  }

  async getAllByIndex<T>(store: StoreName, index: string, value: string | number): Promise<T[]> {
    return this.out<T>(store, await this.db.getAllByIndex<unknown>(store, index, value));
  }

  keys(store: StoreName): Promise<StoreKey[]> {
    return this.db.keys(store);
  }

  count(store: StoreName, index?: string, value?: string | number): Promise<number> {
    return this.db.count(store, index, value);
  }

  countIndexed(store: StoreName, index: string): Promise<number> {
    return this.db.countIndexed(store, index);
  }

  put<T>(store: StoreName, value: T): Promise<void> {
    return this.db.put(store, this.in(store, value));
  }

  putAll<T>(store: StoreName, values: readonly T[]): Promise<void> {
    return this.db.putAll(store, values.map((v) => this.in(store, v)));
  }

  delete(store: StoreName, key: StoreKey): Promise<void> {
    return this.db.delete(store, key);
  }

  deleteAll(store: StoreName, keys: readonly StoreKey[]): Promise<void> {
    return this.db.deleteAll(store, keys);
  }

  batch(ops: readonly WriteOp[], guards?: readonly BatchGuard[]): Promise<void> {
    return this.db.batch(
      ops.map((op) => (op.op === 'put' && op.store === 'houses' ? { ...op, value: this.in(op.store, op.value) } : op)),
      guards,
    );
  }

  clear(store?: StoreName): Promise<void> {
    return this.db.clear(store);
  }

  close(): void {
    this.db.close();
  }

  private in(store: StoreName, value: unknown): unknown {
    if (store !== 'houses' || typeof value !== 'object' || value === null) return value;
    // A house never has `money` or `extras`: a row that does is a candidate already and is stored as it is.
    return 'money' in value || 'extras' in value ? value : storedRowOf(value as HouseRecord);
  }

  private out<T>(store: StoreName, rows: unknown[]): T[] {
    return (store === 'houses' ? rows.map(houseOfRow) : rows) as T[];
  }
}
