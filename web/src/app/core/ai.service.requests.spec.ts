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

import { HttpErrorResponse, HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { type Observable, firstValueFrom } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AI_MAX_LISTING_CHARS, AI_MAX_QUESTION_CHARS, AiOffError, AiService, aiErrorMsg, aiOffMsg, splitCitations } from './ai.service';
import type { AskResponse, HouseDraft, PlanRequest, PlanResponse } from './ai.service';
import { OnDeviceAiError, OnDeviceAiService } from './ai/on-device-ai.service';
import { ConfigService } from './config.service';
import { AI_BASE_URL_KEY, AI_KIND_KEY, AI_MODEL_KEY, AI_OPT_IN_KEY } from './storage-keys';

// The server-backed side of AiService (C-06, S4b-BL-251): the paths and bodies it sends, what it sends when AI is off, and
// the words for every way a call fails. Offline: HttpTestingController and a fake OnDeviceAiService, no network, no timers.

const ON = { enabled: true, mcpEnabled: false, chatModel: 'gemini', embeddingModel: 'embed', offForDevice: false };
const ASK: AskResponse = { answer: 'Quiet street.', citations: [], grounded: false, retrieved: 0 };
const DRAFT = { label: 'Flat', amenities: [], warnings: [] } as unknown as HouseDraft;
const PLAN: PlanResponse = { summary: null, stops: [], totalMeters: 0, totalWalkMinutes: 0, toolCalls: [], fallback: false };
const PLAN_REQUEST: PlanRequest = { question: 'Visit the quiet ones', startLat: 12.97, startLon: 77.64, maxStops: 4 };
const HOUSE = '123e4567-e89b-12d3-a456-426614174000';
const OTHER = 'ABCDEF01-2345-6789-abcd-ef0123456789';

