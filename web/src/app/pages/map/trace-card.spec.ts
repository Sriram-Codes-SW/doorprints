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

import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AlertSound } from '../../core/alert-sound.service';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import { en } from '../../i18n/en';
import { hi } from '../../i18n/hi';
import { ta } from '../../i18n/ta';
import { te } from '../../i18n/te';
import type { Lang } from '../../i18n/languages';
import { TranslationService } from '../../i18n/translation.service';
import { audit } from '../../shared/testing/a11y';
import { FakeRecorder, FakeSound, flush, settle } from '../../shared/testing/trace-fakes';
import { BANNER_MS, TraceCard } from './trace-card';
import { TraceView } from './trace-view';

const DICTS = { en, hi, ta, te } as const;

describe('TraceCard', () => {
  let recorder: FakeRecorder;
  let sound: FakeSound;

  async function render(opts: { lang?: Lang; traceOn?: boolean; wide?: boolean } = {}) {
    TestBed.resetTestingModule();
    recorder = new FakeRecorder();
    sound = new FakeSound();
    vi.stubGlobal('matchMedia', (q: string) => ({ matches: q.includes('min-width') && opts.wide !== false, addEventListener() {}, removeEventListener() {} }));
    TestBed.configureTestingModule({
      providers: [
        { provide: TraceRecorderService, useValue: recorder },
        { provide: AlertSound, useValue: sound },
      ],
    });
    await TestBed.inject(TranslationService).setLang(opts.lang ?? 'en');
    if (opts.traceOn) await TestBed.inject(TraceStore).setTraceOn(true);
    await TestBed.inject(TraceView).open();
    const fixture = TestBed.createComponent(TraceCard);
    await settle(fixture);
    const host = fixture.nativeElement as HTMLElement;
    const $ = <T extends HTMLElement>(sel: string) => host.querySelector<T>(sel);
    return { fixture, host, $, view: TestBed.inject(TraceView), store: TestBed.inject(TraceStore) };
  }

  beforeEach(() => vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'], shouldAdvanceTime: true }));
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    TestBed.resetTestingModule();
    localStorage.clear();
  });

  it('is a details element titled Trace my path, open on a wide screen and one line on a phone', async () => {
    const wide = await render();
    expect(wide.$('summary')!.textContent).toContain('Trace my path');
    expect(wide.$<HTMLDetailsElement>('details')!.open).toBe(true);
    const phone = await render({ wide: false });
    expect(phone.$<HTMLDetailsElement>('details')!.open).toBe(false);
  });

  it('has the trace switch off by default, labelled, with its hint, and no Start button until it is on', async () => {
    const r = await render();
    const sw = r.$<HTMLInputElement>('#trace-on')!;
    expect(sw.checked).toBe(false);
    expect(sw.getAttribute('role')).toBe('switch');
    expect(r.host.querySelector('label[for="trace-on"]')!.textContent).toContain('Trace my path on the map');
    expect(r.$('#trace-start')).toBeNull();
    expect(recorder.starts).toBe(0);
  });

  it('turning the switch on is saved and shows Start a walk with the permission sentence and the only-while-open sentence', async () => {
    const r = await render();
    const sw = r.$<HTMLInputElement>('#trace-on')!;
    sw.checked = true;
    sw.dispatchEvent(new Event('change'));
    await settle(r.fixture);
    expect(await r.store.traceOn()).toBe(true);
    expect(r.$('#trace-start')!.textContent).toContain('Start a walk');
    expect(r.$('#trace-explain')!.textContent).toContain('your browser will ask for your location');
    expect(r.$('#trace-only-open')!.textContent).toContain('Keep this page open while you walk.');
    expect(r.$('#trace-start')!.getAttribute('aria-describedby')).toContain('trace-explain');
  });

  it('never asks for the location until Start a walk is pressed: rendering and switching on call nothing', async () => {
    const r = await render({ traceOn: true });
    expect(recorder.starts).toBe(0);
    r.$<HTMLButtonElement>('#trace-start')!.click();
    await settle(r.fixture);
    expect(recorder.starts).toBe(1);
  });

  it('while recording shows the state, the points kept and Finish walk (not Start), and moves focus to Finish', async () => {
    const r = await render({ traceOn: true });
    r.$<HTMLButtonElement>('#trace-start')!.click();
    recorder.keptCount.set(7);
    await settle(r.fixture);
    expect(r.$('#trace-start')).toBeNull();
    const finish = r.$<HTMLButtonElement>('#trace-finish')!;
    expect(finish.textContent).toContain('Finish walk');
    expect(r.host.textContent).toContain('Recording your walk');
    expect(r.host.textContent).toContain('Points kept: 7');
    expect(document.activeElement === finish || r.host.contains(document.activeElement) || document.activeElement === document.body).toBe(true);
    finish.click();
    await settle(r.fixture);
    expect(recorder.finishes).toBe(1);
    expect(r.$('#trace-start')).not.toBeNull();
  });

  it('says in the summary that a walk is recording, and that it is paused while the page is hidden', async () => {
    const r = await render({ traceOn: true });
    recorder.state.set('recording');
    await settle(r.fixture);
    expect(r.$('#trace-rec')!.textContent).toContain('Recording your walk');
    recorder.state.set('paused');
    await settle(r.fixture);
    expect(r.$('#trace-rec')!.textContent).toContain('Paused: this page is hidden');
  });

  it('announces Recording your walk once through the app live region when a walk starts', async () => {
    const r = await render({ traceOn: true });
    const announce = vi.spyOn(TestBed.inject(Announcer), 'announce');
    recorder.state.set('recording');
    await settle(r.fixture);
    expect(announce.mock.calls.map((c) => c[0].key)).toEqual(['trace.walk.recording']);
  });

  it.each([
    ['unsupported', 'This browser cannot give your location.'],
    ['denied', 'Location is blocked for this site. Allow it in your browser\'s site settings to record a walk.'],
    ['unavailable', 'No position yet. The walk keeps waiting for one.'],
    ['full', 'This browser\'s storage is full, so the walk stopped.'],
    ['storage', 'The walk could not be written to this browser\'s storage, so it stopped.'],
  ] as const)('says the problem %s in words, inside an alert region that exists before it', async (problem, words) => {
    const r = await render({ traceOn: true });
    const region = r.$('.notes')!;
    expect(region.getAttribute('role')).toBe('alert');
    expect(region.textContent).not.toContain(words);
    recorder.problem.set(problem);
    await settle(r.fixture);
    expect(r.$('.notes')!.textContent).toContain(words);
  });

  it('shows Recording paused while this page was hidden on return, with a Dismiss that hides it', async () => {
    const r = await render({ traceOn: true });
    recorder.pausedNotice.set(true);
    await settle(r.fixture);
    expect(r.$('#trace-paused')!.textContent).toContain('Recording paused while this page was hidden.');
    r.$<HTMLButtonElement>('#trace-paused button')!.click();
    await settle(r.fixture);
    expect(recorder.dismissed).toBe(1);
    expect(r.$('#trace-paused')).toBeNull();
  });

  it('warns that a walk lives for this page only when the browser keeps nothing', async () => {
    const r = await render({ traceOn: true });
    r.view.kept.set(true);
    await settle(r.fixture);
    expect(r.$('#trace-not-kept')).toBeNull();
    r.view.kept.set(false);
    await settle(r.fixture);
    expect(r.$('#trace-not-kept')!.textContent).toContain('A walk lives for this page only.');
  });

  describe('the alert banner', () => {
    it('shows when the recorder rings, as an alert, with Dismiss; goes after 10 seconds', async () => {
      const r = await render({ traceOn: true });
      expect(r.$('.banner-slot')!.getAttribute('role')).toBe('alert');
      expect(r.$('#trace-banner')).toBeNull();
      recorder.alertRaised.update((n) => n + 1);
      await flush(r.fixture);
      expect(r.$('#trace-banner')!.textContent).toContain('You have walked this way before.');
      await vi.advanceTimersByTimeAsync(BANNER_MS - 100);
      await flush(r.fixture);
      expect(r.$('#trace-banner')).not.toBeNull();
      await vi.advanceTimersByTimeAsync(200);
      await flush(r.fixture);
      expect(r.$('#trace-banner')).toBeNull();
    });

    it('is dismissed by its button', async () => {
      const r = await render({ traceOn: true });
      recorder.alertRaised.update((n) => n + 1);
      await flush(r.fixture);
      r.$<HTMLButtonElement>('#trace-banner-dismiss')!.click();
      await flush(r.fixture);
      expect(r.$('#trace-banner')).toBeNull();
    });

    it('says the sound is off when the audio is not running, and not when it is', async () => {
      const r = await render({ traceOn: true });
      recorder.alertRaised.update((n) => n + 1);
      await flush(r.fixture);
      expect(r.$('#trace-banner')!.textContent).toContain('Sound is off until you start a walk from this page.');
      sound.running.set(true);
      await flush(r.fixture);
      expect(r.$('#trace-banner')!.textContent).not.toContain('Sound is off');
    });

    it('is not shown for the alerts that rang before the card was made (a walk that goes on across pages)', async () => {
      TestBed.resetTestingModule();
      const r = await render({ traceOn: true });
      expect(r.$('#trace-banner')).toBeNull();
    });
  });

  describe('the settings', () => {
    it('How repeated paths look: three radios in a labelled group, Clear first, the stored one checked, applied and saved on change', async () => {
      const r = await render();
      const radios = [...r.host.querySelectorAll<HTMLInputElement>('input[name="trace-look"]')];
      expect(radios.map((x) => x.value)).toEqual(['CLEAR', 'SUBTLE', 'OFF']);
      expect(radios.map((x) => x.checked)).toEqual([true, false, false]);
      expect(r.$('fieldset legend')!.textContent).toBe('How repeated paths look');
      expect(r.host.textContent).toContain('Thicker and dashed in a second colour');
      expect(r.host.textContent).toContain('This does not change the sound alert.');
      radios[2].click();
      await settle(r.fixture);
      expect(r.view.look()).toBe('OFF');
      expect(await r.store.look()).toBe('OFF');
      expect(radios.map((x) => x.checked)).toEqual([false, false, true]);
    });

    it('shows the stored look when the card opens', async () => {
      TestBed.resetTestingModule();
      const r = await render();
      await r.store.setLook('SUBTLE');
      await r.view.open();
      await settle(r.fixture);
      expect(r.$<HTMLInputElement>('input[value="SUBTLE"]')!.checked).toBe(true);
    });

    it('Warn me when I walk a path again: off by default, disabled with its reason while the trace is off, saved when on', async () => {
      const off = await render();
      const sw = off.$<HTMLInputElement>('#trace-alert')!;
      expect(sw.checked).toBe(false);
      expect(sw.disabled).toBe(true);
      expect(off.$('#trace-alert-hint')!.textContent).toContain('Turn on Trace my path first.');
      const on = await render({ traceOn: true });
      const sw2 = on.$<HTMLInputElement>('#trace-alert')!;
      expect(sw2.disabled).toBe(false);
      expect(sw2.checked).toBe(false);
      expect(on.$('#trace-alert-hint')!.textContent).toContain('Works only while this page is open.');
      sw2.checked = true;
      sw2.dispatchEvent(new Event('change'));
      await settle(on.fixture);
      expect(await on.store.alertOn()).toBe(true);
    });

    it('Keep the screen on is shown only where the browser has a Wake Lock, and says when the browser refused', async () => {
      TestBed.resetTestingModule();
      const r = await render({ traceOn: true });
      expect(r.$('#trace-awake')).not.toBeNull();
      expect(r.$('#trace-awake-hint')!.textContent).toContain('Uses more battery.');
      recorder.keepAwakeFailed.set(true);
      await settle(r.fixture);
      expect(r.$('#trace-awake-failed')!.textContent).toContain('Your browser could not keep the screen on.');
      const none = await render({ traceOn: true });
      none.fixture.destroy();
      recorder.wakeLockSupported = false;
      const again = TestBed.createComponent(TraceCard);
      await settle(again);
      expect((again.nativeElement as HTMLElement).querySelector('#trace-awake')).toBeNull();
    });
  });

  describe('Clear the path', () => {
    it('asks first, with the clear hint; clears only on a yes', async () => {
      const r = await render();
      const confirm = TestBed.inject(ConfirmService);
      const clear = vi.spyOn(r.view, 'clearTrace').mockResolvedValue();
      const ask = vi.spyOn(confirm, 'ask').mockResolvedValueOnce(false).mockResolvedValueOnce(true);
      r.$<HTMLButtonElement>('#trace-clear')!.click();
      await settle(r.fixture);
      expect(ask.mock.calls[0][0]).toEqual({ key: 'trace.card.clearConfirm' });
      expect(clear).not.toHaveBeenCalled();
      r.$<HTMLButtonElement>('#trace-clear')!.click();
      await settle(r.fixture);
      expect(clear).toHaveBeenCalledTimes(1);
      expect(r.$('#trace-clear-hint')!.textContent).toContain('Saved walks stay until you delete them.');
    });
  });

  describe('Saved walks', () => {
    it('shows the count and no delete button when there are none', async () => {
      const r = await render();
      expect(r.$('#trace-saved')!.textContent!.trim()).toBe('Saved walks: 0');
      expect(r.$('#trace-delete-saved')).toBeNull();
    });

    it('shows the count and Delete all saved walks; asks with the count; deletes only on a yes and keeps the trace', async () => {
      const r = await render();
      r.view.savedCount.set(4);
      await settle(r.fixture);
      expect(r.$('#trace-saved')!.textContent!.trim()).toBe('Saved walks: 4');
      const del = vi.spyOn(r.view, 'deleteAllSaved').mockResolvedValue();
      const ask = vi.spyOn(TestBed.inject(ConfirmService), 'ask').mockResolvedValueOnce(false).mockResolvedValueOnce(true);
      r.$<HTMLButtonElement>('#trace-delete-saved')!.click();
      await settle(r.fixture);
      expect(ask.mock.calls[0][0]).toEqual({ key: 'trace.settings.deleteAllConfirm', params: { n: '4' } });
      expect(del).not.toHaveBeenCalled();
      r.$<HTMLButtonElement>('#trace-delete-saved')!.click();
      await settle(r.fixture);
      expect(del).toHaveBeenCalledTimes(1);
    });
  });

  it('turning the trace off while a walk records ends the walk first', async () => {
    const r = await render({ traceOn: true });
    recorder.state.set('recording');
    await settle(r.fixture);
    const sw = r.$<HTMLInputElement>('#trace-on')!;
    sw.checked = false;
    sw.dispatchEvent(new Event('change'));
    await settle(r.fixture);
    expect(recorder.finishes).toBe(1);
    expect(await r.store.traceOn()).toBe(false);
  });

  it('says the shared-browser sentence', async () => {
    const r = await render();
    expect(r.host.textContent).toContain('Other people using this browser profile can see your walks.');
  });

  describe.each(['en', 'hi', 'ta', 'te'] as const)('in %s', (lang) => {
    it('shows no raw key, and passes the accessibility rules, with a walk recording and every note open', async () => {
      const r = await render({ lang, traceOn: true });
      recorder.state.set('recording');
      recorder.problem.set('denied');
      recorder.pausedNotice.set(true);
      recorder.keepAwakeFailed.set(true);
      recorder.alertRaised.update((n) => n + 1);
      r.view.kept.set(false);
      await flush(r.fixture);
      const text = r.host.textContent!;
      expect(text).not.toMatch(/trace\.[a-zA-Z.]+/);
      expect(text).toContain(DICTS[lang]['trace.card.title']);
      expect(audit(r.host)).toEqual([]);
    });
  });

  it('passes the accessibility rules idle, with the trace off', async () => {
    const r = await render();
    expect(audit(r.host)).toEqual([]);
  });
});
