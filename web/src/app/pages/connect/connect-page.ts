import { Component, DestroyRef, Injector, afterNextRender, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiConfig, ConfigService, initialBaseUrl, normalizeBaseUrl } from '../../core/config.service';
import { HouseApiService } from '../../core/house-api.service';
import { StatsDto } from '../../core/models';
import { errorMsg } from '../../core/format';
import { Announcer } from '../../core/announcer.service';
import { Msg } from '../../i18n/translation.service';
import { ConfirmService } from '../../core/confirm.service';
import { AiService } from '../../core/ai.service';
import { AiSessionState } from '../../core/ai-session.state';
import { TPipe } from '../../i18n/t.pipe';
import { RunResult, runResult } from '../../shared/run-result';
import { focusIfLost } from '../../shared/focus';

@Component({
  selector: 'app-connect-page',
  imports: [FormsModule, TPipe],
  templateUrl: './connect-page.html',
  styleUrl: './connect-page.css',
})
export class ConnectPage {
  private readonly config = inject(ConfigService);
  private readonly api = inject(HouseApiService);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly ai = inject(AiService);
  private readonly aiSession = inject(AiSessionState);
  private readonly injector = inject(Injector);
  /** The check in flight. Editing a field or leaving the page drops it, so its result never lands on other values. */
  private request: Subscription | null = null;

  constructor() {
    // Leaving during "Save and continue" must not save and navigate back to Map from another page.
    inject(DestroyRef).onDestroy(() => this.stopCheck());
  }

  protected baseUrl =
    this.config.config()?.baseUrl ?? initialBaseUrl(typeof location === 'undefined' ? '' : location.hostname);
  protected apiKey = this.config.config()?.apiKey ?? '';
  /** Keep the key after the browser closes. Off by default for a new connection (safer on shared computers). */
  protected remember = this.config.configured() ? this.config.remembered() : false;
  protected readonly showKey = signal(false);
  protected readonly testing = signal(false);
  /**
   * The last check's outcome, success or failure. A new check keeps it in place, drawn as being updated with
   * "Testing…" as its state, until that check ends (Android 1.33's rule for Settings' server result,
   * `RefreshableResultCard`), so the buttons below do not jump up by the card's height and back. Each is keyed on
   * its run ({@link RunResult}), so a result with the same words as the last one is read again.
   */
  protected readonly testResult = signal<RunResult<StatsDto> | null>(null);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  /** The check before saving failed: offer "Save anyway" (a server that is down right now can still be the right one). */
  protected readonly offerSaveAnyway = signal(false);
  /**
   * Whether the field errors may show: after the address field was left, or after a submit. Nothing is marked
   * invalid before the user has had a chance to type.
   */
  protected readonly urlTouched = signal(false);
  protected readonly submitted = signal(false);
  protected readonly configured = this.config.configured;
  protected readonly isHttpsPage = typeof location !== 'undefined' && location.protocol === 'https:';

  protected get mixedContent(): boolean {
    return this.isHttpsPage && this.baseUrl.trim().toLowerCase().startsWith('http:');
  }

  protected get showMixedContent(): boolean {
    return this.mixedContent && (this.urlTouched() || this.submitted());
  }

  protected get urlMissing(): boolean {
    return this.submitted() && !this.baseUrl.trim();
  }

  protected get keyMissing(): boolean {
    return this.submitted() && !this.apiKey.trim();
  }

  protected urlDescribedBy(): string | null {
    if (this.urlMissing) return 'baseUrl-required';
    return this.showMixedContent ? 'mixed-content' : null;
  }

  /**
   * Editing either field makes an earlier "Connected …" or failure untrue, and a check still running was started
   * with the old values: it is dropped, so its "Connected" (and, from "Save and continue", its save) cannot be
   * applied to values nobody tested.
   */
  protected onEdit(): void {
    this.stopCheck();
    this.testResult.set(null);
    this.error.set(null);
    this.offerSaveAnyway.set(false);
  }

  /**
   * Both fields filled and an address the browser will not block; otherwise the reason is shown next to the field
   * and focus goes there. The buttons themselves stay enabled, so pressing one always explains itself.
   */
  private validate(): boolean {
    this.submitted.set(true);
    const target = !this.baseUrl.trim() || this.mixedContent ? 'baseUrl' : !this.apiKey.trim() ? 'apiKey' : null;
    if (target) {
      afterNextRender(() => document.getElementById(target)?.focus(), { injector: this.injector });
      return false;
    }
    return true;
  }

  protected test(): void {
    if (this.testing() || !this.validate()) return;
    this.check(false);
  }

  /** "Save and continue": checks the connection first, and saves only when it works (or on "Save anyway"). */
  protected save(): void {
    if (this.testing() || !this.validate()) return;
    this.check(true);
  }

  private check(thenSave: boolean): void {
    // The earlier result or failure (and its "Save anyway") stays in place while this check runs; its end replaces it.
    // What is saved on success is what was tested, captured here (onEdit() also drops the check, see there).
    const tested: ApiConfig = { baseUrl: normalizeBaseUrl(this.baseUrl), apiKey: this.apiKey.trim() };
    this.testing.set(true);
    this.request = this.api.testConnection(tested.baseUrl, tested.apiKey).subscribe({
      next: (stats) => {
        this.request = null;
        this.testing.set(false);
        this.error.set(null);
        this.offerSaveAnyway.set(false);
        if (thenSave) {
          this.announcer.announce({
            key: 'connect.success',
            params: { houses: stats.houses, visits: stats.visits, streets: stats.streets },
          });
          this.commit(tested);
        } else {
          this.testResult.set(runResult(stats));
          // "Save anyway" (focusable while the check ran) is gone: focus goes to "Save and continue", not <body>.
          afterNextRender(() => focusIfLost('connect-save'), { injector: this.injector });
        }
      },
      error: (err: unknown) => {
        this.request = null;
        this.testing.set(false);
        this.testResult.set(null);
        this.error.set(runResult(errorMsg(err)));
        this.offerSaveAnyway.set(thenSave);
        // After "Test", "Save anyway" is removed; if it had focus, focus goes to "Save and continue".
        afterNextRender(() => focusIfLost('connect-save'), { injector: this.injector });
      },
    });
  }

  private stopCheck(): void {
    if (!this.request) return;
    this.request.unsubscribe();
    this.request = null;
    this.testing.set(false);
  }

  /** "Save anyway" after a failed check. It stays on screen during a new check, but does nothing until that ends. */
  protected saveAnyway(): void {
    if (this.testing() || !this.validate()) return;
    this.announcer.announce({ key: 'connect.saved' });
    this.commit({ baseUrl: this.baseUrl, apiKey: this.apiKey });
  }

  private commit(values: ApiConfig): void {
    this.config.save(values, this.remember);
    this.ai.refresh();
    void this.router.navigate(['/']);
  }

  protected async disconnect(): Promise<void> {
    const ok = await this.confirm.ask({ key: 'confirm.disconnect' }, { confirmKey: 'connect.disconnect', danger: true });
    if (!ok) return;
    this.config.clear();
    this.ai.refresh();
    this.aiSession.clear();
    this.baseUrl = initialBaseUrl(typeof location === 'undefined' ? '' : location.hostname);
    this.apiKey = '';
    this.submitted.set(false);
    this.urlTouched.set(false);
    this.onEdit();
    this.announcer.announce({ key: 'connect.disconnected' });
    // The Disconnect button is gone with the connection: focus goes to the page heading, not to <body>.
    afterNextRender(
      () => {
        const h1 = document.getElementById('connect-title');
        h1?.focus();
      },
      { injector: this.injector },
    );
  }
}
