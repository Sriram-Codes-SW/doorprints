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
import type { Criterion, CriterionRow, Scoring, Weight } from '../shared/scoring';
import type { Question, QuestionCategory, QuestionRow, QuestionScope } from '../shared/question';
import type { Viewing, ViewingRow } from '../shared/viewing';

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

  /**
   * The effective scoring (slice 2): the defaults merged with the criterion records and the rating share. Screens
   * read it again after `settled` moves, like every stored value.
   */
  scoring(): Observable<Scoring> {
    return defer(() => from(this.store.scoring()));
  }

  /** The criterion records that exist (what differs from the defaults), for the Criteria screen and the copies. */
  criterionRows(): Observable<CriterionRow[]> {
    return defer(() => from(this.store.criterionRows()));
  }

  /** Saves criteria (each writes a record only when it differs from the default); see `LocalStore.saveCriterion`. */
  saveCriteria(list: readonly Criterion[]): Observable<void> {
    return this.writing(() => this.store.saveCriteria(list));
  }

  addCriterion(label: string, weight: Weight = 2): Observable<Criterion> {
    return defer(() =>
      from(
        this.store.addCriterion(label, weight).then((saved) => {
          this.sync.syncSoon();
          return saved;
        }),
      ),
    );
  }

  /** Deletes a custom criterion that no house has scored. */
  deleteCriterion(key: string): Observable<void> {
    return this.writing(() => this.store.deleteCriterion(key));
  }

  /** Stores the share of the star rating in the overall score, 0..1. */
  setRatingShare(share: number): Observable<void> {
    return this.writing(() => this.store.setRatingShare(share));
  }

  /** Deletes every criterion and preference record: back to the defaults. */
  resetCriteria(): Observable<void> {
    return this.writing(() => this.store.resetCriteria());
  }

  /** True when a live house has a score under this criterion key (a custom one can then only be archived). */
  criterionInUse(key: string): Observable<boolean> {
    return defer(() => from(this.store.criterionInUse(key)));
  }

  /** The question bank, archived questions included, by `sort` then id (slice 3a). */
  questions(): Observable<Question[]> {
    return defer(() => from(this.store.questions()));
  }

  /** The question records that exist, for the copies. */
  questionRows(): Observable<QuestionRow[]> {
    return defer(() => from(this.store.questionRows()));
  }

  /** Saves questions (an edit, an archive, a move); only these records are written. */
  saveQuestions(list: readonly Question[]): Observable<void> {
    return this.writing(() => this.store.saveQuestions(list));
  }

  addQuestion(text: string, category: QuestionCategory = 'OTHER', appliesTo: QuestionScope = 'BOTH'): Observable<Question> {
    return defer(() =>
      from(
        this.store.addQuestion(text, category, appliesTo).then((saved) => {
          this.sync.syncSoon();
          return saved;
        }),
      ),
    );
  }

  /** Deletes a question, a seeded one too; it stays deleted until the bank is reset. */
  deleteQuestion(id: string): Observable<void> {
    return this.writing(() => this.store.deleteQuestion(id));
  }

  /** *Reset to defaults*: the standard questions come back in [language]; the person's own stay. */
  resetQuestions(language: string): Observable<void> {
    return this.writing(() => this.store.resetQuestions(language).then(() => undefined));
  }

  /** Every live viewing, by start then id (slice 3b-1). */
  viewings(): Observable<Viewing[]> {
    return defer(() => from(this.store.viewings()));
  }

  /** The viewing records that exist, for the copies. */
  viewingRows(): Observable<ViewingRow[]> {
    return defer(() => from(this.store.viewingRows()));
  }

  viewingsOf(houseId: string): Observable<Viewing[]> {
    return defer(() => from(this.store.viewingsOf(houseId)));
  }

  /** The earliest PLANNED viewing of the house at or after now, or null. */
  nextViewing(houseId: string, nowMs: number = Date.now()): Observable<Viewing | null> {
    return defer(() => from(this.store.nextViewing(houseId, nowMs)));
  }

  /** A fresh `v_` id that no viewing record, a deleted one included, has. */
  newViewingId(): Observable<string> {
    return defer(() => from(this.store.newViewingId()));
  }

  saveViewing(viewing: Viewing): Observable<Viewing> {
    return defer(() =>
      from(
        this.store.saveViewing(viewing).then((saved) => {
          this.sync.syncSoon();
          return saved;
        }),
      ),
    );
  }

  deleteViewing(id: string): Observable<void> {
    return this.writing(() => this.store.deleteViewing(id));
  }

  /** *It happened* / *Mark viewing done*: DONE, with the visit that shows it happened when there is one. */
  markViewingDone(id: string, visitId?: string | null): Observable<Viewing> {
    return defer(() =>
      from(
        this.store.markViewingDone(id, visitId).then((saved) => {
          this.sync.syncSoon();
          return saved;
        }),
      ),
    );
  }

  private writing(run: () => Promise<void>): Observable<void> {
    return defer(() => from(run().then(() => this.sync.syncSoon())));
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
