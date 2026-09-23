import type { TKey } from '../i18n/en';
import type { ExportOptions } from './export-model';

/**
 * The "what is in this file" list that appears on the cover of the HTML copy, at the top of the Markdown file and
 * in the backup manifest. One translation key per chosen option, always in the same order, so the cover of two
 * exports with the same options is identical.
 */
export function optionSummaryKeys(options: ExportOptions): TKey[] {
  const keys: TKey[] = [];
  keys.push(
    options.scope === 'all'
      ? 'exp.scopeAll'
      : options.scope === 'shortlisted'
        ? 'exp.scopeShortlisted'
        : 'exp.scopeSelected',
  );
  keys.push(options.includeRejected ? 'exp.rejectedIncluded' : 'exp.rejectedLeftOut');
  keys.push(
    options.photos === 'all'
      ? 'exp.photosAll'
      : options.photos === 'shortlisted'
        ? 'exp.photosShortlisted'
        : 'exp.photosNone',
  );
  keys.push(options.includeContacts ? 'exp.contactsIncluded' : 'exp.contactsLeftOut');
  return keys;
}
