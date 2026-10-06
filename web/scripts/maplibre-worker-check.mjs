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

const WORKER = 'maplibre-gl-worker.mjs';
const SHARED = 'maplibre-gl-shared.mjs';

/** The MapLibre version a built file's banner names (`maplibre-gl-js/blob/v6.11.2/`), or null. */
export function extractMaplibreVersion(content) {
  const match = content.match(/maplibre-gl-js\/blob\/v([\d.]+)\//);
  return match?.[1] || null;
}

/**
 * The pure half (S4b-BL-54, S4b-BL-55): given the two built files' text (null = missing), say what is wrong.
 * Both must exist and be non-empty and start with the licence banner; the worker must import the shared file; and
 * their versions must be equal (both are copied from one maplibre-gl package, a mixed pair fails every tile).
 * The same rules run inline in web.yml's pwa-files job, which has no checkout; keep the two in step.
 * @param {{worker: string | null, shared: string | null}} files
 * @returns {string[]} the problems, empty when fine
 */
export function maplibreWorkerProblems({ worker, shared }) {
  const errors = [];
  for (const [name, text] of [[WORKER, worker], [SHARED, shared]]) {
    if (text === null) errors.push(`missing ${name} (required by angular.json assets glob)`);
    else if (text.length === 0) errors.push(`${name} is empty`);
    else if (!text.startsWith('/**')) errors.push(`${name} does not start with license header comment (/** ...)`);
  }
  if (worker && !/from\s*["']\.\/maplibre-gl-shared\.mjs["']/.test(worker)) {
    errors.push(`${WORKER} does not import from "./maplibre-gl-shared.mjs"`);
  }
  const workerVersion = worker ? extractMaplibreVersion(worker) : null;
  const sharedVersion = shared ? extractMaplibreVersion(shared) : null;
  if (worker && !workerVersion) errors.push(`${WORKER} has no version in header`);
  if (shared && !sharedVersion) errors.push(`${SHARED} has no version in header`);
  if (workerVersion && sharedVersion && workerVersion !== sharedVersion) {
    errors.push(`MapLibre version mismatch: worker v${workerVersion} vs shared v${sharedVersion}`);
  }
  return errors;
}

function readOrNull(file) {
  return fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
}

// Run directly: node maplibre-worker-check.mjs <build-dir>
if (import.meta.url === `file://${process.argv[1]}`) {
  const buildDir = process.argv[2];
  if (!buildDir) {
    console.error('Usage: maplibre-worker-check.mjs <build-dir>');
    process.exit(1);
  }
  const errors = maplibreWorkerProblems({
    worker: readOrNull(path.join(buildDir, WORKER)),
    shared: readOrNull(path.join(buildDir, SHARED)),
  });
  for (const e of errors) console.log(`::error::${e}`);
  if (errors.length > 0) {
    console.log(`\n${errors.length} problem(s) with MapLibre worker files in ${buildDir}.`);
    process.exit(1);
  }
  console.log('MapLibre worker files are present and valid.');
}
