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
import { isBuiltInKey } from './scoring';
import type { Criterion } from './scoring';

/**
 * A criterion's name on screen: a built-in's translated name (`check.<key>`), a custom criterion's own label, and the
 * raw key for a criterion whose label is missing. `t` is `TranslationService.t`.
 */
export function criterionName(criterion: Pick<Criterion, 'key' | 'label'>, t: (key: TKey) => string): string {
  return isBuiltInKey(criterion.key) ? t(`check.${criterion.key}` as TKey) : (criterion.label ?? criterion.key);
}
