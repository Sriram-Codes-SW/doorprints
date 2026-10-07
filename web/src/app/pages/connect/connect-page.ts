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

import { Component, DestroyRef, Injector, afterNextRender, computed, inject, signal } from '@angular/core';
import { Location } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { Subscription, switchMap, timer } from 'rxjs';
import { ApiConfig, ConfigService, initialBaseUrl, normalizeBaseUrl } from '../../core/config.service';
import { HouseApiService } from '../../core/house-api.service';
import { StatsDto } from '../../core/models';
import { errorMsg } from '../../core/format';
import { Announcer } from '../../core/announcer.service';
import { Msg } from '../../i18n/translation.service';
import type { TKey } from '../../i18n/en';
import { ConfirmService } from '../../core/confirm.service';
import { AiService, aiErrorMsg, aiOffMsg } from '../../core/ai.service';
import {
  AI_PRESETS, ANTHROPIC_BASE_URL, type AiPreset, type AiProviderConfig, type BaseUrlReason, GEMINI_HOST, isLocalHost, validateWebBaseUrl,
} from '../../core/ai/ai-provider-config';
import {
  ConnectLink,
  PairingPolled,
  PairingService,
  PairingStarted,
  browserDeviceName,
  parseConnectLink,
} from '../../core/pairing.service';
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
  protected readonly ai = inject(AiService);
  private readonly aiSession = inject(AiSessionState);
  private readonly pairingApi = inject(PairingService);
  private readonly injector = inject(Injector);
  /** The check in flight. Editing a field or leaving the page drops it, so its result never lands on other values. */
  private request: Subscription | null = null;
  /** The code request or poll in flight (docs/03 §12.1). Cancel, an edit of the address or leaving the page drops it. */
  private pairRequest: Subscription | null = null;

  constructor() {
    // Leaving during "Save and continue" must not save and navigate back to Map from another page.
    inject(DestroyRef).onDestroy(() => {
      this.stopCheck();
      this.stopPairing();
    });
    // A connect link from the owner page's QR code or link: read it, then take it out of the address bar at once
    // (it is a one-time key to the server), and ask before using it, since anyone can send such a link.
    const params = inject(ActivatedRoute).snapshot.queryParamMap;
    if (params.has('invite') || params.has('server')) {
      this.invite.set(parseConnectLink(params.get('server'), params.get('invite')));
      this.inviteInvalid.set(this.invite() === null);
      inject(Location).replaceState('/connect');
      afterNextRender(() => document.getElementById('invite-heading')?.focus(), { injector: this.injector });
    }
  }

  /** This browser's name on the owner page. */
  protected readonly deviceName = browserDeviceName(typeof navigator === 'undefined' ? '' : navigator.userAgent);
  /** The connect link this page was opened with, waiting for "Connect". */
  protected readonly invite = signal<ConnectLink | null>(null);
  /** The page was opened with a connect link that is broken or not an https:// server. */
  protected readonly inviteInvalid = signal(false);
  protected readonly inviteBusy = signal(false);
  protected readonly inviteError = signal<RunResult<Msg> | null>(null);
  /** The code on screen while this browser waits for the owner to type it. */
  protected readonly pairing = signal<{ code: string; baseUrl: string; minutes: number } | null>(null);
  protected readonly pairBusy = signal(false);
  protected readonly pairError = signal<RunResult<Msg> | null>(null);
  /** "Use an API key instead" is open: the older way, a key typed in. Open when this browser already uses such a key. */
  protected readonly keyMode = signal(ConnectPage.usesTypedKey(this.config.config()?.apiKey));
  protected readonly aiOffMsg = aiOffMsg;

  private static usesTypedKey(key: string | undefined): boolean {
    return !!key && !key.startsWith('dpk_');
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
    this.stopPairing();
    this.pairing.set(null);
    this.pairError.set(null);
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

  /** Enter in the form: the key way when it is open or a key was typed, else "Get a code". */
  protected submit(): void {
    if (this.keyMode() || this.apiKey.trim()) this.save();
    else this.getCode();
  }

  /** The address alone, for the code: filled, and one the browser will not block. */
  private validateUrl(): boolean {
    this.urlTouched.set(true);
    if (!this.baseUrl.trim() || this.mixedContent) {
      this.submitted.set(true);
      afterNextRender(() => document.getElementById('baseUrl')?.focus(), { injector: this.injector });
      return false;
    }
    return true;
  }

  /** "Get a code": asks the server for a code, shows it, and waits for the owner to type it on the owner page. */
  protected getCode(): void {
    if (this.pairBusy() || !this.validateUrl()) return;
    const baseUrl = normalizeBaseUrl(this.baseUrl);
    this.stopPairing();
    this.pairError.set(null);
    this.pairBusy.set(true);
    this.pairRequest = this.pairingApi.start(baseUrl, this.deviceName).subscribe({
      next: (started) => {
        this.pairRequest = null;
        this.pairBusy.set(false);
        this.pairing.set({ code: started.userCode, baseUrl, minutes: Math.max(1, Math.round(started.expiresIn / 60)) });
        this.announcer.announce({ key: 'connect.codeReady', params: { code: spellCode(started.userCode) } });
        this.poll(baseUrl, started, Date.now() + started.expiresIn * 1000, 0);
      },
      error: (err: unknown) => {
        this.pairRequest = null;
        this.pairBusy.set(false);
        this.pairError.set(runResult(pairingErrorMsg(err)));
      },
    });
  }

  /** One poll after the server's interval; a few network failures in a row are tolerated, the code's expiry is not. */
  private poll(baseUrl: string, started: PairingStarted, deadline: number, failures: number): void {
    this.pairRequest = timer(Math.max(1, started.interval) * 1000)
      .pipe(switchMap(() => this.pairingApi.poll(baseUrl, started.pollToken)))
      .subscribe({
        next: (polled) => {
          this.pairRequest = null;
          this.onPolled(baseUrl, started, deadline, polled);
        },
        error: (err: unknown) => {
          this.pairRequest = null;
          if (failures + 1 < 3 && Date.now() < deadline) {
            this.poll(baseUrl, started, deadline, failures + 1);
          } else {
            this.endPairing(pairingErrorMsg(err));
          }
        },
      });
  }

  private onPolled(baseUrl: string, started: PairingStarted, deadline: number, polled: PairingPolled): void {
    if (polled.status === 'approved' && polled.deviceKey) {
      this.pairing.set(null);
      this.announcer.announce({ key: 'connect.paired' });
      this.commit({ baseUrl, apiKey: polled.deviceKey });
    } else if (polled.status === 'denied') {
      this.endPairing({ key: 'connect.codeDenied' });
    } else if (polled.status === 'expired' || Date.now() >= deadline) {
      this.endPairing({ key: 'connect.codeExpired' });
    } else {
      this.poll(baseUrl, started, deadline, 0);
    }
  }

  private endPairing(reason: Msg): void {
    this.pairing.set(null);
    this.pairError.set(runResult(reason));
    afterNextRender(() => focusIfLost('connect-get-code'), { injector: this.injector });
  }

  /** "Cancel" under the code: stops waiting. The code expires on the server by itself. */
  protected cancelCode(): void {
    this.stopPairing();
    this.pairing.set(null);
    this.pairError.set(null);
    afterNextRender(() => document.getElementById('connect-get-code')?.focus(), { injector: this.injector });
  }

  private stopPairing(): void {
    this.pairRequest?.unsubscribe();
    this.pairRequest = null;
    this.pairBusy.set(false);
  }

  /** "Connect" for a connect link: redeems the invite with this browser's name and saves the key it gets. */
  protected connectWithInvite(): void {
    const link = this.invite();
    if (!link || this.inviteBusy()) return;
    this.inviteBusy.set(true);
    this.inviteError.set(null);
    this.pairRequest = this.pairingApi.redeem(link.server, link.invite, this.deviceName).subscribe({
      next: ({ deviceKey }) => {
        this.pairRequest = null;
        this.inviteBusy.set(false);
        this.invite.set(null);
        this.announcer.announce({ key: 'connect.paired' });
        this.commit({ baseUrl: link.server, apiKey: deviceKey });
      },
      error: (err: unknown) => {
        this.pairRequest = null;
        this.inviteBusy.set(false);
        const used = err instanceof HttpErrorResponse && err.status === 410;
        this.inviteError.set(runResult(used ? { key: 'connect.inviteUsed' } : pairingErrorMsg(err)));
      },
    });
  }

  /** "Not now" for a connect link: nothing is sent; the invite expires on the server by itself. */
  protected dismissInvite(): void {
    this.stopPairing();
    this.invite.set(null);
    this.inviteInvalid.set(false);
    this.inviteError.set(null);
    this.inviteBusy.set(false);
    afterNextRender(() => document.getElementById('connect-title')?.focus(), { injector: this.injector });
  }

  /** Own AI chosen, or no server to choose (then the own AI is the only way). */
  protected readonly ownKey = computed(() => this.ai.provider() === 'device' || !this.config.configured());
  protected geminiKey = '';
  protected rememberGemini = this.config.configured() ? this.config.remembered() : false;
  protected readonly showGeminiKey = signal(false);
  /** Busy and result of *Save* and *Test*, for Gemini and for the OpenAI-compatible services alike. */
  protected readonly geminiBusy = signal(false);
  protected readonly geminiResult = signal<RunResult<{ ok: boolean; msg?: Msg }> | null>(null);

  // The AI service of the person's choice (docs/03 §13.2, ADR-35): Gemini keeps its own fields; every other service
  // shares the base URL, model and key fields below.
  protected readonly services: readonly ServiceId[] = [
    'gemini', ...AI_PRESETS.map((p) => p.id).filter((id) => id !== 'custom'), 'anthropic', 'custom',
  ];
  protected readonly serviceLabel = SERVICE_LABEL;
  protected readonly service = signal<ServiceId>(serviceOf(this.ai.aiConfig()));
  protected readonly aiBaseUrl = signal(this.ai.aiConfig().kind !== 'gemini' ? this.ai.aiConfig().baseUrl : '');
  protected readonly aiModel = signal(this.ai.aiConfig().kind !== 'gemini' ? this.ai.aiConfig().model : '');
  protected aiKey = '';
  protected readonly showAiKey = signal(false);
  protected readonly urlReason = signal<BaseUrlReason | null>(null);
  protected readonly modelMissing = signal(false);
  protected readonly aiKeyMissing = signal(false);
  protected readonly isGemini = computed(() => this.service() === 'gemini');
  /** The kind of adapter that answers for the chosen service. */
  private readonly kind = computed<'openai-compatible' | 'anthropic'>(() => (this.service() === 'anthropic' ? 'anthropic' : 'openai-compatible'));
  protected readonly preset = computed<AiPreset | undefined>(() => AI_PRESETS.find((p) => p.id === this.service()));
  protected readonly urlEditable = computed(() => this.service() === 'custom');
  private readonly urlCheck = computed(() => validateWebBaseUrl(this.aiBaseUrl()));
  /** The host the text and key would go to with what is on the screen. */
  protected readonly shownHost = computed(() => {
    if (this.isGemini()) return GEMINI_HOST;
    const check = this.urlCheck();
    return check.valid ? check.host : '';
  });
  protected readonly isLocal = computed(() => isLocalHost(this.shownHost()));
  /** A local service usually needs no key. */
  protected readonly keyOptional = computed(() => (this.preset()?.keyOptional ?? false) || this.isLocal());
  protected readonly modelExample = computed(() => MODEL_EXAMPLE[this.service()] ?? '');
  protected readonly getKeyUrl = computed(() => KEY_PAGE[this.service()] ?? '');
  /** The saved settings (and key) are the ones on the screen: a saved key is never sent to another service. */
  protected readonly savedHere = computed(() => {
    const saved = this.ai.aiConfig();
    if (this.isGemini()) return saved.kind === 'gemini' && this.ai.hasGeminiKey();
    const check = this.urlCheck();
    const savedCheck = validateWebBaseUrl(saved.baseUrl);
    return saved.kind === this.kind() && check.valid && savedCheck.valid && savedCheck.normalised === check.normalised;
  });
  protected readonly keySavedHere = computed(() => this.savedHere() && this.ai.hasGeminiKey());
  protected readonly urlErrorKey = computed<TKey | null>(() => {
    const reason = this.urlReason();
    return reason === null ? null : URL_REASON[reason];
  });

  /** The service select: prefills the base URL from the preset and clears what belonged to the last one. */
  protected chooseService(event: Event): void {
    const id = (event.target as HTMLSelectElement).value as ServiceId;
    const saved = this.ai.aiConfig();
    const url = presetUrl(id);
    const keep = saved.kind !== 'gemini' && serviceOf(saved) === id;
    this.service.set(id);
    this.aiBaseUrl.set(keep ? saved.baseUrl : url);
    this.aiModel.set(keep ? saved.model : '');
    this.aiKey = '';
    this.geminiKey = '';
    this.clearAiErrors();
    this.geminiResult.set(null);
  }

  private clearAiErrors(): void {
    this.urlReason.set(null);
    this.modelMissing.set(false);
    this.aiKeyMissing.set(false);
  }

  protected typedUrl(value: string): void {
    this.aiBaseUrl.set(value);
    this.urlReason.set(null);
    this.geminiResult.set(null);
  }

  protected typedModel(value: string): void {
    this.aiModel.set(value);
    this.modelMissing.set(false);
    this.geminiResult.set(null);
  }

  protected typedKey(): void {
    this.aiKeyMissing.set(false);
    this.geminiResult.set(null);
  }

  /** What the form says, or null (each missing piece is then shown as a field error, and the first one is focused). */
  private readCompat(): AiProviderConfig | null {
    const check = this.urlCheck();
    const model = this.aiModel().trim();
    const keyMissing = this.aiKey.trim() === '' && !this.keyOptional() && !this.keySavedHere();
    this.urlReason.set(check.valid ? null : check.reason);
    this.modelMissing.set(model === '');
    this.aiKeyMissing.set(keyMissing);
    const first = !check.valid ? 'ai-base-url' : model === '' ? 'ai-model' : keyMissing ? 'ai-key' : null;
    if (first !== null) {
      afterNextRender(() => document.getElementById(first)?.focus(), { injector: this.injector });
      return null;
    }
    return { kind: this.kind(), baseUrl: this.aiBaseUrl(), model };
  }

  /** *Save* for an OpenAI-compatible service: the three settings and, if typed, the key; a saved key never follows a changed service. */
  protected saveAi(): void {
    if (this.geminiBusy()) return;
    const config = this.readCompat();
    if (config === null) return;
    const typed = this.aiKey.trim();
    const keep = this.keySavedHere();
    this.ai.setAiConfig(config);
    if (typed !== '' || !keep) this.ai.saveGeminiKey(typed, this.rememberGemini);
    else this.ai.setProvider('device');
    this.aiKey = '';
    this.geminiResult.set(runResult({ ok: true, msg: { key: 'connect.aiSaved' } }));
  }

  /** *Test* for an OpenAI-compatible service: one tiny request with what is typed (or the saved key), nothing saved. */
  protected async testAi(): Promise<void> {
    if (this.geminiBusy()) return;
    const config = this.readCompat();
    if (config === null) return;
    const host = this.shownHost();
    const typed = this.aiKey.trim();
    const key = typed !== '' ? typed : this.keySavedHere() ? this.ai.geminiKeyForTest() : '';
    this.geminiBusy.set(true);
    try {
      await this.ai.testProvider(config, key);
      this.geminiResult.set(runResult({ ok: true, msg: { key: key === '' ? 'connect.aiTestOkLocal' : 'connect.aiTestOk', params: { host } } }));
    } catch (e) {
      this.geminiResult.set(runResult({ ok: false, msg: aiErrorMsg(e, host) }));
    } finally {
      this.geminiBusy.set(false);
    }
  }

  /** *Remove key*: the key and the three settings go; AI goes back to the server, if one is connected. */
  protected removeAi(): void {
    this.ai.removeGeminiKey();
    this.aiBaseUrl.set(presetUrl(this.service()));
    this.aiModel.set('');
    this.aiKey = '';
    this.geminiKey = '';
    this.clearAiErrors();
    this.geminiResult.set(null);
    this.announcer.announce({ key: this.isGemini() ? 'connect.geminiRemoved' : 'connect.aiKeyRemoved' });
    afterNextRender(() => document.getElementById(this.isGemini() ? 'gemini-key' : 'ai-key')?.focus(), { injector: this.injector });
  }

  /** *Save key*: asks Google first, so a mistyped key is not saved; then this browser answers AI with it. */
  protected async saveGeminiKey(): Promise<void> {
    const key = this.geminiKey.trim();
    if (this.geminiBusy()) return;
    if (!key) {
      this.geminiResult.set(runResult({ ok: false, msg: { key: 'connect.geminiKeyRequired' } }));
      afterNextRender(() => document.getElementById('gemini-key')?.focus(), { injector: this.injector });
      return;
    }
    this.geminiBusy.set(true);
    try {
      await this.ai.testGeminiKey(key);
      this.ai.setAiConfig({ kind: 'gemini', baseUrl: '', model: '' });
      this.ai.saveGeminiKey(key, this.rememberGemini);
      this.geminiKey = '';
      this.geminiResult.set(runResult({ ok: true, msg: { key: 'connect.geminiOk' } }));
    } catch (e) {
      this.geminiResult.set(runResult({ ok: false, msg: aiErrorMsg(e, '') }));
    } finally {
      this.geminiBusy.set(false);
    }
  }

  protected async testSavedGeminiKey(): Promise<void> {
    if (this.geminiBusy()) return;
    this.geminiBusy.set(true);
    try {
      await this.ai.testGeminiKey(this.ai.geminiKeyForTest());
      this.geminiResult.set(runResult({ ok: true, msg: { key: 'connect.geminiOk' } }));
    } catch (e) {
      this.geminiResult.set(runResult({ ok: false, msg: aiErrorMsg(e, '') }));
    } finally {
      this.geminiBusy.set(false);
    }
  }

  protected setAiFeatures(event: Event): void {
    const on = (event.target as HTMLInputElement).checked;
    this.ai.setOptIn(on);
    this.announcer.announce({ key: on ? 'connect.aiTurnedOn' : 'connect.aiTurnedOff' });
  }

  protected onKeyModeToggle(event: Event): void {
    this.keyMode.set((event.target as HTMLDetailsElement).open);
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
    this.keyMode.set(false);
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

/** The code letter by letter for a screen reader ("K 7 M Q, 4 X R D"), so it is not read as a word. */
export function spellCode(code: string): string {
  return code
    .split('-')
    .map((part) => part.split('').join(' '))
    .join(', ');
}

/** Pairing failures: an older server without pairing (404), too many tries (429), else the usual reasons. */
export function pairingErrorMsg(err: unknown): Msg {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 404 || err.status === 405) return { key: 'connect.noPairing' };
    if (err.status === 429) {
      const seconds = Number.parseInt(err.headers.get('Retry-After') ?? '', 10);
      return { key: 'ai.rateLimited', params: { s: Number.isFinite(seconds) ? seconds : 60 } };
    }
  }
  return { key: 'connect.failed', params: { reason: errorMsg(err) } };
}

