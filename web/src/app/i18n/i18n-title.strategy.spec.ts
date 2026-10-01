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

import { TestBed } from '@angular/core/testing';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { en } from './en';
import './all-dictionaries'; // every language registered, so setLang switches at once
import { hi } from './hi';
import { ta } from './ta';
import { I18nTitleStrategy, SITE_URL, TitleOverride, isIndexable } from './i18n-title.strategy';
import { TranslationService } from './translation.service';

/** The snapshot is never read: buildTitle() is stubbed, so each test picks the route title key directly. */
const SNAPSHOT = {} as RouterStateSnapshot;

describe('I18nTitleStrategy', () => {
  let strategy: I18nTitleStrategy;
  let i18n: TranslationService;
  let title: Title;
  let meta: Meta;

  function navigateTo(routeTitle: string | undefined): void {
    vi.spyOn(strategy, 'buildTitle').mockReturnValue(routeTitle);
    strategy.updateTitle(SNAPSHOT);
    TestBed.tick();
  }

  function description(): string | null | undefined {
    return meta.getTag('name="description"')?.content;
  }

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    i18n = TestBed.inject(TranslationService);
    i18n.setLang('en');
    strategy = TestBed.inject(I18nTitleStrategy);
    title = TestBed.inject(Title);
    meta = TestBed.inject(Meta);
    TestBed.tick();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('titles a page "<page> · Doorprints"', () => {
    navigateTo('title.compare');
    expect(title.getTitle()).toBe('Compare · Doorprints');
  });

  it('falls back to title.app when the route has no title', () => {
    navigateTo(undefined);
    expect(title.getTitle()).toBe(en['title.app']);
    expect(title.getTitle()).toBe('Doorprints');
  });

  it('falls back to title.app when the route title is not a translation key', () => {
    navigateTo('no.such.key');
    expect(title.getTitle()).toBe('Doorprints');
  });

  it('translates the title again when the language changes', () => {
    navigateTo('title.map');
    i18n.setLang('hi');
    TestBed.tick();
    expect(title.getTitle()).toBe(hi['title.map']);
  });

  it('uses a page’s own title while it is set, in the current language, and the route title again after', () => {
    const own = TestBed.inject(TitleOverride);
    navigateTo('title.house');
    own.message.set({ key: 'title.houseNamed', params: { name: 'Blue gate 2BHK' } });
    TestBed.tick();
    expect(title.getTitle()).toBe('Blue gate 2BHK · Doorprints');
    i18n.setLang('hi');
    TestBed.tick();
    expect(title.getTitle()).toBe(hi['title.houseNamed'].replace('{name}', 'Blue gate 2BHK'));
    own.message.set(null);
    TestBed.tick();
    expect(title.getTitle()).toBe(hi['title.house']);
  });

  it('sets the meta description from app.description, matching index.html in English', () => {
    expect(description()).toBe(en['app.description']);
    expect(en['app.description']).toBe(
      "Doorprints: Remember every house you've seen. Save, rate and compare the houses you visit.",
    );
  });

  it('keeps the description on navigation and follows a language change', () => {
    const updateTag = vi.spyOn(meta, 'updateTag');
    navigateTo('title.compare');
    navigateTo('title.map');
    expect(updateTag.mock.calls.filter(([tag]) => tag.name === 'description')).toHaveLength(0);
    expect(description()).toBe(en['app.description']);

    i18n.setLang('ta');
    TestBed.tick();
    expect(description()).toBe(ta['app.description']);
    expect(updateTag.mock.calls.filter(([tag]) => tag.name === 'description')).toHaveLength(1);
  });

  describe('search tags follow the route', () => {
    const route = (data: Record<string, unknown>) =>
      ({ root: { data: {}, firstChild: { data: {}, firstChild: { data, firstChild: null } } } }) as unknown as RouterStateSnapshot;
    const robots = () => meta.getTag('name="robots"')?.content;
    const canonical = () => document.head.querySelector('link[rel="canonical"]');

    beforeEach(() => vi.spyOn(strategy, 'buildTitle').mockReturnValue(undefined));
    afterEach(() => canonical()?.remove());

    it('indexes only a route with data.index, with a canonical link', () => {
      expect(isIndexable(route({ index: true }))).toBe(true);
      expect(isIndexable(route({}))).toBe(false);
      expect(isIndexable(route({ index: 'yes' }))).toBe(false);
      strategy.updateTitle(route({ index: true }));
      TestBed.tick();
      expect(robots()).toBe('index, follow');
      expect(canonical()?.getAttribute('href')).toBe(SITE_URL);
    });

    it('marks every other route noindex, nofollow and drops the canonical link', () => {
      strategy.updateTitle(route({ index: true }));
      TestBed.tick();
      strategy.updateTitle(route({}));
      TestBed.tick();
      expect(robots()).toBe('noindex, nofollow');
      expect(canonical()).toBeNull();
    });
  });
});
