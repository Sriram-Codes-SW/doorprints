import { houseScore } from '../core/models';
import type { Lang } from '../i18n/languages';
import type { HouseRecord, PhotoRecord, VisitRecord } from '../data/records';

/** The six deterministic formats of docs/11 §5.2. */
export type ExportFormat = 'html' | 'pdf' | 'csv' | 'xlsx' | 'markdown' | 'backup';

export interface ExportOptions {
  /** Which houses go in: everything, only the shortlist, or the ids the user ticked. */
  scope: 'all' | 'shortlisted' | 'selected';
  /** Only read when `scope` is `selected`. */
  selectedIds: readonly string[];
  /** Rejected houses are useful ("we saw it, it was bad"), but the user can leave them out. */
  includeRejected: boolean;
  photos: 'all' | 'shortlisted' | 'none';
  /** Off means phone numbers and names of owners and brokers are left out of the file entirely. */
  includeContacts: boolean;
  /** The language the file is written in; independent of the language the app is being used in. */
  lang: Lang;
}

export const DEFAULT_EXPORT_OPTIONS: ExportOptions = {
  scope: 'all',
  selectedIds: [],
  includeRejected: true,
  photos: 'all',
  includeContacts: true,
  lang: 'en',
};

/** One house with everything that belongs to it, already filtered and ordered. */
export interface ExportHouse {
  house: HouseRecord;
  /** 0–5 overall score, or null when nothing has been scored (same rule as the app: models.houseScore). */
  score: number | null;
  visits: readonly VisitRecord[];
  photos: readonly PhotoRecord[];
}

/** Everything an exporter needs. Built once, then handed to each format writer. */
export interface ExportBundle {
  /** The one timestamp that appears inside a file (cover and manifest); everything else is data. */
  exportedAt: string;
  options: ExportOptions;
  houses: readonly ExportHouse[];
  /** Houses ordered best first, for the ranking table: score desc, then label, then id. */
  ranking: readonly ExportHouse[];
  counts: { houses: number; visits: number; photos: number };
}

/** The contact fields; they are blanked rather than removed so every export has the same shape. */
const CONTACT_FIELDS = ['contactName', 'contactPhone'] as const;

export interface CollectInput {
  houses: readonly HouseRecord[];
  visits: readonly VisitRecord[];
  photos: readonly PhotoRecord[];
  exportedAt: string;
  options: ExportOptions;
}

/**
 * Turns the raw store contents into the bundle, applying scope, contacts and photo options.
 *
 * Ordering is fixed at every level — houses and photos by `createdAt` then `id`, visits by `arrivedAt` then `id`,
 * checklist keys alphabetically — so two exports of the same data are byte-for-byte identical (docs/11 §5.2).
 * Tombstones are never exported.
 */
export function collect(input: CollectInput): ExportBundle {
  const { options } = input;
  const live = input.houses.filter((h) => !h.deleted);
  const chosen = live
    .filter((h) => (options.scope === 'shortlisted' ? h.status === 'SHORTLISTED' : true))
    .filter((h) => (options.scope === 'selected' ? options.selectedIds.includes(h.id) : true))
    .filter((h) => options.includeRejected || h.status !== 'REJECTED')
    .map(stripContacts(options.includeContacts))
    .sort(byCreatedThenId);

  const visitsByHouse = new Map<string, VisitRecord[]>();
  for (const visit of input.visits) {
    if (visit.deleted || !visit.houseId) continue;
    const list = visitsByHouse.get(visit.houseId) ?? [];
    list.push(visit);
    visitsByHouse.set(visit.houseId, list);
  }

  const photosByHouse = new Map<string, PhotoRecord[]>();
  for (const photo of input.photos) {
    if (photo.deleted) continue;
    const list = photosByHouse.get(photo.houseId) ?? [];
    list.push(photo);
    photosByHouse.set(photo.houseId, list);
  }

  const houses: ExportHouse[] = chosen.map((house) => {
    const wantPhotos =
      options.photos === 'all' || (options.photos === 'shortlisted' && house.status === 'SHORTLISTED');
    return {
      house,
      score: houseScore(house),
      visits: (visitsByHouse.get(house.id) ?? [])
        .slice()
        .sort((a, b) => compare(a.arrivedAt, b.arrivedAt) || compare(a.id, b.id)),
      photos: wantPhotos ? (photosByHouse.get(house.id) ?? []).slice().sort(byCreatedThenId) : [],
    };
  });

  return {
    exportedAt: input.exportedAt,
    options,
    houses,
    ranking: rank(houses),
    counts: {
      houses: houses.length,
      visits: houses.reduce((n, h) => n + h.visits.length, 0),
      photos: houses.reduce((n, h) => n + h.photos.length, 0),
    },
  };
}

/**
 * Best first, with unscored houses last. The tie-break is `createdAt` then `id`, matching
 * `ExportBundle.ranked` in `android/shared/.../export/ExportModel.kt`, so the ranking table of a phone copy and
 * of a browser copy of the same data lists the houses in the same order.
 */
export function rank(houses: readonly ExportHouse[]): ExportHouse[] {
  return houses.slice().sort((a, b) => {
    // HouseScore.rankKey: a missing score sorts as -1, below a real 0.
    const sa = a.score ?? -1;
    const sb = b.score ?? -1;
    if (sa !== sb) return sb - sa;
    return compare(a.house.createdAt ?? '', b.house.createdAt ?? '') || compare(a.house.id, b.house.id);
  });
}

/**
 * Checklist scores with the keys in alphabetical order. Object key order is what `JSON.stringify` writes, so
 * sorting here is what makes a backup byte-stable no matter what order the store handed the keys back in.
 */
export function sortedChecklist(checklist: Record<string, number>): Record<string, number> {
  const out: Record<string, number> = {};
  for (const key of Object.keys(checklist).sort()) out[key] = checklist[key];
  return out;
}

function stripContacts(include: boolean): (house: HouseRecord) => HouseRecord {
  if (include) return (house) => house;
  return (house) => {
    const copy = { ...house };
    for (const field of CONTACT_FIELDS) copy[field] = null;
    return copy;
  };
}

function byCreatedThenId<T extends { createdAt?: string | null; id: string }>(a: T, b: T): number {
  return compare(a.createdAt ?? '', b.createdAt ?? '') || compare(a.id, b.id);
}

function compare(a: string, b: string): number {
  // Code-unit comparison, not localeCompare: collation differs between browsers and would break determinism.
  return a < b ? -1 : a > b ? 1 : 0;
}
