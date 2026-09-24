import {
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  afterRenderEffect,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NavigationEnd, NavigationStart, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Announcer } from './core/announcer.service';
import { TranslationService } from './i18n/translation.service';
import { TPipe } from './i18n/t.pipe';
import { isLang } from './i18n/languages';
import { AiService } from './core/ai.service';
import { ConfirmDialog } from './shared/confirm-dialog';
import { AppBanners } from './shared/app-banners';
import { PwaService } from './core/pwa.service';
import { SyncService } from './data/sync.service';
import { hidesBottomBar, inDataSection, inMapSection, sectionCurrent } from './nav-section';

/** How many pages' scroll positions are remembered for Back and Forward (older ones are dropped). */
const SCROLL_MEMORY = 50;

/** Width of the fade at the end of a header navigation that scrolls sideways (the CSS mask uses the same 24px). */
const NAV_FADE_PX = 24;

/**
 * 24px navigation glyphs for the bottom bar (Material Icons shapes, Apache-2.0), drawn with `currentColor` so the
 * selected item follows `--primary` and forced-colours mode keeps them visible.
 */
const NAV_ICONS = {
  map: 'M20.5 3l-.16.03L15 5.1 9 3 3.36 4.9c-.21.07-.36.25-.36.48V20.5c0 .28.22.5.5.5l.16-.03L9 18.9l6 2.1 5.64-1.9c.21-.07.36-.25.36-.48V3.5c0-.28-.22-.5-.5-.5zM15 19l-6-2.11V5l6 2.11V19z',
  compare: 'M9.01 14H2v2h7.01v3L13 15l-3.99-4v3zm5.98-1v-3H22V8h-7.01V5L11 9l3.99 4z',
  ask: 'M21 6h-2v9H6v2c0 .55.45 1 1 1h11l4 4V7c0-.55-.45-1-1-1zm-4 6V3c0-.55-.45-1-1-1H3c-.55 0-1 .45-1 1v14l4-4h10c.55 0 1-.45 1-1z',
  plan: 'M17 12h-5v5h5v-5zM16 1v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2h-1V1h-2zm3 18H5V8h14v11z',
  data: 'M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z',
  connect: 'M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z',
} as const;

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, TPipe, ConfirmDialog, AppBanners],
  template: `
    <a class="skip-link" href="#main" (click)="skipToMain($event)">{{ 'app.skip' | t }}</a>
    <header class="topbar">
      <a class="brand" routerLink="/" [attr.aria-label]="'app.homeLink' | t">
        <img src="favicon.svg" alt="" width="28" height="28" />
        <span class="brand-name" aria-hidden="true">{{ 'app.name' | t }}</span>
      </a>
      <!--
        Before the navigation in the DOM, so on a phone focus goes brand, language (both in the top row), then the
        bottom bar, then the page — not top, bottom, top (WCAG 2.4.3). On wider screens order: 1 keeps it visually at
        the end of the header, as before.
      -->
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
      <!--
        Local-first (docs/11 D-01): the map, compare and data pages work with no server, so they are always here.
        Up to 600px wide the same <nav> becomes a fixed bottom bar (Material 3 NavigationBar, like Root.kt on
        Android): every destination in thumb reach, and a one-row header. It keeps at most five items there, so
        Connect moves into Your data on phones (the sync card links to it). Labels are one word, like Android's
        NavigationBar (nav_map, nav_compare, …), and the bar's real height is measured into --nav-h (see below).
        bar-hidden: on a house (a child of the map) phones get the whole screen for the form, with the toolbar's Back,
        like Android, which shows its NavigationBar on tab destinations only. more: the header list scrolls sideways
        and has items past its end (a fade shows it).
      -->
      <nav
        #nav
        class="nav"
        [class.bar-hidden]="barHidden()"
        [class.more]="navMore()"
        [attr.aria-label]="'nav.label' | t"
        (scroll)="updateNavMore()"
      >
        <!--
          Map is also current on a house (/houses/:id, /houses/new): the house form is a child of the map, as on
          Android, and Back goes there. aria-current is "page" on the map itself and "true" on its child pages.
        -->
        <a routerLink="/" [class.current]="mapSection()" [attr.aria-current]="mapCurrent()">
          <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="24" height="24"><path [attr.d]="icons.map" /></svg></span>
          <span class="nav-label">{{ 'nav.map' | t }}</span>
        </a>
        <a routerLink="/compare" routerLinkActive="current" ariaCurrentWhenActive="page">
          <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="24" height="24"><path [attr.d]="icons.compare" /></svg></span>
          <span class="nav-label">{{ 'nav.compare' | t }}</span>
        </a>
        @if (ai.enabled()) {
          <a routerLink="/ask" routerLinkActive="current" ariaCurrentWhenActive="page">
            <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="24" height="24"><path [attr.d]="icons.ask" /></svg></span>
            <span class="nav-label">{{ 'nav.ask' | t }}</span>
          </a>
          <a routerLink="/plan" routerLinkActive="current" ariaCurrentWhenActive="page">
            <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="24" height="24"><path [attr.d]="icons.plan" /></svg></span>
            <span class="nav-label">{{ 'nav.plan' | t }}</span>
          </a>
        }
        <!--
          Your data is marked current on its own page, and on phones also on Connect, which lives inside it there
          (the bar has no Connect item). A dot marks an automatic sync that stopped working, from any screen.
        -->
        <a routerLink="/data" [class.current]="dataSection()" [attr.aria-current]="dataCurrent()">
          <span class="nav-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="24" height="24"><path [attr.d]="icons.data" /></svg>
            @if (syncProblem()) {
              <span class="badge"></span>
            }
          </span>
          <span class="nav-label">
            {{ 'nav.data' | t }}
            @if (syncProblem()) {
              <span class="badge badge-inline" aria-hidden="true">!</span>
              <span class="sr-only">({{ 'nav.syncProblem' | t }})</span>
            }
          </span>
        </a>
        <a class="nav-connect" routerLink="/connect" routerLinkActive="current" ariaCurrentWhenActive="page">
          <span class="nav-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="24" height="24"><path [attr.d]="icons.connect" /></svg></span>
          <span class="nav-label">{{ 'nav.connect' | t }}</span>
        </a>
      </nav>
    </header>
    <app-banners />
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
      /*
       * No wider than the screen: an absolute box is as wide as its longest word, and at 200% text the Tamil label
       * (உள்ளடக்கத்திற்குச்…) was 422px, which made a 360px phone lay the page out 438px wide and zoom it out.
       */
      max-width: calc(100% - 2 * var(--space-2));
      /* Moved up by its own height, not a fixed 100px: wrapped onto three lines (Tamil at 130%) it showed below that. */
      top: 0;
      transform: translateY(-110%);
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
      transform: none;
    }
    /*
     * Safe areas: index.html asks for viewport-fit=cover with a black-translucent status bar, so an app added to
     * the iPhone Home Screen draws under the status bar. The max() keeps the ordinary padding everywhere else
     * (env() is 0 there) and moves the header's content below the clock, notch or Dynamic Island.
     */
    .topbar {
      --focus: var(--on-header);
      flex: 0 0 auto;
      min-height: var(--header-h);
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--space-1) var(--space-3);
      padding-top: max(var(--space-1), env(safe-area-inset-top));
      padding-bottom: var(--space-1);
      padding-left: max(var(--space-4), env(safe-area-inset-left));
      padding-right: max(var(--space-4), env(safe-area-inset-right));
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
      /* Where the name is hidden (phones, tablets, landscape) the 28px mark alone is still a 44px target (UX-007). */
      min-width: var(--target);
      justify-content: center;
    }
    .brand img {
      border-radius: 6px;
      box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.5);
    }
    .nav {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-1);
    }
    .nav a {
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
    /* In the header the text is enough; the icons are for the bottom bar. */
    .nav-icon {
      display: none;
    }
    .nav-icon svg {
      display: block;
      fill: currentColor;
    }
    /*
     * Darken rather than lighten the teal so white text keeps >= 6:1. Only where there is real hover: on a touch
     * screen a tapped item would keep the hover look until the next tap elsewhere.
     */
    @media (hover: hover) {
      .nav a:hover {
        background: rgba(0, 0, 0, 0.15);
      }
    }
    .nav a.current {
      font-weight: 700;
      background: rgba(0, 0, 0, 0.25);
      border-bottom-color: var(--on-header);
    }
    .lang {
      display: flex;
      align-items: center;
      gap: var(--space-1);
      /* After the navigation on screen, before it in the DOM (see the template). */
      order: 1;
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
    /* Touch screens: the full 44px target (UX-007). */
    @media (pointer: coarse) {
      .lang select {
        min-height: var(--target);
      }
    }
    /*
     * "Sync stopped working": never the only cue (the label says it to screen readers). The bottom bar's dot sits on
     * --surface in --warn-text (5.93:1). The header's mark (below) has its own token, --on-header-alert.
     */
    .badge {
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background: var(--warn-text);
      box-shadow: 0 0 0 2px var(--header-bg);
    }
    .nav-icon {
      position: relative;
    }
    .nav-icon .badge {
      position: absolute;
      top: 3px;
      right: calc(50% - 16px);
      box-shadow: 0 0 0 2px var(--surface);
    }
    /*
     * In the header (desktop, tablet): a 16px "!" disc in --on-header-alert (4.23:1 on the header, more on the hovered
     * and current item; WCAG 1.4.11) with a white ring, so it differs from the header by shape as well as colour. The
     * --warn-text dot was 1.02:1 there, invisible to a mouse user in light mode.
     */
    .badge-inline {
      width: 16px;
      height: 16px;
      margin-inline-start: var(--space-1);
      vertical-align: middle;
      background: var(--on-header-alert);
      box-shadow: 0 0 0 2px var(--on-header);
      color: var(--header-bg);
      font-size: 12px;
      font-weight: 800;
      line-height: 16px;
      text-align: center;
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
      /* Page z-indexes (map controls, sticky toolbars) stay inside the page and never cover the bottom bar. */
      isolation: isolate;
      padding-bottom: env(safe-area-inset-bottom);
      /*
       * WCAG 2.2 SC 2.4.11 Focus Not Obscured: when Tab scrolls a control into view, the browser keeps it clear of
       * a sticky page toolbar. A page with one (house details) measures it into --sticky-top on #main.
       */
      scroll-padding-top: var(--sticky-top, var(--space-4));
      /*
       * A size container, so a page can size a part of itself to the visible page area (100cqh: the space between
       * the header and the bottom bar, whatever the browser's toolbars take). The Map page fits its map to it, so the
       * map is not 55% of the *largest* viewport (vh ignores the address bar) with its list cut at the fold.
       */
      container-type: size;
    }
    .content:focus {
      outline: none;
    }

    /*
     * Phones: brand and language in one 48px row (the 44px picker and 2px above and below: more of the screen for the
     * map, owner report 2026-09-24); the navigation is a Material 3 bottom bar. The brand is its mark only (it
     * measured 28x44 before .brand got its 44px min-width).
     */
    @media (max-width: 600px) {
      .topbar {
        flex-wrap: nowrap;
        min-height: 48px;
        padding-top: max(2px, env(safe-area-inset-top));
        padding-bottom: 2px;
        padding-left: max(var(--space-2), env(safe-area-inset-left));
        padding-right: max(var(--space-2), env(safe-area-inset-right));
      }
      .brand-name {
        display: none;
      }
      .nav {
        --focus: var(--primary);
        position: fixed;
        inset: auto 0 0 0;
        z-index: 20;
        display: grid;
        grid-auto-flow: column;
        /* minmax(0, …): a plain 1fr column is never narrower than its longest word ("உங்கள் தரவு" at 200% text). */
        grid-auto-columns: minmax(0, 1fr);
        gap: 0;
        min-height: 64px;
        align-items: stretch;
        padding: 0 env(safe-area-inset-right) env(safe-area-inset-bottom) env(safe-area-inset-left);
        background: var(--surface);
        border-top: 1px solid var(--border);
        box-shadow: var(--shadow-lg);
      }
      /*
       * Top-aligned, as in M3's NavigationBar: every icon pill sits on the same line even when one label wraps
       * (200% text size, a long translation), instead of the one-line items being centred lower than the rest.
       */
      .nav a {
        flex-direction: column;
        justify-content: flex-start;
        gap: 2px;
        min-width: 0;
        min-height: 64px;
        padding: var(--space-3) 2px var(--space-1);
        border: 0;
        border-radius: 0;
        color: var(--muted);
        font-size: var(--text-xs);
        font-weight: 500;
        line-height: var(--leading-indic);
        text-align: center;
      }
      /* Breaks only a word that cannot fit at all, so a Tamil or Telugu label is not cut in the middle of a word. */
      .nav-label {
        max-width: 100%;
        overflow-wrap: break-word;
        hyphens: auto;
      }
      .nav-icon {
        display: grid;
        place-items: center;
        width: 64px;
        max-width: 100%;
        height: 32px;
        border-radius: 16px;
      }
      /* Only with a real pointer: after a tap on a phone, the hover look would stay on the item. */
      @media (hover: hover) {
        .nav a:hover {
          background: none;
          color: var(--text);
        }
        .nav a:hover .nav-icon {
          background: var(--surface-2);
        }
      }
      .nav a.current {
        background: none;
        color: var(--primary);
        font-weight: 700;
      }
      .nav a.current .nav-icon {
        background: var(--primary-soft);
      }
      .nav a:focus-visible {
        outline-offset: -3px;
      }
      .badge-inline {
        display: none;
      }
      /*
       * A house and the new-house form: no bottom bar, as on Android (its NavigationBar is on tab destinations only).
       * About 180px of fixed chrome left some 400px for a form filled in outdoors; the toolbar's Back goes to the map.
       * The measured --nav-h becomes 0 then (a display: none bar measures 0).
       */
      .nav.bar-hidden {
        display: none;
      }
      /* At most five items in the bar: Connect lives in Your data on phones. */
      .nav-connect {
        display: none !important;
      }
      /*
       * The bar is fixed over the scroller, so the scroller reserves its **measured** height (--nav-h, set by App
       * from a ResizeObserver; it already includes the safe-area padding) rather than a hard-coded 64px: a label
       * that wraps at 200% text size makes the bar taller, and the last card must still clear it. scroll-padding
       * keeps a control reached with Tab out from under the bar (WCAG 2.2 SC 2.4.11).
       */
      .content {
        padding-bottom: max(var(--nav-h, calc(64px + env(safe-area-inset-bottom))), env(safe-area-inset-bottom));
        scroll-padding-bottom: calc(var(--nav-h, 64px) + var(--space-2));
      }
    }

    /*
     * The navigation in the header on a narrow or short screen — a tablet in portrait (601 to 900px), a phone or
     * small tablet in landscape, 400% zoom on a wide window: the items stay on one row that scrolls sideways instead
     * of wrapping into a header two or three rows tall above every page (WCAG 1.4.10). In Tamil or Telugu brand, six
     * items and the language picker came to about 800px. The brand keeps its icon only (its name is its aria-label).
     */
    @media (min-width: 601px) and (max-width: 900px), (min-width: 601px) and (max-height: 500px) {
      .topbar {
        flex-wrap: nowrap;
      }
      .brand {
        margin-right: 0;
      }
      .brand-name {
        display: none;
      }
      .nav {
        flex: 1 1 auto;
        min-width: 0;
        flex-wrap: nowrap;
        overflow-x: auto;
        scrollbar-width: thin;
        padding: 3px;
      }
      .nav a {
        flex: 0 0 auto;
        white-space: nowrap;
      }
      /*
       * The global ring is 3px at a 2px offset, which needs 5px; the scroller clipped it to a 1px line. Drawn inside
       * the item instead, as in the bottom bar.
       */
      .nav a:focus-visible {
        outline-offset: -3px;
      }
      /*
       * More items past the end (Your data and Connect in Tamil or Telugu at 601-700px): the end edge fades out, a cue
       * a touch tablet's hidden overlay scrollbar does not give. Not while an item has focus, so its ring is never
       * faded (Tab scrolls the list; at the end the fade goes away by itself).
       */
      .nav.more {
        mask-image: linear-gradient(to right, #000 calc(100% - 24px), transparent);
      }
      .nav.more:focus-within {
        mask-image: none;
      }
    }

    @media (forced-colors: active) {
      .nav a.current .nav-icon {
        outline: 2px solid CanvasText;
      }
      /* Forced colours drop backgrounds: keep the alert mark visible as an outlined shape. */
      .badge {
        outline: 2px solid CanvasText;
      }
    }
  `,
})
export class App {
  protected readonly i18n = inject(TranslationService);
  protected readonly announcer = inject(Announcer);
  protected readonly ai = inject(AiService);
  protected readonly icons = NAV_ICONS;
  // Constructing it registers the service worker and starts listening for install and update events (S4-05).
  private readonly pwa = inject(PwaService);
  private readonly sync = inject(SyncService);
  private readonly main = viewChild.required<ElementRef<HTMLElement>>('main');
  private readonly nav = viewChild.required<ElementRef<HTMLElement>>('nav');
  private readonly injector = inject(Injector);

