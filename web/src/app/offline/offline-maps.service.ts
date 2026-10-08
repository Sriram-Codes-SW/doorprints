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

/**
 * Offline maps on the website (docs/11 5.20, S4b-BL-79): what the Map and Your data ask of the saved areas. Saves the
 * box on the screen (the download is `offline-download.ts`), lists the areas, deletes one (only the files no other area
 * needs) and reports the browser's storage. The tiles live in Cache Storage and are served to MapLibre by
 * `offline-protocol.ts`; the list is in localStorage (`offline-store.ts`). Zero cost: no server, no library.
 */

import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import { MAP_STYLE_URL } from '../shared/map-style-url';
import { OfflineDownloadError, downloadArea } from './offline-download';
import { OFFLINE_CACHE, setOfflineActive, type CacheLike } from './offline-protocol';
import { areaStorage, loadAreas, storeAreas, type AreaStorage } from './offline-store';
import {
  countTiles,
  estimateAreaBytes,
  freeBytes,
  keysToEvict,
  refusal,
  type GeoBounds,
  type Refusal,
  type SavedArea,
} from './offline-tiles';

/** The browser, as the service sees it (fakes in the unit tests). */
export interface OfflineEnv {
  /** Cache Storage's offline cache, or null where there is none (an insecure page, some private modes). */
  openCache(): Promise<CacheLike | null>;
  /** Deletes the whole offline cache. */
  deleteCache(): Promise<void>;
  fetch(url: string, init?: { signal?: AbortSignal }): Promise<Response>;
  /** `navigator.storage.estimate()`, or null when the browser cannot say. */
  storageEstimate(): Promise<{ usage: number; quota: number } | null>;
  /** True on mobile data or with Data Saver on, false on a connection that is neither, null when the browser cannot tell. */
  metered(): boolean | null;
  online(): boolean;
  readonly storage: AreaStorage | null;
  /** Whether Cache Storage exists here (HTTPS or localhost; not in every private mode). */
  readonly hasCache: boolean;
  now(): number;
  newId(): string;
}

/**
 * The real browser environment: Cache Storage, fetch, the storage estimate and the connection type, each guarded so a missing or refusing API reads as "not available".
 */
