import { Component, ElementRef, Injector, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Announcer } from '../core/announcer.service';
import { LocalStore } from '../data/local-store.service';
import { StorageService } from '../data/storage.service';
import { SyncService } from '../data/sync.service';
import type { SyncProgress } from '../data/sync.service';
import { PwaService } from '../core/pwa.service';
import { TPipe } from '../i18n/t.pipe';
import type { TKey } from '../i18n/en';

/** Which of the non-error banners is on screen; at most one at a time. */
type Notice = 'migration' | 'update' | 'install' | 'storageRisk' | null;

/** 20px Material glyphs (Apache-2.0) in front of each banner title, so the kinds of notice differ by more than text. */
const BANNER_ICONS = {
  info: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z',
  warning: 'M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z',
  error: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z',
} as const;

const PROGRESS_KEY: Readonly<Record<SyncProgress['phase'], TKey>> = {
  sending: 'data.progressSending',
  houses: 'data.progressHouses',
  visits: 'data.progressVisits',
  photos: 'data.progressPhotos',
};

/**
 * The few things the app must tell the user without interrupting them (S4-01 storage warnings, S4-05 install and
 * update). They are banners under the header, never pop-ups, and each can be answered or dismissed.
 *
 * **At most one non-error banner is shown at a time**, in this order: the one-off migration question, the update,
 * the install offer, the storage-risk advice. On a 640px-tall phone two or three stacked banners took a third of
 * the map; the next one simply appears when the current one is answered. The error banner (the browser is not
 * storing anything) is the exception and can sit on top of one other, because it means data is being lost now.
 *
 * Only the error uses a live region (`role="alert"`): the others are labelled regions, so a screen reader does not
 * read the install offer out every time the app starts. The update is announced once through the Announcer (a
 * `role="status"` element inserted together with its text is not reliably read).
 *
 * One notice style in both themes: the page surface with a 4px accent at the start edge and a leading glyph, so
 * in dark mode a notice does not melt into the header (`--primary-soft` there is almost the header colour).
 */
