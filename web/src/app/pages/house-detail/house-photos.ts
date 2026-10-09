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

// What the house page keeps about the photos of one house (docs/11 5.7): the list with each photo's room, tags and
// caption, adding a batch of files, deleting one, and the details editor that is open. It is separate from
// `house-detail-page.ts` (S4b-BL-168) so that a change to what a photo holds edits this file and the photos card of the
// page's template, not the 1,700-line page. The page owns the draft, the large view and the leave guard, and hands in
// what this needs to read from them.
import { signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { Announcer } from '../../core/announcer.service';
import type { ConfirmService } from '../../core/confirm.service';
import { errorMsg } from '../../core/format';
import { resizeImage } from '../../core/image-resize';
import type { LocalDataService, PhotoSummary } from '../../core/local-data.service';
import { ROOM_TYPE_KEY } from '../../core/models';
import type { HouseDto } from '../../core/models';
import type { Msg, TranslationService } from '../../i18n/translation.service';
import { photoTagKey } from '../../shared/photo-tags';
import type { PhotoMeta } from '../../shared/photo-tags';
import type { PhotoMetaInput } from '../../data/photo-store';
import { runResult } from '../../shared/run-result';
import type { RunResult } from '../../shared/run-result';

/** A photo that could not be added, named in the photos card. */
export interface PhotoFailure {
  file: string;
  reason: Msg;
}

/** Where a photo is resized to (longest side, px) and how hard it is compressed, before it is stored. */
const PHOTO_MAX_SIDE = 1600;
const PHOTO_QUALITY = 0.8;

/** A save or photo failed because the browser is out of space (directly, or as the reason of a wrapper message). */
export function isStorageFull(m: Msg): boolean {
  if (m.key === 'error.storageFull') return true;
  const reason = m.params?.['reason'];
  return typeof reason === 'object' && reason !== null && reason.key === 'error.storageFull';
}

/** What the photos of the house page need from the page and from the app. */
export interface HousePhotosDeps {
  api: LocalDataService;
  announcer: Announcer;
  confirm: ConfirmService;
  i18n: TranslationService;
  /** The house being edited (its id, label and rooms), or null before it is read. */
  house: () => HouseDto | null;
  isNew: () => boolean;
  /** A photo was deleted: the page closes its large view. */
  onDeleted: () => void;
}

export class HousePhotos {
  readonly ids = signal<string[]>([]);
  /** The photos' room, tags and caption by photo id (slice 5); read again with the photo list. */
  readonly info = signal<ReadonlyMap<string, PhotoSummary>>(new Map());
  /** The photo whose details editor is open, if any. */
  readonly editing = signal<string | null>(null);
  /** How many files of the batches running are still to be stored. */
  readonly uploading = signal(0);
  /** The files of the current batch that could not be added; one run per batch, so a batch failing again is read. */
  readonly failures = signal<RunResult<readonly PhotoFailure[]> | null>(null);
  /** A failed delete, shown beside the failures. */
  readonly message = signal<RunResult<Msg> | null>(null);
  /** Upload results of the files chosen together, announced once when the last one is done. */
  private batch = { added: 0, failed: 0 };

  constructor(private readonly deps: HousePhotosDeps) {}

  /** Reads the house's photos with their meta (the list under the thumbnails, the details editor). */
  refresh(id: string | undefined = this.deps.house()?.id): void {
    if (!id) return;
    this.deps.api.photos(id).subscribe({
      next: (list) => {
        this.ids.set(list.map((p) => p.id));
        this.info.set(new Map(list.map((p) => [p.id, p])));
      },
      error: () => {
        this.ids.set([]);
        this.info.set(new Map());
      },
    });
  }

  /**
   * Adds the chosen photos one by one. The result is said **once**, when the last file is done ("Photos added: 3.
   * Not added: 1"), not once per photo, and every file that failed is listed in the photos card with its reason.
   * `meta` is the room, tags and caption they get at once (the condition record's photos are tagged MOVE_IN).
   */
  async add(files: readonly File[], meta?: PhotoMetaInput): Promise<void> {
    const house = this.deps.house();
    if (!house || this.deps.isNew() || files.length === 0) return;
    if (this.uploading() === 0) {
      // A new batch: the previous one's results are no longer news.
      this.batch = { added: 0, failed: 0 };
      this.failures.set(null);
      this.message.set(null);
    }
    this.uploading.update((n) => n + files.length);
    for (const file of files) {
      try {
        const blob = await resizeImage(file, PHOTO_MAX_SIDE, PHOTO_QUALITY);
        const res = await firstValueFrom(this.deps.api.uploadPhoto(house.id, blob, undefined, meta));
        this.ids.update((ids) => (ids.includes(res.id) ? ids : [...ids, res.id]));
        this.refresh();
        this.batch.added++;
      } catch (err: unknown) {
        this.batch.failed++;
        // The batch's first failure starts its run; later ones join it, so the list grows in the same node.
        const failure: PhotoFailure = { file: file.name, reason: errorMsg(err) };
        this.failures.update((r) =>
          r === null ? runResult<readonly PhotoFailure[]>([failure]) : { value: [...r.value, failure], run: r.run },
        );
      } finally {
        this.uploading.update((n) => n - 1);
      }
    }
    if (this.uploading() > 0) return;
    const { added, failed } = this.batch;
    this.deps.announcer.announce(
      failed > 0 ? { key: 'house.photosResult', params: { added, failed } } : { key: 'house.photosAdded', params: { n: added } },
    );
  }

  /** Deletes a photo after asking; a failure is said in the card and the photo stays. */
  async remove(id: string): Promise<void> {
    const ok = await this.deps.confirm.ask({ key: 'confirm.deletePhoto' }, { confirmKey: 'common.delete', danger: true });
    if (!ok) return;
    this.message.set(null);
    this.deps.api.deletePhoto(id).subscribe({
      next: () => {
        this.ids.update((ids) => ids.filter((x) => x !== id));
        if (this.editing() === id) this.editing.set(null);
        this.deps.onDeleted();
        this.deps.announcer.announce({ key: 'house.photoDeleted' });
        document.getElementById('photos-heading')?.focus();
      },
      error: (err: unknown) =>
        this.message.set(runResult({ key: 'house.deletePhotoFailed', params: { reason: errorMsg(err) } })),
    });
  }

  /** Opens the details editor of a photo, or closes it when it is the one open. */
  toggleDetails(id: string): void {
    this.editing.set(this.editing() === id ? null : id);
  }

  /** The editor saved: the list is read again and focus goes back to the photo's Details button. */
  detailsSaved(id: string): void {
    this.editing.set(null);
    this.refresh();
    setTimeout(() => document.getElementById('photo-details-' + id)?.focus());
  }

  /** The name of the room a photo is tagged with, or '' when it has none or the room is gone. */
  roomNameOf(p: PhotoMeta): string {
    const room = p.roomId ? (this.deps.house()?.rooms ?? []).find((r) => r.id === p.roomId) : undefined;
    return room ? room.name?.trim() || this.deps.i18n.t(ROOM_TYPE_KEY[room.type]) : '';
  }

  /** A tag in the app language; a tag the person typed is shown as typed. */
  tagLabel(tag: string): string {
    const key = photoTagKey(tag);
    return key ? this.deps.i18n.t(key) : tag;
  }

  tagsOf(p: PhotoMeta): string {
    return p.tags.map((t) => this.tagLabel(t)).join(', ');
  }

  /** True when one of the failures was the browser running out of space (the card then links to Your data). */
  anyStorageFull(failures: readonly PhotoFailure[]): boolean {
    return failures.some((f) => isStorageFull(f.reason));
  }
}
