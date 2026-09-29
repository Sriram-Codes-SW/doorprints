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

import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService, aiErrorMsg, aiOffMsg } from './ai.service';
import { ConfigService } from './config.service';
import { AI_OPT_IN_KEY, AI_PROVIDER_KEY, GEMINI_KEY_KEY } from './storage-keys';
import { OnDeviceAiError, OnDeviceAiService } from './ai/on-device-ai.service';
import { firstValueFrom } from 'rxjs';

const ON = { enabled: true, mcpEnabled: false, chatModel: 'gemini', embeddingModel: 'embed', offForDevice: false };

/**
 * AI shows only when the server offers it to this device and this browser's own *AI features* switch is on; the switch
 * is off until the person turns it on (owner decision of 2026-09-29, docs/03 §12.1).
 */
describe('AiService', () => {
  let http: HttpTestingController;

  function create(connected: boolean): AiService {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    if (connected) TestBed.inject(ConfigService).save({ baseUrl: 'https://a.example.org', apiKey: 'dpk_x' }, false);
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(AiService);
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('with no server, AI is off because there is no server', () => {
    const ai = create(false);
    expect(ai.enabled()).toBe(false);
    expect(ai.offReason()).toBe('noServer');
  });

  it('a server with AI on still needs this browser’s switch, which starts off and is remembered', () => {
    const ai = create(true);
    http.expectOne('/api/ai/status').flush(ON);
    expect(ai.serverEnabled()).toBe(true);
    expect(ai.optedIn()).toBe(false);
    expect(ai.enabled()).toBe(false);
    expect(ai.offReason()).toBe('optIn');

    ai.setOptIn(true);
    expect(ai.enabled()).toBe(true);
    expect(ai.offReason()).toBeNull();
    expect(localStorage.getItem(AI_OPT_IN_KEY)).toBe('1');

    ai.setOptIn(false);
    expect(ai.enabled()).toBe(false);
    expect(localStorage.getItem(AI_OPT_IN_KEY)).toBeNull();
  });

  it('says when the owner has not turned AI on for this device, or AI is off on the server', () => {
    localStorage.setItem(AI_OPT_IN_KEY, '1');
    const ai = create(true);
    http.expectOne('/api/ai/status').flush({ ...ON, enabled: false, offForDevice: true });
    expect(ai.enabled()).toBe(false);
    expect(ai.offReason()).toBe('device');

    ai.refresh();
    http.expectOne('/api/ai/status').flush({ ...ON, enabled: false });
    expect(ai.offReason()).toBe('server');
  });

  it('has words for every reason and for the 403s of the owner’s switches', () => {
    expect(aiOffMsg('noServer').key).toBe('ai.noServer');
    expect(aiOffMsg('server').key).toBe('ai.disabled');
    expect(aiOffMsg('device').key).toBe('ai.offForDevice');
    expect(aiOffMsg('optIn').key).toBe('ai.optInNeeded');
    const forbidden = (code: string) => new HttpErrorResponse({ status: 403, error: { status: 403, code } });
    expect(aiErrorMsg(forbidden('AI_OFF_FOR_DEVICE')).key).toBe('ai.offForDevice');
    expect(aiErrorMsg(forbidden('AI_PAUSED')).key).toBe('ai.disabled');
  });

  // ADR-26: the same calls, answered by the server or by Gemini from this browser with the person's own key.
  it('with no server, an own Gemini key turns AI on once the switch is on; the key is kept as asked', () => {
    const ai = create(false);
    ai.setOptIn(true);
    expect(ai.offReason()).toBe('noKey');
    ai.saveGeminiKey('  AIzaTestKey1234 ', false);
    expect(ai.enabled()).toBe(true);
    expect(ai.usesOwnKey()).toBe(true);
    expect(ai.offReason()).toBeNull();
    expect(ai.geminiKeyHint()).toBe('1234');
    expect(sessionStorage.getItem(GEMINI_KEY_KEY)).toBe('AIzaTestKey1234');
    expect(localStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    ai.saveGeminiKey('AIzaOtherKey5678', true);
    expect(localStorage.getItem(GEMINI_KEY_KEY)).toBe('AIzaOtherKey5678');
    expect(sessionStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    ai.removeGeminiKey();
    expect(localStorage.getItem(GEMINI_KEY_KEY)).toBeNull();
    expect(ai.hasGeminiKey()).toBe(false);
    expect(ai.enabled()).toBe(false);
    expect(ai.offReason()).toBe('noKey');
  });

  it('routes each call to the chosen provider: the server, or this browser with the key', async () => {
    const onDevice = { ask: vi.fn(async () => ({ answer: 'local', citations: [], grounded: false, retrieved: 0 })) };
    TestBed.configureTestingModule({ providers: [{ provide: OnDeviceAiService, useValue: onDevice }] });
    const ai = create(true);
    http.expectOne('/api/ai/status').flush(ON);
    ai.setOptIn(true);
    ai.ask('Quiet?').subscribe();
    http.expectOne('/api/ai/ask').flush({ answer: 'server', citations: [], grounded: false, retrieved: 0 });
    ai.saveGeminiKey('AIzaTestKey1234', false);
    expect(localStorage.getItem(AI_PROVIDER_KEY)).toBe('device');
    expect((await firstValueFrom(ai.ask('Quiet?'))).answer).toBe('local');
    expect(onDevice.ask).toHaveBeenCalledWith('AIzaTestKey1234', 'Quiet?', undefined);
    http.expectNone('/api/ai/ask');
    ai.setProvider('server');
    expect(ai.usesOwnKey()).toBe(false);
    expect(ai.hasGeminiKey()).toBe(true);
    ai.ask('Quiet?').subscribe();
    http.expectOne('/api/ai/ask').flush({ answer: 'server', citations: [], grounded: false, retrieved: 0 });
  });

  it('own key chosen but none saved says so, whatever the server offers', () => {
    localStorage.setItem(AI_OPT_IN_KEY, '1');
    localStorage.setItem(AI_PROVIDER_KEY, 'device');
    const ai = create(true);
    http.expectOne('/api/ai/status').flush(ON);
    expect(ai.enabled()).toBe(false);
    expect(ai.offReason()).toBe('noKey');
    expect(aiOffMsg('noKey').key).toBe('ai.noKey');
  });

  it('has words for the own key\'s failures', () => {
    expect(aiErrorMsg(new OnDeviceAiError('keyRejected')).key).toBe('ai.keyRejected');
    expect(aiErrorMsg(new OnDeviceAiError('rateLimited', 12))).toEqual({ key: 'ai.rateLimited', params: { s: 12 } });
    expect(aiErrorMsg(new OnDeviceAiError('unavailable')).key).toBe('ai.providerDown');
  });
});
