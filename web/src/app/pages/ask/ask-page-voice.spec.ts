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

import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import { LocalDataService } from '../../core/local-data.service';
import { fakeMic } from '../../core/voice/testing/voice-fakes';
import { RECORDER_ENV, VoiceInputService } from '../../core/voice/voice-input.service';
import { TranslationService } from '../../i18n/translation.service';
import { AskPage } from './ask-page';

/**
 * TC-U-247 (docs/06): voice input in *Ask a question* (S4b-BL-221, ADR-37). The microphone sits under the question box (and
 * only with the setting on), the spoken words land in the box as ordinary text, nothing is asked until the person presses
 * Ask, and Ask then sends the words as they were edited: the existing path, unchanged.
 */
describe('AskPage: voice input', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function open(on: boolean) {
    localStorage.clear();
    const ask = vi.fn(() => of({ answer: 'ok', citations: [], grounded: false, retrieved: 0 }));
    const mic = fakeMic();
    vi.stubGlobal('fetch', async () =>
      new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: JSON.stringify({ text: 'which two bhk is cheapest', language: 'en-IN' }) }] } }] }) ));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AskPage],
      providers: [
        provideRouter([]),
        {
          provide: AiService,
          useValue: {
            enabled: signal(true), enabledFor: () => true, usesOwnKey: signal(true), ownHost: signal('generativelanguage.googleapis.com'),
            aiConfig: signal({ kind: 'gemini', baseUrl: '', model: '' }), ownKey: () => 'k-not-real', ask,
          },
        },
        { provide: RECORDER_ENV, useValue: mic.env },
        { provide: LocalDataService, useValue: { houses: () => of([]) } },
      ],
    });
    TestBed.inject(VoiceInputService).setEnabled(on);
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(AskPage);
    await fixture.whenStable();
    return { fixture, ask, mic, host: fixture.nativeElement as HTMLElement };
  }

  it('has no microphone while the setting is off, and never asks for one', async () => {
    const { host, mic } = await open(false);
    expect(host.querySelector('#question-mic')).toBeNull();
    expect(mic.asked()).toBe(0);
  });

  it('the spoken words join the question box, nothing is asked on its own, and Ask sends the edited words', async () => {
    const { fixture, host, ask } = await open(true);
    const box = host.querySelector<HTMLTextAreaElement>('#question')!;
    expect(host.querySelector('#question-mic')).not.toBeNull();
    host.querySelector<HTMLButtonElement>('#question-mic')!.click();
    await vi.waitFor(() => expect(host.querySelector('#question-mic')?.textContent).toContain('Stop'));
    host.querySelector<HTMLButtonElement>('#question-mic')!.click();
    await vi.waitFor(async () => {
      await fixture.whenStable();
      expect(box.value).toBe('which two bhk is cheapest');
    });
    expect(ask).not.toHaveBeenCalled();
    // The person edits the words (the transcript is ordinary text), then presses Ask.
    box.value = box.value + ' in Kochi?';
    box.dispatchEvent(new Event('input'));
    host.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await fixture.whenStable();
    expect(ask).toHaveBeenCalledTimes(1);
    expect((ask.mock.calls[0] as unknown[])[0]).toBe('which two bhk is cheapest in Kochi?');
  });
});
