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

import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LocalStore } from '../data/local-store.service';
import { SyncService } from '../data/sync.service';
import { Announcer } from './announcer.service';
import { LocalDataService } from './local-data.service';
import { SpaceService } from './space.service';
import { binRows, parseNotes, restoredPhoto, withNote } from '../data/house-bin';
import type { RestoreResult } from '../data/house-bin';
import { notConnectedReport } from '../data/drive/photo-restorer';
import type { PhotoRestoreReport, PhotoRestoreRequest } from '../data/drive/photo-restorer';
import { summarize } from '../data/drive/photo-restore-rules';
import type { GonePhoto, PhotoRestoreReason, RestoreConnection } from '../data/drive/photo-restore-rules';
import { isoNow } from '../data/records';
import { SETTING_KEYS, type HouseRecord, type PhotoRecord } from '../data/records';

/** How long *Undo* is offered after a delete, in milliseconds (the phones' snackbar is shown as long: DeletedHouseUndo.kt). */
export const UNDO_MS = 10_000;

/** The delete the person can still take back. */
export interface UndoOffer {
  readonly id: string;
  readonly name: string;
  /** Every id the delete took, when it was *Delete all in this space* (absent for one candidate). */
  readonly ids?: readonly string[];
  /** The words for the kind in the plural ("houses"), for the many-candidates message. */
  readonly kindWords?: string;
}

/** One line of *Recently deleted*: a tombstone of this space, with what is known of its photos. */
export interface BinEntry {
  readonly id: string;
  readonly name: string;
  /** The ISO instant of the delete (the tombstone's `updatedAt`). */
  readonly deletedAt: string;
  /** How many photos the candidate had when it was deleted here (0 when unknown, for instance deleted on another device). */
  readonly photos: number;
}

/** Where a restore gets photos back from besides this device: the person's own Google Drive (the Data page provides it). */
export interface PhotoRestorer {
  restorePhotos(req: PhotoRestoreRequest): Promise<PhotoRestoreReport>;
}

/** What a restore from the list did, for the words after it. */
export interface Restored {
  readonly name: string;
  /** How many photos the candidate had (0 when none, or when nobody recorded any). */
  readonly photosTotal: number;
  /** How many are back, from the copies on this device or from the person's Google Drive. */
  readonly photosBack: number;
  /** Of those, how many came from Google Drive. */
  readonly photosFromDrive: number;
  /** Why the others did not come back, each reason once. */
  readonly reasons: readonly PhotoRestoreReason[];
  /** What a second attempt needs while one can help, or null; held in memory by the card only (S4b-BL-293). */
  readonly retry: PhotoRetry | null;
}

/**
 * What retrying a restored candidate's photos needs (S4b-BL-293): everything the restore already had in hand, so a retry
 * asks Drive again without re-reading the store. Held in memory by {@link DeleteUndoService} while the person is on
 * *Recently deleted*; it is not stored, so a reload forgets it (the candidate is already back; only its photos wait).
 */
export interface PhotoRetry {
  readonly name: string;
  readonly houseId: string;
  readonly houseDeletedAt: number;
  /** How many photos the candidate had when it was deleted here (0 when unknown). */
  readonly noted: number;
  /** The photos with no bytes here that failed for a reason a second try can fix (Drive not connected, or offline). */
  readonly known: readonly GonePhoto[];
  /** The old photo ids already stored here or failed for good: a retry never takes them again, so none is stored twice. */
  readonly settled: ReadonlySet<string>;
  /** How many photos are back so far, so the words after a retry add up. */
  readonly back: number;
  /** Why the settled ones that did not come back did not. */
  readonly settledReasons: readonly PhotoRestoreReason[];
}

/** The name a candidate goes by in the words around a delete: its label, else its address, else ''. */
export function nameOfRow(row: Pick<HouseRecord, 'label' | 'address'>): string {
  return row.label?.trim() || row.address?.trim() || '';
}

/**
 * Deleting one candidate on the website, taken back (deletion safeguards PR 1, docs/11 5.27.6). The candidate is
 * deleted at once, as before (`LocalStore.deleteHouse`: a tombstone that syncs, its photos dropped, its saved walks
 * deleted); this service then offers *Undo* for {@link UNDO_MS} (the snackbar in the app shell reads {@link offer}).
 * *Undo* flips the tombstone back and puts the photos back from copies held in memory for that time; the same flip is
 * the *Restore* of *Recently deleted* ({@link bin}, {@link restore}), which has no copies and so brings back only
 * photos whose bytes are still stored. Saved walks have no tombstone and never come back, which is why the page asks
 * once before a delete that takes walks with it.
 */
@Injectable({ providedIn: 'root' })
export class DeleteUndoService {
  private readonly store = inject(LocalStore);
  private readonly data = inject(LocalDataService);
  private readonly sync = inject(SyncService);
  private readonly announcer = inject(Announcer);
  private readonly space = inject(SpaceService);

