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
