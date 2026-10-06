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

// check-specs: allow-no-production-import (a source test: it reads the source files of the trace, the place check and the export paths, and imports none of them)
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * Two source tests for the path trace and the place check (docs/11 5.27.7a and 5.27.13, docs/02 T-I30 and T-I42, docs/06
 * TC-U-152 and TC-U-154). They read the source text (comments removed), because the rule is about what the code can reach:
 *
 * 1. NO LEAK. The trace and its files must not touch `history.` (a result in the back stack), `sessionStorage` or
 *    `localStorage` (a walk kept past the page), `queryParams` (a place or a distance in a URL), `ListReturn` (a result
 *    carried to the list), `console.` (a place or a count in a log), `sendBeacon`, `fetch(` or `XMLHttpRequest` (a request).
 *    The allowed list is NONE. It covers the files that exist now and, by name pattern, the page files a later change adds
 *    (the map's trace and check files, the house page's check card).
 * 2. NOT IN ANY EXPORT. The two stores and the classes that read them are named in no file of the export, Drive backup and sync,
 *    server sync, AI or shared-listing paths.
 *
 * Paths are relative to the `web` folder, where `ng test` runs.
 */

const APP = 'src/app';

/** The source with block comments and line comments removed, so a sentence that explains a rule is not read as a breach of it. */
export function stripComments(source: string): string {
  return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/[^\n]*/g, '$1');
}

/** The tokens of `tokens` that appear in `source` (comments removed). */
export function found(source: string, tokens: readonly string[]): string[] {
  const code = stripComments(source);
  return tokens.filter((t) => code.includes(t));
}

/** Every `.ts` file that is not a spec under `dir` (recursive). */
function tsFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const path = `${dir}/${name}`;
    if (statSync(path).isDirectory()) out.push(...tsFiles(path));
    else if (name.endsWith('.ts') && !name.endsWith('.spec.ts') && !name.endsWith('.d.ts')) out.push(path);
  }
  return out.sort();
}

/** The files of `dir` (not recursive) whose name matches. */
function inDir(dir: string, name: RegExp): string[] {
  return readdirSync(dir)
    .filter((n) => name.test(n))
    .map((n) => `${dir}/${n}`)
    .sort();
}

/** The trace and place check files that exist now. */
const NOW_FILES = [
  'shared/trace-geo.ts',
  'shared/trace-repeats.ts',
  'shared/trace-recorder.ts',
  'shared/trace-place-check.ts',
  'shared/trace-place-text.ts',
  'data/trace-rows.ts',
  'data/trace-store.ts',
  'core/trace-recorder.service.ts',
  'core/alert-sound.service.ts',
].map((f) => `${APP}/${f}`);

/** The files a later change adds, by name: the map's trace and check files, the walk-end sheet, the house page's check card, the style. */
function laterFiles(): string[] {
  return [
    ...inDir(`${APP}/pages/map`, /^(trace-.*|place-check.*|walk-end-sheet.*)\.ts$/),
    ...inDir(`${APP}/pages/house-detail`, /^house-(check|walks?)-.*\.ts$/),
    ...inDir(`${APP}/shared`, /^trace-style\.ts$/),
  ].filter((f) => !f.endsWith('.spec.ts'));
}

const NO_LEAK = ['history.', 'sessionStorage', 'localStorage', 'queryParams', 'ListReturn', 'console.', 'sendBeacon', 'fetch(', 'XMLHttpRequest'];

describe('the source scanner itself', () => {
  it('finds a token in code, and ignores it in a comment or a doc comment', () => {
    expect(found('const a = history.state;', NO_LEAK)).toEqual(['history.']);
    expect(found('// never history.state\nconst a = 1;', NO_LEAK)).toEqual([]);
    expect(found('/* console.log is banned */ const a = 1;', NO_LEAK)).toEqual([]);
    expect(found('/**\n * sessionStorage is not used\n */\nconst a = 1;', NO_LEAK)).toEqual([]);
    expect(found("const url = 'https://x'; localStorage.setItem('a', b);", NO_LEAK)).toEqual(['localStorage']);
    expect(found('await fetch(url); navigator.sendBeacon(u);', NO_LEAK)).toEqual(['sendBeacon', 'fetch(']);
  });

  it('finds every file that exists now, so a rename cannot silently empty the scan', () => {
    for (const file of NOW_FILES) expect(readFileSync(file, 'utf8').length, file).toBeGreaterThan(100);
  });
});

