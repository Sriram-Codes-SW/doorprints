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
 * A `.css` file imported with `with { loader: 'text' }` (Angular's esbuild text loader): its content as a string.
 * Used by the accessibility sweep to read the design tokens of src/styles.css (shared/testing/a11y.ts).
 */
declare module '*.css' {
  const text: string;
  export default text;
}

/** The `index.html` page shell imported the same way (the sweep checks its `lang`, viewport and fonts). */
declare module '*.html' {
  const text: string;
  export default text;
}
