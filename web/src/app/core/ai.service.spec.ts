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
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AiService, aiErrorMsg, aiOffMsg } from './ai.service';
import { ConfigService } from './config.service';
import { AI_OPT_IN_KEY } from './storage-keys';

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
});
