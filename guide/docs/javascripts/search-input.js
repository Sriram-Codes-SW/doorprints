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

// Material's search reads the box on key-up and focus only (components/search/query), so text that arrives without a
// key-up (a paste from the context menu, voice typing, a tapped keyboard suggestion, an input method that composes a
// word) was never searched, in every language, until some other key was pressed. Say "key-up" whenever the text
// changes; Material ignores a repeat of the same text (docs/10 S4b-BL-183). tools/guide-search-check tests it.
(function () {
  var box = document.querySelector('[data-md-component="search-query"]');
  if (!box) return;
  box.addEventListener("input", function () {
    box.dispatchEvent(new Event("keyup"));
  });
})();
