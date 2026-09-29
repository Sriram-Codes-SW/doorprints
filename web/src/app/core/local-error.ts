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

import type { TKey } from '../i18n/en';
import type { Params } from '../i18n/translation.service';

/**
 * A failure of the local store rather than of a network call. It carries a translation key — and, where the
 * message needs one, its `{placeholder}` values — so the user sees the reason in the app language (docs/05
 * A11Y/i18n: never show raw English technical text).
 */
export class LocalDataError extends Error {
  readonly key: TKey;
  readonly params?: Params;

  constructor(key: TKey, params?: Params) {
    super(key);
    this.key = key;
    this.params = params;
    this.name = 'LocalDataError';
  }
}
