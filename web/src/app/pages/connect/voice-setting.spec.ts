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
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../../core/ai.service';
import type { AiProviderConfig } from '../../core/ai/ai-provider-config';
import { VOICE_INPUT_KEY } from '../../core/storage-keys';
import { fakeMic } from '../../core/voice/testing/voice-fakes';
import { RECORDER_ENV, VoiceInputService } from '../../core/voice/voice-input.service';
import { TranslationService } from '../../i18n/translation.service';
import { VoiceSetting } from './voice-setting';

/**
 * TC-U-246 (docs/06): the *Voice input* switch of the AI features card (S4b-BL-221, ADR-37): off by default, offered only
 * where it can work (own key, a service that takes audio), with the disclosure on screen before it is turned on.
 */
describe('VoiceSetting', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function open(opts: { optedIn?: boolean; own?: boolean; config?: AiProviderConfig; recorder?: boolean } = {}) {
    localStorage.clear();
    const mic = fakeMic();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: AiService,
          useValue: {
            optedIn: signal(opts.optedIn ?? true), enabled: signal(true), usesOwnKey: signal(opts.own ?? true),
            aiConfig: signal(opts.config ?? { kind: 'gemini', baseUrl: '', model: '' }), ownKey: () => 'k',
          },
        },
        { provide: RECORDER_ENV, useValue: opts.recorder === false ? { ...mic.env, recorder: null } : mic.env },
      ],
    });
    await TestBed.inject(TranslationService).setLang('en');
    const fixture = TestBed.createComponent(VoiceSetting);
    await fixture.whenStable();
    return { fixture, mic, host: fixture.nativeElement as HTMLElement };
  }
  const text = (host: HTMLElement, sel: string) => host.querySelector(sel)?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

  it('shows nothing until AI features are on', async () => {
    const { host } = await open({ optedIn: false });
    expect(host.querySelector('#voice')).toBeNull();
  });

  it('is off by default, with the disclosure (host and list price) already on screen and the switch described by it', async () => {
    const { host, mic } = await open();
    const box = host.querySelector<HTMLInputElement>('#voice-input')!;
    expect(box.checked).toBe(false);
    expect(box.getAttribute('aria-describedby')).toBe('voice-hint voice-disclosure');
    expect(text(host, '#voice-disclosure')).toContain('generativelanguage.googleapis.com');
    expect(text(host, '#voice-disclosure')).toContain('INR 0.17 a minute');
    expect(text(host, '#voice-disclosure')).toContain('numbers and names');
    expect(host.querySelector('label[for="voice-input"]')?.textContent).toContain('Voice input');
    expect(mic.asked()).toBe(0);
  });

  it('turning it on and off is kept in this browser and asks for no microphone', async () => {
    const { fixture, host, mic } = await open();
    const box = host.querySelector<HTMLInputElement>('#voice-input')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(localStorage.getItem(VOICE_INPUT_KEY)).toBe('1');
    expect(TestBed.inject(VoiceInputService).available()).toBe(true);
    box.checked = false;
    box.dispatchEvent(new Event('change'));
    expect(localStorage.getItem(VOICE_INPUT_KEY)).toBeNull();
    expect(mic.asked()).toBe(0);
  });

  it.each([
    ['the connected server answering', { own: false }],
    ['Anthropic', { config: { kind: 'anthropic', baseUrl: '', model: 'm' } as AiProviderConfig }],
    ['a local server', { config: { kind: 'openai-compatible', baseUrl: 'http://localhost:11434/v1', model: 'm' } as AiProviderConfig }],
    ['a custom address', { config: { kind: 'openai-compatible', baseUrl: 'https://llm.example.in/v1', model: 'm' } as AiProviderConfig }],
  ])('with %s there is no switch, only the reason', async (_name, opts) => {
    const { host } = await open(opts);
    expect(host.querySelector('#voice-input')).toBeNull();
    expect(text(host, '#voice-needs-key')).toContain('your own AI key');
  });

  it('says so when the switch is on but this browser cannot record', async () => {
    const { fixture, host } = await open({ recorder: false });
    expect(host.querySelector('#voice-no-recorder')).toBeNull();
    const box = host.querySelector<HTMLInputElement>('#voice-input')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    await fixture.whenStable();
    expect(text(host, '#voice-no-recorder')).toContain('cannot record audio');
  });
});
