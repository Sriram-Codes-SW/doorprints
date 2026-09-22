import { Component, ElementRef, Injector, afterNextRender, inject, viewChild } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ConfigService } from './core/config.service';
import { Announcer } from './core/announcer.service';
import { TranslationService } from './i18n/translation.service';
import { TPipe } from './i18n/t.pipe';
import { isLang } from './i18n/languages';
import { AiService } from './core/ai.service';
import { ConfirmDialog } from './shared/confirm-dialog';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe, ConfirmDialog],
  template: `
    <a class="skip-link" href="#main" (click)="skipToMain($event)">{{ 'app.skip' | t }}</a>
    <header class="topbar">
      <a class="brand" routerLink="/" [attr.aria-label]="'app.homeLink' | t">
        <img src="favicon.svg" alt="" width="28" height="28" />
        <span class="brand-name" aria-hidden="true">{{ 'app.name' | t }}</span>
      </a>
      <nav [attr.aria-label]="'nav.label' | t">
        @if (config.configured()) {
          <a
            routerLink="/"
            routerLinkActive="current"
            ariaCurrentWhenActive="page"
            [routerLinkActiveOptions]="{ exact: true }"
            >{{ 'nav.map' | t }}</a
          >
          <a routerLink="/compare" routerLinkActive="current" ariaCurrentWhenActive="page">{{ 'nav.compare' | t }}</a>
          @if (ai.enabled()) {
            <a routerLink="/ask" routerLinkActive="current" ariaCurrentWhenActive="page">{{ 'nav.ask' | t }}</a>
            <a routerLink="/plan" routerLinkActive="current" ariaCurrentWhenActive="page">{{ 'nav.plan' | t }}</a>
          }
        }
        <a routerLink="/connect" routerLinkActive="current" ariaCurrentWhenActive="page">{{ 'nav.connect' | t }}</a>
      </nav>
      <div class="lang">
        <label for="lang-select" class="lang-label">
          <span class="icon" aria-hidden="true">🌐</span>
          <span class="sr-only">{{ 'lang.label' | t }}</span>
        </label>
        <select id="lang-select" [value]="i18n.lang()" (change)="onLang($event)">
          @for (l of i18n.languages; track l.code) {
            <option [value]="l.code" [attr.lang]="l.code" [selected]="l.code === i18n.lang()">{{ l.nativeName }}</option>
          }
        </select>
      </div>
    </header>
    <main id="main" #main class="content" tabindex="-1">
      <router-outlet />
    </main>
    <app-confirm-dialog />
    <div class="sr-only" role="status" aria-live="polite" aria-atomic="true">
      @if (announcer.message(); as m) {
        {{ m.key | t: m.params }}
      }
    </div>
  `,
  styles: `
    :host {
      display: flex;
      flex-direction: column;
      height: 100vh;
      height: 100dvh;
    }
    .skip-link {
      position: absolute;
      left: var(--space-2);
      top: -100px;
      z-index: 1000;
      padding: var(--space-2) var(--space-4);
      background: var(--surface);
      color: var(--text);
      border: 2px solid var(--focus);
      border-radius: 8px;
      font-weight: 700;
    }
    .skip-link:focus {
      top: var(--space-2);
    }
    .topbar {
      --focus: var(--on-header);
      flex: 0 0 auto;
      min-height: var(--header-h);
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-3);
      padding: var(--space-1) var(--space-4);
      background: var(--header-bg);
      color: var(--on-header);
      box-shadow: 0 1px 4px rgba(0, 0, 0, 0.2);
      z-index: 10;
    }
    .brand {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      min-height: var(--target);
      color: var(--on-header);
      text-decoration: none;
      font-weight: 700;
      font-size: var(--text-lg);
      margin-right: auto;
    }
    .brand img {
      border-radius: 6px;
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.5);
    }
    nav {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
    }
    nav a {
      display: inline-flex;
      align-items: center;
      min-height: var(--target);
      padding: 0 var(--space-3);
      color: var(--on-header);
      text-decoration: none;
      border-radius: 6px;
      font-weight: 500;
      border-bottom: 3px solid transparent;
    }
    /* Darken rather than lighten the teal so white text keeps >= 6:1. */
    nav a:hover {
      background: rgba(0, 0, 0, 0.15);
    }
    nav a.current {
      font-weight: 700;
      background: rgba(0, 0, 0, 0.25);
      border-bottom-color: var(--on-header);
    }
    .lang {
      display: flex;
      align-items: center;
      gap: var(--space-1);
    }
    .lang-label {
      display: inline-flex;
    }
    .lang select {
      width: auto;
      min-height: 40px;
      padding: var(--space-1) var(--space-2);
      background: rgba(0, 0, 0, 0.18);
      color: var(--on-header);
      border: 1px solid rgba(255, 255, 255, 0.7);
    }
    .lang select option {
      color: var(--text);
      background: var(--surface);
    }
    .content {
      flex: 1 1 auto;
      min-height: 0;
      overflow: auto;
      position: relative;
    }
    .content:focus {
      outline: none;
    }
    @media (max-width: 480px) {
      .topbar {
        padding: var(--space-1) var(--space-2);
      }
      .brand-name {
        display: none;
      }
      nav a {
        padding: 0 var(--space-2);
      }
    }
  `,
})
export class App {
  protected readonly config = inject(ConfigService);
  protected readonly i18n = inject(TranslationService);
  protected readonly announcer = inject(Announcer);
  protected readonly ai = inject(AiService);
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');

  constructor() {
    // After an in-app navigation, move focus to the new page's heading so screen-reader and keyboard
    // users start at the top of the new content (the first load keeps the browser default).
    const injector = inject(Injector);
    let lastPath: string | null = null;
    inject(Router).events.subscribe((e) => {
      if (!(e instanceof NavigationEnd)) return;
      const path = e.urlAfterRedirects.split(/[?#]/)[0];
      const changed = lastPath !== null && lastPath !== path;
      lastPath = path;
      if (changed) afterNextRender(() => this.focusHeading(), { injector });
    });
  }

  protected onLang(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    if (!isLang(value)) return;
    this.i18n.setLang(value);
    this.announcer.announce({ key: 'lang.changed' });
  }

  private focusHeading(): void {
    const main = this.main().nativeElement;
    const target = main.querySelector<HTMLElement>('h1') ?? main;
    if (target !== main) target.setAttribute('tabindex', '-1');
    target.focus({ preventScroll: true });
  }

  /** In-page anchors would resolve against <base href> and navigate, so move focus by hand. */
  protected skipToMain(event: Event): void {
    event.preventDefault();
    const main = this.main().nativeElement;
    main.focus();
    main.scrollIntoView();
  }
}
