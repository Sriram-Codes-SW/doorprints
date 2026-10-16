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

/**
 * The *Trace my path* card on the Map page (docs/11 5.27.1, 5.27.5, 5.27.8, docs/03 section 6.2b row 8, S4b-FR-15, S4b-FR-17):
 * the switch, *Start a walk* and *Finish walk* with what is recording, the alert and the screen lock, *How repeated paths
 * look*, *Clear the path*, and the alert banner. A `<details>` above the list: open on wide screens, one line on phones.
 *
 * The recorder service, not this card, owns the watch, the screen lock and the beep, so a walk goes on when the Map is left
 * and the card finds it again. Nothing here asks for the location except the click of *Start a walk*.
 */

import { Component, Injector, afterNextRender, effect, inject, signal, untracked } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { AlertSound } from '../../core/alert-sound.service';
import { ConfirmService } from '../../core/confirm.service';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import type { TraceProblem } from '../../core/trace-recorder.service';
import { REPEAT_LOOKS } from '../../data/trace-store';
import type { RepeatLook } from '../../data/trace-store';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { TraceView } from './trace-view';

/** How long the *You have walked this way before* banner stays (docs/11 5.27.5). */
export const BANNER_MS = 10_000;
/** A screen at least this wide shows the card open. */
const WIDE_QUERY = '(min-width: 761px)';

const PROBLEM_KEY: Record<TraceProblem, TKey> = {
  unsupported: 'trace.web.unavailable',
  denied: 'trace.web.denied',
  unavailable: 'trace.web.noFix',
  full: 'trace.web.full',
  storage: 'trace.web.storage',
};

const LOOK_KEYS: Record<RepeatLook, { label: TKey; desc: TKey }> = {
  CLEAR: { label: 'trace.repeatLook.clear', desc: 'trace.repeatLook.clearDesc' },
  SUBTLE: { label: 'trace.repeatLook.subtle', desc: 'trace.repeatLook.subtleDesc' },
  OFF: { label: 'trace.repeatLook.off', desc: 'trace.repeatLook.offDesc' },
};

/**
 * The *Trace my path* card: switch, start and finish a walk, the retrace alert and screen lock settings, and the alert
 * banner. The recorder service owns the walk, so it goes on when the page is left.
 */
