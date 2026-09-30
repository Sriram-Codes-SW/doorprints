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

import { Injectable, inject } from '@angular/core';
import { Observable, defer, from, map } from 'rxjs';
import { uuid } from './models';
import type { HouseDto, StatsDto, VisitDto } from './models';
import { LocalStore } from '../data/local-store.service';
import { SyncService } from '../data/sync.service';
import { StorageService } from '../data/storage.service';
import { houseToDto, visitToDto } from '../data/records';
import { LocalDataError } from './local-error';
import type { Broker, BrokerRow } from '../shared/broker';

/**
 * What the screens talk to (S4-01). The method names and shapes are the ones `HouseApiService` had, so the pages
 * did not have to change: only the store behind them did. Everything is answered from IndexedDB, works with no
 * account and no server, and never fails because the network is down.
 *
 * `HouseApiService` is still there, but only the sync engine and the Connect page use it now.
 */
@Injectable({ providedIn: 'root' })
export class LocalDataService {
  private readonly store = inject(LocalStore);
  private readonly sync = inject(SyncService);
  private readonly storage = inject(StorageService);

  /**
   * Bumped after **every** write to this browser's store: an edit on this screen, a sync pull, the 30-minute
   * background sync, the `online` sync, or the first-run "download my houses to this browser".
   *
   * A screen that shows stored data reads this in an `effect` and re-reads. Without that the pages load once and
   * never again, so a pull writes N houses into IndexedDB while the map behind the banner stays empty until the
   * user happens to reload the page. Exposed here rather than from `LocalStore` so the pages keep the one token
   * they already inject.
   */
  readonly revision = this.store.revision;
  /**
   * {@link revision} coalesced for screens (LocalStore.settled): it moves once a burst of writes pauses, so a
   * sync pull that writes 200 rows makes a list re-read a handful of times rather than 200.
   */
  readonly settled = this.store.settled;

  stats(): Observable<StatsDto> {
    return defer(() => from(this.store.stats()));
  }

  houses(): Observable<HouseDto[]> {
    return defer(() => from(this.store.liveHouses().then((list) => list.map(houseToDto))));
  }

  house(id: string): Observable<HouseDto> {
    return defer(() =>
      from(this.store.getHouse(id)).pipe(
        map((house) => {
          if (!house) throw notFound();
          return houseToDto(house);
        }),
      ),
    );
  }

  saveHouse(house: HouseDto): Observable<HouseDto> {
    return defer(() =>
      from(
        this.store.saveHouse(house).then((saved) => {
          this.sync.syncSoon();
          // docs/11 §5.10: ask the browser to keep the data once the user has saved their first house.
          void this.storage.requestPersistence();
          return houseToDto(saved);
        }),
      ),
    );
  }

  deleteHouse(id: string): Observable<void> {
    return defer(() =>
      from(
        this.store.deleteHouse(id).then(() => {
          this.sync.syncSoon();
        }),
      ),
    );
  }

  /** The live brokers, oldest edit first (slice 1b). */
  brokers(): Observable<BrokerRow[]> {
    return defer(() => from(this.store.brokers()));
  }

  saveBroker(id: string, broker: Broker): Observable<BrokerRow> {
    return defer(() =>
      from(
        this.store.saveBroker(id, broker).then((saved) => {
          this.sync.syncSoon();
          return saved;
        }),
      ),
    );
  }

  /** Deletes a broker; its houses keep the contact details and lose the link. */
  deleteBroker(id: string): Observable<void> {
    return defer(() =>
      from(
        this.store.deleteBroker(id).then(() => {
          this.sync.syncSoon();
        }),
      ),
    );
  }

  /** The live houses linked to a broker. */
  brokerHouses(id: string): Observable<HouseDto[]> {
    return defer(() => from(this.store.brokerHouses(id).then((list) => list.map(houseToDto))));
  }

  /** Live visits per house id, from one read of this browser's store. */
  visitCounts(): Observable<Map<string, number>> {
    return defer(() => from(this.store.visitCountsByHouse()));
  }

  visits(houseId: string): Observable<VisitDto[]> {
    return defer(() => from(this.store.visitsOf(houseId).then((list) => list.map(visitToDto))));
  }

  saveVisit(visit: VisitDto): Observable<VisitDto> {
    return defer(() =>
      from(
        this.store.saveVisit(visit).then((saved) => {
          this.sync.syncSoon();
          return visitToDto(saved);
        }),
      ),
    );
  }

  deleteVisit(id: string): Observable<void> {
    return defer(() =>
      from(
        this.store.deleteVisit(id).then(() => {
          this.sync.syncSoon();
        }),
      ),
    );
  }

  photoIds(houseId: string): Observable<string[]> {
    return defer(() => from(this.store.photosOf(houseId).then((list) => list.map((p) => p.id))));
  }

  uploadPhoto(houseId: string, file: Blob, id: string = uuid()): Observable<{ id: string }> {
    return defer(() =>
      from(this.store.addPhoto(houseId, file, id)).pipe(
        map((result) => {
          if (!result.ok) throw photoLimit();
          this.sync.syncSoon();
          return { id: result.id };
        }),
      ),
    );
  }

  photo(id: string): Observable<Blob> {
    return defer(() =>
      from(this.store.getPhoto(id)).pipe(
        map((record) => {
          // A photo that only exists on the server (not downloaded yet) reads as missing until the next sync.
          if (!record || record.deleted || !record.blob) throw notFound();
          return record.blob;
        }),
      ),
    );
  }

  deletePhoto(id: string): Observable<void> {
    return defer(() =>
      from(
        this.store.deletePhoto(id).then(() => {
          this.sync.syncSoon();
        }),
      ),
    );
  }
}

function notFound(): LocalDataError {
  return new LocalDataError('error.notFoundLocal');
}

function photoLimit(): LocalDataError {
  return new LocalDataError('error.photoLimit');
}
