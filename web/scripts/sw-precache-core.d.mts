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

// Types for sw-precache-core.mjs, so src/app/core/sw-precache.spec.ts can import it under `strict`.
export declare const EXCLUDED: readonly string[];
export declare const EXCLUDED_DIRS: readonly string[];
export declare const BUILD_PLACEHOLDER: string;
export declare const PRECACHE_PLACEHOLDER: string;
export declare function precacheList(files: Iterable<string>): string[];
export declare function buildId(entries: ReadonlyArray<{ path: string; digest: string }>): string;
export declare function stampServiceWorker(source: string, stamp: { id: string; precache: readonly string[] }): string;
export declare function cspFromFirebaseConfig(configText: string): string | null;
export declare function metaCspFromPolicy(policy: string): string | null;
export declare function injectMetaCsp(html: string, policy: string): string;
export declare function navigationPlan(
  pathname: string,
  deployment: { basePath: string; precache: readonly string[] },
): 'shell' | 'file' | 'network';
export declare function isAcceptable(
  path: string,
  isShell: boolean,
  response: { readonly ok: boolean; readonly headers: { get(name: string): string | null } },
): boolean;
export declare function baseHrefOf(html: string): string;
export declare function stampManifestId(manifestText: string, basePath: string): string;