type ServiceId = 'gemini' | 'anthropic' | AiPreset['id'];

const SERVICE_LABEL: Readonly<Record<ServiceId, TKey>> = {
  gemini: 'connect.aiService.gemini',
  openai: 'connect.aiService.openai',
  openrouter: 'connect.aiService.openrouter',
  groq: 'connect.aiService.groq',
  ollama: 'connect.aiService.ollama',
  lmstudio: 'connect.aiService.lmstudio',
  anthropic: 'connect.aiService.anthropic',
  custom: 'connect.aiService.custom',
};

const URL_REASON: Readonly<Record<BaseUrlReason, TKey>> = {
  empty: 'connect.aiBaseUrlInvalid.empty',
  notAnUrl: 'connect.aiBaseUrlInvalid.notAnUrl',
  scheme: 'connect.aiBaseUrlInvalid.scheme',
  userinfo: 'connect.aiBaseUrlInvalid.userinfo',
  query: 'connect.aiBaseUrlInvalid.query',
  fragment: 'connect.aiBaseUrlInvalid.fragment',
  endpoint: 'connect.aiBaseUrlInvalid.endpoint',
  insecureHost: 'connect.aiBaseUrlInvalid.insecureHost',
};

/** Example model names, shown as placeholders only: the person always types the model (docs/03 §13.2). */
const MODEL_EXAMPLE: Partial<Record<ServiceId, string>> = {
  openai: 'gpt-4o-mini',
  openrouter: 'openai/gpt-4o-mini',
  groq: 'llama-3.3-70b-versatile',
  ollama: 'llama3.1',
  lmstudio: 'qwen2.5-7b-instruct',
};

/** Where each hosted service hands out keys. */
const KEY_PAGE: Partial<Record<ServiceId, string>> = {
  openai: 'https://platform.openai.com/api-keys',
  openrouter: 'https://openrouter.ai/keys',
  groq: 'https://console.groq.com/keys',
  anthropic: 'https://console.anthropic.com/settings/keys',
};

/** The base URL a service prefills: its preset's, Anthropic's own, or none (Gemini, Custom). */
function presetUrl(id: ServiceId): string {
  return id === 'anthropic' ? ANTHROPIC_BASE_URL : (AI_PRESETS.find((p) => p.id === id)?.baseUrl ?? '');
}

/** The select's choice for the saved settings: Gemini, Anthropic, the preset whose base URL was saved, or Custom. */
function serviceOf(config: AiProviderConfig): ServiceId {
  if (config.kind === 'gemini') return 'gemini';
  if (config.kind === 'anthropic') return 'anthropic';
  const check = validateWebBaseUrl(config.baseUrl);
  return AI_PRESETS.find((p) => p.id !== 'custom' && check.valid && p.baseUrl === check.normalised)?.id ?? 'custom';
}
