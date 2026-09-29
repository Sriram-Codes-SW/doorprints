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

import { bootstrapApplication } from '@angular/platform-browser';
import { App } from './app/app';
import { appConfig } from './app/app.config';
import { startUnlessFramed } from './app/core/frame-guard';
import { migrateLegacyStorage } from './app/core/storage-keys';
import { initialLang } from './app/i18n/translation.service';
import { loadDictionary } from './app/i18n/languages';

// Before anything reads storage: move keys saved under the pre-rename names (house-hunt.*, hh.*) to doorprints.*.
migrateLegacyStorage();

// Inside another site's frame the app does not start; it shows a translated "open in its own tab" message instead
// (defence in depth behind web/firebase.json's frame-ancestors / X-Frame-Options, see core/frame-guard.ts).
// The saved language's strings first (its own chunk; English is built in), so the first paint is already in it.
// If it cannot be fetched, the app starts in English.
loadDictionary(initialLang())
  .catch(() => undefined)
  .finally(() =>
    startUnlessFramed({ win: window, doc: document, href: location.href, lang: initialLang }, () => {
      bootstrapApplication(App, appConfig).catch((err: unknown) => console.error(err));
    }),
  );
