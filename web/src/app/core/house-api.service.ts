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

import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { HouseDto, PhotoChangeDto, RecordDto, StatsDto, VisitDto, uuid } from './models';
import type { PhotoMeta } from '../shared/photo-tags';
import { candidateOf, houseOf } from './candidate';
import type { CandidateDto } from './candidate';

/**
 * The typed client for the optional self-hosted server's REST API (houses, visits, records, photos). The sync engine
 * uses it to push and pull; the pages themselves read and write the local store.
 *
 * Paths are relative, so the API interceptor adds the configured base URL and `X-API-Key`; only
 * {@link testConnection} sends a candidate URL and key itself. Every house read goes through `normalizeHouse`
 * so an older server row still has a checklist and a status. HTTP errors are not caught here.
 *
 * **The wire is the candidate's** since kinds step 2 (the server, #281) and step 3 (this client, S4b-BL-206): the
 * houses are `/api/candidates`, each one a {@link CandidateDto} with `kind`, `money` and `extras`
 * (`core/candidate.ts` turns it into the {@link HouseDto} the app reads and back), and a visit or a photo names its
 * house as `candidateId`. Records travel as before: the server never reads their payload.
 */
@Injectable({ providedIn: 'root' })
export class HouseApiService {
  private readonly http = inject(HttpClient);

  stats(): Observable<StatsDto> {
    return this.http.get<StatsDto>('/api/stats');
  }

  /** Checks a candidate base URL and key without saving them. */
  testConnection(baseUrl: string, apiKey: string): Observable<StatsDto> {
    return this.http.get<StatsDto>(`${baseUrl}/api/stats`, { headers: { 'X-API-Key': apiKey } });
  }

  houses(): Observable<HouseDto[]> {
    return this.http.get<CandidateDto[]>(CANDIDATES).pipe(map((list) => list.map(normalizeHouse)));
  }

  /**
   * Every house changed after `since` (tombstones included), in sync-version order. The sync engine
   * (data/sync.service.ts) uses this; `since = 0` pulls everything, which is the first-run migration.
   */
  housesSince(since: number): Observable<HouseDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<CandidateDto[]>(CANDIDATES, { params }).pipe(map((l) => l.map(normalizeHouse)));
  }

  visitsSince(since: number): Observable<VisitDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<WireVisit[]>('/api/visits', { params }).pipe(map((l) => l.map(visitOfWire)));
  }

  /** Every record envelope changed after `since`, tombstones included, of every type (docs/11 5.30 item 2). */
  recordsSince(since: number): Observable<RecordDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<RecordDto[]>('/api/records', { params });
  }

  /** Photo metadata changes (new photos and delete tombstones) after a sync version. */
  photoChangesSince(since: number): Observable<PhotoChangeDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<WirePhoto[]>('/api/photos', { params }).pipe(map((l) => l.map(photoOfWire)));
  }

  house(id: string): Observable<HouseDto> {
    return this.http.get<CandidateDto>(`${CANDIDATES}/${encodeURIComponent(id)}`).pipe(map(normalizeHouse));
  }

  /**
   * Pushes a row exactly as it is stored locally, keeping its `updatedAt`. The sync engine must not restamp the
   * time: the stored value is what last-write-wins compares on every device (data/sync-rules.ts).
   */
  pushHouse(house: HouseDto): Observable<HouseDto> {
    return this.http
      .put<CandidateDto>(`${CANDIDATES}/${encodeURIComponent(house.id)}`, candidateBody(house))
      .pipe(map(normalizeHouse));
  }

  pushVisit(visit: VisitDto): Observable<VisitDto> {
    return this.http.put<WireVisit>(`/api/visits/${encodeURIComponent(visit.id)}`, wireVisit(visit)).pipe(map(visitOfWire));
  }

  /** Pushes a record envelope as stored, `updatedAt` kept, like {@link pushHouse}; the server never reads `payload`. */
  pushRecord(record: RecordDto): Observable<RecordDto> {
    const path = `/api/records/${encodeURIComponent(record.type)}/${encodeURIComponent(record.id)}`;
    return this.http.put<RecordDto>(path, record);
  }

  /** Saves an edit made now: unlike {@link pushHouse} it stamps `updatedAt` with the current time. */
  saveHouse(house: HouseDto): Observable<HouseDto> {
    const body = candidateBody({ ...house, updatedAt: new Date().toISOString() });
    return this.http
      .put<CandidateDto>(`${CANDIDATES}/${encodeURIComponent(house.id)}`, body)
      .pipe(map(normalizeHouse));
  }

  deleteHouse(id: string): Observable<unknown> {
    return this.http.delete(`${CANDIDATES}/${encodeURIComponent(id)}`);
  }

  nearby(lat: number, lon: number, radius: number): Observable<HouseDto[]> {
    const params = new HttpParams().set('lat', lat).set('lon', lon).set('radius', radius);
    return this.http.get<CandidateDto[]>(`${CANDIDATES}/nearby`, { params }).pipe(map((l) => l.map(normalizeHouse)));
  }

  onStreet(name: string): Observable<HouseDto[]> {
    const params = new HttpParams().set('name', name);
    return this.http.get<CandidateDto[]>(`${CANDIDATES}/street`, { params }).pipe(map((l) => l.map(normalizeHouse)));
  }

  visits(houseId: string): Observable<VisitDto[]> {
    const params = new HttpParams().set('candidateId', houseId);
    return this.http.get<WireVisit[]>('/api/visits', { params }).pipe(map((l) => l.map(visitOfWire)));
  }

  saveVisit(visit: VisitDto): Observable<VisitDto> {
    const body = wireVisit({ ...visit, updatedAt: new Date().toISOString() });
    return this.http.put<WireVisit>(`/api/visits/${encodeURIComponent(visit.id)}`, body).pipe(map(visitOfWire));
  }

  deleteVisit(id: string): Observable<unknown> {
    return this.http.delete(`/api/visits/${encodeURIComponent(id)}`);
  }

  photoIds(houseId: string): Observable<string[]> {
    return this.http.get<string[]>(`${CANDIDATES}/${encodeURIComponent(houseId)}/photos`);
  }

  uploadPhoto(houseId: string, file: Blob, id: string = uuid()): Observable<{ id: string }> {
    const form = new FormData();
    form.append('id', id);
    form.append('file', file, 'photo.jpg');
    return this.http.post<{ id: string }>(`${CANDIDATES}/${encodeURIComponent(houseId)}/photos`, form);
  }

  photo(id: string): Observable<Blob> {
    return this.http.get(`/api/photos/${encodeURIComponent(id)}`, { responseType: 'blob' });
  }

  /**
   * Sends a photo's room, tags and caption (slice 5). The server keeps the newer `metaUpdatedAt`: an older one changes
   * nothing and the answer is the meta it holds, which the sync engine applies when it is newer than ours.
   */
  putPhotoMeta(id: string, meta: PhotoMeta): Observable<PhotoChangeDto> {
    const body = { roomId: meta.roomId, tags: meta.tags, caption: meta.caption, metaUpdatedAt: meta.metaUpdatedAt };
    return this.http.put<WirePhoto>(`/api/photos/${encodeURIComponent(id)}/meta`, body).pipe(map(photoOfWire));
  }

  deletePhoto(id: string): Observable<unknown> {
    return this.http.delete(`/api/photos/${encodeURIComponent(id)}`);
  }
}

