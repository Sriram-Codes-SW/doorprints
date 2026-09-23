import type { TKey } from '../i18n/en';
import type { Params } from '../i18n/translation.service';

/**
 * A failure of the local store rather than of a network call. It carries a translation key — and, where the
 * message needs one, its `{placeholder}` values — so the user sees the reason in the app language (docs/05
 * A11Y/i18n: never show raw English technical text).
 */
export class LocalDataError extends Error {
  readonly key: TKey;
  readonly params?: Params;

  constructor(key: TKey, params?: Params) {
    super(key);
    this.key = key;
    this.params = params;
    this.name = 'LocalDataError';
  }
}
