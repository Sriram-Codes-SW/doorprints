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

import { en } from './en';
import type { Dict } from './en';
import { hi } from './hi';
import { ta } from './ta';
import { te } from './te';
import { Lang, registerDictionaries } from './languages';

/**
 * Every language's dictionary, loaded at once: for tests and checks that go through all of them. **The app must not
 * import this file** (it would put all four languages back into the first download); it loads them one at a time
 * through `loadDictionary` in languages.ts. Importing it also registers them, so `TranslationService.setLang` switches
 * synchronously in tests.
 */
export const DICTIONARIES: Readonly<Record<Lang, Dict>> = { en, hi, ta, te };
registerDictionaries(DICTIONARIES);
