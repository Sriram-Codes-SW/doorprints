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

import { InjectionToken } from '@angular/core';
import { country } from './country-pack';
import type { CountryPack } from './country-pack';

/**
 * The single provider of the country pack (ADR-38): a component or service asks `inject(COUNTRY)`. It resolves to the
 * active pack ({@link country}), which is India's in the app. Plain functions that cannot inject (the AI core, the export
 * formatters) call `country()` directly; both read the same object.
 */
export const COUNTRY = new InjectionToken<CountryPack>('COUNTRY', {
  providedIn: 'root',
  factory: () => country(),
});
