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
import { uuid } from '../core/models';
import type { HouseDto, VisitDto } from '../core/models';
import { LocalStore } from '../data/local-store.service';
import { houseFromDto, isoNow, millis, photoMetaOf, visitFromDto, withPhotoMeta } from '../data/records';
import type { HouseRecord, PhotoRecord, RecordRecord, VisitRecord } from '../data/records';
import { AREA_NOTE_TYPE, AREA_TYPE, PLACE_TYPE, areaFromPayload, areaNoteFromPayload, areaNoteToPayload, areaToPayload, placeFromPayload, placeToPayload } from '../shared/area';
import { BROKER_TYPE, brokerFromPayload, brokerToPayload } from '../shared/broker';
import { cleanMeta } from '../shared/photo-tags';
import { QUESTION_TYPE, questionFromPayload, questionToPayload } from '../shared/question';
import { CRITERION_TYPE, PREFERENCE_TYPE, criterionFromPayload, criterionToPayload } from '../shared/scoring';
import { VIEWING_TYPE, viewingFromPayload, viewingToPayload } from '../shared/viewing';
import type { BackupHouse, BackupPhoto, BackupVisit } from './backup-export';
import { isUpdate } from './backup-reader';
import type { BackupArchive } from './backup-reader';
import { plan, preview } from './import-plan';
import type { ImportActions, ImportFlags, ImportPreview, LocalVersions } from './import-plan';

/** What an import wrote, in the preview's words; for a copy, what its undo removes. */
export interface ImportOutcome {
  houses: number;
  updatedHouses: number;
  restoredHouses: number;
  visits: number;
  photos: number;
  photosSkipped: number;
  records: number;
  removedHouses: number;
  /** COPY only: the rows it added, each with the `updatedAt` it was written with, for {@link ImportService.undoCopy}. */
  undo: CopyUndo | null;
}

/**
 * The rows a COPY import added, each with the `updatedAt` it was written with, so *Undo* removes only those that were not edited since.
 */
export interface CopyUndo {
  houses: Map<string, number>;
  visits: Map<string, number>;
  photos: string[];
  /** `type\nid` to `updatedAt`. */
  records: Map<string, number>;
}

/**
 * *Import a backup* on the website (S4b-BL-75): reads what this browser holds for the last-write-wins comparison, and
 * writes what {@link plan} chose into IndexedDB, the same way Android's `Repository.applyImport` writes into Room. Every
 * written row is dirty, so a connected server gets it on the next sync. A house brought back over a tombstone is stamped
 * past it; a visit the server unlinked is put back in its house; the photo bytes come from the archive, verified; an
 * update file's deletions (S4b-BL-82) delete the houses last, as the person's own delete would.
 */
@Injectable({ providedIn: 'root' })
export class ImportService {
  private readonly store = inject(LocalStore);

  async localVersions(): Promise<LocalVersions> {
    const houses = await this.store.allHouses();
    const visits = await this.store.allVisits();
    const photos = await this.store.photos.all();
    const versions = async (type: string) => new Map((await this.store.allRecordsOf(type)).map((r) => [r.id, millis(r.updatedAt)]));
    const live = async (type: string) => new Set((await this.store.allRecordsOf(type)).filter((r) => !r.deleted).map((r) => r.id));
    return {
      houses: new Map(houses.map((h) => [h.id, millis(h.updatedAt)])),
      visits: new Map(visits.map((v) => [v.id, millis(v.updatedAt)])),
      photoIds: new Set(photos.map((p) => p.id)),
      deletedHouseIds: new Set(houses.filter((h) => h.deleted).map((h) => h.id)),
      scoredHouseIds: new Set(houses.filter((h) => !h.deleted && Object.keys(h.checklist ?? {}).length > 0).map((h) => h.id)),
      unlinkedVisitIds: new Set(visits.filter((v) => !v.deleted && !v.houseId).map((v) => v.id)),
      syncedDeletedHouseIds: new Set(houses.filter((h) => h.deleted && !h.dirty).map((h) => h.id)),
      brokers: await versions(BROKER_TYPE),
      criteria: await versions(CRITERION_TYPE),
      preferences: await versions(PREFERENCE_TYPE),
      questions: await versions(QUESTION_TYPE),
      viewings: await versions(VIEWING_TYPE),
      areas: await versions(AREA_TYPE),
      places: await versions(PLACE_TYPE),
      areaNotes: await versions(AREA_NOTE_TYPE),
      photoMeta: new Map(photos.map((p) => [p.id, photoMetaOf(p).metaUpdatedAt])),
      liveQuestions: await live(QUESTION_TYPE),
      liveCriteria: await live(CRITERION_TYPE),
    };
  }

  /** The preview of `archive` for `flags` against this browser; nothing is written. */
  async preview(archive: BackupArchive, flags: Omit<ImportFlags, 'applyDeletions'>): Promise<ImportPreview> {
    return preview(archive.data, archive.photoEntries, await this.localVersions(), { ...flags, applyDeletions: isUpdate(archive.manifest) });
  }

