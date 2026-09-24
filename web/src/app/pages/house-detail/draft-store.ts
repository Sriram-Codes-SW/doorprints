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

export interface StoredDraft {
  draft: HouseDto;
  /** A new house opened without a position: whether the pin had been put. */
  locationSet: boolean;
}

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

export function readDraft(key: string): StoredDraft | null {
  try {
    return typeof sessionStorage === 'undefined' ? null : parseStoredDraft(sessionStorage.getItem(key));
  } catch {
    return null;
  }
}

export function writeDraft(key: string, value: StoredDraft): void {
  try {
    sessionStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Blocked or full: the edits are only in memory, as before.
  }
}

export function clearDraft(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    // Blocked: nothing was stored either.
  }
}