  /** The current path, without query or fragment. */
  private readonly path = signal('/');
  /** The bottom-bar layout (up to 600px), where Connect has no item of its own and lives in Your data. */
  private readonly phone = signal(false);
  /** A page without the phone bottom bar: a house or the new-house form (the bar is hidden by CSS on phones only). */
  protected readonly barHidden = computed(() => hidesBottomBar(this.path()));
  /** The header navigation scrolls sideways and has items past its end (601-900px, short screens): the end fades. */
  protected readonly navMore = signal(false);
  /** Re-measures the bottom bar into --nav-h (set up after the first render). */
  private measureBar: () => void = () => undefined;
  /** The map and the pages under it (a house, a new house): Map is the current item there. */
  protected readonly mapSection = computed(() => inMapSection(this.path()));
  protected readonly mapCurrent = computed(() => sectionCurrent(this.path() === '/', this.mapSection()));
  protected readonly dataSection = computed(() => inDataSection(this.path(), this.phone()));
  protected readonly dataCurrent = computed(() => sectionCurrent(this.path().startsWith('/data'), this.dataSection()));
  /**
   * An automatic sync failed (a revoked key, a server that went away) and nothing has worked since. The sync card on
   * Your data says why; the dot tells the user from any screen that there is something to read there. Cleared when
   * a run succeeds (SyncService clears lastError only then, S4b-BL-1), and not shown for a failure the user saw happen.
   */
  protected readonly syncProblem = computed(
    () => this.sync.enabled() && this.sync.lastError() !== null && !this.sync.lastErrorForced(),
  );

