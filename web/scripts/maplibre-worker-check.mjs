#!/usr/bin/env node
/**
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

import fs from 'fs';
import path from 'path';

/**
 * Check that MapLibre GL worker files are present, non-empty, have license headers, and the worker imports the shared file.
 * @param {string} buildDir - Directory containing the built files
 * @returns {Object} - { errors: string[], warnings: string[] }
 */
export function checkMaplibreWorkerFiles(buildDir) {
  const errors = [];
  const warnings = [];

  const workerPath = path.join(buildDir, 'maplibre-gl-worker.mjs');
  const sharedPath = path.join(buildDir, 'maplibre-gl-shared.mjs');

  // Check worker file exists
  if (!fs.existsSync(workerPath)) {
    errors.push(`missing maplibre-gl-worker.mjs (required by angular.json assets glob)`);
    return { errors, warnings };
  }

  // Check worker file is not empty
  const workerStats = fs.statSync(workerPath);
  if (workerStats.size === 0) {
    errors.push(`maplibre-gl-worker.mjs is empty`);
    return { errors, warnings };
  }

  // Check shared file exists
  if (!fs.existsSync(sharedPath)) {
    errors.push(`missing maplibre-gl-shared.mjs (required by angular.json assets glob)`);
    return { errors, warnings };
  }

  // Check shared file is not empty
  const sharedStats = fs.statSync(sharedPath);
  if (sharedStats.size === 0) {
    errors.push(`maplibre-gl-shared.mjs is empty`);
    return { errors, warnings };
  }

  // Check both files start with license header comment
  const workerContent = fs.readFileSync(workerPath, 'utf8');
  const sharedContent = fs.readFileSync(sharedPath, 'utf8');

  if (!workerContent.startsWith('/**')) {
    errors.push(`maplibre-gl-worker.mjs does not start with license header comment (/** ...)`);
  } else {
    console.log(`ok: maplibre-gl-worker.mjs (${workerStats.size} bytes, license header present)`);
  }

  if (!sharedContent.startsWith('/**')) {
    errors.push(`maplibre-gl-shared.mjs does not start with license header comment (/** ...)`);
  } else {
    console.log(`ok: maplibre-gl-shared.mjs (${sharedStats.size} bytes, license header present)`);
  }

  // Check worker file imports from shared file
  const importRegex = /from\s*["']\.\/maplibre-gl-shared\.mjs["']/;
  if (!importRegex.test(workerContent)) {
    errors.push(`maplibre-gl-worker.mjs does not import from "./maplibre-gl-shared.mjs"`);
  } else {
    console.log(`ok: maplibre-gl-worker.mjs imports "./maplibre-gl-shared.mjs"`);
  }

  return { errors, warnings };
}

// If run directly, check the build directory
if (import.meta.url === `file://${process.argv[1]}`) {
  const buildDir = process.argv[2];
  if (!buildDir) {
    console.error(`Usage: maplibre-worker-check.mjs <build-dir>`);
    process.exit(1);
  }

  const { errors, warnings } = checkMaplibreWorkerFiles(buildDir);

  for (const w of warnings) console.log(`::warning::${w}`);
  for (const e of errors) console.log(`::error::${e}`);

  if (errors.length > 0) {
    console.log(`\n${errors.length} problem(s) with MapLibre worker files in ${buildDir}.`);
    process.exit(1);
  }

  console.log('\nMapLibre worker files are present and valid.');
}
