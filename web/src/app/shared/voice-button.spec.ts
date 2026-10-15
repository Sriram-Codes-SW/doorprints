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

import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AiService } from '../core/ai.service';
import type { AiProviderConfig } from '../core/ai/ai-provider-config';
import { MAX_AUDIO_MS } from '../core/ai/transcriber';
import { FakeRecorder, fakeMic } from '../core/voice/testing/voice-fakes';
import { RECORDER_ENV, VoiceInputService } from '../core/voice/voice-input.service';
import { TranslationService } from '../i18n/translation.service';
import { VoiceButton } from './voice-button';

/**
 * TC-U-245 (docs/06): the microphone button (voice PR 4, S4b-BL-221, ADR-37) through a host box, with a fake microphone and a
 * stubbed provider. The states and their words, the transcript landing in the box and nothing being sent on its own, the
 * stop / cancel / error / limit / page-hide / destroy paths, focus, and that with the setting off nothing is drawn and the
 * microphone is never asked for.
 */
@Component({
  imports: [VoiceButton],
  template: `
    <textarea id="box" [value]="text"></textarea>
    <app-voice-button fieldId="box" [value]="text" [max]="max" kind="house" (valueChange)="onChange($event)" />
  `,
})
class Host {
  text = '';
  max: number | null = null;
  changes: string[] = [];
  onChange(v: string): void {
    this.text = v;
    this.changes.push(v);
  }
}

const GEMINI: AiProviderConfig = { kind: 'gemini', baseUrl: '', model: '' };
const answer = (text: string) =>
  new Response(JSON.stringify({ candidates: [{ content: { parts: [{ text: JSON.stringify({ text, language: 'en-IN' }) }] } }] }));

interface Deferred {
  resolve: (r: Response) => void;
  reject: (e: unknown) => void;
}