  /** Writes the import the person confirmed. The local state is read again, so the plan matches what is here now. */
  async apply(archive: BackupArchive, flags: Omit<ImportFlags, 'applyDeletions'>, now: number = Date.now()): Promise<ImportOutcome> {
    const local = await this.localVersions();
    const actions = plan(archive.data, archive.photoEntries, local, { ...flags, applyDeletions: isUpdate(archive.manifest) }, uuid);
    return this.write(actions, archive, now);
  }

  private async write(actions: ImportActions, archive: BackupArchive, now: number): Promise<ImportOutcome> {
    const copy = actions.mode === 'COPY';
    const undo: CopyUndo | null = copy ? { houses: new Map(), visits: new Map(), photos: [], records: new Map() } : null;
    const records: RecordRecord[] = [];
    const record = (type: string, id: string, payload: Record<string, unknown>, updatedAt: number) => {
      records.push({ type, id, payload, updatedAt: isoNow(updatedAt), deleted: false, syncVersion: 0, dirty: true });
      undo?.records.set(`${type}\n${id}`, updatedAt);
    };
    for (const b of actions.brokers) {
      const { id, updatedAt, ...rest } = b;
      const broker = brokerFromPayload(rest);
      record(BROKER_TYPE, id, broker ? brokerToPayload(broker) : rest, updatedAt);
    }
    for (const c of actions.criteria) {
      const { key, updatedAt, ...rest } = c;
      const criterion = criterionFromPayload(key, rest);
      // A copy's criteria keep their keys (the houses' scores name them); undo leaves them, as on Android.
      records.push({ type: CRITERION_TYPE, id: key, payload: criterion ? criterionToPayload(criterion) : rest, updatedAt: isoNow(updatedAt), deleted: false, syncVersion: 0, dirty: true });
    }
    for (const p of actions.preferences) {
      records.push({ type: PREFERENCE_TYPE, id: p.key, payload: { value: p.value }, updatedAt: isoNow(p.updatedAt), deleted: false, syncVersion: 0, dirty: true });
    }
    for (const q of actions.questions) {
      const { id, updatedAt, ...rest } = q;
      const question = questionFromPayload(id, rest);
      records.push({ type: QUESTION_TYPE, id, payload: question ? questionToPayload(question) : rest, updatedAt: isoNow(updatedAt), deleted: false, syncVersion: 0, dirty: true });
    }
    for (const v of actions.viewings) {
      const { id, updatedAt, ...rest } = v;
      const viewing = viewingFromPayload(id, rest);
      record(VIEWING_TYPE, id, viewing ? viewingToPayload(viewing) : rest, updatedAt);
    }
    for (const a of actions.areas) {
      const { id, updatedAt, ...rest } = a;
      const area = areaFromPayload(id, rest);
      records.push({ type: AREA_TYPE, id, payload: area ? areaToPayload(area) : rest, updatedAt: isoNow(updatedAt), deleted: false, syncVersion: 0, dirty: true });
    }
    for (const p of actions.places) {
      const { id, updatedAt, ...rest } = p;
      const place = placeFromPayload(id, rest);
      records.push({ type: PLACE_TYPE, id, payload: place ? placeToPayload(place) : rest, updatedAt: isoNow(updatedAt), deleted: false, syncVersion: 0, dirty: true });
    }
    for (const n of actions.areaNotes) {
      const { id, updatedAt, ...rest } = n;
      const note = areaNoteFromPayload(id, rest);
      records.push({ type: AREA_NOTE_TYPE, id, payload: note ? areaNoteToPayload(note) : rest, updatedAt: isoNow(updatedAt), deleted: false, syncVersion: 0, dirty: true });
    }

    const houses: HouseRecord[] = [];
    let updatedHouses = 0;
    let restoredHouses = 0;
    for (const h of actions.houses) {
      const existing = await this.store.getHouseRow(h.id);
      let row = houseRecord(h, existing?.syncVersion ?? 0);
      if (actions.restoredHouseIds.has(h.id)) {
        // The undelete sticks only when it is newer than the tombstone, here and on the server.
        row = { ...row, updatedAt: isoNow(Math.max(now, millis(existing?.updatedAt) + 1)) };
        restoredHouses++;
      } else if (actions.updatedHouseIds.has(h.id)) {
        updatedHouses++;
      }
      houses.push(row);
      undo?.houses.set(row.id, millis(row.updatedAt));
    }
    const writtenHouses = new Set(houses.map((h) => h.id));

    const visits: VisitRecord[] = [];
    for (const v of actions.visits) {
      if (actions.relinkedVisitIds.has(v.id)) {
        const here = await this.store.getVisitRow(v.id);
        // Deleted, or put in a house, since the preview: that is a newer decision of the person's.
        if (here && (here.deleted || here.houseId)) continue;
        const base = here ?? visitRecord(v, 0);
        visits.push({ ...base, houseId: v.houseId ?? null, updatedAt: isoNow(Math.max(now, millis(here?.updatedAt) + 1)), dirty: true });
        continue;
      }
      const existing = copy ? undefined : await this.store.getVisitRow(v.id);
      const row = visitRecord(v, existing?.syncVersion ?? 0);
      visits.push(row);
      undo?.visits.set(row.id, millis(row.updatedAt));
    }

    const photos: PhotoRecord[] = [];
    let skipped = 0;
    for (const p of actions.photos) {
      const entry = actions.photoSources.get(p.id);
      const bytes = entry ? await archive.photoBytes(entry) : null;
      const house = writtenHouses.has(p.houseId) ? true : (await this.store.getHouseRow(p.houseId))?.deleted === false;
      if (!bytes || !house) {
        skipped++;
        continue;
      }
      photos.push(photoRecord(p, bytes));
      undo?.photos.push(p.id);
    }

    await this.store.putImported({ records, houses, visits, photos });
    // Photos already here whose meta is newer in the file: only the meta, marked for the next sync.
    for (const p of actions.photoMeta) {
      const here = await this.store.photos.get(p.id);
      if (here && !here.deleted) await this.store.photos.put(withPhotoMeta(here, cleanMeta(p), true));
    }
    let removed = 0;
    for (const id of actions.removedHouseIds) {
      if ((await this.store.getHouseRow(id))?.deleted !== false) continue;
      await this.store.deleteHouse(id, now);
      removed++;
    }
    return {
      houses: houses.length,
      updatedHouses,
      restoredHouses,
      visits: visits.length,
      photos: photos.length,
      photosSkipped: skipped,
      records: records.length,
      removedHouses: removed,
      undo,
    };
  }

