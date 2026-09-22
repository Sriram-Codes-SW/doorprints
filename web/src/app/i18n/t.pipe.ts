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