describe('the trace and the place check keep nothing past the page and send nothing (no leak, no allowed list)', () => {
  it('has the files it must scan', () => {
    expect(NOW_FILES.length).toBeGreaterThanOrEqual(9);
  });

  it.each(NOW_FILES)('%s refers to none of the banned names', (file) => {
    expect(found(readFileSync(file, 'utf8'), NO_LEAK), file).toEqual([]);
  });

  it('the files a later change adds (the map and house page trace and check components, the style) are held to the same rule', () => {
    for (const file of laterFiles()) expect(found(readFileSync(file, 'utf8'), NO_LEAK), file).toEqual([]);
  });
});

describe('the only caller of the place check is the button (TC-U-154)', () => {
  it('names placeCheck( in no file but its own and the check control, panel and house card', () => {
    const allowed = [/shared\/trace-place-check\.ts$/, /pages\/map\/place-check[^/]*\.ts$/, /pages\/house-detail\/house-check-card\.ts$/];
    const callers = tsFiles(APP).filter((f) => stripComments(readFileSync(f, 'utf8')).includes('placeCheck('));
    for (const f of callers) expect(allowed.some((re) => re.test(f)), `${f} calls placeCheck( but is not the check's own file or a check button`).toBe(true);
  });

  it('nothing but the recorder and the Map calls the trace store\'s writes, and the check never writes (it imports no store writer)', () => {
    const text = stripComments(readFileSync(`${APP}/shared/trace-place-check.ts`, 'utf8') + readFileSync(`${APP}/shared/trace-place-text.ts`, 'utf8'));
    expect(text).not.toMatch(/TraceStore|putPoint|saveWalk|LocalStore|from '\.\.\/data/);
  });
});

describe('the walks are in no export, backup, sync or AI path (TC-U-152)', () => {
  /** The paths docs/11 5.27.7a names: the exporters, the Drive backup and sync, the server sync, the AI, the shared-listing code. */
  const PATHS = [
    ...tsFiles(`${APP}/export`),
    ...tsFiles(`${APP}/data/drive`),
    ...tsFiles(`${APP}/core/ai`),
    `${APP}/core/ai.service.ts`,
    `${APP}/core/ai-session.state.ts`,
    ...inDir(`${APP}/data`, /^sync.*\.ts$/).filter((f) => !f.endsWith('.spec.ts')),
    `${APP}/shared/listing-text.ts`,
    `${APP}/core/launch-files.service.ts`,
  ];
  const STORES = ['trace_points', 'saved_walks', 'TraceStore', 'TracePointRow', 'SavedWalkRow', 'trace-store', 'trace-rows', 'trace-recorder', 'TraceRecorder', 'shared/trace-', 'trace-place', 'trace-repeats'];

  it('scans the exporters, the Drive backup and sync rows, the sync files, the AI and the shared-listing code', () => {
    expect(PATHS.length).toBeGreaterThan(60);
    for (const must of ['export/backup-export.ts', 'export/html-export.ts', 'export/export.service.ts', 'data/drive/backup/drive-backup.service.ts', 'data/drive/sync-file.ts', 'data/drive/drive-sync-backend.ts', 'data/sync.service.ts', 'data/sync-backend.ts', 'core/ai/ai-core.ts', 'shared/listing-text.ts']) {
      expect(PATHS.some((p) => p.endsWith(must)), must).toBe(true);
    }
  });

  it.each(PATHS)('%s names no walk store or trace class', (file) => {
    expect(found(readFileSync(file, 'utf8'), STORES), file).toEqual([]);
  });
});
