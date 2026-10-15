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
        @if (voice.providerOk()) {
          <div class="field checkbox">
            <input
              id="voice-input"
              type="checkbox"
              [checked]="voice.enabled()"
              (change)="toggle($event)"
              aria-describedby="voice-hint voice-disclosure"
            />
            <label for="voice-input">{{ 'voice.switch' | t }}</label>
            <p id="voice-hint" class="muted small">{{ 'voice.switchHint' | t }}</p>
          </div>
          <p id="voice-disclosure" class="muted small">{{ 'voice.disclosure' | t: { host: voice.host(), price: voice.price() ?? '' } }}</p>
          @if (voice.enabled() && !recordable) {
            <p id="voice-no-recorder" class="muted small">{{ 'voice.noRecorder' | t }}</p>
          }
        } @else {
          <p id="voice-needs-key" class="muted small">{{ 'voice.needsOwnKey' | t }}</p>
        }
      </div>
    }
  `,
})
export class VoiceSetting {
  protected readonly ai = inject(AiService);
  protected readonly voice = inject(VoiceInputService);
  protected readonly recordable = canRecord(inject(RECORDER_ENV));

  protected toggle(event: Event): void {
    this.voice.setEnabled((event.target as HTMLInputElement).checked);
  }
}