  /** The delete that can be undone now, or null. */
  readonly offer = signal<UndoOffer | null>(null);
  private held: readonly PhotoRecord[] = [];
  private timer: ReturnType<typeof setTimeout> | undefined;

  /** Deletes the candidate and offers *Undo*. Throws what the delete threw; then nothing is offered. */
  async remove(id: string, name: string): Promise<void> {
    const photos = await this.store.photos.ofHouse(id);
    await firstValueFrom(this.data.deleteHouse(id), { defaultValue: undefined });
    await this.note(id, photos.length);
    this.held = photos;
    this.offer.set({ id, name });
    this.arm();
  }

  /**
   * *Delete all in this space* (Settings > What you track): tombstones the given candidates in ONE batch and offers one
   * *Undo* for all of them. The caller names the ids of that space only (the card's kind); this never reads another row.
   * Returns how many were deleted.
   */
  async removeMany(ids: readonly string[], kindWords: string): Promise<number> {
    const photos: PhotoRecord[] = [];
    for (const id of ids) photos.push(...(await this.store.photos.ofHouse(id)));
    const gone = await this.store.deleteHouses(ids);
    if (!gone.length) return 0;
    this.sync.syncSoon();
    await this.noteMany(gone.map((id) => [id, photos.filter((p) => p.houseId === id).length] as const));
    this.held = photos.filter((p) => gone.includes(p.houseId));
    this.offer.set({ id: gone[0], name: String(gone.length), ids: gone, kindWords });
    this.arm();
    return gone.length;
  }

  /** Takes the last delete back: the candidate(s), and the photos from the copies. A no-op after the time is up. */
  async undo(): Promise<void> {
    const offer = this.offer();
    if (!offer) return;
    const held = this.held;
    this.dismiss();
    const ids = offer.ids ?? [offer.id];
    let back = 0;
    for (const id of ids) {
      const result = await this.store.restoreHouse(id, Date.now(), held.filter((p) => p.houseId === id));
      if (result) back++;
    }
    if (!back) return;
    await this.noteMany(ids.map((id) => [id, 0] as const));
    this.sync.syncSoon();
    this.announcer.announce(
      offer.ids ? { key: 'delete.restoredMany', params: { n: back, kind: offer.kindWords ?? '' } } : { key: 'delete.restored', params: { name: offer.name } },
    );
  }

  /** Hides the offer and drops the copies. */
  dismiss(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    this.held = [];
    this.offer.set(null);
  }

  /** Holds the time while the pointer or the focus is on the snackbar, and restarts it when they leave (WCAG 2.2.1). */
  hold(on: boolean): void {
    if (!this.offer()) return;
    clearTimeout(this.timer);
    this.timer = undefined;
    if (!on) this.arm();
  }

