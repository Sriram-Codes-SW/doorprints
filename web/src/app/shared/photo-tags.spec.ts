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

import { describe, expect, it } from 'vitest';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { FIXED_TAGS, MAX_CAPTION, PhotoTags, cleanMeta, coerced, hasMeta, incomingWins, photoTagKey, validate, withTag, withoutTag } from './photo-tags';

/** Photo tags (docs/11 5.7): the vector M6 of Kotlin `PhotoTagsTest`, and the meta rules around it. */
describe('PhotoTags', () => {
  it('m6_a_custom_tag_equal_to_a_fixed_key_in_any_case_is_refused', () => {
    expect(validate(['DAMP'])).toBeNull();
    expect(validate(['damp'])).toBe('FIXED_KEY');
    expect(validate(['Kitchen_Fittings'])).toBe('FIXED_KEY');
    expect(validate(['damp corner'])).toBeNull();
    // Read from a file or the wire it becomes the fixed tag.
    expect(coerced(['damp', 'Move_In'])).toEqual(['DAMP', 'MOVE_IN']);
  });

  it('m6_a_repeated_tag_is_dropped_on_read_and_refused_in_a_file', () => {
    expect(coerced(['leaky tap', 'Leaky Tap', 'DAMP', 'DAMP', 'damp'])).toEqual(['leaky tap', 'DAMP']);
    expect(validate(['leaky tap', 'LEAKY TAP'])).toBe('DUPLICATE');
    expect(validate(['DAMP', 'DAMP'])).toBe('DUPLICATE');
  });

  it('m6_more_than_ten_tags_are_refused_and_a_client_keeps_the_first_ten', () => {
    const eleven = Array.from({ length: 11 }, (_, i) => `tag ${i}`);
    expect(validate(eleven.slice(0, 10))).toBeNull();
    expect(validate(eleven)).toBe('TOO_MANY');
    expect(coerced(eleven)).toEqual(eleven.slice(0, 10));
  });

  it('m6_a_tag_over_thirty_characters_or_blank_is_refused_and_dropped_on_read', () => {
    const long = 'x'.repeat(31);
    expect(validate(['x'.repeat(30)])).toBeNull();
    expect(validate([long])).toBe('TOO_LONG');
    expect(validate([' '])).toBe('EMPTY');
    expect(coerced([long, '  ', 'ok', 7 as unknown as string])).toEqual(['ok']);
  });

  it('m6_an_unknown_fixed_looking_key_is_kept_as_a_custom_tag', () => {
    expect(coerced(['MOVE_OUT'])).toEqual(['MOVE_OUT']);
    expect(validate(['MOVE_OUT'])).toBeNull();
    expect(PhotoTags.FIXED).toBe(FIXED_TAGS);
    expect(FIXED_TAGS).toHaveLength(15);
  });

  it('adds and removes tags the way the editor does', () => {
    expect(withTag(['DAMP'], 'leaky tap')).toEqual(['DAMP', 'leaky tap']);
    expect(withTag(['DAMP'], 'damp')).toEqual(['DAMP']);
    expect(withTag(['DAMP'], '  ')).toEqual(['DAMP']);
    expect(withTag(Array.from({ length: 10 }, (_, i) => `t${i}`), 'eleventh')).toHaveLength(10);
    expect(withoutTag(['DAMP', 'Leaky Tap'], 'leaky tap')).toEqual(['DAMP']);
  });

  it('coerces the meta: the room up to 64 characters, the caption cut at 200, the stamp a whole number from 0', () => {
    expect(cleanMeta({ roomId: 'r'.repeat(65), caption: '   ', metaUpdatedAt: -5 })).toEqual({ roomId: null, tags: [], caption: null, metaUpdatedAt: 0 });
    const meta = cleanMeta({ roomId: 'c1', tags: ['damp'], caption: 'c'.repeat(300), metaUpdatedAt: 12.4 });
    expect(meta.caption).toHaveLength(MAX_CAPTION);
    expect(meta).toMatchObject({ roomId: 'c1', tags: ['DAMP'], metaUpdatedAt: 12 });
    expect(hasMeta(cleanMeta(null))).toBe(false);
    expect(hasMeta(cleanMeta({ tags: ['DAMP'] }))).toBe(true);
  });

  it('lets only a strictly newer metaUpdatedAt replace the stored meta', () => {
    expect(incomingWins(5, 6)).toBe(true);
    expect(incomingWins(5, 5)).toBe(false);
    expect(incomingWins(5, 4)).toBe(false);
  });

  it('has a translated name in all four languages for every fixed tag, and none for a custom one', () => {
    for (const tag of FIXED_TAGS) {
      const key = photoTagKey(tag);
      expect(key, tag).not.toBeNull();
      for (const lang of ['en', 'hi', 'ta', 'te'] as const) expect((DICTIONARIES[lang] as Record<string, string>)[key as string], `${lang}/${tag}`).toBeTruthy();
    }
    expect(photoTagKey('damp corner')).toBeNull();
  });
});
