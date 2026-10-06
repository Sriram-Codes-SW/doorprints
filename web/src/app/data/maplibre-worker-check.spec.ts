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
import { extractMaplibreVersion, maplibreWorkerProblems } from '../../../scripts/maplibre-worker-check.mjs';

/**
 * S4b-BL-54 and S4b-BL-55: what the pwa-files job checks about MapLibre's two worker files, tested through the real
 * function (the job runs the same rules inline because it has no checkout).
 */
const banner = (v: string) => `/**\n* MapLibre GL JS\n* @license 3-Clause BSD https://github.com/maplibre/maplibre-gl-js/blob/v${v}/LICENSE.txt\n*/\n`;
const worker = (v: string) => banner(v) + 'import{D as e}from"./maplibre-gl-shared.mjs";e();';
const shared = (v: string) => banner(v) + 'export const D=1;';

describe('maplibreWorkerProblems', () => {
  it('finds nothing wrong with a matching pair', () => {
    expect(maplibreWorkerProblems({ worker: worker('6.11.2'), shared: shared('6.11.2') })).toEqual([]);
  });

  it('names a missing worker file', () => {
    expect(maplibreWorkerProblems({ worker: null, shared: shared('6.11.2') })).toEqual([
      'missing maplibre-gl-worker.mjs (required by angular.json assets glob)',
    ]);
  });

  it('names a missing shared file', () => {
    const problems = maplibreWorkerProblems({ worker: worker('6.11.2'), shared: null });
    expect(problems).toContain('missing maplibre-gl-shared.mjs (required by angular.json assets glob)');
  });

  it('names an empty file', () => {
    expect(maplibreWorkerProblems({ worker: worker('6.11.2'), shared: '' })).toEqual(['maplibre-gl-shared.mjs is empty']);
  });

  it('names a file without the licence banner', () => {
    const problems = maplibreWorkerProblems({ worker: 'import{D as e}from"./maplibre-gl-shared.mjs";', shared: shared('6.11.2') });
    expect(problems).toContain('maplibre-gl-worker.mjs does not start with license header comment (/** ...)');
  });

  it('names a worker that no longer imports the shared file', () => {
    const w = banner('6.11.2') + 'import{D as e}from"./other.mjs";';
    expect(maplibreWorkerProblems({ worker: w, shared: shared('6.11.2') })).toEqual([
      'maplibre-gl-worker.mjs does not import from "./maplibre-gl-shared.mjs"',
    ]);
  });

  it('accepts the import with spaces and single quotes', () => {
    const w = banner('6.11.2') + "import { D as e } from './maplibre-gl-shared.mjs';";
    expect(maplibreWorkerProblems({ worker: w, shared: shared('6.11.2') })).toEqual([]);
  });

  it('names both versions when worker and shared differ', () => {
    const problems = maplibreWorkerProblems({ worker: worker('6.11.2'), shared: shared('6.10.0') });
    expect(problems).toEqual(['MapLibre version mismatch: worker v6.11.2 vs shared v6.10.0']);
  });

  it('names a shared file whose banner has no version', () => {
    const s = '/**\n* MapLibre GL JS\n*/\nexport const D=1;';
    expect(maplibreWorkerProblems({ worker: worker('6.11.2'), shared: s })).toEqual(['maplibre-gl-shared.mjs has no version in header']);
  });

  it('names a worker whose banner has no version', () => {
    const w = '/**\n* MapLibre GL JS\n*/\nimport{D as e}from"./maplibre-gl-shared.mjs";';
    expect(maplibreWorkerProblems({ worker: w, shared: shared('6.11.2') })).toEqual(['maplibre-gl-worker.mjs has no version in header']);
  });
});

describe('extractMaplibreVersion', () => {
  it('reads the version from the banner', () => {
    expect(extractMaplibreVersion(banner('6.11.2'))).toBe('6.11.2');
  });
  it('is null without a banner', () => {
    expect(extractMaplibreVersion('export const D=1;')).toBeNull();
  });
});
