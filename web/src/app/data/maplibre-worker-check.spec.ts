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

import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { extractMaplibreVersion, maplibreWorkerProblems, relativeImportOf } from '../../../scripts/maplibre-worker-check.mjs';

/**
 * S4b-BL-54 and S4b-BL-55, reshaped for maplibre-gl 6.13: what the pwa-files job checks about MapLibre's worker file,
 * tested through the real function (the job runs the same rules inline because it has no checkout). Since 6.13 the
 * worker is self-contained and `maplibre-gl-shared.mjs` is an empty stub that angular.json no longer copies.
 */
const banner = (v: string) => `/**\n* MapLibre GL JS\n* @license 3-Clause BSD https://github.com/maplibre/maplibre-gl-js/blob/v${v}/LICENSE.txt\n*/\n`;
const worker = (v: string) => banner(v) + 'var e=Object.create;self.onmessage=()=>e;';

describe('maplibreWorkerProblems', () => {
  it('finds nothing wrong with a self-contained worker', () => {
    expect(maplibreWorkerProblems({ worker: worker('6.13.0') })).toEqual([]);
  });

  it('names a missing worker file', () => {
    expect(maplibreWorkerProblems({ worker: null })).toEqual(['missing maplibre-gl-worker.mjs (required by angular.json assets glob)']);
  });

  it('names an empty worker file', () => {
    expect(maplibreWorkerProblems({ worker: '' })).toEqual(['maplibre-gl-worker.mjs is empty']);
  });

  it('names a file without the licence banner', () => {
    const problems = maplibreWorkerProblems({ worker: 'var e=1;' });
    expect(problems).toContain('maplibre-gl-worker.mjs does not start with license header comment (/** ...)');
  });

  it('names a worker that imports a sibling file (the old 6.11 layout: the sibling is not copied)', () => {
    const w = banner('6.11.2') + 'import{D as e}from"./maplibre-gl-shared.mjs";e();';
    expect(maplibreWorkerProblems({ worker: w })).toEqual([
      'maplibre-gl-worker.mjs imports "./maplibre-gl-shared.mjs", a sibling file angular.json does not copy (it must be self-contained)',
    ]);
  });

  it('names a sibling import with spaces, single quotes, a bare import or a dynamic import', () => {
    for (const code of [
      "import { D as e } from './other.mjs';",
      'import "./side-effect.mjs";',
      'const m = await import("../up.mjs");',
      'export * from"./re-export.mjs";',
    ]) {
      expect(maplibreWorkerProblems({ worker: banner('6.13.0') + code }).length, code).toBe(1);
    }
  });

  it('does not mind a package or absolute import (only a sibling file is missing from the copy)', () => {
    expect(maplibreWorkerProblems({ worker: banner('6.13.0') + 'import x from "maplibre-gl";' })).toEqual([]);
  });

  it('names a worker whose banner has no version', () => {
    const w = '/**\n* MapLibre GL JS\n*/\nvar e=1;';
    expect(maplibreWorkerProblems({ worker: w })).toEqual(['maplibre-gl-worker.mjs has no version in header']);
  });

  it('names both versions when the worker is not the installed maplibre-gl', () => {
    expect(maplibreWorkerProblems({ worker: worker('6.11.2'), expectedVersion: '6.13.0' })).toEqual([
      'MapLibre version mismatch: worker v6.11.2 vs installed maplibre-gl v6.13.0',
    ]);
    expect(maplibreWorkerProblems({ worker: worker('6.13.0'), expectedVersion: '6.13.0' })).toEqual([]);
  });
});

describe('extractMaplibreVersion', () => {
  it('reads the version from the banner', () => {
    expect(extractMaplibreVersion(banner('6.13.0'))).toBe('6.13.0');
  });
  it('is null without a banner', () => {
    expect(extractMaplibreVersion('export const D=1;')).toBeNull();
  });
});

describe('relativeImportOf', () => {
  it('is null for code without a relative import', () => {
    expect(relativeImportOf('var a="from the end of the file"; b.import("x");')).toBeNull();
  });
});

describe('the installed maplibre-gl (a bump that changes the layout fails here, before CI)', () => {
  const dist = (file: string) => readFileSync(`node_modules/maplibre-gl/dist/${file}`, 'utf8');
  const installed = JSON.parse(readFileSync('node_modules/maplibre-gl/package.json', 'utf8')).version;

  it('ships a worker that passes the check at the installed version', () => {
    expect(maplibreWorkerProblems({ worker: dist('maplibre-gl-worker.mjs'), expectedVersion: installed })).toEqual([]);
  });

  it('ships a main module that imports no sibling file either (angular.json copies only the worker)', () => {
    expect(relativeImportOf(dist('maplibre-gl.mjs'))).toBeNull();
  });
});