function setup(opts: { on?: boolean; fetchImpl?: (() => Promise<Response>) | 'hold'; mic?: Parameters<typeof fakeMic>[0] } = {}) {
  const mic = fakeMic(opts.mic ?? 'ok');
  const ai = { enabled: signal(true), usesOwnKey: signal(true), aiConfig: signal(GEMINI), ownKey: () => 'k-not-real', ask: vi.fn(), extractListing: vi.fn(), planVisits: vi.fn() };
  const pending: Deferred[] = [];
  const fetchSpy = vi.fn(() => {
    if (opts.fetchImpl === 'hold') return new Promise<Response>((resolve, reject) => pending.push({ resolve, reject }));
    return (opts.fetchImpl ?? (() => Promise.resolve(answer('is the cheapest one near the metro'))))();
  });
  vi.stubGlobal('fetch', fetchSpy);
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({ providers: [{ provide: AiService, useValue: ai }, { provide: RECORDER_ENV, useValue: mic.env }] });
  TestBed.inject(VoiceInputService).setEnabled(opts.on ?? true);
  const fixture = TestBed.createComponent(Host);
  fixture.detectChanges();
  return { fixture, mic, ai, fetchSpy, pending, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
  for (let i = 0; i < 8; i++) await new Promise((r) => setTimeout(r, 0));
  fixture.detectChanges();
}

const mic = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('#box-mic');
const cancelBtn = (el: HTMLElement) => el.querySelector<HTMLButtonElement>('#box-mic-cancel');
const status = (el: HTMLElement) => el.querySelector('[role="status"]')?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const alertText = (el: HTMLElement) => el.querySelector('[role="alert"]')?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('VoiceButton', () => {
  beforeEach(() => {
    localStorage.clear();
    FakeRecorder.reset();
  });
  afterEach(async () => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    await TestBed.inject(TranslationService).setLang('en');
    localStorage.clear();
  });

  it('with the setting off draws nothing and never asks for the microphone', async () => {
    const { fixture, el, mic: fake } = setup({ on: false });
    await settle(fixture);
    expect(mic(el)).toBeNull();
    expect(el.querySelector('app-voice-button')?.children.length).toBe(0);
    expect(fake.asked()).toBe(0);
  });

  it('is a labelled, keyboard-reachable button whose name holds its visible word, with the disclosure as its description', () => {
    const { el } = setup();
    const b = mic(el)!;
    expect(b.tagName).toBe('BUTTON');
    expect(b.type).toBe('button');
    expect(b.getAttribute('tabindex')).toBeNull();
    expect(b.getAttribute('aria-label')).toBe('Speak instead of typing');
    expect(b.textContent).toContain('Speak');
    const hint = el.querySelector('#' + b.getAttribute('aria-describedby'))!;
    expect(hint.textContent).toContain('generativelanguage.googleapis.com');
    expect(hint.textContent).toContain('not saved');
    expect(el.querySelector('[role="status"]')?.getAttribute('aria-live')).toBe('polite');
  });

  it('one tap asks for the microphone and records; the same button then says Stop, with Cancel beside it', async () => {
    const { fixture, el, mic: fake } = setup();
    expect(fake.asked()).toBe(0);
    mic(el)!.click();
    await settle(fixture);
    expect(fake.asked()).toBe(1);
    expect(status(el)).toBe('Recording. Speak now, then press Stop.');
    expect(mic(el)!.textContent).toContain('Stop');
    expect(mic(el)!.getAttribute('aria-label')).toBe('Stop recording and write out the words');
    expect(cancelBtn(el)).not.toBeNull();
    expect(el.querySelector('.voice-time')?.textContent).toBe('0:00');
  });

  it('stop sends the one clip, then the words land after what is in the box, the box gets the focus, and nothing is sent on', async () => {
    const { fixture, host, el, ai, fetchSpy, mic: fake } = setup();
    host.text = 'Which flat';
    document.body.appendChild(el);
    fixture.detectChanges();
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    expect(host.changes).toEqual(['Which flat is the cheapest one near the metro']);
    expect(status(el)).toBe('Done. The words are in the box. Check them before you send.');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(document.activeElement?.id).toBe('box');
    expect(ai.ask).not.toHaveBeenCalled();
    expect(ai.extractListing).not.toHaveBeenCalled();
    expect(ai.planVisits).not.toHaveBeenCalled();
    expect(fake.streams[0].released).toBe(true);
    expect(mic(el)!.textContent).toContain('Speak');
    el.remove();
  });

  it('while the words are being written out the button says so, is not pressable twice, and Cancel is there', async () => {
    const { fixture, el, fetchSpy, mic: fake } = setup({ fetchImpl: 'hold' });
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    expect(status(el)).toBe('Writing out the words…');
    expect(mic(el)!.getAttribute('aria-disabled')).toBe('true');
    mic(el)!.click();
    await settle(fixture);
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(fake.asked()).toBe(1);
    expect(cancelBtn(el)).not.toBeNull();
  });

  it('Cancel while recording keeps nothing, sends nothing, lets the microphone go and puts focus back on the button', async () => {
    const { fixture, el, host, fetchSpy, mic: fake } = setup();
    document.body.appendChild(el);
    mic(el)!.click();
    await settle(fixture);
    cancelBtn(el)!.click();
    await settle(fixture);
    expect(fake.streams[0].released).toBe(true);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(host.changes).toEqual([]);
    expect(status(el)).toBe('Cancelled. Nothing was kept.');
    expect(document.activeElement).toBe(mic(el));
    el.remove();
  });

  it('Cancel while the words are being written out drops the late answer: nothing lands in the box', async () => {
    const { fixture, el, host, pending } = setup({ fetchImpl: 'hold' });
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    cancelBtn(el)!.click();
    await settle(fixture);
    pending[0].resolve(answer('too late to matter'));
    await settle(fixture);
    expect(host.changes).toEqual([]);
    expect(status(el)).toBe('Cancelled. Nothing was kept.');
  });

  it('Escape cancels a recording', async () => {
    const { fixture, el, mic: fake } = setup();
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await settle(fixture);
    expect(fake.streams[0].released).toBe(true);
    expect(status(el)).toBe('Cancelled. Nothing was kept.');
  });

  it('a refused microphone says so in an alert, offers typing, and leaves the button usable', async () => {
    const { fixture, el, fetchSpy } = setup({ mic: 'NotAllowedError' });
    document.body.appendChild(el);
    mic(el)!.click();
    await settle(fixture);
    expect(alertText(el)).toContain('cannot use the microphone');
    expect(alertText(el)).toContain('type');
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(mic(el));
    expect(mic(el)!.textContent).toContain('Speak');
    el.remove();
  });

  it('no microphone is told apart from a refused one', async () => {
    const { fixture, el } = setup({ mic: 'NotFoundError' });
    mic(el)!.click();
    await settle(fixture);
    expect(alertText(el)).toBe('No microphone was found. Type instead.');
  });

  it('nothing heard says so and changes nothing in the box', async () => {
    const { fixture, el, host } = setup({ fetchImpl: () => Promise.resolve(answer('   ')) });
    host.text = 'keep me';
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    expect(alertText(el)).toContain('Nothing was heard');
    expect(host.changes).toEqual([]);
    expect(host.text).toBe('keep me');
  });

  it("the provider's own failure is worded like the other AI failures, with the box untouched", async () => {
    const { fixture, el, host } = setup({ fetchImpl: () => Promise.resolve(new Response('{}', { status: 429, headers: { 'Retry-After': '30' } })) });
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    expect(alertText(el)).toContain('30');
    expect(host.changes).toEqual([]);
  });

  it('the one-minute limit ends the recording by itself, writes out what was said and tells the person', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'setInterval', 'clearTimeout', 'clearInterval', 'Date'] });
    const { fixture, el, host } = setup();
    mic(el)!.click();
    await vi.advanceTimersByTimeAsync(10);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(MAX_AUDIO_MS);
    await vi.advanceTimersByTimeAsync(10);
    fixture.detectChanges();
    expect(host.changes).toHaveLength(1);
    expect(status(el)).toContain('stopped at its one-minute limit');
  });

  it('words that do not fit the box are cut and the status says so', async () => {
    const { fixture, el, host } = setup();
    host.max = 20;
    host.text = 'Which flat';
    fixture.detectChanges();
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    expect(host.text.length).toBeLessThanOrEqual(20);
    expect(status(el)).toContain('did not fit');
  });

  it('the tab being hidden mid-recording cancels it; nothing is sent', async () => {
    const { fixture, el, mic: fake, fetchSpy } = setup();
    mic(el)!.click();
    await settle(fixture);
    window.dispatchEvent(new Event('pagehide'));
    await settle(fixture);
    expect(fake.streams[0].released).toBe(true);
    expect(status(el)).toBe('Cancelled. Nothing was kept.');
    expect(mic(el)!.textContent).toContain('Speak');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('leaving the page (the component destroyed) mid-recording lets the microphone go and sends nothing', async () => {
    const { fixture, el, mic: fake, fetchSpy } = setup();
    mic(el)!.click();
    await settle(fixture);
    fixture.destroy();
    expect(fake.streams[0].released).toBe(true);
    expect(FakeRecorder.last.state).toBe('inactive');
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('leaving the page while the words are written out drops the answer', async () => {
    const { fixture, el, host, pending } = setup({ fetchImpl: 'hold' });
    mic(el)!.click();
    await settle(fixture);
    mic(el)!.click();
    await settle(fixture);
    fixture.destroy();
    pending[0].resolve(answer('late'));
    await new Promise((r) => setTimeout(r, 0));
    expect(host.changes).toEqual([]);
  });

  it('turning the setting off mid-recording ends it and removes the button', async () => {
    const { fixture, el, mic: fake } = setup();
    mic(el)!.click();
    await settle(fixture);
    TestBed.inject(VoiceInputService).setEnabled(false);
    await settle(fixture);
    expect(fake.streams[0].released).toBe(true);
    expect(mic(el)).toBeNull();
  });

  it('a permission prompt answered after Cancel lets the stream go and records nothing', async () => {
    const { fixture, el, mic: fake } = setup({ mic: 'wait' });
    mic(el)!.click();
    await settle(fixture);
    fixture.destroy();
    fake.gate();
    await new Promise((r) => setTimeout(r, 0));
    expect(fake.streams[0].released).toBe(true);
    expect(FakeRecorder.instances).toHaveLength(0);
    void el;
  });

  it('speaks the person\'s language: Hindi labels, and the English ones return', async () => {
    const { fixture, el } = setup();
    await TestBed.inject(TranslationService).setLang('hi');
    await settle(fixture);
    expect(mic(el)!.getAttribute('aria-label')).toBe('टाइप करने की जगह बोलें');
    expect(mic(el)!.textContent).toContain('बोलें');
  });
});
