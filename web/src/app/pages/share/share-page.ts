import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ConfirmService } from '../../core/confirm.service';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TPipe } from '../../i18n/t.pipe';
import { Announcer } from '../../core/announcer.service';
import { SHARE_TEXT_KEY } from './share-text';
import { RunResult, runResult } from '../../shared/run-result';

export { SHARE_TEXT_KEY };

/**
 * The PWA share target (S4-05, manifest `share_target`): when the user shares a listing from a browser or another
 * app on Android, the system opens `/share?title=…&text=…&url=…` here.
 *
 * Sprint 4a ends at the paste box: the shared text is put in a textarea the user can read, correct and copy, and
 * "Add a house from this" takes it to the map to place the pin, then to the new-house form. **Parsing it into fields
 * (the no-AI parser, duplicate detection, "Where is it?") is S4-13 in Sprint 4b** — this page is deliberately the
 * handler it will build on, so the manifest entry and the route can ship now and the parser drops in behind them.
 *
 * The text is kept in sessionStorage until it is used or the page is left, so a reload or a phone discarding the
 * tab does not lose it; leaving after editing it asks first (the route's canDeactivate).
 */
@Component({
  selector: 'app-share-page',
  imports: [FormsModule, TPipe, RouterLink],
  template: `
    <!-- Same structure as Your data (and Android's settings subscreens): title and intro above, one card below. -->
    <div class="page">
      <header class="page-head">
        <h1>{{ 'share.title' | t }}</h1>
        <!-- Opened with nothing shared (a direct visit, or an app that sent no text): say so, and invite a paste. -->
        <p class="muted">{{ (arrivedEmpty ? 'share.emptyIntro' : 'share.intro') | t }}</p>
      </header>

      <div class="card">
        <div class="field">
          <label for="shared">{{ 'share.text' | t }}</label>
          <textarea
            id="shared"
            name="shared"
            rows="10"
            [(ngModel)]="text"
            (ngModelChange)="onEdit()"
            spellcheck="false"
            [placeholder]="'share.placeholder' | t"
            aria-describedby="shared-hint"
          ></textarea>
          <p id="shared-hint" class="field-hint">{{ 'share.hint' | t }}</p>
          @if (copyFailed(); as card) {
            @for (c of [card]; track c.run) {
              <p class="error" role="alert">{{ 'share.copyFailed' | t }}</p>
            }
          }
        </div>

        <div class="actions">
          <!-- aria-disabled, not disabled, as everywhere else in the app: with the box empty the buttons stay in the
               tab order (and say they are unavailable) instead of disappearing from it. -->
          <button
            type="button"
            class="btn btn-primary"
            [attr.aria-disabled]="!text.trim() ? 'true' : null"
            (click)="createHouse()"
          >
            {{ 'share.create' | t }}
          </button>
          <button type="button" class="btn" [attr.aria-disabled]="!text.trim() ? 'true' : null" (click)="copy()">
            {{ (copied() ? 'share.copied' : 'share.copy') | t }}
          </button>
          <a class="btn" routerLink="/">{{ 'house.backToMap' | t }}</a>
        </div>
      </div>
    </div>
  `,
  styles: `
    .page {
      max-width: var(--content-narrow);
      margin: 0 auto;
      padding: var(--space-5) var(--space-4) var(--space-7);
      display: flex;
      flex-direction: column;
      gap: var(--space-4);
    }
    /* The page's own gap separates the heading from the card (styles.css gives other pages a margin). */
    .page-head {
      margin-bottom: 0;
    }
    .card > .field:first-child {
      margin-top: 0;
    }
    .actions {
      display: flex;
      gap: var(--space-2);
      flex-wrap: wrap;
      margin-top: var(--space-3);
    }
    @media (max-width: 480px) {
      .page {
        padding: var(--space-4) var(--space-3) var(--space-6);
      }
    }
  `,
})
export class SharePage implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);

  protected text = '';
  /** The text as it arrived (or was restored): leaving after changing it asks first. */
  private original = '';
  /** Set when the text is being taken on ("Add a house from this"), so leaving does not ask. */
  private handedOn = false;
  /** True when the page opened with nothing shared, so the intro invites a paste instead of describing one. */
  protected arrivedEmpty = false;
  protected readonly copied = signal(false);
  /** The clipboard refused; keyed on its run, so Copy failing twice in a row is read twice (web UX gate R9). */
  protected readonly copyFailed = signal<RunResult<true> | null>(null);
  private copiedTimer: ReturnType<typeof setTimeout> | undefined;

  ngOnInit(): void {
    const q = this.route.snapshot.queryParamMap;
    // Android sends title, text and url separately; most apps fill only one or two of them.
    this.text = [q.get('title'), q.get('text'), q.get('url')]
      .map((part) => (part ?? '').trim())
      .filter((part) => part !== '')
      .join('\n');
    if (this.text === '') {
      // Nothing in the URL: a reload, or a tab the phone discarded, after the text was put here.
      this.text = readShareText();
    }
    this.original = this.text;
    this.arrivedEmpty = this.text === '';
    writeShareText(this.text);
    // The text is in the textarea now; take it out of the address bar and out of history too. A shared listing
    // often carries an owner's or broker's phone number, and `/share?text=…` would otherwise stay in the history
    // of what may be a shared computer — the same reason `createHouse()` hands the text on in navigation state.
    // `replaceUrl` overwrites the entry the system opened rather than adding a clean one after it; the router
    // reuses this component for a query-only change, so the text stays on screen.
    if (q.keys.length > 0) {
      void this.router.navigate([], { relativeTo: this.route, queryParams: {}, replaceUrl: true });
    }
  }

  ngOnDestroy(): void {
    clearTimeout(this.copiedTimer);
    // Used or knowingly left: the text is not kept any longer (it often holds a phone number).
    writeShareText('');
  }

  /** Route guard: leaving with text the user edited here asks first; Back from the map is not blocked otherwise. */
  canLeave(): boolean | Promise<boolean> {
    if (this.handedOn || this.text.trim() === '' || this.text === this.original) return true;
    return this.confirm.ask({ key: 'confirm.leaveShared' }, { confirmKey: 'confirm.leaveAnyway', danger: true });
  }

  /**
   * Takes the text to the map in "add a house" mode, so the user places the pin **before** the form opens.
   *
   * A shared listing has no coordinates until the S4-13 parser, and the form used to open at latitude 0,
   * longitude 0 (the Gulf of Guinea), where the house was then saved. MapPage reads `pickLocation` from the
   * navigation state, turns add mode on with a "tap where this house is" hint, and hands the text on to
   * `/houses/new` together with the position the user chose.
   *
   * The text travels in the navigation **state**, never in the URL: a shared listing routinely carries the owner's
   * or the broker's phone number, and up to 8 000 characters of it in the address bar and in browser history is
   * the shared-computer case docs/11 §5.10 is about. `replaceUrl`, so Back from the map or the form does not land
   * on this page again with its text already gone.
   */
  protected createHouse(): void {
    const shared = this.text.trim();
    if (!shared) {
      // Pressed with the box empty: the box, with its hint, is what to fill in first.
      focusBox();
      return;
    }
    this.handedOn = true;
    void this.router.navigate(['/'], { state: { shared, pickLocation: true }, replaceUrl: true });
  }

  /** An edit makes an earlier "Copied" untrue. */
  protected onEdit(): void {
    this.copied.set(false);
    this.copyFailed.set(null);
    writeShareText(this.text);
  }

  protected async copy(): Promise<void> {
    // The button is only aria-disabled with the box empty: this is the guard behind it.
    if (!this.text.trim()) {
      focusBox();
      return;
    }
    clearTimeout(this.copiedTimer);
    try {
      await navigator.clipboard.writeText(this.text);
      this.copyFailed.set(null);
      this.copied.set(true);
      // The label change alone is not reliably announced, so say it too.
      this.announcer.announce({ key: 'share.copied' });
      this.copiedTimer = setTimeout(() => this.copied.set(false), 3000);
    } catch {
      this.copied.set(false);
      this.copyFailed.set(runResult<true>(true));
    }
  }
}

function focusBox(): void {
  if (typeof document !== 'undefined') document.getElementById('shared')?.focus();
}

function readShareText(): string {
  try {
    return typeof sessionStorage === 'undefined' ? '' : (sessionStorage.getItem(SHARE_TEXT_KEY) ?? '');
  } catch {
    return '';
  }
}

/** Keeps the text for this tab only (sessionStorage); an empty text removes it. */
function writeShareText(text: string): void {
  try {
    if (text.trim() === '') sessionStorage.removeItem(SHARE_TEXT_KEY);
    else sessionStorage.setItem(SHARE_TEXT_KEY, text);
  } catch {
    // Storage blocked: the text lives only on the screen, as before.
  }
}