  private arm(): void {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.dismiss(), UNDO_MS);
  }

  /** This space's tombstones younger than 30 days, the newest first. */
  async bin(now: number = Date.now()): Promise<BinEntry[]> {
    const rows = await this.store.deletedHouses();
    const notes = parseNotes(await this.store.setting(SETTING_KEYS.binPhotos));
    const space = { kind: this.space.kind(), multi: this.space.multi(), dflt: this.space.dflt(), available: this.space.available };
    return binRows(rows, space, now).map((r) => ({ id: r.id, name: nameOfRow(r), deletedAt: r.updatedAt ?? '', photos: notes[r.id] ?? 0 }));
  }

  /**
   * Restores one entry of the list. Undefined when it is no longer a tombstone (restored on another device meanwhile).
   * The candidate comes back first and whatever happens to its photos cannot undo that: the photos with no bytes left here
   * are asked of `restorer` (the person's own Google Drive, which keeps the encrypted copy of a deleted photo for 30 days,
   * docs/15 §1.4) and put back one by one; each that cannot come back is counted with its reason (S4b-BL-293).
   */
  async restore(entry: BinEntry, now: number = Date.now(), restorer: PhotoRestorer | null = null): Promise<Restored | undefined> {
    const result: RestoreResult | undefined = await this.store.restoreHouse(entry.id, now);
    if (!result) return undefined;
    await this.note(entry.id, 0);
    const noted = Math.max(entry.photos, result.photosBack + result.photosGone);
    const ctx: PhotoRetry = { name: entry.name, houseId: entry.id, houseDeletedAt: result.deletedAt, noted, known: result.gone, settled: new Set(), back: 0, settledReasons: [] };
    return this.photosBackFor(ctx, now, restorer);
  }

  /**
   * Tries the photos of an already-restored candidate again, when the person presses *Retry* in *Recently deleted* after
   * reconnecting Google Drive (S4b-BL-293). The candidate is already back, so this touches only its photos: it asks Drive
   * for the same files the restore did and puts back each that answers, exactly like {@link restore}. Nothing is deleted.
   */
  async retryPhotos(retry: PhotoRetry, now: number = Date.now(), restorer: PhotoRestorer | null = null): Promise<Restored> {
    return this.photosBackFor(retry, now, restorer);
  }

  /**
   * One attempt: asks Drive through {@link bringPhotosBack}, adds the words up across attempts, and decides whether another
   * attempt can still help. Shared by {@link restore} and {@link retryPhotos} so the two cannot drift (S4b-BL-293).
   */
  private async photosBackFor(ctx: PhotoRetry, now: number, restorer: PhotoRestorer | null): Promise<Restored> {
    const brought = await this.bringPhotosBack(ctx, now, restorer);
    this.sync.syncSoon();
    const back = ctx.back + brought.fromDrive;
    const summary = summarize(ctx.noted, back, [...ctx.settledReasons, ...brought.failed], brought.connection);
    // A second try can help only for the reasons Drive being reached again fixes. The next attempt carries just the photos
    // that still have no bytes and failed for one of those, and never the ones settled, so none is stored twice.
    const canHelp = summary.reasons.some((r) => r === 'OFFLINE' || r === 'NOT_CONNECTED');
    const next: PhotoRetry | null = canHelp
      ? {
          name: ctx.name, houseId: ctx.houseId, houseDeletedAt: ctx.houseDeletedAt, noted: ctx.noted,
          known: brought.stillGone, settled: brought.settled, back,
          settledReasons: [...ctx.settledReasons, ...brought.failed.filter((r) => r !== 'OFFLINE' && r !== 'NOT_CONNECTED')],
        }
      : null;
    return { name: ctx.name, photosTotal: summary.total, photosBack: summary.restored, photosFromDrive: back, reasons: summary.reasons, retry: next };
  }

  /**
   * Asks the person's own Google Drive for the photos of one candidate that have no bytes here, and puts each that answers
   * back under a new photo id; every failure becomes a reason for a photo, none is thrown. Drive is always asked: a
   * candidate deleted on another device leaves no note or tombstone here, only its sync file.
   */
  private async bringPhotosBack(
    ctx: PhotoRetry,
    now: number,
    restorer: PhotoRestorer | null,
  ): Promise<{ fromDrive: number; failed: PhotoRestoreReason[]; connection: RestoreConnection; stillGone: GonePhoto[]; settled: ReadonlySet<string> }> {
    if (!restorer) {
      const failedNow = ctx.known.filter((p) => !ctx.settled.has(p.id));
      return { fromDrive: 0, failed: failedNow.map(() => 'NOT_CONNECTED'), connection: 'NOT_CONNECTED', stillGone: [...failedNow], settled: new Set(ctx.settled) };
    }
    let report: PhotoRestoreReport;
    try {
      report = await restorer.restorePhotos({ houseId: ctx.houseId, houseDeletedAt: ctx.houseDeletedAt, known: ctx.known });
    } catch {
      report = notConnectedReport(ctx.known);
    }
    // A photo an earlier attempt settled is left out of the count and never stored again: the sync file lists every photo of
    // the candidate on every read, so without this a retry would put a second copy of an already-restored photo back.
    const failedNow = report.failed.filter((f) => !ctx.settled.has(f.photo.id));
    const failed: PhotoRestoreReason[] = failedNow.map((f) => f.reason);
    const settled = new Set(ctx.settled);
    for (const f of failedNow) if (f.reason !== 'OFFLINE' && f.reason !== 'NOT_CONNECTED') settled.add(f.photo.id);
    let fromDrive = 0;
    const stamp = isoNow(now);
    for (const got of report.fetched) {
      if (ctx.settled.has(got.photo.id)) continue;
      try {
        await this.store.photos.put(restoredPhoto(got.photo, ctx.houseId, got.bytes, stamp));
        fromDrive++;
        settled.add(got.photo.id);
      } catch {
        failed.push('UNREADABLE');
        settled.add(got.photo.id);
      }
    }
    const stillGone = failedNow.filter((f) => f.reason === 'OFFLINE' || f.reason === 'NOT_CONNECTED').map((f) => f.photo);
    return { fromDrive, failed, connection: report.connection, stillGone, settled };
  }

  /** {@link note} for many at once: one read and one write of the notes. */
  private async noteMany(counts: readonly (readonly [string, number])[]): Promise<void> {
    const keep = new Set((await this.store.deletedHouses()).map((h) => h.id));
    let notes = parseNotes(await this.store.setting(SETTING_KEYS.binPhotos));
    for (const [id, count] of counts) notes = withNote(notes, id, count, keep);
    await this.store.setSetting(SETTING_KEYS.binPhotos, JSON.stringify(notes));
  }

  /** Remembers how many photos a deleted candidate had (0 forgets it), dropping the notes of tombstones that are gone. */
  private async note(id: string, count: number): Promise<void> {
    const keep = new Set((await this.store.deletedHouses()).map((h) => h.id));
    const notes = parseNotes(await this.store.setting(SETTING_KEYS.binPhotos));
    await this.store.setSetting(SETTING_KEYS.binPhotos, JSON.stringify(withNote(notes, id, count, keep)));
  }
}
