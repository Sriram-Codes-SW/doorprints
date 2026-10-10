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

import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from './ai.service';
import { GEMINI_URL, GeminiChatModel, OnDeviceAiService } from './ai/on-device-ai.service';
import { AI_KIND_KEY, AI_OPT_IN_KEY, AI_PROVIDER_KEY, AI_QUALITY_KEY, GEMINI_KEY_KEY } from './storage-keys';

/**
 * TC-U-190 (docs/06): how `AiService` keeps and uses the *AI speed and cost* setting (S4b-BL-198 step 2): saved on this
 * device, handed to the own-key Gemini adapter only, kept but ignored while AI is off, and forgotten with *Remove key*.
 */
describe('AiService: AI speed and cost', () => {
  let http: HttpTestingController;

  function create(onDevice?: Partial<OnDeviceAiService>): AiService {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), ...(onDevice ? [{ provide: OnDeviceAiService, useValue: onDevice }] : [])],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(AiService);
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    http?.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('is Quality until the person chooses, and a choice is saved on this device', () => {
    const ai = create();
    expect(ai.aiQuality()).toBe('quality');
    ai.setAiQuality('economy');
    expect(ai.aiQuality()).toBe('economy');
    expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('economy');
  });

  it('reads a stored unknown value as Quality', () => {
    localStorage.setItem(AI_QUALITY_KEY, 'turbo');
    expect(create().aiQuality()).toBe('quality');
  });

  it('keeps the choice while AI is off or another service is chosen, and Remove key forgets it', () => {
    const ai = create();
    ai.setOptIn(true);
    ai.setAiQuality('balanced');
    ai.setOptIn(false);
    expect(ai.aiQuality()).toBe('balanced');
    ai.setAiConfig({ kind: 'openai-compatible', baseUrl: 'http://localhost:11434/v1', model: 'llama' });
    expect(ai.aiQuality()).toBe('balanced');
    expect(localStorage.getItem(AI_QUALITY_KEY)).toBe('balanced');
    ai.removeGeminiKey();
    expect(ai.aiQuality()).toBe('quality');
    expect(localStorage.getItem(AI_QUALITY_KEY)).toBeNull();
  });

  it('gives the plain key to the adapter with Quality, so nothing changes for anyone who does not touch the setting', async () => {
    const onDevice = { ask: vi.fn(async () => ({ answer: 'a', citations: [], grounded: false, retrieved: 0 })) };
    const ai = create(onDevice);
    localStorage.setItem(AI_OPT_IN_KEY, '1');
    ai.saveGeminiKey('AIzaTestKey1234', false);
    await new Promise<void>((resolve) => ai.ask('Quiet?').subscribe(() => resolve()));
    expect(onDevice.ask).toHaveBeenCalledWith('AIzaTestKey1234', 'Quiet?', undefined);
  });

  it('gives a Gemini adapter with the choice to the own-key calls when the choice is not Quality', async () => {
    const onDevice = { ask: vi.fn(async () => ({ answer: 'a', citations: [], grounded: false, retrieved: 0 })) };
    const ai = create(onDevice);
    ai.saveGeminiKey('AIzaTestKey1234', false);
    ai.setAiQuality('economy');
    await new Promise<void>((resolve) => ai.ask('Quiet?').subscribe(() => resolve()));
    const via = (onDevice.ask.mock.calls[0] as unknown[])[0];
    expect(via).toBeInstanceOf(GeminiChatModel);
  });

  it('Test key sends the choice too, so a key that cannot use it is found out then', async () => {
    const ai = create();
    ai.saveGeminiKey('AIzaTestKey1234', false);
    ai.setAiQuality('economy');
    const done = ai.testGeminiKey('AIzaTestKey1234');
    const req = http.expectOne(GEMINI_URL);
    expect(req.request.body.generationConfig.thinkingConfig).toEqual({ thinkingLevel: 'LOW' });
    req.flush({ candidates: [{ content: { parts: [{ text: '{"ok":true}' }] } }] });
    await done;
  });

  it('Test key with Quality sends no thinking field', async () => {
    const ai = create();
    const done = ai.testGeminiKey('AIzaTestKey1234');
    const req = http.expectOne(GEMINI_URL);
    expect(JSON.stringify(req.request.body)).not.toMatch(/thinking/i);
    req.flush({ candidates: [{ content: { parts: [{ text: '{"ok":true}' }] } }] });
    await done;
  });

  it('a stored Economy is ignored for another service: the test request of an OpenAI-compatible provider carries no thinking field', async () => {
    const test = vi.fn(async () => undefined);
    const ai = create({ test });
    localStorage.setItem(AI_KIND_KEY, 'openai-compatible');
    localStorage.setItem(AI_PROVIDER_KEY, 'device');
    localStorage.setItem(GEMINI_KEY_KEY, 'k');
    ai.setAiQuality('economy');
    await ai.testProvider({ kind: 'openai-compatible', baseUrl: 'https://api.example.com/v1', model: 'm' }, 'k');
    const via = (test.mock.calls[0] as unknown[])[0];
    expect(via).not.toBeInstanceOf(GeminiChatModel);
    expect(JSON.stringify(via)).not.toMatch(/thinking|economy/i);
  });
});