describe('AiService against the server', () => {
  let http: HttpTestingController;

  function create(connected: boolean, optedIn = true): AiService {
    if (optedIn) localStorage.setItem(AI_OPT_IN_KEY, '1');
    if (connected) TestBed.inject(ConfigService).save({ baseUrl: 'https://a.example.org', apiKey: 'dpk_x' }, false);
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(AiService);
  }

  /** A server with AI on, this browser opted in: the state in which the three calls are offered. */
  function ready(): AiService {
    const ai = create(true);
    http.expectOne('/api/ai/status').flush(ON);
    expect(ai.enabled()).toBe(true);
    return ai;
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
    sessionStorage.clear();
    vi.restoreAllMocks();
  });

  describe('what is asked of the server, and when', () => {
    it('reads /api/ai/status once when a server is connected, and sends nothing else', () => {
      const ai = create(true);
      const req = http.expectOne('/api/ai/status');
      expect(req.request.method).toBe('GET');
      req.flush(ON);
      expect(ai.serverEnabled()).toBe(true);
    });

    it('with no server connected, nothing is sent at all and the reason is "no server"', () => {
      const ai = create(false, false);
      http.expectNone(() => true);
      expect(ai.serverEnabled()).toBe(false);
      expect(ai.offReason()).toBe('noServer');
    });

    it('a status that fails, over HTTP or the network, counts as AI off on the server', () => {
      const ai = ready();
      ai.refresh();
      http.expectOne('/api/ai/status').flush({ status: 500 }, { status: 500, statusText: 'Server Error' });
      expect(ai.serverEnabled()).toBe(false);
      expect(ai.offReason()).toBe('server');
      ai.refresh();
      http.expectOne('/api/ai/status').flush(ON);
      expect(ai.enabled()).toBe(true);
      ai.refresh();
      http.expectOne('/api/ai/status').error(new ProgressEvent('error'));
      expect(ai.serverEnabled()).toBe(false);
      expect(ai.offReason()).toBe('server');
    });

    it('a status that comes back later switches AI on, and disconnecting forgets it without asking again', () => {
      const ai = create(true);
      http.expectOne('/api/ai/status').flush({ ...ON, enabled: false });
      expect(ai.enabled()).toBe(false);
      ai.refresh();
      http.expectOne('/api/ai/status').flush(ON);
      expect(ai.enabled()).toBe(true);
      TestBed.inject(ConfigService).clear();
      ai.refresh();
      http.expectNone('/api/ai/status');
      expect(ai.serverEnabled()).toBe(false);
      // The switch is on and there is no server: what is missing is the person's own key.
      expect(ai.offReason()).toBe('noKey');
    });

    it('with the browser switch off, a server that offers AI is asked for its status only: nothing is posted', () => {
      const ai = create(true, false);
      http.expectOne('/api/ai/status').flush(ON);
      expect(ai.serverEnabled()).toBe(true);
      expect(ai.enabled()).toBe(false);
      expect(ai.offReason()).toBe('optIn');
      http.expectNone((r) => r.method !== 'GET');
    });

    it('the calls are cold: nothing is sent until the page subscribes, and one subscription sends one request', () => {
      const ai = ready();
      const asked = ai.ask('Quiet?');
      ai.extractListing('2BHK');
      ai.planVisits(PLAN_REQUEST);
      http.expectNone(() => true);
      asked.subscribe();
      http.expectOne('/api/ai/ask').flush(ASK);
    });
  });

  describe('the three calls refuse while AI is off (S4b-BL-251): nothing is sent, the Observable errors', () => {
    const calls: [string, (ai: AiService) => Observable<unknown>][] = [
      ['extractListing', (ai) => ai.extractListing('2BHK, call 98450 12345')],
      ['ask', (ai) => ai.ask('Quiet?', { maxPrice: 20000 })],
      ['planVisits', (ai) => ai.planVisits(PLAN_REQUEST)],
    ];

    async function refusal(ai: AiService, call: (ai: AiService) => Observable<unknown>): Promise<unknown> {
      return firstValueFrom(call(ai)).then(
        () => null,
        (err: unknown) => err,
      );
    }

    for (const [name, call] of calls) {
      it(`${name}: off by this browser's switch`, async () => {
        const ai = create(true, false);
        http.expectOne('/api/ai/status').flush(ON);
        const e = await refusal(ai, call);
        expect(e).toBeInstanceOf(AiOffError);
        expect((e as AiOffError).reason).toBe('optIn');
        expect(aiErrorMsg(e)).toEqual({ key: 'ai.optInNeeded' });
        http.expectNone(() => true);
      });

      it(`${name}: off for no key (switch on, no server, no own key)`, async () => {
        const ai = create(false, true);
        const e = await refusal(ai, call);
        expect((e as AiOffError).reason).toBe('noKey');
        expect(aiErrorMsg(e)).toEqual({ key: 'ai.noKey' });
        http.expectNone(() => true);
      });

      it(`${name}: off for no server (switch off, no server, no own key)`, async () => {
        const ai = create(false, false);
        const e = await refusal(ai, call);
        expect((e as AiOffError).reason).toBe('noServer');
        expect(aiErrorMsg(e)).toEqual({ key: 'ai.noServer' });
        http.expectNone(() => true);
      });

      it(`${name}: off on the server, and before its status has come back`, async () => {
        const ai = create(true);
        expect(((await refusal(ai, call)) as AiOffError).reason).toBe('server');
        http.expectOne('/api/ai/status').flush({ ...ON, enabled: false });
        expect(((await refusal(ai, call)) as AiOffError).reason).toBe('server');
        http.expectNone(() => true);
      });

      it(`${name}: off for this device`, async () => {
        const ai = create(true);
        http.expectOne('/api/ai/status').flush({ ...ON, offForDevice: true });
        const e = await refusal(ai, call);
        expect((e as AiOffError).reason).toBe('device');
        expect(aiErrorMsg(e)).toEqual({ key: 'ai.offForDevice' });
        http.expectNone(() => true);
      });

      it(`${name}: on, it sends`, () => {
        const ai = ready();
        call(ai).subscribe();
        const sent = http.expectOne((r) => r.url.startsWith('/api/ai/') && r.method === 'POST');
        sent.flush({});
      });
    }

    it('the setup tests are not guarded: a key can be tested while AI is off', async () => {
      const onDevice = { test: vi.fn(async () => undefined) };
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), { provide: OnDeviceAiService, useValue: onDevice }],
      });
      const ai = create(false, false);
      expect(ai.enabled()).toBe(false);
      await ai.testGeminiKey(' AIzaTestKey1234 ');
      await ai.testProvider({ kind: 'gemini', baseUrl: '', model: '' }, 'AIzaTestKey1234');
      expect(onDevice.test).toHaveBeenCalledTimes(2);
    });
  });

  describe('request shapes', () => {
    it('extractListing posts { text } to /api/ai/extract-listing, as typed, and returns the draft', async () => {
      const ai = ready();
      const text = '2BHK in Indiranagar, 25,000 a month. Call Ravi 98450 12345';
      const result = firstValueFrom(ai.extractListing(text));
      const req = http.expectOne('/api/ai/extract-listing');
      expect(req.request.method).toBe('POST');
      // The service sends the text as it is; leaving the contact out of what reaches the model is the server's job (and
      // the on-device core's, ai-core.spec.ts), so this pins that the service neither drops nor rewrites the text.
      expect(req.request.body).toEqual({ text });
      req.flush(DRAFT);
      expect(await result).toEqual(DRAFT);
    });

    it('extractListing with a kind (kinds PR 7) posts { text, kind }; without one the body is the text alone', async () => {
      const ai = ready();
      const result = firstValueFrom(ai.extractListing('Pine Hill School, CBSE', 'school'));
      const req = http.expectOne('/api/ai/extract-listing');
      expect(req.request.body).toEqual({ text: 'Pine Hill School, CBSE', kind: 'school' });
      req.flush(DRAFT);
      expect(await result).toEqual(DRAFT);
      ai.extractListing('2BHK').subscribe();
      const house = http.expectOne('/api/ai/extract-listing');
      expect(Object.keys(house.request.body)).toEqual(['text']);
      house.flush(DRAFT);
    });

    it('ask without filters posts { question } alone, with no filters key', async () => {
      const ai = ready();
      const result = firstValueFrom(ai.ask('Which is quietest?'));
      const req = http.expectOne('/api/ai/ask');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ question: 'Which is quietest?' });
      expect('filters' in req.request.body).toBe(false);
      req.flush(ASK);
      expect(await result).toEqual(ASK);
    });

    it('ask with filters posts { question, filters } with the filters unchanged', () => {
      const ai = ready();
      const filters = { status: 'SHORTLISTED', priceType: 'RENT', maxPrice: 30000, minBedrooms: 2, minRating: 4 } as const;
      ai.ask('Which is quietest?', filters).subscribe();
      const req = http.expectOne('/api/ai/ask');
      expect(req.request.body).toEqual({ question: 'Which is quietest?', filters });
      req.flush(ASK);
    });

    it('planVisits posts the whole request to /api/ai/plan-visits and returns the plan', async () => {
      const ai = ready();
      const result = firstValueFrom(ai.planVisits(PLAN_REQUEST));
      const req = http.expectOne('/api/ai/plan-visits');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual(PLAN_REQUEST);
      req.flush(PLAN);
      expect(await result).toEqual(PLAN);
    });

    it('the limits the pages count against are the server defaults', () => {
      expect(AI_MAX_LISTING_CHARS).toBe(8000);
      expect(AI_MAX_QUESTION_CHARS).toBe(1000);
    });
  });

  describe('a server failure reaches the page as the error it can map', () => {
    /** The error a call ends with when the server answers [status]. */
    async function failure(ai: AiService, status: number, body: string | object | null = { status }, headers?: Record<string, string>): Promise<unknown> {
      const outcome = firstValueFrom(ai.ask('Quiet?')).then(
        () => null,
        (e: unknown) => e,
      );
      http.expectOne('/api/ai/ask').flush(body, { status, statusText: 'x', headers });
      const e = await outcome;
      expect(e).toBeInstanceOf(HttpErrorResponse);
      return e;
    }

    it('401 and a plain 403 say the key is not accepted', async () => {
      const ai = ready();
      expect(aiErrorMsg(await failure(ai, 401))).toEqual({ key: 'error.auth' });
      expect(aiErrorMsg(new HttpErrorResponse({ status: 403, error: { status: 403, code: 'OTHER' } }))).toEqual({ key: 'error.auth' });
      expect(aiErrorMsg(new HttpErrorResponse({ status: 403, error: null }))).toEqual({ key: 'error.auth' });
    });

    it('402 is a spent quota or credit on the server (S4b-BL-244), whatever its body, in the words of the own key\'s', async () => {
      const ai = ready();
      expect(aiErrorMsg(await failure(ai, 402, 'Payment Required'))).toEqual({ key: 'ai.quotaExhausted' });
      expect(aiErrorMsg(await failure(ai, 402, { status: 402, detail: 'Quota used up', code: 'AI_QUOTA_EXHAUSTED' }))).toEqual({
        key: 'ai.quotaExhausted',
      });
    });

    it('429 says to wait the seconds of Retry-After, 60 when the header is missing or not a number', async () => {
      const ai = ready();
      expect(aiErrorMsg(await failure(ai, 429, {}, { 'Retry-After': '17' }))).toEqual({ key: 'ai.rateLimited', params: { s: 17 } });
      expect(aiErrorMsg(await failure(ai, 429))).toEqual({ key: 'ai.rateLimited', params: { s: 60 } });
      expect(aiErrorMsg(await failure(ai, 429, {}, { 'Retry-After': 'soon' }))).toEqual({ key: 'ai.rateLimited', params: { s: 60 } });
      expect(aiErrorMsg(await failure(ai, 429, {}, { 'Retry-After': '0' }))).toEqual({ key: 'ai.rateLimited', params: { s: 0 } });
      // An HTTP-date (RFC 9110) is read as the seconds until then, as everywhere else (retryAfterSeconds).
      vi.useFakeTimers({ toFake: ['Date'] });
      vi.setSystemTime(new Date('2026-10-13T10:00:00Z'));
      try {
        const date = await failure(ai, 429, {}, { 'Retry-After': 'Tue, 13 Oct 2026 10:00:30 GMT' });
        expect(aiErrorMsg(date)).toEqual({ key: 'ai.rateLimited', params: { s: 30 } });
      } finally {
        vi.useRealTimers();
      }
    });

    it('503 says the AI service is down, whatever the body (AI_UNAVAILABLE or none)', async () => {
      const ai = ready();
      const down = { key: 'ai.providerDown' };
      expect(aiErrorMsg(await failure(ai, 503, { status: 503, code: 'AI_UNAVAILABLE', detail: 'Provider quota' }))).toEqual(down);
      expect(aiErrorMsg(await failure(ai, 503, '<html>Bad gateway</html>'))).toEqual(down);
      expect(aiErrorMsg(await failure(ai, 503, null))).toEqual(down);
    });

    it('404 (an older server, or AI not built in) says AI is not offered', async () => {
      const ai = ready();
      expect(aiErrorMsg(await failure(ai, 404))).toEqual({ key: 'ai.disabled' });
    });

    it('403 with the owner’s codes says AI is off for this device, or paused', () => {
      const forbidden = (code: unknown) => new HttpErrorResponse({ status: 403, error: { status: 403, code } });
      expect(aiErrorMsg(forbidden('AI_OFF_FOR_DEVICE'))).toEqual({ key: 'ai.offForDevice' });
      expect(aiErrorMsg(forbidden('AI_PAUSED'))).toEqual({ key: 'ai.disabled' });
      expect(aiErrorMsg(forbidden(7))).toEqual({ key: 'error.auth' });
    });

    it('any other server error uses the general words (500 is "server", 400 shows the problem detail)', async () => {
      const ai = ready();
      expect(aiErrorMsg(await failure(ai, 500))).toEqual({ key: 'error.server' });
      expect(aiErrorMsg(await failure(ai, 400, { status: 400, title: 'Question too long' }))).toEqual({
        key: 'error.detail',
        params: { detail: 'Question too long' },
      });
    });

    it('no answer at all (network failure) says the server cannot be reached, or that the device is offline', async () => {
      const ai = ready();
      const outcome = firstValueFrom(ai.planVisits(PLAN_REQUEST)).then(
        () => null,
        (e: unknown) => e,
      );
      http.expectOne('/api/ai/plan-visits').error(new ProgressEvent('error'));
      const e = await outcome;
      expect(e).toBeInstanceOf(HttpErrorResponse);
      expect((e as HttpErrorResponse).status).toBe(0);
      expect(aiErrorMsg(e)).toEqual({ key: 'error.network' });
      vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
      expect(aiErrorMsg(e)).toEqual({ key: 'error.offline' });
    });

    it('a timeout that the browser reports as a failed request reads like a network failure', () => {
      const timeout = new HttpErrorResponse({ status: 0, error: new DOMException('signal timed out', 'TimeoutError') });
      expect(aiErrorMsg(timeout)).toEqual({ key: 'error.network' });
    });

    it('a cancelled call (the page unsubscribes) cancels its request and leaves nothing pending', () => {
      const ai = ready();
      const sub = ai.ask('Quiet?').subscribe();
      const req = http.expectOne('/api/ai/ask');
      sub.unsubscribe();
      expect(req.cancelled).toBe(true);
    });
  });

  describe('the own AI is never sent to the server', () => {
    const fake = () => ({
      extractListing: vi.fn(async () => DRAFT),
      ask: vi.fn(async () => ASK),
      planVisits: vi.fn(async () => PLAN),
    });

    function ownKey(onDevice: ReturnType<typeof fake>): AiService {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({
        providers: [provideHttpClient(), provideHttpClientTesting(), { provide: OnDeviceAiService, useValue: onDevice }],
      });
      const ai = create(true);
      http.expectOne('/api/ai/status').flush(ON);
      ai.saveGeminiKey('AIzaTestKey1234', false);
      expect(ai.usesOwnKey()).toBe(true);
      return ai;
    }

    it('extract and plan go to this browser with the key and the same arguments, and no /api/ai request is made', async () => {
      const onDevice = fake();
      const ai = ownKey(onDevice);
      expect(await firstValueFrom(ai.extractListing('2BHK, call 98450 12345'))).toEqual(DRAFT);
      expect(onDevice.extractListing).toHaveBeenCalledWith('AIzaTestKey1234', '2BHK, call 98450 12345');
      expect(await firstValueFrom(ai.planVisits(PLAN_REQUEST))).toEqual(PLAN);
      expect(onDevice.planVisits).toHaveBeenCalledWith('AIzaTestKey1234', PLAN_REQUEST);
      http.expectNone(() => true);
    });

    it('ask passes its filters on, and nothing runs until the page subscribes', async () => {
      const onDevice = fake();
      const ai = ownKey(onDevice);
      const filters = { maxPrice: 20000 };
      const asked = ai.ask('Cheap?', filters);
      expect(onDevice.ask).not.toHaveBeenCalled();
      await firstValueFrom(asked);
      expect(onDevice.ask).toHaveBeenCalledWith('AIzaTestKey1234', 'Cheap?', filters);
      http.expectNone(() => true);
    });

    it('a failure of the own AI reaches the page as an OnDeviceAiError with its own words, a timeout among them', async () => {
      const onDevice = fake();
      onDevice.ask.mockRejectedValueOnce(new OnDeviceAiError('unavailable'));
      const ai = ownKey(onDevice);
      const e = await firstValueFrom(ai.ask('Quiet?')).then(
        () => null,
        (err: unknown) => err,
      );
      expect(e).toBeInstanceOf(OnDeviceAiError);
      // A request that ran out of time is raised as `unavailable` (gemini-timeout.spec.ts), so it reads as the AI being down.
      expect(aiErrorMsg(e)).toEqual({ key: 'ai.providerDown' });
    });

    it('with no server and no key, AI is off and says there is no server (once the switch is on, that the key is missing)', () => {
      TestBed.resetTestingModule();
      TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
      const ai = create(false, false);
      expect(ai.usesOwnKey()).toBe(false);
      expect(ai.enabled()).toBe(false);
      expect(aiOffMsg(ai.offReason()!)).toEqual({ key: 'ai.noServer' });
      http.expectNone(() => true);
    });
  });

  describe('aiErrorMsg for the own AI', () => {
    it('has words for each kind: wait, key, model, safety, quota, unavailable', () => {
      expect(aiErrorMsg(new OnDeviceAiError('rateLimited', 30))).toEqual({ key: 'ai.rateLimited', params: { s: 30 } });
      expect(aiErrorMsg(new OnDeviceAiError('rateLimited'))).toEqual({ key: 'ai.rateLimited', params: { s: 60 } });
      expect(aiErrorMsg(new OnDeviceAiError('keyRejected'))).toEqual({ key: 'ai.keyRejected' });
      expect(aiErrorMsg(new OnDeviceAiError('modelNotFound'))).toEqual({ key: 'ai.modelNotFound' });
      expect(aiErrorMsg(new OnDeviceAiError('blocked'))).toEqual({ key: 'ai.blocked' });
      expect(aiErrorMsg(new OnDeviceAiError('quotaExhausted'))).toEqual({ key: 'ai.quotaExhausted' });
      expect(aiErrorMsg(new OnDeviceAiError('unavailable'))).toEqual({ key: 'ai.providerDown' });
    });

    it('"unreachable" with no host known (Gemini saved) is the AI being down, not a host', () => {
      expect(aiErrorMsg(new OnDeviceAiError('unreachable'))).toEqual({ key: 'ai.providerDown' });
    });

    it('a rejected key names the saved host of an OpenAI-compatible service or Anthropic, not Google', () => {
      localStorage.setItem(AI_KIND_KEY, 'anthropic');
      localStorage.setItem(AI_BASE_URL_KEY, 'https://api.anthropic.com/v1');
      localStorage.setItem(AI_MODEL_KEY, 'claude-haiku-4-5');
      expect(aiErrorMsg(new OnDeviceAiError('keyRejected'))).toEqual({ key: 'ai.keyRejectedHost', params: { host: 'api.anthropic.com' } });
      expect(aiErrorMsg(new OnDeviceAiError('unreachable'))).toEqual({ key: 'ai.unreachable', params: { host: 'api.anthropic.com' } });
    });

    it('an error that is neither goes to the general mapping', () => {
      expect(aiErrorMsg(new Error('boom'))).toEqual({ key: 'error.detail', params: { detail: 'boom' } });
      expect(aiErrorMsg(null)).toEqual({ key: 'error.unknown' });
      expect(aiErrorMsg('text')).toEqual({ key: 'error.unknown' });
    });

    it('a 429 reads Retry-After from the headers of a real HttpErrorResponse', () => {
      const e = new HttpErrorResponse({ status: 429, headers: new HttpHeaders({ 'Retry-After': '9' }) });
      expect(aiErrorMsg(e)).toEqual({ key: 'ai.rateLimited', params: { s: 9 } });
    });
  });

  describe('splitCitations', () => {
    it('cuts an answer at each [house:id] marker, in order, keeping the text between', () => {
      expect(splitCitations(`Try [house:${HOUSE}] first, then [house:${OTHER}].`)).toEqual([
        { kind: 'text', text: 'Try ' },
        { kind: 'cite', houseId: HOUSE },
        { kind: 'text', text: ' first, then ' },
        { kind: 'cite', houseId: OTHER },
        { kind: 'text', text: '.' },
      ]);
    });

    it('an answer with no marker is one text part, an empty answer none, and a marker alone one cite', () => {
      expect(splitCitations('No houses match.')).toEqual([{ kind: 'text', text: 'No houses match.' }]);
      expect(splitCitations('')).toEqual([]);
      expect(splitCitations(`[house:${HOUSE}]`)).toEqual([{ kind: 'cite', houseId: HOUSE }]);
    });

    it('two markers in a row make two cites with no empty text between', () => {
      expect(splitCitations(`[house:${HOUSE}][house:${OTHER}]`)).toEqual([
        { kind: 'cite', houseId: HOUSE },
        { kind: 'cite', houseId: OTHER },
      ]);
    });

    it('only a full 36-character id is a marker: short ids, other prefixes and bad characters stay text', () => {
      for (const s of ['[house:123]', `[houses:${HOUSE}]`, `[house: ${HOUSE}]`, `[house:${HOUSE.slice(1)}]`, `[house:${'g'.repeat(36)}]`]) {
        expect(splitCitations(s)).toEqual([{ kind: 'text', text: s }]);
      }
    });
  });
});