@Component({
  selector: 'app-trace-card',
  imports: [TPipe],
  host: { class: 'trace-card' },
  template: `
    <!-- Present before a banner arrives, so it is announced. -->
    <div role="alert" class="banner-slot">
      @if (bannerOpen()) {
        <div class="banner" id="trace-banner">
          <p>
            <span class="icon" aria-hidden="true">⚑</span>
            {{ 'trace.alert.banner' | t }}
          </p>
          @if (!sound.running()) {
            <p class="small">{{ 'trace.alert.soundOff' | t }}</p>
          }
          <button type="button" class="btn btn-sm" id="trace-banner-dismiss" (click)="dismissBanner()">{{ 'trace.alert.dismiss' | t }}</button>
        </div>
      }
    </div>

    <details class="trace" [open]="open()" (toggle)="onToggle($event)">
      <summary>
        <span class="icon" aria-hidden="true">⟿</span>
        {{ 'trace.card.title' | t }}
        @if (recorder.state() !== 'idle') {
          <span class="rec" id="trace-rec">{{ (recorder.state() === 'paused' ? 'trace.walk.pausedState' : 'trace.walk.recording') | t }}</span>
        }
      </summary>

      <div class="body">
        <label class="switch" for="trace-on">
          <input id="trace-on" type="checkbox" role="switch" [checked]="view.traceOn()" aria-describedby="trace-on-hint" (change)="toggleOn($event)" />
          {{ 'trace.card.switch' | t }}
        </label>
        <p id="trace-on-hint" class="muted small">{{ 'trace.card.switchHint' | t }}</p>

        @if (view.traceOn()) {
          @if (recorder.state() === 'idle') {
            <p id="trace-explain" class="small">{{ 'trace.web.permissionExplain' | t }}</p>
            <button type="button" class="btn btn-primary" id="trace-start" aria-describedby="trace-explain trace-only-open" (click)="start()">
              <span class="icon" aria-hidden="true">▶</span> {{ 'trace.walk.start' | t }}
            </button>
          } @else {
            <p class="small">
              {{ (recorder.state() === 'paused' ? 'trace.walk.pausedState' : 'trace.walk.recording') | t }}
              · {{ 'trace.card.points' | t: { n: i18n.number(recorder.keptCount()) } }}
            </p>
            <button type="button" class="btn btn-primary" id="trace-finish" (click)="finish()">
              <span class="icon" aria-hidden="true">■</span> {{ 'trace.walk.finish' | t }}
            </button>
          }
          <p id="trace-only-open" class="muted small">{{ 'trace.walk.onlyOpen' | t }}</p>
        }

        <!-- Present before a problem or a note arrives, so it is announced. -->
        <div role="alert" class="notes">
          @if (recorder.problem(); as problem) {
            <p class="error" id="trace-problem">{{ problemKey(problem) | t }}</p>
          }
          @if (view.loadError()) {
            <p class="error" id="trace-load-error">{{ 'trace.card.loadError' | t }}</p>
          }
        </div>
        @if (recorder.pausedNotice()) {
          <p class="warn-box small" id="trace-paused">
            {{ 'trace.walk.paused' | t }}
            <button type="button" class="btn btn-sm" (click)="recorder.dismissPausedNotice()">{{ 'trace.alert.dismiss' | t }}</button>
          </p>
        }
        @if (!view.kept()) {
          <p class="warn-box small" id="trace-not-kept">{{ 'trace.card.notKept' | t }}</p>
        }

        <fieldset class="looks" aria-describedby="trace-look-hint">
          <legend>{{ 'trace.repeatLook.title' | t }}</legend>
          <p id="trace-look-hint" class="muted small">{{ 'trace.repeatLook.hint' | t }}</p>
          @for (look of looks; track look) {
            <label class="choice">
              <input type="radio" name="trace-look" [value]="look" [checked]="view.look() === look" (change)="setLook(look)" />
              <span>
                <span class="choice-name">{{ lookKeys[look].label | t }}</span>
                <span class="muted small">{{ lookKeys[look].desc | t }}</span>
              </span>
            </label>
          }
        </fieldset>

        <label class="switch" for="trace-alert">
          <input
            id="trace-alert"
            type="checkbox"
            role="switch"
            [checked]="view.alertOn() && view.traceOn()"
            [disabled]="!view.traceOn()"
            aria-describedby="trace-alert-hint"
            (change)="toggleAlert($event)"
          />
          {{ 'trace.alert.title' | t }}
        </label>
        <p id="trace-alert-hint" class="muted small">
          {{ (view.traceOn() ? 'trace.alert.hintWeb' : 'trace.alert.needsTrace') | t }}
        </p>

        @if (recorder.wakeLockSupported) {
          <label class="switch" for="trace-awake">
            <input id="trace-awake" type="checkbox" role="switch" [checked]="view.keepAwake()" aria-describedby="trace-awake-hint" (change)="toggleAwake($event)" />
            {{ 'trace.walk.keepAwake' | t }}
          </label>
          <p id="trace-awake-hint" class="muted small">{{ 'trace.walk.keepAwakeHint' | t }}</p>
          @if (recorder.keepAwakeFailed()) {
            <p class="warn-box small" id="trace-awake-failed">{{ 'trace.walk.keepAwakeFailed' | t }}</p>
          }
        }

        <div class="actions">
          <button type="button" class="btn btn-sm" id="trace-clear" aria-describedby="trace-clear-hint" (click)="clear()">{{ 'trace.card.clear' | t }}</button>
        </div>
        <p id="trace-clear-hint" class="muted small">{{ 'trace.settings.clearHint' | t }}</p>
        <p id="trace-saved" class="small">{{ 'trace.settings.saved' | t: { n: i18n.number(view.savedCount()) } }}</p>
        @if (view.savedCount() > 0) {
          <div class="actions">
            <button type="button" class="btn btn-sm" id="trace-delete-saved" (click)="deleteSaved()">{{ 'trace.settings.deleteAll' | t }}</button>
          </div>
        }
        <p class="muted small">{{ 'trace.web.shared' | t }}</p>
      </div>
    </details>
  `,
  styles: `
    :host {
      display: block;
      margin: var(--space-3) var(--space-4) 0;
    }
    details.trace {
      border: 1px solid var(--border);
      border-radius: var(--radius);
      background: var(--surface);
    }
    summary {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      min-height: var(--target);
      padding: var(--space-2) var(--space-3);
      cursor: pointer;
      font-weight: 600;
    }
    .rec {
      margin-inline-start: auto;
      font-size: var(--text-sm);
      font-weight: 600;
      color: var(--primary);
    }
    .body {
      display: flex;
      flex-direction: column;
      gap: var(--space-2);
      padding: 0 var(--space-3) var(--space-3);
    }
    .body p {
      margin: 0;
    }
    .switch {
      display: flex;
      align-items: center;
      gap: var(--space-2);
      min-height: var(--target);
      font-weight: 600;
    }
    .switch input {
      width: 1.25rem;
      height: 1.25rem;
      flex: none;
    }
    .looks {
      margin: 0;
      padding: 0;
      border: 0;
    }
    .looks legend {
      font-weight: 600;
    }
    .choice {
      display: flex;
      align-items: flex-start;
      gap: var(--space-2);
      min-height: var(--target);
      padding: var(--space-1) 0;
    }
    .choice input {
      width: 1.25rem;
      height: 1.25rem;
      flex: none;
      margin-top: var(--space-1);
    }
    .choice span span {
      display: block;
    }
    .choice-name {
      font-weight: 600;
    }
    .banner {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      margin-bottom: var(--space-2);
      border: 1px solid var(--warn-border);
      border-radius: var(--radius);
      background: var(--warn-bg);
      color: var(--warn-text);
      font-weight: 600;
    }
    .banner p {
      margin: 0;
    }
    .banner p.small {
      font-weight: 400;
      flex-basis: 100%;
    }
    .banner button {
      margin-inline-start: auto;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }
    .error {
      color: var(--error-text);
    }
  `,
})
export class TraceCard {
  protected readonly view = inject(TraceView);
  protected readonly recorder = inject(TraceRecorderService);
  protected readonly sound = inject(AlertSound);
  protected readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);
  private readonly confirm = inject(ConfirmService);
  private readonly injector = inject(Injector);

  protected readonly looks = REPEAT_LOOKS;
  protected readonly lookKeys = LOOK_KEYS;
  protected readonly bannerOpen = signal(false);
  /** Open on a wide screen, one line on a phone; the person's own toggle is kept after that. */
  protected readonly open = signal(typeof matchMedia !== 'undefined' && matchMedia(WIDE_QUERY).matches);
  private bannerTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    // The banner shows when the recorder rings, for BANNER_MS or until it is dismissed.
    let raised = this.recorder.alertRaised();
    effect(() => {
      const n = this.recorder.alertRaised();
      if (n === raised) return;
      raised = n;
      untracked(() => this.showBanner());
    });
    // What the recorder is doing, said once through the app's polite live region.
    let state = this.recorder.state();
    effect(() => {
      const now = this.recorder.state();
      if (now === state) return;
      state = now;
      if (now === 'recording') untracked(() => this.announcer.announce({ key: 'trace.walk.recording' }));
    });
    effect(() => {
      if (this.recorder.pausedNotice()) untracked(() => this.announcer.announce({ key: 'trace.walk.paused' }));
    });
  }

  /** The sentence for why a walk is not recording. */
  protected problemKey(problem: TraceProblem): TKey {
    return PROBLEM_KEY[problem];
  }

  protected onToggle(event: Event): void {
    this.open.set((event.target as HTMLDetailsElement).open);
  }

  /** Shows the alert banner for a few seconds. */
  private showBanner(): void {
    clearTimeout(this.bannerTimer);
    this.bannerOpen.set(true);
    this.bannerTimer = setTimeout(() => this.dismissBanner(), BANNER_MS);
  }

  protected dismissBanner(): void {
    clearTimeout(this.bannerTimer);
    this.bannerOpen.set(false);
  }

  /** The trace switch; turning it off ends a walk that is recording. */
  protected async toggleOn(event: Event): Promise<void> {
    const on = (event.target as HTMLInputElement).checked;
    // Turning the trace off while a walk records ends the walk (what is stored stays).
    if (!on && this.recorder.state() !== 'idle') await this.view.finishWalk();
    await this.view.setTraceOn(on);
  }

  /** Called from the click: the recorder asks the browser for the location in this same call. */
  protected start(): void {
    void this.view.startWalk();
    this.focusLater('trace-finish');
  }

  protected finish(): void {
    void this.view.finishWalk();
    this.focusLater('trace-start');
  }

  /** A button that replaces the one pressed would leave focus on <body>: put it on the new one once it exists. */
  private focusLater(id: string): void {
    afterNextRender(() => document.getElementById(id)?.focus(), { injector: this.injector });
  }

  protected setLook(look: RepeatLook): void {
    void this.view.setLook(look);
  }

  protected toggleAlert(event: Event): void {
    void this.view.setAlertOn((event.target as HTMLInputElement).checked);
  }

  protected toggleAwake(event: Event): void {
    void this.view.setKeepAwake((event.target as HTMLInputElement).checked);
  }

  /** *Delete all saved walks*: asks with the count first; the 30-day trace stays. */
  protected async deleteSaved(): Promise<void> {
    const ok = await this.confirm.ask(
      { key: 'trace.settings.deleteAllConfirm', params: { n: this.i18n.number(this.view.savedCount()) } },
      { confirmKey: 'trace.settings.deleteAll', danger: true },
    );
    if (ok) await this.view.deleteAllSaved();
  }

  /** *Clear the path*: asks, then removes the 30-day trace (saved walks stay). */
  protected async clear(): Promise<void> {
    const ok = await this.confirm.ask({ key: 'trace.card.clearConfirm' }, { confirmKey: 'trace.card.clear', danger: true });
    if (ok) await this.view.clearTrace();
  }
}
