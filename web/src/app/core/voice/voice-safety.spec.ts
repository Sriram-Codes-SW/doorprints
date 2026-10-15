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

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../ai.service';
import { MAX_AUDIO_BYTES, MAX_AUDIO_MS } from '../ai/transcriber';
import { VoiceButton } from '../../shared/voice-button';
import { TranslationService } from '../../i18n/translation.service';
import { VOICE_INPUT_KEY } from '../storage-keys';
import { AudioRecorder } from './audio-recorder';
import { FAKE_AUDIO, FAKE_AUDIO_B64, FakeRecorder, fakeMic } from './testing/voice-fakes';
import { RECORDER_ENV, VoiceInputService } from './voice-input.service';

/**
 * TC-S-51 (docs/06, with TC-S-23 for the headers), the safety proof of voice input (S4b-BL-221, ADR-37, threat-model rows T-I45 to T-I48, T-D14): the audio
 * and the words are held in memory for one call and reach nothing else. A whole spoken turn is run through the real
 * component, service, recorder and transcriber with a fake microphone and a stubbed `fetch`, while every place the audio
 * could leak is watched; the exit paths are driven one by one; and the source is read for the ways a leak could be added.
 */
@Component({
  imports: [VoiceButton],
  template: `<textarea id="box"></textarea><app-voice-button fieldId="box" [value]="text" (valueChange)="text = $event" />`,
})
class Host {
  text = '';
}

const SPOKEN = 'call me on nine eight four five zero one two three four five about the Beltola flat';
const providerAnswer = () =>
  new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: JSON.stringify({ text: SPOKEN, language: 'en-IN' }) }] } }] }));

/** Everything that must never hold the audio, the base64 of it, a blob address of it or the words. */
const SECRETS = [FAKE_AUDIO, FAKE_AUDIO_B64, SPOKEN, 'blob:'];
const holdsSecret = (value: unknown): boolean => SECRETS.some((s) => String(value).includes(s));

function mount(setting = true) {
  const mic = fakeMic();
  const ai = { enabled: signal(true), usesOwnKey: signal(true), aiConfig: signal({ kind: 'gemini', baseUrl: '', model: '' }), ownKey: () => 'k-not-real-9999' };
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: AiService, useValue: ai }, { provide: RECORDER_ENV, useValue: mic.env }] });
  if (setting) TestBed.inject(VoiceInputService).setEnabled(true);
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  const el = fixture.nativeElement as HTMLElement;
  return { fixture, el, mic, ai, button: () => el.querySelector<HTMLButtonElement>('#box-mic') };
}
const settle = async (fixture: { detectChanges(): void }) => {
  for (let i = 0; i < 8; i++) await new Promise((r) => setTimeout(r, 0));
  fixture.detectChanges();
};

