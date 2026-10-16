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

// The photo rows of the browser's store (docs/11 5.7, 5.24): the limit per house, a delete that leaves a tombstone only
// for a photo the server already has, the room/tags/caption meta with its last-write-wins stamp, and which photos a full
// resend marks again. It is separate from LocalStore (S4b-BL-168) so that a change to what a photo holds edits this file
// and `records.ts`, not the 1,500-line service. LocalStore owns the database and the revision; it hands both in.
import { uuid } from '../core/models';
import type { LocalDb } from './local-db';
import { isoNow, photoMetaOf, sortByCreated, withPhotoMeta } from './records';
import type { PhotoRecord } from './records';
import { MOVE_IN_TAG, cleanMeta, hasMeta, incomingWins } from '../shared/photo-tags';
import type { PhotoMeta } from '../shared/photo-tags';

/** Same ceiling as the Android app and the server (shared MAX_PHOTOS_PER_HOUSE). */
export const MAX_PHOTOS_PER_HOUSE = 20;

/** The outcome of adding a photo: its id, or `limit` when the house already holds the most photos. */
export type AddPhotoResult = { ok: true; id: string } | { ok: false; reason: 'limit' };

/** What a person says about a photo: its room, tags and caption. */
export type PhotoMetaInput = Pick<PhotoMeta, 'roomId' | 'tags' | 'caption'>;

export class PhotoStore {
  /**
   * @param database the opened database, once the store is ready
   * @param changed called after each write, so views and the sync engine see a new revision
   */
  constructor(
    private readonly database: () => Promise<LocalDb>,
    private readonly changed: () => void,
  ) {}

  /** Every photo row, tombstones included. */
  async all(): Promise<PhotoRecord[]> {
    const db = await this.database();
    return sortByCreated(await db.getAll<PhotoRecord>('photos'));
  }

  /** A house's live photos, through the `houseId` index: the store holds Blobs, so every photo is not read (S4b-BL-66). */
  async ofHouse(houseId: string): Promise<PhotoRecord[]> {
    const db = await this.database();
    const rows = await db.getAllByIndex<PhotoRecord>('photos', 'houseId', houseId);
    return sortByCreated(rows.filter((p) => !p.deleted));
  }

  /** The stored photo rows with these ids, deleted ones included, in one read. */
  async rowsByIds(ids: readonly string[]): Promise<PhotoRecord[]> {
    const db = await this.database();
    return db.getMany<PhotoRecord>('photos', ids);
  }

  /** One photo row by id, whether or not it is deleted. */
  async get(id: string): Promise<PhotoRecord | undefined> {
    const db = await this.database();
    return db.get<PhotoRecord>('photos', id);
  }

  /**
   * Stores photo bytes for a house. The blob is already resized and re-encoded by the caller. `meta` is the photo's
   * room, tags and caption when they are known at once (the condition record's *Add a photo* chooses MOVE_IN), stamped
   * `metaUpdatedAt = now` and waiting to be pushed.
   */
  async add(houseId: string, blob: Blob, id: string = uuid(), now: number = Date.now(), meta?: PhotoMetaInput): Promise<AddPhotoResult> {
    if ((await this.ofHouse(houseId)).length >= MAX_PHOTOS_PER_HOUSE) return { ok: false, reason: 'limit' };
    const db = await this.database();
    const record: PhotoRecord = {
      id,
      houseId,
      blob,
      contentType: blob.type || 'image/jpeg',
      sizeBytes: blob.size,
      createdAt: isoNow(now),
      updatedAt: isoNow(now),
      deleted: false,
      syncVersion: 0,
      uploaded: false,
    };
    const clean = meta ? cleanMeta({ ...meta, metaUpdatedAt: now }) : null;
    await db.put('photos', clean && hasMeta({ ...clean, metaUpdatedAt: 0 }) ? withPhotoMeta(record, clean, true) : record);
    this.changed();
    return { ok: true, id };
  }

