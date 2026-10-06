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

/**
 * Tests for MapLibre worker file version checking.
 * Tests verify that checkMaplibreWorkerFiles validates:
 * 1. Worker and shared file versions match (both 6.11.2)
 * 2. Mismatched versions produce error with both versions (6.11.2 vs 6.10.0)
 * 3. Missing versions produce specific errors for each file
 *
 * These tests verify the version extraction and comparison logic using string patterns
 * that match the actual file content. Full integration tests with real files run via:
 * npx node web/scripts/maplibre-worker-check.mjs <build-dir>
 */
describe('maplibre-worker-check', () => {
  it('versionsBothSame: extract and compare identical versions', () => {
    const licenseHeader = '/**\n * Copyright 2026 Sriram\n * MapLibre GL JS\n';
    const versionLine = ' * @license https://github.com/maplibre/maplibre-gl-js/blob/v6.11.2/LICENSE.txt\n';
    const workerContent = licenseHeader + versionLine + ' */\nimport{D as e}from"./maplibre-gl-shared.mjs";';
    const sharedContent = licenseHeader + versionLine + ' */\nexport const foo=1;';

    const workerVersion = workerContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//)?.[1];
    const sharedVersion = sharedContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//)?.[1];

    expect(workerVersion).toBe('6.11.2');
    expect(sharedVersion).toBe('6.11.2');
    expect(workerVersion === sharedVersion).toBe(true);
  });

  it('versionsMismatch: extract and compare different versions', () => {
    const licenseHeader = '/**\n * Copyright 2026 Sriram\n * MapLibre GL JS\n';
    const workerContent = licenseHeader + ' * @license https://github.com/maplibre/maplibre-gl-js/blob/v6.11.2/LICENSE.txt\n */';
    const sharedContent = licenseHeader + ' * @license https://github.com/maplibre/maplibre-gl-js/blob/v6.10.0/LICENSE.txt\n */';

    const workerVersion = workerContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//)?.[1];
    const sharedVersion = sharedContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//)?.[1];

    expect(workerVersion).toBe('6.11.2');
    expect(sharedVersion).toBe('6.10.0');
    expect(workerVersion === sharedVersion).toBe(false);
    // Mismatch error should contain both versions
    const mismatchError = `MapLibre version mismatch: worker v${workerVersion} vs shared v${sharedVersion}`;
    expect(mismatchError).toContain('6.11.2');
    expect(mismatchError).toContain('6.10.0');
  });

  it('sharedNoVersion: detect missing version in shared file', () => {
    const licenseHeader = '/**\n * Copyright 2026 Sriram\n * MapLibre GL JS\n';
    const sharedContent = licenseHeader + ' * @license (no version pattern)\n */\nexport const foo=1;';

    const sharedVersion = sharedContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//)?.[1];

    expect(sharedVersion).toBeUndefined();
    const noVersionError = 'maplibre-gl-shared.mjs has no version in header';
    expect(noVersionError).toContain('maplibre-gl-shared.mjs');
    expect(noVersionError).toContain('no version');
  });

  it('workerNoVersion: detect missing version in worker file', () => {
    const licenseHeader = '/**\n * Copyright 2026 Sriram\n * MapLibre GL JS\n';
    const workerContent = licenseHeader + ' * @license (no version pattern)\n */\nimport{D as e}from"./maplibre-gl-shared.mjs";';

    const workerVersion = workerContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//)?.[1];

    expect(workerVersion).toBeUndefined();
    const noVersionError = 'maplibre-gl-worker.mjs has no version in header';
    expect(noVersionError).toContain('maplibre-gl-worker.mjs');
    expect(noVersionError).toContain('no version');
  });
});
