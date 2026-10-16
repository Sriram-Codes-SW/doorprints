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
import {
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withNavigationErrorHandler,
  withRouterConfig,
} from '@angular/router';
import { routesFor } from './app.routes';
import { spacesOn } from './core/kind-setting';
import { apiInterceptor } from './core/api.interceptor';
import { ChunkRecovery } from './core/chunk-recovery';
import { LocalStore } from './data/local-store.service';
import { I18nTitleStrategy } from './i18n/i18n-title.strategy';
import { initialLang } from './i18n/translation.service';

// Angular 21+ is zoneless by default, so no zone.js / provideZoneChangeDetection here.
/**
 * The app's providers: the router (a Back that a guard cancels leaves history as it was), the HTTP client with the API
 * interceptor, translated route titles, and the one-time seeding of the question bank before the first screen.
 * Zoneless.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(
      // Today's routes with one kind enabled; the chooser and the spaces with two or more (kinds PR 8, S4b-BL-212).
      routesFor(spacesOn()),
      withComponentInputBinding(),
      // A Back (popstate) that a guard cancels — "Keep editing" in the house page's unsaved-changes dialog — must
      // leave history as it was. The default, 'replace', writes the house URL over the entry the browser had just
      // moved back to (the list, Compare, Ask, Plan): that page was lost, and the next Back left the app, or did
      // nothing in an installed app whose first entry was the map. 'computed' goes forward again with historyGo()
      // and leaves the previous entry alone (UX lead review 2026-09-22).
      withRouterConfig({ canceledNavigationResolution: 'computed' }),
      // A lazy page whose script fails to load is tried again once, then the tab reloads once, then a message offers
      // Try again; every other navigation error is left alone (core/chunk-recovery.ts).
      withNavigationErrorHandler((error) => inject(ChunkRecovery).handle(error)),
    ),
    provideHttpClient(withFetch(), withInterceptors([apiInterceptor])),
    // Route titles below are translation keys; this strategy translates them and follows language changes.
    { provide: TitleStrategy, useClass: I18nTitleStrategy },
    // The question bank is seeded once per install, before the first screen reads it (slice 3a); a failure must not
    // stop the app, which then simply starts with an empty bank the person can reset.
    provideAppInitializer(() =>
      inject(LocalStore)
        .questions.seedOnce(initialLang)
        .catch(() => undefined),
    ),
  ],
};
