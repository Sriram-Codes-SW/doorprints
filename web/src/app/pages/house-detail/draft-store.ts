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

import type { HouseDto } from '../../core/models';

/**
 * Unsaved house edits, kept in sessionStorage so they survive what `beforeunload` cannot warn about: a phone
 * browser discarding the background tab while the user is in WhatsApp copying a number. Android keeps the same
 * draft across rotation and process death (rememberSaveable, HouseEditScreen). sessionStorage, not localStorage:
 * it belongs to this tab only and is gone with it, which is right for half-typed contact details on a shared
 * computer.
 *
 * One entry per route: `new:<lat>,<lon>` for a new house (the position it was opened at, or `new:` without one) and
 * the house id for an existing one. Every access is wrapped: storage may be blocked (private mode, a policy).
 */
export const DRAFT_PREFIX = 'doorprints.houseDraft:';

/** An unsaved house form as kept in sessionStorage. */
export interface StoredDraft {
  draft: HouseDto;
  /** A new house opened without a position: whether the pin had been put. */
  locationSet: boolean;
}

/** The storage key for a route: the house id, or a new house by its starting position. */
export function draftKey(id: string | null, lat: string | null, lon: string | null): string {
  if (id) return DRAFT_PREFIX + id;
  return DRAFT_PREFIX + (lat && lon ? `new:${lat},${lon}` : 'new:');
}

/** Parses a stored draft; null for anything that is not one (a corrupt value, an older shape). */
export function parseStoredDraft(raw: string | null): StoredDraft | null {
  if (!raw) return null;
  try {
    const value: unknown = JSON.parse(raw);
    if (!value || typeof value !== 'object') return null;
    const { draft, locationSet } = value as { draft?: unknown; locationSet?: unknown };
    if (!draft || typeof draft !== 'object') return null;
    const d = draft as Partial<HouseDto>;
    if (typeof d.id !== 'string' || typeof d.label !== 'string') return null;
    if (typeof d.lat !== 'number' || typeof d.lon !== 'number' || !Number.isFinite(d.lat) || !Number.isFinite(d.lon)) {
      return null;
    }
    if (!d.checklist || typeof d.checklist !== 'object') return null;
    return { draft: d as HouseDto, locationSet: locationSet !== false };
  } catch {
    return null;
  }
}

/** The kept draft for `key`, or null when there is none, it is corrupt, or storage is blocked. */
export function readDraft(key: string): StoredDraft | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : parseStoredDraft(sessionStorage.getItem(key));
  } catch {
    return null;
  }
}

/** Keeps the draft; silently does nothing when storage is blocked or full. */
export function writeDraft(key: string, value: StoredDraft): void {
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Blocked or full: the edits are only in memory, as before.
  }
}

/** Forgets the kept draft, after a save, a discard or a deliberate leave. */
export function clearDraft(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    // Blocked: nothing was stored either.
  }
}
