import { ApplicationConfig } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { apiInterceptor } from './core/api.interceptor';
import { I18nTitleStrategy } from './i18n/i18n-title.strategy';

// Angular 21+ is zoneless by default, so no zone.js / provideZoneChangeDetection here.
export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([apiInterceptor])),
    // Route titles below are translation keys; this strategy translates them and follows language changes.
    { provide: TitleStrategy, useClass: I18nTitleStrategy },
  ],
};