@Component({
  selector: 'app-banners',
  imports: [TPipe, RouterLink],
  template: `
    @if (store.storageProblem(); as problem) {
      <div class="banner error" role="alert">
        <div class="body">
          <strong class="title">
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path [attr.d]="icons.error" /></svg>
            {{ 'storage.noIdbTitle' | t }}
          </strong>
          <p>{{ (problem === 'unavailable' ? 'storage.noIdbBody' : 'storage.blockedBody') | t }}</p>
        </div>
      </div>
    }

    @switch (notice()) {
      @case ('migration') {
        <div class="banner" role="region" aria-labelledby="migration-title">
          <div class="body">
            <strong id="migration-title" class="title">
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path [attr.d]="icons.info" /></svg>
              {{ 'data.migrationTitle' | t }}
            </strong>
            <p>{{ 'data.migrationBody' | t }}</p>
            @if (sync.running()) {
              <!-- Deliberately not a live region: it changes once per row, which would flood a screen reader. -->
              <p class="progress">
                @if (sync.progress(); as p) {
                  {{ progressKey[p.phase] | t: { done: p.done, total: p.total } }}
                } @else {
                  {{ 'data.migrationRunning' | t }}
                }
              </p>
            }
            @if (sync.lastFailure(); as failure) {
              <!-- Keyed on its run: "Try again" failing the same way again is a new node, so it is read again. While
                   "Try again" runs, the failure keeps its place, its sign dimmed, with the bar along its foot (outside
                   the alert, so it is not announced), until the run ends (S4b-BL-1). -->
              <div class="refresh-slot failure-slot" [class.stale]="sync.running()">
                @for (c of [failure]; track c.run) {
                  <p class="failure" role="alert">{{ 'data.syncFailed' | t: { reason: c.value } }}</p>
                }
                @if (sync.running()) {
                  <div class="refresh-bar" role="progressbar" [attr.aria-label]="'data.migrationRunning' | t"></div>
                }
              </div>
              @if (failure.value.key === 'error.storageFull') {
                <!-- No space left: Your data is where a backup is made and the space use is shown. -->
                <p><a routerLink="/data" [queryParams]="{ export: 'backup' }">{{ 'nav.data' | t }}</a></p>
              }
            }
          </div>
          <div class="actions">
            @if (sync.running() || sync.migration() === 'running') {
              <button #stopBtn type="button" class="btn btn-sm" (click)="stop()">{{ 'data.migrationStop' | t }}</button>
            } @else {
              <button #downloadBtn type="button" class="btn btn-primary btn-sm" (click)="download()">
                {{ (sync.lastError() ? 'common.retry' : 'data.migrationYes') | t }}
              </button>
              <button type="button" class="btn btn-sm" (click)="skip()">{{ 'data.migrationNo' | t }}</button>
            }
          </div>
        </div>
      }
      @case ('update') {
        <div class="banner" role="region" aria-labelledby="update-title">
          <div class="body">
            <strong id="update-title" class="title">
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path [attr.d]="icons.info" /></svg>
              {{ 'pwa.updateTitle' | t }}
            </strong>
            <p>{{ 'pwa.updateBody' | t }}</p>
          </div>
          <div class="actions">
            <button type="button" class="btn btn-primary btn-sm" (click)="reload()">{{ 'pwa.reload' | t }}</button>
            <!-- For this visit only: the next start offers the new version again (it is still waiting). -->
            <button type="button" class="btn btn-sm" (click)="dismissUpdate()">{{ 'pwa.dismiss' | t }}</button>
          </div>
        </div>
      }
      @case ('install') {
        <div class="banner" role="region" aria-labelledby="install-title">
          <div class="body">
            <strong id="install-title" class="title">
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path [attr.d]="icons.info" /></svg>
              {{ 'pwa.installTitle' | t }}
            </strong>
            <p>{{ 'pwa.installBody' | t }}</p>
            @if (iosHelp()) {
              <details>
                <summary>{{ 'pwa.iosHow' | t }}</summary>
                <ol>
                  <li>{{ 'pwa.iosStep1' | t }}</li>
                  <li>{{ 'pwa.iosStep2' | t }}</li>
                  <li>{{ 'pwa.iosStep3' | t }}</li>
                </ol>
              </details>
            }
          </div>
          <div class="actions">
            @if (pwa.canInstall()) {
              <button type="button" class="btn btn-primary btn-sm" (click)="install()">{{ 'pwa.install' | t }}</button>
            }
            <button type="button" class="btn btn-sm" (click)="dismissInstall()">{{ 'pwa.dismiss' | t }}</button>
          </div>
        </div>
      }
      @case ('storageRisk') {
        <div class="banner" role="region" aria-labelledby="risk-title">
          <div class="body">
            <strong id="risk-title" class="title warn">
              <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path [attr.d]="icons.warning" /></svg>
              {{ 'storage.warnTitle' | t }}
            </strong>
            <p>{{ 'storage.warnBody' | t }}</p>
          </div>
          <div class="actions">
            <!-- With intent: Your data opens with the restorable backup ZIP chosen and its button focused (docs/05
                 §14.6 keeps the word "backup" for that file), not with the default web page copy. -->
            <a class="btn btn-sm" routerLink="/data" [queryParams]="{ export: 'backup' }">{{ 'storage.makeBackup' | t }}</a>
            <button type="button" class="btn btn-sm" (click)="dismissRisk()">{{ 'pwa.dismiss' | t }}</button>
          </div>
        </div>
      }
    }
  `,
  styles: `
    /*
     * The banners sit between the header and the page, outside the page's scroller, so a long one could take the
     * page: the iPhone install offer in Tamil was about 290px, all of a 568px iPhone SE but a strip, and every pixel
     * of a phone in landscape or with the keyboard open (the page measured 1px). At most 40% of the visible height;
     * a longer banner scrolls inside itself (its buttons are focusable, so Tab reaches them and scrolls it along).
     * A banner is never that tall on a desktop, where nothing changes.
     */
    :host {
      display: block;
      max-height: 40vh;
      max-height: 40dvh;
      overflow-y: auto;
      overscroll-behavior: contain;
    }
    .banner {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2) var(--space-4);
      padding: var(--space-3) max(var(--space-4), env(safe-area-inset-right)) var(--space-3)
        max(var(--space-4), env(safe-area-inset-left));
      background: var(--surface);
      border-inline-start: 4px solid var(--primary);
      border-bottom: 1px solid var(--border);
      color: var(--text);
    }
    .banner.error {
      border-inline-start-color: var(--error-text);
    }
    .title {
      display: flex;
      align-items: flex-start;
      gap: var(--space-2);
    }
    .title svg {
      flex: 0 0 auto;
      margin-top: 0.1em;
      fill: currentColor;
      color: var(--primary);
    }
    .title.warn svg {
      color: var(--warn-text);
    }
    .banner.error .title svg {
      color: var(--error-text);
    }
    .body {
      min-width: 0;
      flex: 1 1 16rem;
    }
    .banner p {
      margin: var(--space-1) 0 0;
      font-size: var(--text-sm);
    }
    .banner .progress {
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
    .banner .failure {
      color: var(--error-text);
      font-weight: 600;
    }
    .banner .failure::before {
      content: '⚠ ';
    }
    /* Room under the failure line for the "being updated" bar, kept while no run goes so the line does not move. */
    .failure-slot {
      padding-bottom: calc(var(--space-1) + 2px);
    }
    .failure-slot .refresh-bar {
      left: 0;
      right: 0;
    }
    .failure-slot.stale .failure::before {
      opacity: var(--stale-alpha);
    }
    .banner ol {
      margin: var(--space-1) 0 0;
      padding-inline-start: 1.25rem;
      font-size: var(--text-sm);
    }
    .banner summary {
      margin-top: var(--space-1);
      font-size: var(--text-sm);
      cursor: pointer;
      min-height: var(--target-min);
    }
    .actions {
      display: flex;
      gap: var(--space-2);
      flex-wrap: wrap;
    }
    /* Touch screens get full-size targets (the 44px .btn-sm rule is global, in styles.css). */
    @media (pointer: coarse) {
      .banner summary {
        min-height: var(--target);
        display: flex;
        align-items: center;
      }
    }
  `,
})
export class AppBanners {
  protected readonly store = inject(LocalStore);
  protected readonly sync = inject(SyncService);
  protected readonly pwa = inject(PwaService);
  private readonly storage = inject(StorageService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  private readonly downloadBtn = viewChild<ElementRef<HTMLButtonElement>>('downloadBtn');
  private readonly stopBtn = viewChild<ElementRef<HTMLButtonElement>>('stopBtn');

  protected readonly progressKey = PROGRESS_KEY;
  protected readonly icons = BANNER_ICONS;
  protected readonly iosHelp = signal(StorageService.isIos() && !StorageService.isStandalone());
  /** True once this browser holds at least one house: the install offer waits for that (docs/11 §5.10). */
  private readonly hasHouses = signal(false);

  /**
   * The install offer, **once**: only after the first saved house (when the app also asks the browser to keep the
   * data), and "Not now" lasts 30 days (PwaService.dismissInstall). Chromium offers a real prompt; on iOS there is
   * only the Add-to-Home-Screen help. The Your data page always has the same offer, so dismissing loses nothing.
   */
  protected readonly showInstall = computed(
    () => this.hasHouses() && !this.pwa.installDismissed() && (this.pwa.canInstall() || this.iosHelp()),
  );

  /** Only worth saying once the browser has actually refused to keep the data. */
  protected readonly showStorageRisk = computed(
    () => !this.storage.riskDismissed() && this.storage.atRisk() && this.store.storageProblem() === null,
  );

  /** The one non-error banner to show, by priority: migration > update > install > storage risk. */
  protected readonly notice = computed<Notice>(() => {
    const migration = this.sync.migration();
    if (migration === 'offered' || migration === 'running') return 'migration';
    if (this.pwa.updateReady() && !this.pwa.updateDismissed()) return 'update';
    if (this.showInstall()) return 'install';
    if (this.showStorageRisk()) return 'storageRisk';
    return null;
  });

  private counting = false;
  private recount = false;
  private updateAnnounced = false;

  constructor() {
    void this.storage.refresh();
    // Follows the store: a house saved, a sync pull, or "Remove all data". Checks are coalesced so a pull that
    // bumps the revision once per row does not start one full read per row.
    effect(() => {
      this.store.settled();
      void this.countHouses();
    });
    // The update notice is a labelled region, not a live one; say it once, when it first appears.
    effect(() => {
      if (this.notice() !== 'update' || this.updateAnnounced) return;
      this.updateAnnounced = true;
      untracked(() => this.announcer.announce({ key: 'pwa.updateTitle' }));
    });
  }

  private async countHouses(): Promise<void> {
    if (this.counting) {
      this.recount = true;
      return;
    }
    this.counting = true;
    try {
      do {
        this.recount = false;
        this.hasHouses.set((await this.store.liveHouses()).length > 0);
      } while (this.recount);
    } catch {
      this.hasHouses.set(false);
    } finally {
      this.counting = false;
    }
  }

  /**
   * "Download now" / "Try again". On success the banner goes away, so before it does the result is announced and
   * focus moves to the page heading — otherwise it would fall to <body> with the removed button. On failure the
   * reason is shown in the banner itself (`role="alert"`) and focus goes back to the button, now "Try again".
   */
  protected async download(): Promise<void> {
    const done = this.sync.downloadToThisBrowser();
    // The button the user pressed is replaced by "Stop" at once: focus goes there instead of falling to <body>.
    afterNextRender(() => this.stopBtn()?.nativeElement.focus(), { injector: this.injector });
    await done;
    const state = this.sync.migration();
    if (state === 'done') {
      const houses = (await this.store.liveHouses()).length;
      this.announcer.announce({ key: 'data.migrationDone', params: { n: houses } });
      focusMainHeading();
    } else if (state === 'skipped') {
      // Stopped from the banner: say where to carry on.
      this.announcer.announce({ key: 'data.migrationStopped' });
      focusMainHeading();
    } else if (state === 'offered') {
      afterNextRender(() => this.downloadBtn()?.nativeElement.focus(), { injector: this.injector });
    }
  }

  /**
   * "Stop": keeps what has arrived and pauses sync; Your data offers "Download now" to carry on. The banner goes with
   * the button, so focus moves to the page heading now (download() says where to carry on once the run has ended).
   */
  protected stop(): void {
    this.sync.stopDownload();
    focusMainHeading();
  }

  /** "Not now" on the update notice: hidden for this visit, so the notices behind it can show; focus moves on. */
  protected dismissUpdate(): void {
    this.pwa.dismissUpdate();
    focusMainHeading();
  }

  protected async skip(): Promise<void> {
    await this.sync.skipMigration();
    focusMainHeading();
  }

  /**
   * The browser's install dialog. Whatever the answer, the offer is gone afterwards (Chromium hands out one prompt),
   * so the banner unmounts with the focused button: focus goes to the page heading rather than to <body>.
   */
  protected async install(): Promise<void> {
    const accepted = await this.pwa.install();
    if (accepted) this.announcer.announce({ key: 'data.installDone' });
    afterNextRender(() => focusMainHeading(), { injector: this.injector });
  }

  /** "Not now" on the install offer: 30 days (PwaService), and focus moves on with the banner. */
  protected dismissInstall(): void {
    this.pwa.dismissInstall();
    focusMainHeading();
  }

  /** "Not now" on the storage-risk advice: a week (StorageService.dismissRisk), and focus moves on. */
  protected dismissRisk(): void {
    this.storage.dismissRisk();
    focusMainHeading();
  }

  protected reload(): void {
    void this.pwa.applyUpdate();
  }
}

/** Moves focus to the current page's heading (or the main region) when a banner that held it disappears. */
function focusMainHeading(): void {
  if (typeof document === 'undefined') return;
  const main = document.getElementById('main');
  if (!main) return;
  const heading = main.querySelector<HTMLElement>('h1');
  const target = heading ?? main;
  if (heading) heading.setAttribute('tabindex', '-1');
  target.focus({ preventScroll: true });
}
