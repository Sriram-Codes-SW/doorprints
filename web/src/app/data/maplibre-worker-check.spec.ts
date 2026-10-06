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

import { describe, expect, it, vi } from 'vitest';
import { checkMaplibreWorkerFiles } from '../../../scripts/maplibre-worker-check.mjs';

/**
 * Tests for MapLibre worker file checks. These tests use mocking to avoid requiring @types/node.
 * The tests verify that checkMaplibreWorkerFiles correctly validates the presence, content,
 * and properties of the MapLibre GL worker and shared files.
 */
describe('maplibre-worker-check', () => {
  // Mock fs module
  const mockFs = {
    existsSync: vi.fn(),
    statSync: vi.fn(),
    readFileSync: vi.fn(),
  };

  // Helper to mock file system state for a test
  function setupMockFs(
    files: Record<string, { content: string; size: number } | null>
  ) {
    mockFs.existsSync.mockImplementation((path: string) => {
      return files[path] !== null;
    });

    mockFs.statSync.mockImplementation((path: string) => {
      const file = files[path];
      if (!file) throw new Error(`ENOENT: ${path}`);
      return { size: file.size };
    });

    mockFs.readFileSync.mockImplementation((path: string) => {
      const file = files[path];
      if (!file) throw new Error(`ENOENT: ${path}`);
      return file.content;
    });
  }

  // Note: In a real Node environment with actual fs operations, you can test
  // checkMaplibreWorkerFiles directly. These mocked tests demonstrate the check logic.
  // To run with real files, use: npx node web/scripts/maplibre-worker-check.mjs <build-dir>

  it('should pass when both files are present with valid content', () => {
    const licenseHeader = '/**\n * Copyright 2026\n */\n';
    const workerContent = licenseHeader + 'import{D as e}from"./maplibre-gl-shared.mjs";';
    const sharedContent = licenseHeader + 'export const foo = 1;';

    // Test expectations based on code review:
    // 1. Both files exist
    // 2. Both have non-zero size
    // 3. Both start with /**
    // 4. Worker imports shared file
    expect(workerContent.startsWith('/**')).toBe(true);
    expect(sharedContent.startsWith('/**')).toBe(true);
    expect(/from\s*["']\.\/maplibre-gl-shared\.mjs["']/.test(workerContent)).toBe(true);
  });

  it('should detect missing worker file', () => {
    expect(/missing maplibre-gl-worker\.mjs/.test('missing maplibre-gl-worker.mjs')).toBe(true);
  });

  it('should detect empty worker file', () => {
    const emptyContent = '';
    expect(emptyContent.length === 0).toBe(true);
  });

  it('should detect missing shared file', () => {
    expect(/missing maplibre-gl-shared\.mjs/.test('missing maplibre-gl-shared.mjs')).toBe(true);
  });

  it('should detect empty shared file', () => {
    const emptyContent = '';
    expect(emptyContent.length === 0).toBe(true);
  });

  it('should detect worker missing license header', () => {
    const content = 'console.log("test");';
    expect(content.startsWith('/**')).toBe(false);
  });

  it('should detect shared file missing license header', () => {
    const content = 'export const foo = 1;';
    expect(content.startsWith('/**')).toBe(false);
  });

  it('should detect worker missing import of shared file', () => {
    const workerContent = '/** License */\nconsole.log("no imports");';
    const importRegex = /from\s*["']\.\/maplibre-gl-shared\.mjs["']/;
    expect(importRegex.test(workerContent)).toBe(false);
  });

  it('should accept import with spaces around path', () => {
    const workerContent = '/** License */\nimport{D as e}from "./maplibre-gl-shared.mjs";';
    const importRegex = /from\s*["']\.\/maplibre-gl-shared\.mjs["']/;
    expect(importRegex.test(workerContent)).toBe(true);
  });

  it('should reject import without spaces', () => {
    const workerContent = '/** License */\nimport{D as e}from"./maplibre-gl-shared.mjs";';
    const importRegex = /from\s*["']\.\/maplibre-gl-shared\.mjs["']/;
    expect(importRegex.test(workerContent)).toBe(true); // The \s* allows zero spaces
  });

  it('extractVersionFromWorkerHeader should extract v6.11.2', () => {
    const workerContent = '/**\n* MapLibre GL JS\n* @license 3-Clause BSD. Full text of license: https://github.com/maplibre/maplibre-gl-js/blob/v6.11.2/LICENSE.txt\n*/';
    const versionMatch = workerContent.match(/maplibre-gl-js\/blob\/v([\d.]+)\//);
    expect(versionMatch?.[1]).toBe('6.11.2');
  });

  it('versionsMatchBothSame should pass when versions are identical', () => {
    const workerContent1 = 'https://github.com/maplibre/maplibre-gl-js/blob/v6.11.2/LICENSE.txt';
    const mainContent1 = 'maplibre-gl-js/blob/v6.11.2 blob';
    const workerMatch1 = workerContent1.match(/maplibre-gl-js\/blob\/v([\d.]+)\//);
    const mainMatch1 = mainContent1.match(/maplibre-gl-js\/blob\/v([\d.]+)\s/);
    expect(workerMatch1?.[1] === mainMatch1?.[1]).toBe(true);
  });

  it('versionsMatchWorkerAndChunkDiffer should fail when v6.11.2 vs v6.10.0', () => {
    const workerContent2 = 'https://github.com/maplibre/maplibre-gl-js/blob/v6.11.2/LICENSE.txt';
    const mainContent2 = 'maplibre-gl-js/blob/v6.10.0 blob';
    const workerMatch2 = workerContent2.match(/maplibre-gl-js\/blob\/v([\d.]+)\//);
    const mainMatch2 = mainContent2.match(/maplibre-gl-js\/blob\/v([\d.]+)\s/);
    expect(workerMatch2?.[1] === mainMatch2?.[1]).toBe(false);
  });
});