function browserEnv(): OfflineEnv {
  return {
    openCache: async () => {
      try {
        return typeof caches === 'undefined' ? null : await caches.open(OFFLINE_CACHE);
      } catch {
        return null;
      }
    },
    deleteCache: async () => {
      try {
        if (typeof caches !== 'undefined') await caches.delete(OFFLINE_CACHE);
      } catch {
        // Cache Storage refused: the list is emptied anyway, and the files are only unreachable.
      }
    },
    fetch: (url, init) => fetch(url, init),
    storageEstimate: async () => {
      try {
        const e = await navigator.storage?.estimate?.();
        return e && typeof e.quota === 'number' ? { usage: e.usage ?? 0, quota: e.quota } : null;
      } catch {
        return null;
      }
    },
    metered: () => {
      const c = (typeof navigator === 'undefined' ? undefined : (navigator as { connection?: { saveData?: boolean; type?: string } }).connection);
      if (!c) return null;
      return c.saveData === true || c.type === 'cellular';
    },
    online: () => typeof navigator === 'undefined' || navigator.onLine !== false,
    storage: areaStorage(),
    hasCache: typeof caches !== 'undefined',
    now: () => Date.now(),
    newId: () => (typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`),
  };
}

/** Injection token for the browser environment; the default is the real one. */
export const OFFLINE_ENV = new InjectionToken<OfflineEnv>('OFFLINE_ENV', { providedIn: 'root', factory: browserEnv });

/** What a save came to. */
export type SaveOutcome = 'saved' | 'cancelled' | 'failed' | 'busy' | 'unsupported' | 'offline' | Refusal;

/** What the dialog shows before the download. */
export interface SavePlan {
  readonly tiles: number;
  readonly bytes: number;
  readonly refusal: Refusal | null;
  /** Room left in the browser's storage, or null when the browser does not say. */
  readonly free: number | null;
  readonly metered: boolean | null;
  readonly online: boolean;
}

/** The download as the page shows it. */
export interface SaveProgress {
  readonly name: string;
  readonly done: number;
  readonly total: number;
  readonly bytes: number;
}

/**
 * Saves parts of the base map in the browser so the map still draws without a network, and lists, verifies and removes them.
 * It owns the list of saved areas (kept in localStorage, with the tiles in Cache Storage), one download at a time with progress and cancel, and the flag that turns on the offline map requests. A cancelled or failed download removes what it saved.
 */
@Injectable({ providedIn: 'root' })
export class OfflineMapsService {
  private readonly env = inject(OFFLINE_ENV);

  /** The saved areas, newest first. */
  readonly areas = signal<readonly SavedArea[]>([]);
  /** The download in progress, or null. */
  readonly progress = signal<SaveProgress | null>(null);
  /** The browser's storage (usage and quota), or null when it does not say. */
  readonly storage = signal<{ usage: number; quota: number } | null>(null);
  /** False where Cache Storage or localStorage is missing: no button, a note in Your data. */
  readonly supported = signal(this.env.storage !== null && this.env.hasCache);
  readonly saving = computed(() => this.progress() !== null);
  /** What the saved areas take, in bytes. */
  readonly usedBytes = computed(() => this.areas().reduce((sum, a) => sum + (a.state === 'ready' ? a.bytes : 0), 0));

  private controller: AbortController | null = null;

  constructor() {
    const list = loadAreas(this.env.storage);
    // An area left "saving" by a tab that was closed mid-download is not usable: shown as failed, for the person to delete.
    const fixed = list.map((a) => (a.state === 'saving' ? { ...a, state: 'failed' as const } : a));
    this.areas.set(fixed);
    if (fixed.some((a, i) => a !== list[i])) storeAreas(this.env.storage, fixed);
    else setOfflineActive(fixed.some((a) => a.state === 'ready'));
  }

  /** Reads the browser's storage figures again. */
  async refreshStorage(): Promise<void> {
    this.storage.set(await this.env.storageEstimate());
  }

  /**
   * Checks that each ready area's style file is still in the cache (a browser may clear a site's storage), and marks
   * the ones that are gone as failed so the list does not promise a map that is not there.
   */
  async verify(): Promise<void> {
    const cache = await this.env.openCache();
    const list = this.areas();
    if (!cache || list.length === 0) return;
    const next: SavedArea[] = [];
    for (const a of list) {
      const styleKey = a.assets[0];
      const present = a.state !== 'ready' || !styleKey || (await cache.match(styleKey)) !== undefined;
      next.push(present ? a : { ...a, state: 'failed' });
    }
    if (next.some((a, i) => a !== list[i])) this.commit(next);
  }

  /** The numbers the dialog shows for `bounds`: tiles, the estimate, why it cannot be saved and the room left. */
  async plan(bounds: GeoBounds): Promise<SavePlan> {
    await this.refreshStorage();
    const tiles = countTiles(bounds);
    const bytes = estimateAreaBytes(bounds);
    const storage = this.storage();
    return {
      tiles,
      bytes,
      refusal: refusal(tiles, bytes, this.areas().length, storage),
      free: freeBytes(storage),
      metered: this.env.metered(),
      online: this.env.online(),
    };
  }

  /** Saves `bounds` as `name`. Resolves when the download is over; {@link cancel} stops it. */
  async save(name: string, bounds: GeoBounds): Promise<SaveOutcome> {
    if (this.controller) return 'busy';
    if (!this.supported()) return 'unsupported';
    if (!this.env.online()) return 'offline';
    await this.refreshStorage();
    const tiles = countTiles(bounds);
    const refused = refusal(tiles, estimateAreaBytes(bounds), this.areas().length, this.storage());
    if (refused) return refused;
    const cache = await this.env.openCache();
    if (!cache) return 'unsupported';

    const label = name.trim().slice(0, 60);
    const controller = new AbortController();
    this.controller = controller;
    this.progress.set({ name: label, done: 0, total: tiles, bytes: 0 });
    let planned: SavedArea | null = null;
    try {
      const area = await downloadArea(
        { id: this.env.newId(), name: label, bounds, now: this.env.now() },
        { cache, fetch: (u, i) => this.env.fetch(u, i), styleUrl: MAP_STYLE_URL },
        {
          signal: controller.signal,
          planned: (a) => {
            planned = a;
            this.commit([a, ...this.areas()]);
          },
          progress: (p) => this.progress.set({ name: label, done: p.done, total: p.total, bytes: p.bytes }),
        },
      );
      this.commit(this.areas().map((a) => (a.id === area.id ? area : a)));
      void this.refreshStorage();
      return 'saved';
    } catch (e) {
      const partial = planned as SavedArea | null;
      if (partial) await this.dropFiles(partial);
      if (partial) this.commit(this.areas().filter((a) => a.id !== partial.id));
      if (controller.signal.aborted) return 'cancelled';
      if (e instanceof OfflineDownloadError && e.reason === 'too-large') return 'too-large';
      return 'failed';
    } finally {
      this.progress.set(null);
      this.controller = null;
    }
  }

  /** Stops the download in progress; what it saved so far is removed. */
  cancel(): void {
    this.controller?.abort();
  }

  /** Deletes an area and the files only it needed. */
  async remove(id: string): Promise<void> {
    const area = this.areas().find((a) => a.id === id);
    if (!area) return;
    const remaining = this.areas().filter((a) => a.id !== id);
    await this.dropFiles(area, remaining);
    this.commit(remaining);
    void this.refreshStorage();
  }

  /** "Remove all data": every saved area and the whole cache. */
  async removeAll(): Promise<void> {
    this.controller?.abort();
    await this.env.deleteCache();
    this.areas.set([]);
    setOfflineActive(false);
  }

  private async dropFiles(area: SavedArea, remaining: readonly SavedArea[] = this.areas()): Promise<void> {
    const cache = await this.env.openCache();
    if (!cache) return;
    for (const key of keysToEvict(area, remaining)) {
      try {
        await cache.delete(key);
      } catch {
        // A file that cannot be deleted is only wasted room.
      }
    }
  }

  private commit(list: readonly SavedArea[]): void {
    const sorted = [...list].sort((a, b) => b.savedAt - a.savedAt);
    this.areas.set(sorted);
    storeAreas(this.env.storage, sorted);
  }
}