/** The server's list of candidates (kinds step 2, #281): the houses since the format reset. */
const CANDIDATES = '/api/candidates';

/**
 * The house of a candidate the server sent, with the fields a row may lack filled in (an empty checklist, status
 * `NEW`), so callers can rely on them.
 */
function normalizeHouse(c: CandidateDto): HouseDto {
  const h = houseOf(c);
  return { ...h, checklist: h.checklist ?? {}, status: h.status ?? 'NEW' };
}

/** The candidate a push sends: the house's, without the search distance (it is only ever an answer). */
function candidateBody(house: HouseDto): CandidateDto {
  const { distanceMeters: _d, ...body } = candidateOf(house);
  return body;
}

/** A visit on the wire: its house is `candidateId` (the server's `VisitDto` since #281). */
type WireVisit = Omit<VisitDto, 'houseId'> & { candidateId?: string | null };
/** A photo change on the wire: its house is `candidateId` (the server's `PhotoDto` since #281). */
type WirePhoto = Omit<PhotoChangeDto, 'houseId'> & { candidateId: string };

/** The visit the app reads: `candidateId` back to `houseId`, the key order kept otherwise. */
function visitOfWire(w: WireVisit): VisitDto {
  return renameKey(w, 'candidateId', 'houseId') as unknown as VisitDto;
}

/** The visit the server takes: `houseId` as `candidateId`, in the same place. */
function wireVisit(v: VisitDto): WireVisit {
  return renameKey(v, 'houseId', 'candidateId') as unknown as WireVisit;
}

/** The photo change the app reads: `candidateId` back to `houseId`. */
function photoOfWire(w: WirePhoto): PhotoChangeDto {
  return renameKey(w, 'candidateId', 'houseId') as unknown as PhotoChangeDto;
}

/** A copy of `row` with the key `from` named `to`, in the same position; a row without it is copied as it is. */
function renameKey(row: object, from: string, to: string): Record<string, unknown> {
  return Object.fromEntries(Object.entries(row).map(([k, v]) => [k === from ? to : k, v]));
}
