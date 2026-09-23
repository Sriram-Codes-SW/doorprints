import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding, withRouterConfig } from '@angular/router';
import { routes } from './app.routes';
import { apiInterceptor } from './core/api.interceptor';
import { I18nTitleStrategy } from './i18n/i18n-title.strategy';

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
  ],
};
