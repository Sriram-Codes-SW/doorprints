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
 * Photo tags (docs/11 5.7, slice 5): the fixed tags, the limits and the pure rules. The twin of Kotlin `PhotoTags` in
 * android/shared, pinned by the same vector M6 (`photo-tags.spec.ts`, `PhotoTagsTest`).
 *
 * A tag is a fixed key or a custom text of 1..30 characters. No two tags are equal ignoring case, and a custom tag
 * may not equal a fixed key in any case (it would be the fixed tag under another spelling).
 */

import type { TKey } from '../i18n/en';

export const FIXED_TAGS = [
  'EXTERIOR',
  'ENTRANCE',
  'KITCHEN_FITTINGS',
  'BATHROOM_FITTINGS',
  'DAMP',
  'CRACK',
  'LEAK',
  'VIEW',
  'WATER_TANK',
  'METER',
  'PARKING',
  'LIFT',
  'GOOD_POINT',
  'PROBLEM',
  'MOVE_IN',
] as const;

/** One of the built-in photo tags. */
export type FixedTag = (typeof FIXED_TAGS)[number];

/** The tag the condition record (docs/11 5.24) is made of. */
export const MOVE_IN_TAG: FixedTag = 'MOVE_IN';

export const MAX_TAGS = 10;
export const MAX_TAG_LENGTH = 30;
export const MAX_CAPTION = 200;
export const MAX_PHOTO_ROOM_ID = 64;

/** The translation key of a fixed tag (`photoTag.KITCHEN_FITTINGS`), or null for a custom tag, which is shown as typed. */
export function photoTagKey(tag: string): TKey | null {
  return isFixedTag(tag) ? (`photoTag.${tag}` as TKey) : null;
}

/** Whether the tag is one of the built-in tags. */
export function isFixedTag(tag: string): tag is FixedTag {
  return (FIXED_TAGS as readonly string[]).includes(tag);
}

/** Why a list of tags is refused; `null` from {@link validate} when it is fine. */
export type TagProblem = 'TOO_MANY' | 'EMPTY' | 'TOO_LONG' | 'FIXED_KEY' | 'DUPLICATE';

/**
 * The tags as the store keeps them (what a client does with a row it reads): a fixed key in any case is the fixed key,
 * any other text of 1..30 characters is a custom tag (an unknown fixed-looking key included), a blank or over-long one
 * is dropped, a repeat (ignoring case) is dropped, and only the first 10 are kept.
 */
export function coerced(raw: readonly unknown[] | null | undefined): string[] {
  if (!Array.isArray(raw)) return [];
  const seen = new Set<string>();
  const out: string[] = [];
  for (const item of raw) {
    if (typeof item !== 'string') continue;
    const trimmed = item.trim();
    if (trimmed === '' || trimmed.length > MAX_TAG_LENGTH) continue;
    const upper = trimmed.toUpperCase();
    const tag = isFixedTag(upper) ? upper : trimmed;
    const key = tag.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(tag);
    if (out.length === MAX_TAGS) break;
  }
  return out;
}

/** The first reason the list is refused (a server PUT and a file are refused for it), or `null`. */
export function validate(tags: readonly string[]): TagProblem | null {
  if (tags.length > MAX_TAGS) return 'TOO_MANY';
  const seen = new Set<string>();
  for (const tag of tags) {
    if (tag.trim() === '') return 'EMPTY';
    if (tag.length > MAX_TAG_LENGTH) return 'TOO_LONG';
    if (!isFixedTag(tag) && isFixedTag(tag.toUpperCase())) return 'FIXED_KEY';
    const key = tag.toLowerCase();
    if (seen.has(key)) return 'DUPLICATE';
    seen.add(key);
  }
  return null;
}

/** The tags with `tag` added by the editor (a fixed key in any case is that fixed tag): the same list when it is blank, a repeat or the list is full. */
export function withTag(tags: readonly string[], tag: string): string[] {
  if (tags.length >= MAX_TAGS) return [...tags];
  const next = coerced([...tags, tag]);
  return next.length > tags.length ? next : [...tags];
}

/** The tags without `tag` (compared ignoring case). */
export function withoutTag(tags: readonly string[], tag: string): string[] {
  return tags.filter((t) => t.toLowerCase() !== tag.toLowerCase());
}

/** The meta of one photo as the screens and the wire hold it. */
export interface PhotoMeta {
  roomId: string | null;
  tags: string[];
  caption: string | null;
  /** Epoch milliseconds of the last edit; 0 = never edited. */
  metaUpdatedAt: number;
}

/**
 * Photo meta as the store keeps it: the room id when 1..64 characters, the tags coerced, the caption cut at 200
 * characters (null when blank), `metaUpdatedAt` a whole number >= 0.
 */
export function cleanMeta(raw: {
  roomId?: unknown;
  tags?: unknown;
  caption?: unknown;
  metaUpdatedAt?: unknown;
} | null | undefined): PhotoMeta {
  const roomId = typeof raw?.roomId === 'string' && raw.roomId !== '' && raw.roomId.length <= MAX_PHOTO_ROOM_ID ? raw.roomId : null;
  const caption = typeof raw?.caption === 'string' && raw.caption.trim() !== '' ? raw.caption.slice(0, MAX_CAPTION) : null;
  const at = typeof raw?.metaUpdatedAt === 'number' && Number.isFinite(raw.metaUpdatedAt) ? Math.round(raw.metaUpdatedAt) : 0;
  return {
    roomId,
    tags: coerced(Array.isArray(raw?.tags) ? (raw.tags as unknown[]) : null),
    caption,
    metaUpdatedAt: at < 0 ? 0 : at,
  };
}

/** True when a photo has any meta to show or write. */
export function hasMeta(meta: Pick<PhotoMeta, 'roomId' | 'tags' | 'caption' | 'metaUpdatedAt'>): boolean {
  return !!meta.roomId || meta.tags.length > 0 || !!meta.caption || meta.metaUpdatedAt > 0;
}

/** Last write wins on `metaUpdatedAt`: an incoming edit replaces the stored one only when it is strictly newer. */
export function incomingWins(storedAt: number, incomingAt: number): boolean {
  return incomingAt > storedAt;
}

/** The same helpers as one object, the twin of Kotlin's `PhotoTags`. */
export const PhotoTags = { FIXED: FIXED_TAGS, MAX: MAX_TAGS, coerced, validate } as const;
