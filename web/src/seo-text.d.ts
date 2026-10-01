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

/**
 * Files imported with `with { loader: 'text' }` (Angular's esbuild text loader) so a spec can hold them to what a
 * search engine expects: `public/robots.txt`, `public/sitemap.xml`, `public/manifest.webmanifest` (seo.spec.ts).
 */
declare module '*.txt' {
  const text: string;
  export default text;
}

declare module '*.xml' {
  const text: string;
  export default text;
}

declare module '*.webmanifest' {
  const text: string;
  export default text;
}
