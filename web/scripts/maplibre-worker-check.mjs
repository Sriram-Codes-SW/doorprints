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

/** The MapLibre version a built file's banner names (`maplibre-gl-js/blob/v6.13.0/`), or null. */
export function extractMaplibreVersion(content) {
  const match = content.match(/maplibre-gl-js\/blob\/v([\d.]+)\//);
  return match?.[1] || null;
}

/** The first relative module specifier the text imports (`from "./x"`, `import "./x"`, `import("./x")`), or null. */
export function relativeImportOf(content) {
  const match = content.match(/\b(?:from|import)\s*\(?\s*["'](\.{1,2}\/[^"']+)["']/);
  return match?.[1] || null;
}

/**
 * The pure half (S4b-BL-54, S4b-BL-55, reshaped for maplibre-gl 6.13): given the built worker's text (null = missing),
 * say what is wrong. Up to 6.12 the worker imported `./maplibre-gl-shared.mjs` and the pair had to match; since 6.13
 * (maplibre-gl-js #8621) the worker is self-contained and `maplibre-gl-shared.mjs` is an empty, deprecated stub, so
 * angular.json copies the worker alone. The worker must exist, be non-empty, start with the licence banner and name its
 * version, and it must import no sibling file (a sibling is not copied: the `**` rewrite would answer the import with
 * index.html and the worker would fail on every tile). With `expectedVersion` (the installed package's version, known
 * where there is a checkout) the worker's banner must be that version: a stale worker is what #8621 fixed upstream.
 * The same rules run inline in web.yml's pwa-files job, which has no checkout (minus `expectedVersion`); keep the two in step.
 * @param {{worker: string | null, expectedVersion?: string}} files
 * @returns {string[]} the problems, empty when fine
 */
export function maplibreWorkerProblems({ worker, expectedVersion }) {
  const errors = [];
  if (worker === null) errors.push(`missing ${WORKER} (required by angular.json assets glob)`);
  else if (worker.length === 0) errors.push(`${WORKER} is empty`);
  else if (!worker.startsWith('/**')) errors.push(`${WORKER} does not start with license header comment (/** ...)`);
  if (!worker) return errors;
  const sibling = relativeImportOf(worker);
  if (sibling) {
    errors.push(`${WORKER} imports "${sibling}", a sibling file angular.json does not copy (it must be self-contained)`);
  }
  const version = extractMaplibreVersion(worker);
  if (!version) errors.push(`${WORKER} has no version in header`);
  else if (expectedVersion && version !== expectedVersion) {
    errors.push(`MapLibre version mismatch: worker v${version} vs installed maplibre-gl v${expectedVersion}`);
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
  const installed = readOrNull(new URL('../node_modules/maplibre-gl/package.json', import.meta.url).pathname);
  const errors = maplibreWorkerProblems({
    worker: readOrNull(path.join(buildDir, WORKER)),
    expectedVersion: installed ? JSON.parse(installed).version : undefined,
  });
  for (const e of errors) console.log(`::error::${e}`);
  if (errors.length > 0) {
    console.log(`\n${errors.length} problem(s) with MapLibre worker files in ${buildDir}.`);
    process.exit(1);
  }
  console.log('MapLibre worker files are present and valid.');
}