  /**
   * Undoes a copy import (Android's `undoCopyImport`, S4b-BL-86 style): removes exactly the rows it added that nobody
   * changed since, as deletes of this browser's own; a house edited since stays, with its visits and photos.
   */
  async undoCopy(undo: CopyUndo, now: number = Date.now()): Promise<{ removed: number; kept: number }> {
    let removed = 0;
    let kept = 0;
    const keptHouses = new Set<string>();
    for (const [id, at] of undo.houses) {
      const row = await this.store.getHouseRow(id);
      if (!row || row.deleted) continue;
      if (millis(row.updatedAt) !== at) {
        kept++;
        keptHouses.add(id);
        continue;
      }
      await this.store.deleteHouse(id, now);
      removed++;
    }
    for (const [id, at] of undo.visits) {
      const row = await this.store.getVisitRow(id);
      if (row && !row.deleted && millis(row.updatedAt) === at && !(row.houseId && keptHouses.has(row.houseId))) await this.store.deleteVisit(id, now);
    }
    for (const id of undo.photos) {
      const row = await this.store.photos.get(id);
      if (row && !row.deleted && !keptHouses.has(row.houseId)) await this.store.photos.delete(id, now);
    }
    for (const [key, at] of undo.records) {
      const [type, id] = key.split('\n');
      const row = await this.store.getRecord(type, id);
      if (row && millis(row.updatedAt) === at) await this.store.deleteRecord(type, id, now);
    }
    return { removed, kept };
  }
}

/** A backup's house as this browser stores it: the reader's own coercion (`houseFromDto`), dirty so it is pushed. */
function houseRecord(h: BackupHouse, syncVersion: number): HouseRecord {
  const dto = { ...h, createdAt: isoNow(h.createdAt), updatedAt: isoNow(h.updatedAt), deleted: false, syncVersion } as unknown as HouseDto;
  return houseFromDto(dto, true);
}

/** A backup visit as this browser stores it, marked dirty so it is pushed. */
function visitRecord(v: BackupVisit, syncVersion: number): VisitRecord {
  const dto = {
    ...v,
    arrivedAt: isoNow(v.arrivedAt),
    leftAt: v.leftAt != null ? isoNow(v.leftAt) : null,
    updatedAt: isoNow(v.updatedAt),
    deleted: false,
    syncVersion,
  } as unknown as VisitDto;
  return visitFromDto(dto, true);
}

/** A backup photo as this browser stores it: the verified bytes as a JPEG blob, with its metadata, not yet uploaded. */
function photoRecord(p: BackupPhoto, bytes: Uint8Array): PhotoRecord {
  const blob = new Blob([bytes as BlobPart], { type: 'image/jpeg' });
  const record: PhotoRecord = {
    id: p.id,
    houseId: p.houseId,
    blob,
    contentType: 'image/jpeg',
    sizeBytes: bytes.length,
    createdAt: isoNow(p.createdAt),
    updatedAt: isoNow(p.createdAt),
    deleted: false,
    syncVersion: 0,
    uploaded: false,
  };
  const meta = cleanMeta(p);
  return withPhotoMeta(record, meta, meta.metaUpdatedAt > 0);
}
