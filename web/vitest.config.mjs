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

export default {
  test: {
    // Vitest 5 caches transformed modules on disk by default (node_modules/.vitest-cache). Under @angular/build:unit-test the
    // test bundle is built in memory, and the cache served the previous run's source after an edit (a mutated source file
    // passed its spec; tools/mutate.mjs reported every mutation as survived). Off: every run reads the current source.
    fsModuleCache: false,
  },
};