  /**
   * Saves a photo's room, tags and caption (docs/11 5.7): coerced, stamped `metaUpdatedAt = now` and marked for the next
   * sync. Nothing is written, and `false` comes back, when the photo is gone or the meta is what it already is.
   */
  async setMeta(id: string, meta: PhotoMetaInput, now: number = Date.now()): Promise<boolean> {
    const db = await this.database();
    const existing = await db.get<PhotoRecord>('photos', id);
    if (!existing || existing.deleted) return false;
    const clean = cleanMeta({ ...meta, metaUpdatedAt: 1 });
    const before = photoMetaOf(existing);
    if (before.roomId === clean.roomId && before.caption === clean.caption && before.tags.join('\n') === clean.tags.join('\n')) return false;
    // Strictly newer than what is stored, so two edits inside one millisecond still order.
    const stamp = Math.max(now, before.metaUpdatedAt + 1);
    await db.put('photos', withPhotoMeta(existing, { ...clean, metaUpdatedAt: stamp }, true));
    this.changed();
    return true;
  }

  /** Applies the meta a server row carries when it is strictly newer than the stored one (last write wins). */
  async applyMetaFromServer(id: string, incoming: PhotoMeta): Promise<boolean> {
    const db = await this.database();
    const existing = await db.get<PhotoRecord>('photos', id);
    if (!existing || existing.deleted) return false;
    if (!incomingWins(photoMetaOf(existing).metaUpdatedAt, incoming.metaUpdatedAt)) return false;
    await db.put('photos', withPhotoMeta(existing, incoming, false));
    this.changed();
    return true;
  }

  /** The push of a photo's meta went through: the flag clears only when the meta was not edited again meanwhile. */
  async markMetaClean(id: string, pushedAt: number): Promise<void> {
    const db = await this.database();
    const existing = await db.get<PhotoRecord>('photos', id);
    if (existing && existing.metaDirty && (existing.metaUpdatedAt ?? 0) === pushedAt) {
      const { metaDirty: _dirty, ...clean } = existing;
      await db.put('photos', clean);
    }
  }

  /** The photos of one house tagged MOVE_IN, oldest first: the condition record (docs/11 5.24). */
  async conditionOf(houseId: string): Promise<PhotoRecord[]> {
    return (await this.ofHouse(houseId)).filter((p) => photoMetaOf(p).tags.some((t) => t === MOVE_IN_TAG));
  }

  /**
   * Drops the bytes now. A photo the server already has keeps a tombstone so the delete is pushed on the next
   * sync (threat model F-15); one that never left this browser is removed outright.
   */
  async delete(id: string, now: number = Date.now()): Promise<void> {
    const db = await this.database();
    const existing = await db.get<PhotoRecord>('photos', id);
    if (!existing) return;
    if (existing.uploaded) {
      await db.put('photos', { ...existing, blob: null, deleted: true, updatedAt: isoNow(now) });
    } else {
      await db.delete('photos', id);
    }
    this.changed();
  }

  /** Forgets a photo completely (used when the server confirms a delete, or a tombstone arrives from elsewhere). */
  async forget(id: string): Promise<void> {
    const db = await this.database();
    await db.delete('photos', id);
    this.changed();
  }

  /** Stores a photo row as given (sync and import); no dirty flag or limit is applied here. */
  async put(record: PhotoRecord): Promise<void> {
    const db = await this.database();
    await db.put('photos', record);
    this.changed();
  }

  /**
   * The photo part of a full resend (`LocalStore.markAllForResync`, which bumps the revision once): a photo whose bytes
   * the server has is marked not uploaded, and one with meta that was clean is marked dirty. Deleted photos stay as
   * they are (a delete of a photo the server does not have is a no-op).
   */
  async markAllForResync(): Promise<void> {
    const db = await this.database();
    for (const photo of await db.getAll<PhotoRecord>('photos')) {
      if (photo.deleted) continue;
      const resendBytes = !!photo.blob && photo.uploaded;
      const resendMeta = (photo.metaUpdatedAt ?? 0) > 0 && photo.metaDirty !== true;
      if (resendBytes || resendMeta) {
        await db.put('photos', { ...photo, ...(resendBytes ? { uploaded: false } : {}), ...(resendMeta ? { metaDirty: true } : {}) });
      }
    }
  }
}
