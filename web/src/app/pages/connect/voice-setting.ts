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

import { Component, inject } from '@angular/core';
import { AiService } from '../../core/ai.service';
import { canRecord } from '../../core/voice/audio-recorder';
import { RECORDER_ENV, VoiceInputService } from '../../core/voice/voice-input.service';
import { TPipe } from '../../i18n/t.pipe';

/**
 * The *Voice input* switch of the *AI features* card (voice input, docs/03 ADR-37): off until the person turns it on, in
 * this browser only. It is there once *AI features* are on. It works only with the person's own AI key and a service that
 * takes audio (Gemini, OpenAI, Groq, OpenRouter); for any other setup it says why there is no switch instead of offering one
 * that does nothing. The disclosure is on screen before the switch is turned on: where the audio goes, the list price of a
 * minute, and that a number or a name said aloud is sent as audio and is not redacted as typed text is. The switch is a
 * preference (localStorage, never in a backup); no audio or words are kept anywhere.
 */
@Component({
  selector: 'app-voice-setting',
  imports: [TPipe],
  template: `
    @if (ai.optedIn()) {
      <div id="voice" class="voice-setting">
        <fieldset class="field">
          <legend>{{ 'voice.provider' | t }}</legend>
          <div class="field checkbox">
            <input id="voice-provider-own" type="radio" name="voiceProvider" [checked]="voice.provider() === 'own'" (change)="choose('own')" />
            <label for="voice-provider-own">{{ 'voice.providerOwn' | t }}</label>
          </div>
          <div class="field checkbox">
            <input id="voice-provider-sarvam" type="radio" name="voiceProvider" [checked]="voice.provider() === 'sarvam'" (change)="choose('sarvam')" />
            <label for="voice-provider-sarvam">{{ 'voice.providerSarvam' | t }}</label>
          </div>
        </fieldset>
        @if (voice.provider() === 'sarvam') {
          <div class="field">
            <label for="voice-sarvam-key">{{ 'voice.sarvamKey' | t }}</label>
            <input id="voice-sarvam-key" type="password" autocomplete="off" spellcheck="false" [value]="sarvamKey" (input)="sarvamKey = $any($event.target).value" aria-describedby="voice-sarvam-hint" />
            <p id="voice-sarvam-hint" class="muted small">
              {{ voice.hasSarvamKey() ? ('voice.sarvamKeySaved' | t: { hint: voice.sarvamKeyHint() }) : ('voice.sarvamKeyHint' | t) }}
            </p>
            <div class="field checkbox">
              <input id="voice-sarvam-remember" type="checkbox" [checked]="remember" (change)="remember = $any($event.target).checked" />
              <label for="voice-sarvam-remember">{{ 'voice.sarvamRemember' | t }}</label>
            </div>
            <button type="button" id="voice-sarvam-save" class="btn btn-sm" (click)="saveKey()">{{ 'voice.sarvamSave' | t }}</button>
            @if (voice.hasSarvamKey()) {
              <button type="button" id="voice-sarvam-remove" class="btn btn-sm" (click)="removeKey()">{{ 'voice.sarvamRemove' | t }}</button>
            }
          </div>
        }
        @if (voice.usable()) {
          <div class="field checkbox">
            <input
              id="voice-input"
              type="checkbox"
              [checked]="voice.enabled()"
              (change)="toggle($event)"
              aria-describedby="voice-hint voice-disclosure"
            />
            <label for="voice-input">{{ 'voice.switch' | t }}</label>
            <p id="voice-hint" class="muted small">{{ (voice.provider() === 'sarvam' ? 'voice.switchHintSarvam' : 'voice.switchHint') | t }}</p>
          </div>
          <p id="voice-disclosure" class="muted small">
            {{ (voice.provider() === 'sarvam' ? 'voice.disclosureSarvam' : 'voice.disclosure') | t: { host: voice.host(), price: voice.price() ?? '' } }}
          </p>
          @if (voice.enabled() && !recordable) {
            <p id="voice-no-recorder" class="muted small">{{ 'voice.noRecorder' | t }}</p>
          }
        } @else if (voice.provider() === 'own') {
          <p id="voice-needs-key" class="muted small">{{ 'voice.needsOwnKey' | t }}</p>
        } @else {
          <p id="voice-needs-sarvam" class="muted small">{{ 'voice.needsSarvamKey' | t }}</p>
        }
      </div>
    }
  `,
})
export class VoiceSetting {
  protected readonly ai = inject(AiService);
  protected readonly voice = inject(VoiceInputService);
  protected readonly recordable = canRecord(inject(RECORDER_ENV));

  /** The Sarvam key being typed; saved only by the button, never kept in the page after. */
  protected sarvamKey = '';
  protected remember = false;

  protected choose(p: 'own' | 'sarvam'): void {
    this.voice.setProvider(p);
  }

  protected saveKey(): void {
    if (this.sarvamKey.trim() === '') return;
    this.voice.saveSarvamKey(this.sarvamKey, this.remember);
    this.sarvamKey = '';
  }

  protected removeKey(): void {
    this.voice.removeSarvamKey();
    this.sarvamKey = '';
  }

  protected toggle(event: Event): void {
    this.voice.setEnabled((event.target as HTMLInputElement).checked);
  }
}
