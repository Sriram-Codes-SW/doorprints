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

import { ApplicationConfig, inject, provideAppInitializer } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding, withRouterConfig } from '@angular/router';
import { routes } from './app.routes';
import { apiInterceptor } from './core/api.interceptor';
import { LocalStore } from './data/local-store.service';
import { I18nTitleStrategy } from './i18n/i18n-title.strategy';
import { initialLang } from './i18n/translation.service';

// Angular 21+ is zoneless by default, so no zone.js / provideZoneChangeDetection here.
export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(
      routes,
      withComponentInputBinding(),
      // A Back (popstate) that a guard cancels — "Keep editing" in the house page's unsaved-changes dialog — must
      // leave history as it was. The default, 'replace', writes the house URL over the entry the browser had just
      // moved back to (the list, Compare, Ask, Plan): that page was lost, and the next Back left the app, or did
      // nothing in an installed app whose first entry was the map. 'computed' goes forward again with historyGo()
      // and leaves the previous entry alone (UX lead review 2026-09-22).
      withRouterConfig({ canceledNavigationResolution: 'computed' }),
    ),
    provideHttpClient(withFetch(), withInterceptors([apiInterceptor])),
    // Route titles below are translation keys; this strategy translates them and follows language changes.
    { provide: TitleStrategy, useClass: I18nTitleStrategy },
    // The question bank is seeded once per install, before the first screen reads it (slice 3a); a failure must not
    // stop the app, which then simply starts with an empty bank the person can reset.
    provideAppInitializer(() =>
      inject(LocalStore)
        .seedQuestionsOnce(initialLang)
        .catch(() => undefined),
    ),
  ],
};
