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

// S4b-BL-291: the Full backup's options and gaps and the import preview act on every space, so with two or more kinds on their
// words name no kind (the `*` table of space-tool-words.ts); with one kind they are the house's, word for word. The expected
// English is written here, not read from the dictionary.
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { afterEach, describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';
import { DEFAULT_KIND_KEY, ENABLED_KINDS_KEY, EXPERIMENTAL_KINDS_KEY } from '../../core/kind-setting';
import { toolWord, toolWordPairs } from '../../core/space-tool-words';
import type { HouseDto } from '../../core/models';
import { LocalStore } from '../../data/local-store.service';
import { DICTIONARIES } from '../../i18n/all-dictionaries';
import type { TKey } from '../../i18n/en';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { ImportBackupCard } from './import-backup';

const LANGS: Lang[] = ['en', 'hi', 'ta', 'te'];
const HOUSE_WORD: Record<Lang, RegExp> = { en: /house|BHK|\bflat\b/i, hi: /मकान|घर/, ta: /வீடு|வீட்/, te: /ఇల్లు|ఇళ్ళు|ఇళ్లు|ఇంటి|ఇంట/ };
const dict = (lang: Lang) => DICTIONARIES[lang] as Record<string, string>;
const holes = (s: string) => (s.match(/\{\w+\}/g) ?? []).sort();
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const twoKinds = () => {
  localStorage.setItem(EXPERIMENTAL_KINDS_KEY, '1');
  localStorage.setItem(ENABLED_KINDS_KEY, '["house","school"]');
  localStorage.setItem(DEFAULT_KIND_KEY, 'house');
};

afterEach(() => {
  localStorage.clear();
  TestBed.resetTestingModule();
});

describe('the kind-neutral words (S4b-BL-291)', () => {
  const pairs = toolWordPairs('*');

  it('cover the Full backup’s options and gaps, the import preview and the app-wide texts of Connect and the first download', () => {
    expect(pairs.map(([house]) => house).sort()).toEqual([
      'connect.aiHint', 'connect.aiSends', 'connect.intro', 'connect.inviteBody', 'connect.success', 'confirm.clearBrowser', 'data.clearBody',
      'data.migrationDone', 'data.migrationTitle', 'data.progressHouses', 'data.syncPaused',
      'data.empty', 'data.emptyAction', 'data.countHouses', 'data.gapNotSelected', 'data.gapNotShortlisted', 'data.gapPhotosNotShortlisted',
      'data.gapRejected', 'data.includeRejected', 'data.noShortlisted', 'data.noneMatch', 'data.photosShortlisted', 'data.preview', 'data.scope',
      'data.scopeAll', 'imp.checklistsCleared', 'imp.done', 'imp.doneRemoved', 'imp.floorsLeftBlank', 'imp.intro', 'imp.modeCopyHint',
      'imp.modeMergeHint', 'imp.newHouses', 'imp.removedHouses', 'imp.restore', 'imp.seeHouses', 'imp.updatedHouses',
    ].sort());
  });

  for (const lang of LANGS) {
    it(`each reads in ${lang} with the house word gone and the same placeholders`, () => {
      const d = dict(lang);
      for (const [house, any] of pairs) {
        expect(d[any], `${lang} ${any}`).toBeTruthy();
        expect(d[any], `${lang} ${any}`).not.toBe(d[house]);
        expect(holes(d[any]), `${lang} ${any}`).toEqual(holes(d[house]));
        expect(d[any].replace(/\{\w+\}/g, ''), `${lang} ${any}`).not.toMatch(HOUSE_WORD[lang]);
      }
    });
  }

  it('every preview line that names a house in English has a neutral reading', () => {
    const lines = ['imp.copyDuplicates', 'imp.newHouses', 'imp.updatedHouses', 'imp.checklistsCleared', 'imp.floorsLeftBlank', 'imp.restoredHouses',
      'imp.newerHere', 'imp.deletedHere', 'imp.removedHouses', 'imp.newVisits', 'imp.updatedVisits', 'imp.newPhotos', 'imp.photosMissing',
      'imp.newBrokers', 'imp.updatedBrokers', 'imp.otherRecords'] as TKey[];
    for (const k of lines) expect(dict('en')[toolWord('*', k)], k).not.toMatch(/house/i);
  });

  it('a house keeps its own words with one kind: the table is only read under `*`', () => {
    expect(toolWord('house', 'imp.newHouses')).toBe('imp.newHouses');
    expect(toolWord(null, 'data.scopeAll')).toBe('data.scopeAll');
    expect(toolWord('*', 'imp.newHouses')).not.toBe('imp.newHouses');
  });
});

describe('Your data, the Full backup, with two kinds on (S4b-BL-291)', () => {
  async function open() {
    TestBed.configureTestingModule({ providers: [provideRouter(routes)] });
    await TestBed.inject(TranslationService).setLang('en');
    const store = TestBed.inject(LocalStore);
    await store.saveHouse({ id: 'h1', label: 'Green View', lat: 13, lon: 80, status: 'NEW', checklist: {}, deleted: false, syncVersion: 0 } as unknown as HouseDto);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/data');
    const settle = async () => {
      for (let i = 0; i < 4; i++) {
        await harness.fixture.whenStable();
        await new Promise((r) => setTimeout(r, 0));
        harness.detectChanges();
      }
    };
    await settle();
    const page = harness.routeDebugElement!.componentInstance as { setFormat(f: string): void; setScope(s: string): void };
    page.setFormat('backup');
    await settle();
    return { host: harness.routeNativeElement as HTMLElement, page, settle };
  }
  const scopeWords = (host: HTMLElement) => [text(host.querySelector('fieldset.sub legend')), ...[...host.querySelectorAll('fieldset.sub .option')].map(text)];

  it('names no kind: the options, and the gaps of the note', async () => {
    twoKinds();
    const { host, page, settle } = await open();
    expect(scopeWords(host)).toEqual(['Entries', 'All entries', 'Shortlisted only', 'All photos', 'Shortlisted entries only', 'No photos']);
    page.setScope('shortlisted');
    await settle();
    expect(text(host.querySelector('.partial p'))).toContain('This backup leaves out entries that are not shortlisted');
    expect(text(host.querySelector('.partial p'))).not.toMatch(/house/i);
  });

  it('with one kind, the house’s words, as they were', async () => {
    const { host, page, settle } = await open();
    expect(scopeWords(host)).toEqual(['Houses', 'All houses', 'Shortlisted only', 'All photos', 'Shortlisted houses only', 'No photos']);
    page.setScope('shortlisted');
    await settle();
    expect(text(host.querySelector('.partial p'))).toContain('This backup leaves out houses that are not shortlisted');
  });
});

describe('Import a backup, with two kinds on (S4b-BL-291)', () => {
  async function render() {
    TestBed.configureTestingModule({ imports: [ImportBackupCard], providers: [provideRouter([])] });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(ImportBackupCard);
    fixture.detectChanges();
    await fixture.whenStable();
    return fixture;
  }

  it('the introduction names no house', async () => {
    twoKinds();
    const el = (await render()).nativeElement as HTMLElement;
    expect(text(el.querySelector('#import-intro'))).toMatch(/^Bring your saved entries, visits and photos from a Doorprints Full backup/);
  });

  it('with one kind it is the house’s', async () => {
    const el = (await render()).nativeElement as HTMLElement;
    expect(text(el.querySelector('#import-intro'))).toMatch(/^Bring houses, visits and photos from a Doorprints Full backup/);
  });
});
