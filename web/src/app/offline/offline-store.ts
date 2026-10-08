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
 * Offline maps on the website (S4b-BL-79): the list of saved areas, kept in localStorage (small: a name, a box and a
 * few dozen addresses per area; the tiles are in Cache Storage). Parsed defensively, since it is read from storage a
 * newer or older build, or a person, may have written.
 */

import { OFFLINE_AREAS_KEY } from '../core/storage-keys';
import { MAX_AREAS, MAX_ZOOM, type SavedArea } from './offline-tiles';
import { setOfflineActive } from './offline-protocol';

/** The part of `Storage` this needs (a fake in the unit tests). */
export type AreaStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function isNum(n: unknown): n is number {
  return typeof n === 'number' && Number.isFinite(n);
}

function isStr(s: unknown): s is string {
  return typeof s === 'string';
}

/**
 * Reads one saved area from untrusted stored JSON, or null when it is unusable; missing optional parts get safe defaults and the zoom is clamped.
 */
function parseArea(raw: unknown): SavedArea | null {
  if (!raw || typeof raw !== 'object') return null;
  const a = raw as Record<string, unknown>;
  const b = a['bounds'] as Record<string, unknown> | undefined;
  if (!isStr(a['id']) || !isStr(a['name']) || !b || ![b['south'], b['west'], b['north'], b['east']].every(isNum)) return null;
  const state = a['state'];
  if (state !== 'saving' && state !== 'ready' && state !== 'failed') return null;
  const sources = Array.isArray(a['sources']) ? a['sources'] : [];
  const assets = Array.isArray(a['assets']) ? a['assets'] : [];
  const maxZoom = isNum(a['maxZoom']) ? Math.min(MAX_ZOOM, Math.max(0, Math.round(a['maxZoom']))) : MAX_ZOOM;
  return {
    id: a['id'],
    name: a['name'],
    bounds: { south: b['south'] as number, west: b['west'] as number, north: b['north'] as number, east: b['east'] as number },
    sources: sources
      .filter((s): s is { template: string; maxZoom: number } => !!s && isStr((s as { template?: unknown }).template) && isNum((s as { maxZoom?: unknown }).maxZoom))
      .map((s) => ({ template: s.template, maxZoom: s.maxZoom })),
    maxZoom,
    assets: assets.filter(isStr),
    tiles: isNum(a['tiles']) ? a['tiles'] : 0,
    bytes: isNum(a['bytes']) ? a['bytes'] : 0,
    state,
    savedAt: isNum(a['savedAt']) ? a['savedAt'] : 0,
  };
}

/** The saved areas, newest first; an empty list when nothing is stored or what is stored cannot be read. */
export function loadAreas(storage: AreaStorage | null): SavedArea[] {
  if (!storage) return [];
  try {
    const text = storage.getItem(OFFLINE_AREAS_KEY);
    if (!text) return [];
    const parsed: unknown = JSON.parse(text);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .map(parseArea)
      .filter((a): a is SavedArea => a !== null)
      .slice(0, MAX_AREAS * 2)
      .sort((x, y) => y.savedAt - x.savedAt);
  } catch {
    return [];
  }
}

/** Writes the list; false when the browser refused (storage full or blocked). Also tells the map hook whether any area is saved. */
export function storeAreas(storage: AreaStorage | null, areas: readonly SavedArea[]): boolean {
  setOfflineActive(areas.some((a) => a.state === 'ready'));
  if (!storage) return false;
  try {
    if (areas.length === 0) storage.removeItem(OFFLINE_AREAS_KEY);
    else storage.setItem(OFFLINE_AREAS_KEY, JSON.stringify(areas));
    return true;
  } catch {
    return false;
  }
}

/** This origin's localStorage, or null where it is missing or blocked. */
export function areaStorage(): AreaStorage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage;
  } catch {
    return null;
  }
}

/** Sets the map hook from what is stored: called when a map is first made, before the style is requested. */
export function syncOfflineActive(storage: AreaStorage | null = areaStorage()): void {
  setOfflineActive(loadAreas(storage).some((a) => a.state === 'ready'));
}
