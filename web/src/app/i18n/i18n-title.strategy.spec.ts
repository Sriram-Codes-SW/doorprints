import { TestBed } from '@angular/core/testing';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { en } from './en';
import { hi } from './hi';
import { ta } from './ta';
import { I18nTitleStrategy } from './i18n-title.strategy';
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
    expect(updateTag).not.toHaveBeenCalled();
    expect(description()).toBe(en['app.description']);

    i18n.setLang('ta');
    TestBed.tick();
    expect(description()).toBe(ta['app.description']);
    expect(updateTag).toHaveBeenCalledTimes(1);
  });
});
