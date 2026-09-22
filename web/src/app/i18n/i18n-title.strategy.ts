import { Injectable, effect, inject, signal } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TKey, en } from './en';
import { TranslationService } from './translation.service';

/** Route `title`s are translation keys; the document title follows both navigation and language changes. */
@Injectable({ providedIn: 'root' })
export class I18nTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly i18n = inject(TranslationService);
  private readonly key = signal<string | undefined>(undefined);

  constructor() {
    super();
    effect(() => {
      const key = this.key();
      this.title.setTitle(this.i18n.t(isKey(key) ? key : 'title.app'));
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.key.set(this.buildTitle(snapshot));
  }
}

function isKey(key: string | undefined): key is TKey {
  return !!key && Object.prototype.hasOwnProperty.call(en, key);
}