  /** Scroll positions of #main by the id of the navigation that showed the page, for Back and Forward. */
  private readonly scrollPositions = new Map<number, number>();

  constructor() {
    this.measureBottomBar();
    this.watchPhoneLayout();
    // After a navigation, a language switch or Ask and Plan appearing: the bottom bar may have gone or come back (a
    // house hides it), and in a header that scrolls sideways the current item is brought into view and the end fade
    // follows what is left to scroll.
    afterRenderEffect(() => {
      this.path();
      this.i18n.lang();
      this.ai.enabled();
      this.barHidden();
      this.measureBar();
      this.revealCurrentNavItem();
      this.updateNavMore();
    });
    // After an in-app navigation: the new page starts at the top (or, for Back and Forward, where the user left it),
    // and focus moves to its heading so screen-reader and keyboard users start at the top of the new content (the
    // first load keeps the browser default).
    //
    // The page scroller is <main>, not the window, so the router's own scroll restoration does not reach it: without
    // this, opening a house from a list scrolled halfway down landed in the middle of the house form, under its
    // sticky toolbar.
    let lastPath: string | null = null;
    let shownBy = 0;
    let restoreFrom: number | null = null;
    inject(Router).events.subscribe((e) => {
      if (e instanceof NavigationStart) {
        // Leaving the page shown by navigation `shownBy`: remember where it was scrolled to.
        this.rememberScroll(shownBy);
        restoreFrom = e.navigationTrigger === 'popstate' && e.restoredState ? e.restoredState.navigationId : null;
        return;
      }
      if (!(e instanceof NavigationEnd)) return;
      shownBy = e.id;
      const path = e.urlAfterRedirects.split(/[?#]/)[0];
      this.path.set(path);
      const changed = lastPath !== null && lastPath !== path;
      lastPath = path;
      if (!changed) return;
      const saved = restoreFrom === null ? undefined : this.scrollPositions.get(restoreFrom);
      const main = document.getElementById('main');
      if (saved === undefined && main) main.scrollTop = 0;
      afterNextRender(
        () => {
          if (saved !== undefined) this.restoreScroll(saved);
          this.focusHeading();
        },
        { injector: this.injector },
      );
    });
  }

  private rememberScroll(navigationId: number): void {
    // By element id, not the view query: the first navigation starts before the shell has rendered.
    const main = document.getElementById('main');
    if (!main) return;
    this.scrollPositions.delete(navigationId);
    this.scrollPositions.set(navigationId, main.scrollTop);
    if (this.scrollPositions.size > SCROLL_MEMORY) {
      const oldest = this.scrollPositions.keys().next().value;
      if (oldest !== undefined) this.scrollPositions.delete(oldest);
    }
  }

  /**
   * Back or Forward to a page the user had scrolled: put it back there. A page that reads its data asynchronously
   * (the house list comes from IndexedDB) is not tall enough on its first render, so this waits — a few frames, at
   * most about a second — until the content can reach the old position, and then stops.
   */
  private restoreScroll(top: number): void {
    const main = this.main().nativeElement;
    let frames = 0;
    const attempt = () => {
      const reachable = main.scrollHeight - main.clientHeight >= top - 1;
      if (reachable || frames >= 60) {
        main.scrollTop = top;
        return;
      }
      frames++;
      requestAnimationFrame(attempt);
    };
    attempt();
  }

  private watchPhoneLayout(): void {
    if (typeof matchMedia === 'undefined') return;
    const query = matchMedia('(max-width: 600px)');
    this.phone.set(query.matches);
    const onChange = () => this.phone.set(query.matches);
    query.addEventListener('change', onChange);
    inject(DestroyRef).onDestroy(() => query.removeEventListener('change', onChange));
  }

  /**
   * Publishes the bottom bar's real height as `--nav-h` on the host, so `.content` reserves exactly that much and
   * scroll-padding keeps focused controls clear of it. Measured, not assumed: the bar grows when a label wraps (200%
   * text size, SC 1.4.4) and includes the home-indicator safe area. On wider screens the nav is in the header and
   * nothing is reserved.
   */
  private measureBottomBar(): void {
    const host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;
    let observer: ResizeObserver | null = null;
    afterNextRender(() => {
      if (typeof ResizeObserver === 'undefined' || typeof matchMedia === 'undefined') return;
      const phone = matchMedia('(max-width: 600px)');
      const update = () => {
        const height = phone.matches ? this.nav().nativeElement.getBoundingClientRect().height : 0;
        host.style.setProperty('--nav-h', `${Math.ceil(height)}px`);
      };
      this.measureBar = update;
      observer = new ResizeObserver(() => {
        update();
        this.updateNavMore();
      });
      observer.observe(this.nav().nativeElement);
      update();
    });
    inject(DestroyRef).onDestroy(() => observer?.disconnect());
  }

  /**
   * In a header whose items scroll sideways, the current item is scrolled into view (clear of the end fade). Only the
   * list itself scrolls, not the page, and nothing happens when everything fits (the bottom bar, a wide header).
   */
  private revealCurrentNavItem(): void {
    const nav = this.nav().nativeElement;
    if (nav.scrollWidth <= nav.clientWidth + 1) return;
    const current = nav.querySelector<HTMLElement>('a.current');
    if (!current) return;
    const box = nav.getBoundingClientRect();
    const item = current.getBoundingClientRect();
    if (item.left < box.left) {
      nav.scrollLeft -= box.left - item.left;
    } else if (item.right > box.right - NAV_FADE_PX) {
      nav.scrollLeft += item.right - (box.right - NAV_FADE_PX);
    }
  }

  /** Whether the header navigation has items past its end, for the end fade (none when everything fits). */
  protected updateNavMore(): void {
    const nav = this.nav().nativeElement;
    this.navMore.set(nav.scrollWidth - nav.clientWidth - nav.scrollLeft > 1);
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
