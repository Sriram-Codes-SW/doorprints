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

import { Pipe, PipeTransform, inject } from '@angular/core';
import { TKey } from './en';
import { Params, TranslationService } from './translation.service';

/**
 * `{{ 'house.save' | t }}` or `{{ 'common.bhk' | t: { n: 2 } }}`.
 * Impure so it re-runs on every change detection; it reads the `lang` signal, so a language switch
 * also schedules change detection in this zoneless app. The work per call is one map lookup.
 */
@Pipe({ name: 't', pure: false })
export class TPipe implements PipeTransform {
  private readonly i18n = inject(TranslationService);

  transform(key: TKey, params?: Params): string {
    return this.i18n.t(key, params);
  }
}
