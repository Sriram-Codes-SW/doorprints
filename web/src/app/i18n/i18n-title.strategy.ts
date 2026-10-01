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

import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, RouterStateSnapshot, TitleStrategy } from '@angular/router';
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

/** The one canonical address of the public site (index.html carries the same one, and sitemap.xml lists it). */
export const SITE_URL = 'https://doorprints.web.app/';

/**
 * A route is kept out of search results unless it says `data: { index: true }`: everything behind the landing page
 * shows the visitor's own, local data (houses, notes, brokers) and is the same HTML shell for a crawler anyway.
 * Default-deny, so a route added later is private until someone decides otherwise (app.routes.ts, seo.spec.ts).
 */
export function isIndexable(snapshot: RouterStateSnapshot): boolean {
  let route: ActivatedRouteSnapshot | null = snapshot.root;
  while (route?.firstChild) route = route.firstChild;
  return route?.data?.['index'] === true;
}

/**
 * Route `title`s are translation keys; the document title follows both navigation and language changes.
 * Titles read "<page> · Doorprints" (brand last, so tabs stay distinguishable); pages without a title get
 * `title.app`. Search tags follow the route too: `robots: noindex, nofollow` and no canonical link on routes that do
 * not opt in with `data: { index: true }`, the canonical link on the one that does. The meta description is `app.description` (the English one matches index.html) and follows
 * the language only, in its own effect, so navigation does not rewrite it.
 */
@Injectable({ providedIn: 'root' })
export class I18nTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly i18n = inject(TranslationService);
  private readonly pageTitle = inject(TitleOverride);
  private readonly doc = inject(DOCUMENT);
  private readonly key = signal<string | undefined>(undefined);
  private readonly indexable = signal(true);

  constructor() {
    super();
    // Reads `key`, a page's own title and the language: re-runs on navigation, on language change, and when a page
    // names itself (TitleOverride).
    effect(() => {
      const key = this.key();
      const own = this.pageTitle.message();
      this.title.setTitle(own ? this.i18n.msg(own) : this.i18n.t(isKey(key) ? key : 'title.app'));
    });
    // Reads only the route: robots and canonical (index.html ships both for the landing page, so a crawler that
    // does not run scripts sees them too).
    effect(() => {
      const indexable = this.indexable();
      this.meta.updateTag({ name: 'robots', content: indexable ? 'index, follow' : 'noindex, nofollow' });
      const head = this.doc.head;
      let link = head.querySelector<HTMLLinkElement>('link[rel="canonical"]');
      if (!indexable) link?.remove();
      else if (!link) {
        link = this.doc.createElement('link');
        link.rel = 'canonical';
        link.href = SITE_URL;
        head.appendChild(link);
      }
    });
    // Reads only the language (through t()): re-runs on language change, never on navigation.
    effect(() => {
      this.meta.updateTag({ name: 'description', content: this.i18n.t('app.description') });
    });
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.key.set(this.buildTitle(snapshot));
    this.indexable.set(isIndexable(snapshot));
  }
}

function isKey(key: string | undefined): key is TKey {
  return !!key && Object.prototype.hasOwnProperty.call(en, key);
}