describe('voice input safety proof (T-I48)', () => {
  const cleanup: (() => void)[] = [];
  beforeEach(async () => {
    localStorage.clear();
    sessionStorage.clear();
    FakeRecorder.reset();
    await TestBed.inject(TranslationService).setLang('en');
  });
  afterEach(() => {
    for (const undo of cleanup.splice(0)) undo();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('T-I48: a whole spoken turn leaves no audio, base64, blob address or transcript in storage, IndexedDB, the cache, the console or a beacon', async () => {
    const { fixture, el, button } = mount();
    const calls: { url: string; init: RequestInit }[] = [];
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      calls.push({ url, init });
      return providerAnswer();
    });
    const idb = vi.fn();
    const cacheApi = vi.fn();
    const beacon = vi.fn();
    vi.stubGlobal('indexedDB', { open: idb, deleteDatabase: idb });
    vi.stubGlobal('caches', { open: cacheApi, match: cacheApi, keys: cacheApi, delete: cacheApi, has: cacheApi });
    Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true });
    cleanup.push(() => { delete (navigator as { sendBeacon?: unknown }).sendBeacon; });
    const blobUrl = vi.fn(() => 'blob:never');
    const realUrl = URL.createObjectURL;
    cleanup.push(() => { (URL as unknown as { createObjectURL: unknown }).createObjectURL = realUrl; });
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = blobUrl;
    const written: [string, string][] = [];
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, k: string, v: string) {
      written.push([k, v]);
    });
    const consoles = (['log', 'info', 'warn', 'error', 'debug', 'trace'] as const).map((m) => vi.spyOn(console, m));

    button()!.click();
    await settle(fixture);
    button()!.click();
    await settle(fixture);
    expect((fixture.componentInstance as Host).text).toBe(SPOKEN);

    // The only network call is the one to the provider, with the audio in its body; the key is in a header, not the address.
    expect(calls).toHaveLength(1);
    expect(calls[0].url.startsWith('https://generativelanguage.googleapis.com/')).toBe(true);
    expect(calls[0].url).not.toContain('k-not-real');
    expect(String(calls[0].init.body)).toContain(FAKE_AUDIO_B64);
    // Nothing else.
    expect(setItem).not.toHaveBeenCalled();
    expect(written).toEqual([]);
    expect(idb).not.toHaveBeenCalled();
    expect(cacheApi).not.toHaveBeenCalled();
    expect(beacon).not.toHaveBeenCalled();
    expect(blobUrl).not.toHaveBeenCalled();
    for (const c of consoles) expect(c).not.toHaveBeenCalled();
    // And no stored value, no attribute and no script-visible global holds the audio or its base64 after the turn.
    for (const store of [localStorage, sessionStorage]) {
      for (let i = 0; i < store.length; i++) expect(holdsSecret(store.getItem(store.key(i)!)), store.key(i)!).toBe(false);
    }
    const attributes = [...el.querySelectorAll('*')].flatMap((n) => [...n.attributes].map((a) => a.value));
    expect(attributes.some((a) => a.includes(FAKE_AUDIO) || a.includes(FAKE_AUDIO_B64) || a.includes('blob:'))).toBe(false);
  });

  it('T-I48: turning the setting on writes only the switch, and no audio exists before the first tap', () => {
    const written: [string, string][] = [];
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (this: Storage, k: string, v: string) {
      written.push([k, v]);
    });
    const { mic } = mount();
    expect(written).toEqual([[VOICE_INPUT_KEY, '1']]);
    expect(mic.asked()).toBe(0);
    expect(FakeRecorder.instances).toHaveLength(0);
  });

  it('T-I47: the setting off means no microphone button and no getUserMedia call, however the page is used', async () => {
    const { fixture, el, mic } = mount(false);
    await settle(fixture);
    expect(el.querySelector('#box-mic')).toBeNull();
    expect(el.querySelector('app-voice-button')?.children.length).toBe(0);
    window.dispatchEvent(new Event('pagehide'));
    expect(mic.asked()).toBe(0);
    expect(FakeRecorder.instances).toHaveLength(0);
  });

  describe('T-I47: the recorder and every track are stopped and released on every exit path', () => {
    const state = (r: FakeRecorder) => r.state;

    it('stop', async () => {
      const { fixture, mic, button } = mount();
      vi.stubGlobal('fetch', async () => providerAnswer());
      button()!.click();
      await settle(fixture);
      button()!.click();
      await settle(fixture);
      expect(mic.streams.every((s) => s.released)).toBe(true);
      expect(state(FakeRecorder.last)).toBe('inactive');
    });

    it('cancel', async () => {
      const { fixture, el, mic, button } = mount();
      button()!.click();
      await settle(fixture);
      el.querySelector<HTMLButtonElement>('#box-mic-cancel')!.click();
      await settle(fixture);
      expect(mic.streams.every((s) => s.released)).toBe(true);
      expect(state(FakeRecorder.last)).toBe('inactive');
    });

    it('error (the recorder fails)', async () => {
      const { fixture, mic, button } = mount();
      button()!.click();
      await settle(fixture);
      FakeRecorder.last.onerror?.({});
      await settle(fixture);
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('error (the provider fails after stop)', async () => {
      const { fixture, mic, button } = mount();
      vi.stubGlobal('fetch', async () => new Response('{}', { status: 503 }));
      button()!.click();
      await settle(fixture);
      button()!.click();
      await settle(fixture);
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('page hide', async () => {
      const { fixture, mic, button } = mount();
      button()!.click();
      await settle(fixture);
      window.dispatchEvent(new Event('pagehide'));
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('tab hidden', async () => {
      const { fixture, mic, button } = mount();
      button()!.click();
      await settle(fixture);
      vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
      document.dispatchEvent(new Event('visibilitychange'));
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('route change (the page is destroyed)', async () => {
      const { fixture, mic, button } = mount();
      button()!.click();
      await settle(fixture);
      fixture.destroy();
      expect(mic.streams.every((s) => s.released)).toBe(true);
      expect(state(FakeRecorder.last)).toBe('inactive');
    });

    it('the setting turned off', async () => {
      const { fixture, mic, button } = mount();
      button()!.click();
      await settle(fixture);
      TestBed.inject(VoiceInputService).setEnabled(false);
      await settle(fixture);
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('the time limit', async () => {
      vi.useFakeTimers();
      const mic = fakeMic();
      const r = new AudioRecorder(mic.env);
      const ended = vi.fn();
      r.onEnded = ended;
      await r.start();
      await vi.advanceTimersByTimeAsync(MAX_AUDIO_MS);
      expect(ended).toHaveBeenCalledTimes(1);
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('the size cap', async () => {
      const mic = fakeMic();
      const r = new AudioRecorder(mic.env);
      const ended = vi.fn();
      r.onEnded = ended;
      await r.start();
      FakeRecorder.last.emit(new Blob([new Uint8Array(MAX_AUDIO_BYTES + 1)]));
      await new Promise((res) => setTimeout(res, 0));
      expect(ended).toHaveBeenCalledTimes(1);
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });

    it('a permission answered after the person left', async () => {
      const mic = fakeMic('wait');
      const r = new AudioRecorder(mic.env);
      const started = r.start();
      r.release();
      mic.gate();
      await expect(started).rejects.toMatchObject({ reason: 'cancelled' });
      expect(mic.streams.every((s) => s.released)).toBe(true);
    });
  });

  it('the provider key path is the existing own-key path: the voice code reads the key only from AiService.ownKey() and keeps no secret', () => {
    const files = voiceSources();
    for (const [file, text] of files) {
      expect(text, file).not.toMatch(/GEMINI_KEY_KEY|apiKey\s*=|sessionStorage|STORAGE_PREFIX|localStorage\.(?:get|set|remove)Item\((?!VOICE_INPUT_KEY)/);
    }
    expect(files.get('core/voice/voice-input.service.ts')).toContain('this.ai.ownKey()');
  });

  it('the voice source cannot store, log, copy or beacon audio: no IndexedDB, Cache, blob address, data URL, console or beacon', () => {
    for (const [file, text] of voiceSources()) {
      expect(text, file).not.toMatch(/indexedDB|caches\.|createObjectURL|readAsDataURL|console\.|sendBeacon|new Audio\(|\.play\(\)|localStorage\.setItem\((?!VOICE_INPUT_KEY)/);
    }
  });

  it('only the setting and the button use the voice code: no backup, export, sync, share or storage module reads it', () => {
    const allowed = new Set([
      'shared/voice-button.ts',
      'core/storage-keys.ts', // defines the switch's key name
      'pages/connect/voice-setting.ts',
      'pages/ask/ask-page.ts', // imports the button component only
      'pages/plan/plan-page.ts',
      'pages/house-detail/house-listing-card.ts',
    ]);
    for (const [file, text] of allSources()) {
      if (file.startsWith('core/voice/')) continue;
      if (/core\/voice\/|VOICE_INPUT_KEY|shared\/voice-button/.test(text)) expect(allowed.has(file), file).toBe(true);
    }
    for (const [file, text] of allSources()) {
      if (/^(export|data|share)\//.test(file) || /sync|backup/i.test(file)) {
        expect(text, file).not.toMatch(/core\/voice|VOICE_INPUT_KEY|voice-input/);
      }
    }
  });

  it('the service worker leaves a POST and any other origin to the browser, so the audio call is never cached', () => {
    const sw = readFileSync('public/sw.js', 'utf8');
    const handler = sw.slice(sw.indexOf("self.addEventListener('fetch'"));
    expect(handler.indexOf("request.method !== 'GET'")).toBeGreaterThan(0);
    expect(handler.indexOf("request.method !== 'GET'")).toBeLessThan(handler.indexOf('respondWith'));
    expect(handler.indexOf('url.origin !== self.location.origin')).toBeLessThan(handler.indexOf('respondWith'));
  });

  it('the app has no analytics or beacon that could carry the audio', () => {
    for (const [file, text] of allSources()) expect(text, file).not.toMatch(/sendBeacon|gtag\(|googletagmanager|plausible\.io|posthog|mixpanel/i);
  });

  it('firebase.json allows the microphone for this origin only, and the camera and location likewise', () => {
    const json = JSON.parse(readFileSync('firebase.json', 'utf8')) as { hosting: { headers: { source: string; headers: { key: string; value: string }[] }[] } };
    const value = json.hosting.headers.find((h) => h.source === '**')!.headers.find((h) => h.key === 'Permissions-Policy')!.value;
    expect(value).toContain('microphone=(self)');
    expect(value).toContain('camera=(self)');
    expect(value).toContain('geolocation=(self)');
    expect(value).not.toContain('*');
    expect(value).toContain('payment=()');
  });
});

const SRC = 'src/app';
function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = `${dir}/${name}`;
    if (statSync(full).isDirectory()) walk(full, out);
    else if ((name.endsWith('.ts') || name.endsWith('.html')) && !name.endsWith('.spec.ts') && !full.includes('/testing/')) out.push(full);
  }
  return out;
}
/** Every product source of the app, by path under `src/app`. */
function allSources(): Map<string, string> {
  return new Map(walk(SRC).map((f) => [f.slice(SRC.length + 1), readFileSync(f, 'utf8')]));
}
/** The voice code: the recorder, the service, the transcript helper, the button and the setting. */
function voiceSources(): Map<string, string> {
  const names = ['core/voice/audio-recorder.ts', 'core/voice/voice-input.service.ts', 'core/voice/transcript-insert.ts', 'shared/voice-button.ts', 'pages/connect/voice-setting.ts'];
  const all = allSources();
  return new Map(names.map((n) => [n, all.get(n) ?? '']));
}
