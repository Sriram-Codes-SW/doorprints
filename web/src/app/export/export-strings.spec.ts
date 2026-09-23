import { describe, expect, it } from 'vitest';
import { LANGUAGES } from '../i18n/languages';
import { ALL_EXPORT_STRINGS, EXPORT_STRING_KEYS, ExportStrings } from './export-strings';
import { CHECKLIST, STATUSES } from '../core/models';

/**
 * The export vocabulary is the other half of a cross-platform contract with
 * `android/shared/src/commonMain/kotlin/com/househunt/shared/export/ExportStrings.kt`, whose own
 * `ExportStringsTest` checks the same properties on the Kotlin side. Keep the two suites in step.
 */
describe('ExportStrings', () => {
  it('has one table per export language, and only those', () => {
    expect(ALL_EXPORT_STRINGS.map((s) => s.language)).toEqual(LANGUAGES.map((l) => l.code));
  });

  it('defines exactly the same keys in every language, with nothing empty', () => {
    for (const strings of ALL_EXPORT_STRINGS) {
      for (const key of EXPORT_STRING_KEYS) {
        expect(strings.get(key).trim(), `${strings.language}/${key}`).not.toBe('');
      }
    }
  });

  it('covers every house status and every built-in checklist key', () => {
    for (const strings of ALL_EXPORT_STRINGS) {
      for (const status of STATUSES) expect(strings.status(status)).not.toBe(status);
      for (const item of CHECKLIST) expect(strings.check(item.key)).not.toBe(item.key);
    }
  });

  it('falls back to the raw key for something a newer app version wrote', () => {
    // Sprint 4b's custom criteria (docs/11 §5.4) arrive as keys this build has never seen.
    expect(ExportStrings.of('ta').check('customCriterion')).toBe('customCriterion');
    expect(ExportStrings.of('ta').status('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(ExportStrings.of('ta').source('SOMETHING_NEW')).toBe('SOMETHING_NEW');
  });

  it('is not the app catalogue: the export language is chosen per export', () => {
    // A copy written in Tamil while the app itself is in English is the whole point of the option.
    expect(ExportStrings.of('ta').get('col.rank')).not.toBe(ExportStrings.of('en').get('col.rank'));
  });

  it('treats an unknown key as a programming error rather than returning something wrong', () => {
    expect(() => ExportStrings.of('en').get('col.nope' as (typeof EXPORT_STRING_KEYS)[number])).toThrow();
  });
});
