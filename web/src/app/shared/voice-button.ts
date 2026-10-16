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

import { Component, Injector, OnDestroy, afterNextRender, computed, effect, inject, input, output, signal } from '@angular/core';
import { aiErrorMsg } from '../core/ai.service';
import { AudioClipRejectedError } from '../core/ai/transcriber';
import { type AudioRecorder, type RecordedClip, RecorderError } from '../core/voice/audio-recorder';
import { appendTranscript } from '../core/voice/transcript-insert';
import { VoiceInputService } from '../core/voice/voice-input.service';
import type { TKey } from '../i18n/en';
import { type Msg } from '../i18n/translation.service';
import { TPipe } from '../i18n/t.pipe';
import { GLYPHS } from './glyphs';
import { baselineKindId } from '../core/candidate';

type Phase = 'idle' | 'starting' | 'recording' | 'transcribing';

/**
 * The microphone beside a text box (voice input, docs/03 ADR-37), built once and placed under the three AI boxes: *Ask a
 * question*, *Fill in from listing text* and *Plan notes*. It is drawn only when the person has turned *Voice input* on and
 * their own AI can take audio ({@link VoiceInputService.available}); otherwise nothing is drawn and the microphone is never
 * asked for.
 *
 * One tap starts recording (the browser asks for the microphone then, the first time), a second tap stops it, *Cancel* or
 * Escape throws it away. The words land at the end of the box as ordinary text ({@link appendTranscript}), the box gets the
 * focus, and nothing is sent: the page's own Ask, Fill in or Plan button does that later, after the person has read and
 * edited the words. The audio is held in memory by the recorder for the one call and is let go on every exit, including this
 * component being destroyed (a route change); no audio is stored, logged or copied (T-I48). Words in `voice.*`.
 *
 * Accessible: a real button with a name that contains its visible word, a polite status ("Recording", "Writing out the
 * words", "Done") that is not repeated each second, failures in an alert, Cancel and Escape for the keyboard, focus kept
 * on the button through the states and sent to the box when the words arrive.
 */
