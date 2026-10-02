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
import { encodeSyncFile, nestingDepth, parseSyncFile, SYNC_MAX_BYTES, SYNC_MAX_ROWS, SYNC_MAX_ROWS_OF, SyncFileError, syncFile, utf8Length } from './sync-file';
import type { SyncFileProblem, SyncRow } from './sync-file';
import { simRow } from './sync-sim';

/**
 * The `sync/1` file (S4b-BL-130): the caps that the shared vectors cannot carry (a 16 MiB file, 20 001 rows), the
 * canonical writer, and the UTF-8 count. The problem codes are in `sync-vectors.spec.ts`. Twin of Kotlin's `SyncFileTest`.
 */

const a = 'device-a01';
const now = 1_790_000_000_000;

function problemOf(run: () => unknown): SyncFileProblem {
  try {
    run();
  } catch (e) {
    if (e instanceof SyncFileError) return e.problem;
    throw e;
  }
  throw new Error('expected a SyncFileError');
}
const problem = (text: string, device = a) => problemOf(() => parseSyncFile(text, device));

const envelope = (houses: string, extra = '') =>
  `{"format":"doorprints-sync/1","deviceId":"${a}","seq":1,"writtenAt":"2026-09-21T14:13:20Z","houses":[${houses}],"visits":[],"records":[],"photos":[]${extra}}`;

describe('sync file', () => {
  it('is written canonically and reads back', () => {
    const rows = {
      houses: [simRow('houses/h2', now, a, false), simRow('houses/h1', now - 1, a, true)],
      records: [simRow('records/note/r1', now, a, false), simRow('records/area/r1', now, a, false)],
    };
    const text = encodeSyncFile(syncFile(a, 7, now, rows));
    const root = JSON.parse(text) as Record<string, Record<string, string>[]>;
    expect(Object.keys(root)).toEqual(['format', 'deviceId', 'seq', 'writtenAt', 'houses', 'visits', 'records', 'photos']);
    expect(root['houses'].map((r) => r['id'])).toEqual(['h1', 'h2']);
    expect(root['records'].map((r) => r['type'])).toEqual(['area', 'note']);
    const back = parseSyncFile(text, a);
    expect(back.seq).toBe(7);
    expect(back.writtenAt).toBe(now);
    expect(back.rows.houses.map((r) => r.key)).toEqual(['h1', 'h2']);
    expect(back.rows.houses[0].stamp).toEqual({ updatedAt: now - 1, by: a, deleted: true });
    expect(encodeSyncFile(back)).toBe(text);
  });

  it('refuses to write what a reader would refuse', () => {
    const twice = { houses: [simRow('houses/h1', now, a, false), simRow('houses/h1', now + 1, a, false)] };
    expect(problemOf(() => encodeSyncFile(syncFile(a, 1, now, twice)))).toBe('DUPLICATE_ROW');
    const many: SyncRow[] = Array.from({ length: SYNC_MAX_ROWS_OF.houses + 1 }, (_, i) => simRow(`houses/h${i}`, now, a, false));
    expect(problemOf(() => encodeSyncFile(syncFile(a, 1, now, { houses: many })))).toBe('TOO_LARGE');
  });

  it('refuses a list over its cap and accepts one at it', () => {
    const row = (i: number) => `{"id":"h${i}","updatedAt":"2026-09-21T14:13:20Z","deleted":false,"by":"${a}"}`;
    const rows = Array.from({ length: SYNC_MAX_ROWS_OF.houses + 1 }, (_, i) => row(i));
    expect(problem(envelope(rows.join(',')))).toBe('TOO_LARGE');
    expect(parseSyncFile(envelope(rows.slice(0, SYNC_MAX_ROWS_OF.houses).join(',')), a).rows.houses.length).toBe(SYNC_MAX_ROWS_OF.houses);
  });

  it('caps all lists together', () => {
    const per = Math.floor(SYNC_MAX_ROWS / 3) + 1;
    const list = (path: (i: number) => string) => Array.from({ length: per }, (_, i) => simRow(path(i), now, a, false).json);
    const text = JSON.stringify({
      format: 'doorprints-sync/1',
      deviceId: a,
      seq: 1,
      writtenAt: '2026-09-21T14:13:20Z',
      houses: [],
      visits: list((i) => `visits/v${i}`),
      records: list((i) => `records/note/r${i}`),
      photos: list((i) => `photos/p${i}`),
    });
    expect(problem(text)).toBe('TOO_LARGE');
  });

  it('refuses a file over sixteen mebibytes before parsing', () => {
    const notes = 'x'.repeat(SYNC_MAX_BYTES);
    const text = envelope(`{"id":"h1","notes":"${notes}","updatedAt":"2026-09-21T14:13:20Z","deleted":false,"by":"${a}"}`);
    expect(problem(text)).toBe('TOO_LARGE');
    expect(problem('é'.repeat(SYNC_MAX_BYTES / 2 + 1))).toBe('TOO_LARGE');
  });

  it('treats deep nesting as a problem, not a crash', () => {
    const deep = '['.repeat(100_000) + ']'.repeat(100_000);
    expect(problem(envelope('', `,"x":${deep}`))).toBe('TOO_LARGE');
    expect(nestingDepth('{"a":"[[[[\\"[["}')).toBe(1);
    expect(nestingDepth('{"a":[{}],"b":[[]]}')).toBe(3);
  });

  it('counts UTF-8 as the encoder does', () => {
    for (const s of ['', 'abc', 'é', 'हिन्दी', 'தமிழ்', 'తెలుగు', '😀', 'a😀b']) {
      expect(utf8Length(s), s).toBe(new TextEncoder().encode(s).length);
    }
    expect(utf8Length('\uD800')).toBe(3);
    expect(utf8Length('\uDC00x')).toBe(4);
    expect(utf8Length('\uD800𐀀')).toBe(7);
  });

  it('is strict about exact JSON types and ids', () => {
    const row = (patch: string) => `{"id":"h1","updatedAt":"2026-09-21T14:13:20Z","deleted":false,"by":"${a}"${patch}}`;
    expect(parseSyncFile(envelope(row('')), a).rows.houses.length).toBe(1);
    expect(problem(envelope(row('').replace('false', '"false"')))).toBe('BAD_ROW');
    expect(problem(envelope(row('').replace('"h1"', '".."')))).toBe('BAD_ROW');
    expect(problem(envelope(row('')).replace('"seq":1', '"seq":"1"'))).toBe('BAD_ROW');
    expect(problem(envelope(row('')).replace('"seq":1', '"seq":1.5'))).toBe('BAD_ROW');
    expect(problem(envelope(row('')), 'device-b02')).toBe('WRONG_DEVICE');
    expect(problem('[]')).toBe('NOT_JSON');
    expect(problem('nope')).toBe('NOT_JSON');
  });
});
