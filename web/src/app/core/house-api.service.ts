import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { HouseDto, PhotoChangeDto, StatsDto, VisitDto, uuid } from './models';

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
    return this.http.get<HouseDto[]>('/api/houses').pipe(map((list) => list.map(normalizeHouse)));
  }

  /**
   * Every house changed after `since` (tombstones included), in sync-version order. The sync engine
   * (data/sync.service.ts) uses this; `since = 0` pulls everything, which is the first-run migration.
   */
  housesSince(since: number): Observable<HouseDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<HouseDto[]>('/api/houses', { params }).pipe(map((l) => l.map(normalizeHouse)));
  }

  visitsSince(since: number): Observable<VisitDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<VisitDto[]>('/api/visits', { params });
  }

  /** Photo metadata changes (new photos and delete tombstones) after a sync version. */
  photoChangesSince(since: number): Observable<PhotoChangeDto[]> {
    const params = new HttpParams().set('since', since);
    return this.http.get<PhotoChangeDto[]>('/api/photos', { params });
  }

  house(id: string): Observable<HouseDto> {
    return this.http.get<HouseDto>(`/api/houses/${encodeURIComponent(id)}`).pipe(map(normalizeHouse));
  }

  /**
   * Pushes a row exactly as it is stored locally, keeping its `updatedAt`. The sync engine must not restamp the
   * time: the stored value is what last-write-wins compares on every device (data/sync-rules.ts).
   */
  pushHouse(house: HouseDto): Observable<HouseDto> {
    const body: HouseDto = { ...house };
    delete body.distanceMeters;
    return this.http
      .put<HouseDto>(`/api/houses/${encodeURIComponent(house.id)}`, body)
      .pipe(map(normalizeHouse));
  }

  pushVisit(visit: VisitDto): Observable<VisitDto> {
    return this.http.put<VisitDto>(`/api/visits/${encodeURIComponent(visit.id)}`, visit);
  }

  saveHouse(house: HouseDto): Observable<HouseDto> {
    const body: HouseDto = { ...house, updatedAt: new Date().toISOString() };
    delete body.distanceMeters;
    return this.http
      .put<HouseDto>(`/api/houses/${encodeURIComponent(house.id)}`, body)
      .pipe(map(normalizeHouse));
  }

  deleteHouse(id: string): Observable<unknown> {
    return this.http.delete(`/api/houses/${encodeURIComponent(id)}`);
  }

  nearby(lat: number, lon: number, radius: number): Observable<HouseDto[]> {
    const params = new HttpParams().set('lat', lat).set('lon', lon).set('radius', radius);
    return this.http.get<HouseDto[]>('/api/houses/nearby', { params }).pipe(map((l) => l.map(normalizeHouse)));
  }

  onStreet(name: string): Observable<HouseDto[]> {
    const params = new HttpParams().set('name', name);
    return this.http.get<HouseDto[]>('/api/houses/street', { params }).pipe(map((l) => l.map(normalizeHouse)));
  }

  visits(houseId: string): Observable<VisitDto[]> {
    const params = new HttpParams().set('houseId', houseId);
    return this.http.get<VisitDto[]>('/api/visits', { params });
  }

  saveVisit(visit: VisitDto): Observable<VisitDto> {
    const body: VisitDto = { ...visit, updatedAt: new Date().toISOString() };
    return this.http.put<VisitDto>(`/api/visits/${encodeURIComponent(visit.id)}`, body);
  }

  deleteVisit(id: string): Observable<unknown> {
    return this.http.delete(`/api/visits/${encodeURIComponent(id)}`);
  }

  photoIds(houseId: string): Observable<string[]> {
    return this.http.get<string[]>(`/api/houses/${encodeURIComponent(houseId)}/photos`);
  }

  uploadPhoto(houseId: string, file: Blob, id: string = uuid()): Observable<{ id: string }> {
    const form = new FormData();
    form.append('id', id);
    form.append('file', file, 'photo.jpg');
    return this.http.post<{ id: string }>(`/api/houses/${encodeURIComponent(houseId)}/photos`, form);
  }

  photo(id: string): Observable<Blob> {
    return this.http.get(`/api/photos/${encodeURIComponent(id)}`, { responseType: 'blob' });
  }

  deletePhoto(id: string): Observable<unknown> {
    return this.http.delete(`/api/photos/${encodeURIComponent(id)}`);
  }
}

function normalizeHouse(h: HouseDto): HouseDto {
  return { ...h, checklist: h.checklist ?? {}, status: h.status ?? 'NEW' };
}