@Component({
  selector: 'app-voice-button',
  imports: [TPipe],
  template: `
    @if (voice.available()) {
      <div class="voice" (keydown.escape)="cancelOnEscape($event)">
        <div class="voice-row">
          <button
            type="button"
            class="btn voice-btn"
            [id]="fieldId() + '-mic'"
            [class.recording]="phase() === 'recording'"
            [attr.aria-label]="micLabel() | t"
            [attr.aria-disabled]="phase() === 'transcribing' || phase() === 'starting' ? 'true' : null"
            [attr.aria-describedby]="fieldId() + '-voice-hint'"
            (click)="toggle()"
          >
            <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">
              <path [attr.d]="phase() === 'recording' ? glyphs.stop : glyphs.mic" fill="currentColor" />
            </svg>
            <span>{{ visibleWord() | t }}</span>
          </button>
          @if (phase() === 'recording' || phase() === 'transcribing') {
            <button type="button" class="btn btn-sm" [id]="fieldId() + '-mic-cancel'" [attr.aria-label]="'voice.cancelAria' | t" (click)="cancel()">
              {{ 'voice.cancel' | t }}
            </button>
          }
          @if (phase() === 'recording') {
            <span class="voice-time" aria-hidden="true">{{ clock() }}</span>
          }
        </div>
        <p [id]="fieldId() + '-voice-hint'" class="field-hint">{{ (voice.shortLimit() ? 'voice.hintShort' : 'voice.hint') | t: { host: voice.host() } }}</p>
        <div class="field-hint voice-status" role="status" aria-live="polite">
          @if (status(); as key) {
            {{ key | t }}
          }
        </div>
        <div role="alert">
          @if (problem(); as p) {
            <p class="field-error">{{ p.key | t: p.params }}</p>
          }
        </div>
      </div>
    }
  `,
  styles: `
    .voice {
      margin-top: var(--space-2);
    }
    .voice-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
    }
    .voice-btn {
      display: inline-flex;
      align-items: center;
      gap: var(--space-2);
    }
    .voice-btn.recording {
      border-color: var(--danger, currentColor);
    }
    .voice-time {
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class VoiceButton implements OnDestroy {
  /** The id of the text box this microphone writes into; it gets the focus when the words arrive. */
  readonly fieldId = input.required<string>();
  /** What the box holds now; the words are added after it. */
  readonly value = input<string>('');
  /** The box's character limit, if it has one; words past it are left out and the status says so. */
  readonly max = input<number | null>(null);
  /** The space on screen (key terms for the model). */
  readonly kind = input<string>(baselineKindId());
  /** The box's new value: what it held, then the words. */
  readonly valueChange = output<string>();

  protected readonly voice = inject(VoiceInputService);
  private readonly injector = inject(Injector);
  protected readonly glyphs = GLYPHS;

  protected readonly phase = signal<Phase>('idle');
  /** The polite status, in words that are true of the state now. */
  protected readonly status = signal<TKey | null>(null);
  protected readonly problem = signal<Msg | null>(null);
  private readonly seconds = signal(0);
  protected readonly clock = computed(() => `${Math.floor(this.seconds() / 60)}:${String(this.seconds() % 60).padStart(2, '0')}`);

  private recorder: AudioRecorder | null = null;
  private ticker: ReturnType<typeof setInterval> | undefined;
  /** Bumped by every start and every exit, so a late answer of an earlier run is thrown away, never written into the box. */
  private run = 0;

  constructor() {
    // The switch turned off, or the AI changed, while a recording is on: it ends, with nothing kept.
    effect(() => {
      if (!this.voice.available()) this.discard();
    });
  }

  ngOnDestroy(): void {
    this.discard();
  }

  protected micLabel(): TKey {
    return this.phase() === 'recording' ? 'voice.stopAria' : this.phase() === 'transcribing' ? 'voice.working' : 'voice.micAria';
  }

  protected visibleWord(): TKey {
    return this.phase() === 'recording' ? 'voice.stop' : this.phase() === 'transcribing' ? 'voice.working' : 'voice.mic';
  }

  protected toggle(): void {
    if (this.phase() === 'recording') void this.finish();
    else if (this.phase() === 'idle') void this.begin();
  }

  protected cancelOnEscape(event: Event): void {
    if (this.phase() === 'recording' || this.phase() === 'transcribing') {
      event.stopPropagation();
      this.cancel();
    }
  }

  /** Throws the recording or the pending words away; focus returns to the microphone button. */
  protected cancel(): void {
    this.discard();
    this.status.set('voice.statusCancelled');
    afterNextRender(() => document.getElementById(this.fieldId() + '-mic')?.focus(), { injector: this.injector });
  }

  /** The tap that starts: the microphone is asked for here, never earlier. */
  private async begin(): Promise<void> {
    const run = ++this.run;
    this.problem.set(null);
    this.phase.set('starting');
    this.status.set('voice.statusStarting');
    const recorder = this.voice.newRecorder();
    recorder.onEnded = (how) => this.endedByItself(run, how);
    this.recorder = recorder;
    try {
      await recorder.start();
    } catch (err) {
      if (run !== this.run) return;
      this.recorder = null;
      this.fail(err);
      return;
    }
    if (run !== this.run) return;
    this.phase.set('recording');
    this.status.set('voice.statusRecording');
    this.seconds.set(0);
    this.ticker = setInterval(() => this.seconds.set(Math.floor((this.recorder?.elapsedMs() ?? 0) / 1000)), 1000);
  }

  /** The second tap: the recording ends and its words are asked for. */
  private async finish(): Promise<void> {
    const run = this.run;
    const recorder = this.recorder;
    if (recorder === null) return;
    clearInterval(this.ticker);
    this.phase.set('transcribing');
    this.status.set('voice.statusWorking');
    try {
      await this.words(run, await recorder.stop());
    } catch (err) {
      if (run === this.run) this.fail(err);
    }
  }

  /** The recording ended without a tap: a limit or a cut-short recording (the words are asked for), the page hidden or an error. */
  private endedByItself(run: number, how: { clip?: RecordedClip; failure?: string; early?: boolean }): void {
    if (run !== this.run) return;
    clearInterval(this.ticker);
    this.recorder = null;
    if (how.clip) {
      this.phase.set('transcribing');
      this.status.set('voice.statusWorking');
      this.words(run, how.clip, how.early === true).catch((err: unknown) => {
        if (run === this.run) this.fail(err);
      });
    } else if (how.failure === 'cancelled') {
      this.phase.set('idle');
      this.status.set('voice.statusCancelled');
    } else if (how.failure === 'endedEarly') {
      // Cut short from outside before anything was said: nothing to keep and nothing untrue to show.
      this.discard();
      this.status.set(null);
    } else {
      this.fail(new RecorderError('failed'));
    }
  }

  /** Asks the provider for the words and puts them in the box; a clip heard as nothing says so. */
  private async words(run: number, clip: RecordedClip, early = false): Promise<void> {
    this.recorder = null;
    const spoken = (await this.voice.transcribe(clip, this.kind())).text.trim();
    if (run !== this.run) return;
    this.phase.set('idle');
    if (spoken === '') {
      this.problem.set({ key: 'voice.nothingHeard' });
      this.status.set(null);
      return;
    }
    const inserted = appendTranscript(this.value(), spoken, this.max());
    this.valueChange.emit(inserted.text);
    this.status.set(
      inserted.cut
        ? 'voice.statusDoneCut'
        : clip.limit
          ? this.voice.shortLimit()
            ? 'voice.statusDoneLimitShort'
            : 'voice.statusDoneLimit'
          : early
            ? 'voice.statusDoneEarly'
            : 'voice.statusDone',
    );
    afterNextRender(() => document.getElementById(this.fieldId())?.focus(), { injector: this.injector });
  }

  private fail(err: unknown): void {
    this.discard();
    this.problem.set(this.messageOf(err));
    this.status.set(null);
    afterNextRender(() => document.getElementById(this.fieldId() + '-mic')?.focus(), { injector: this.injector });
  }

  private messageOf(err: unknown): Msg {
    if (err instanceof RecorderError) {
      if (err.reason === 'denied') return { key: 'voice.denied' };
      if (err.reason === 'noDevice') return { key: 'voice.noDevice' };
      return { key: 'voice.failed' };
    }
    if (err instanceof AudioClipRejectedError) {
      if (err.reason === 'empty') return { key: 'voice.nothingHeard' };
      return err.reason === 'tooLarge' || err.reason === 'tooLong' ? { key: 'voice.tooLong' } : { key: 'voice.failed' };
    }
    return aiErrorMsg(err);
  }

  /** Every exit: the run ends (a late answer is dropped), the recorder lets the microphone go, the clock stops. */
  private discard(): void {
    this.run++;
    clearInterval(this.ticker);
    this.ticker = undefined;
    this.recorder?.release();
    this.recorder = null;
    this.phase.set('idle');
  }
}
