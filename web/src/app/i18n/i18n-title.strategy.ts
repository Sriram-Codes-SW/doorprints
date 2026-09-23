import { Injectable, effect, inject, signal } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TKey, en } from './en';
import { Msg, TranslationService } from './translation.service';

/**
 * A title a page sets for itself when the route's own is too general: a house page is titled with the house's name
 * ("Blue gate 2BHK · Doorprints"), so several open houses can be told apart in the tab bar (WCAG 2.4.2). The page
 * sets it once the name is known and clears it when it goes; it is translated like a route title, so it follows a
 * language change too.
 */
@Injectable({ providedIn: 'root' })
export class TitleOverride {
  readonly message = signal<Msg | null>(null);
}

/**
 * Route `title`s are translation keys; the document title follows both navigation and language changes.
 * Titles read "<page> · Doorprints" (brand last, so tabs stay distinguishable); pages without a title get
 * `title.app`. The meta description is `app.description` (the English one matches index.html) and follows
 * the language only, in its own effect, so navigation does not rewrite it.
 */
@Injectable({ providedIn: 'root' })
export class I18nTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly i18n = inject(TranslationService);
  private readonly pageTitle = inject(TitleOverride);
  private readonly key = signal<string | undefined>(undefined);

  constructor() {
    super();
    // Reads `key`, a page's own title and the language: re-runs on navigation, on language change, and when a page
    // names itself (TitleOverride).
    effect(() => {
      const key = this.key();
      const own = this.pageTitle.message();
      this.title.setTitle(own ? this.i18n.msg(own) : this.i18n.t(isKey(key) ? key : 'title.app'));
    });
    // Reads only the language (through t()): re-runs on language change, never on navigation.
    effect(() => {
      this.meta.updateTag({ name: 'description', content: this.i18n.t('app.description') });
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.key.set(this.buildTitle(snapshot));
  }
}

function isKey(key: string | undefined): key is TKey {
  return !!key && Object.prototype.hasOwnProperty.call(en, key);
}
