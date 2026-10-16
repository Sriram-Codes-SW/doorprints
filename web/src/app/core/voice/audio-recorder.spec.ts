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

import { afterEach, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { MAX_AUDIO_BYTES, MAX_AUDIO_MS } from '../ai/transcriber';
import { AudioRecorder, RecorderError, canRecord } from './audio-recorder';
import { FAKE_AUDIO, FakeRecorder, fakeMic } from './testing/voice-fakes';

/**
 * TC-U-242 (docs/06): the website's in-memory recorder (voice PR 4, S4b-BL-221, ADR-37), against a fake `MediaRecorder` and
 * a fake microphone: nothing real is recorded and no provider is called. What matters: the microphone is asked for only in
 * `start()`, the clip is a few bytes in memory, the 60 s and 2 MB limits end it, and every exit (stop, cancel, error, the
 * page hidden or closed, a permission prompt answered late) stops the recorder and every track of the stream (T-I47, T-I48).
 */
describe('AudioRecorder', () => {
  beforeEach(() => {
    FakeRecorder.reset();
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('canRecord: needs both the microphone API and a recorder', () => {
    const mic = fakeMic();
    expect(canRecord(mic.env)).toBe(true);
    expect(canRecord({ ...mic.env, recorder: null })).toBe(false);
    expect(canRecord({ ...mic.env, getUserMedia: null })).toBe(false);
  });

  it('asks for the microphone only when start() is called, never when the recorder is made', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    expect(mic.asked()).toBe(0);
    await r.start();
    expect(mic.asked()).toBe(1);
    expect(r.recording).toBe(true);
    r.release();
  });

  it('stop gives the clip in memory (bytes, type, length) and lets every track go', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    await r.start();
    expect(FakeRecorder.last.options.audioBitsPerSecond).toBe(32_000);
    expect(FakeRecorder.last.options.mimeType).toBe('audio/webm;codecs=opus');
    vi.advanceTimersByTime(7000);
    const clip = await r.stop();
    expect(new TextDecoder().decode(clip.bytes)).toBe(FAKE_AUDIO);
    expect(clip.mime).toBe('audio/webm;codecs=opus');
    expect(clip.limit).toBeNull();
    expect(clip.durationMs).toBeGreaterThanOrEqual(7000);
    expect(mic.streams[0].released).toBe(true);
    expect(r.recording).toBe(false);
  });

  it('takes the first type the browser supports: Safari gives mp4', async () => {
    FakeRecorder.supported = ['audio/mp4'];
    try {
      const r = new AudioRecorder(fakeMic().env);
      await r.start();
      expect(FakeRecorder.last.options.mimeType).toBe('audio/mp4');
      r.release();
    } finally {
      FakeRecorder.supported = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'];
    }
  });

  it('cancel keeps nothing, lets every track go and rejects a pending stop', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    await r.start();
    const pending = r.stop();
    r.cancel();
    await expect(pending).rejects.toMatchObject({ reason: 'cancelled' });
    expect(mic.streams[0].released).toBe(true);
    expect(FakeRecorder.last.state).toBe('inactive');
    expect(r.recording).toBe(false);
  });

  it('the time limit ends the recording by itself at 60 seconds and says so', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    vi.advanceTimersByTime(MAX_AUDIO_MS - 1);
    expect(r.recording).toBe(true);
    vi.advanceTimersByTime(1);
    await vi.advanceTimersByTimeAsync(0);
    expect(ended).toHaveBeenCalledTimes(1);
    expect(ended.mock.calls[0][0].clip.limit).toBe('time');
    expect(mic.streams[0].released).toBe(true);
  });

  it('the 2 MB cap ends it: a chunk that would pass the cap is left out and the clip stays under it', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    const rec = FakeRecorder.last;
    rec.lastChunk = '';
    rec.emit(new Blob([new Uint8Array(MAX_AUDIO_BYTES - 10)]));
    rec.emit(new Blob([new Uint8Array(11)]));
    await vi.advanceTimersByTimeAsync(0);
    expect(ended).toHaveBeenCalledTimes(1);
    const clip = ended.mock.calls[0][0].clip;
    expect(clip.limit).toBe('size');
    expect(clip.bytes.byteLength).toBe(MAX_AUDIO_BYTES - 10);
    expect(mic.streams[0].released).toBe(true);
  });

  it.each([
    ['NotAllowedError', 'denied'],
    ['NotFoundError', 'noDevice'],
    ['AbortError', 'failed'],
  ] as const)('a microphone refused with %s is %s, and nothing is left open', async (name, reason) => {
    const mic = fakeMic(name);
    const r = new AudioRecorder(mic.env);
    await expect(r.start()).rejects.toMatchObject({ reason });
    expect(r.recording).toBe(false);
    expect(FakeRecorder.instances).toHaveLength(0);
  });

  it('no recorder or no microphone API is "unsupported"', async () => {
    const mic = fakeMic();
    await expect(new AudioRecorder({ ...mic.env, recorder: null }).start()).rejects.toMatchObject({ reason: 'unsupported' });
    await expect(new AudioRecorder({ ...mic.env, getUserMedia: null }).start()).rejects.toBeInstanceOf(RecorderError);
    expect(mic.asked()).toBe(0);
  });

  it('a cancel while the permission prompt is open lets the stream go the moment it is granted', async () => {
    const mic = fakeMic('wait');
    const r = new AudioRecorder(mic.env);
    const started = r.start();
    r.cancel();
    mic.gate();
    await expect(started).rejects.toMatchObject({ reason: 'cancelled' });
    expect(mic.streams[0].released).toBe(true);
    expect(FakeRecorder.instances).toHaveLength(0);
  });

  it('a recorder that cannot be made (a type the browser refuses) lets the stream go', async () => {
    const mic = fakeMic();
    class Refusing {
      static isTypeSupported = () => true;
      constructor() {
        throw new DOMException('no', 'NotSupportedError');
      }
    }
    const r = new AudioRecorder({ ...mic.env, recorder: Refusing as unknown as typeof MediaRecorder });
    await expect(r.start()).rejects.toMatchObject({ reason: 'failed' });
    expect(mic.streams[0].released).toBe(true);
  });

  it('a recorder error ends the recording, lets everything go and reports it', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    FakeRecorder.last.onerror?.({});
    expect(ended).toHaveBeenCalledWith({ failure: 'failed' });
    expect(mic.streams[0].released).toBe(true);
    expect(r.recording).toBe(false);
  });

  it('a recording the browser ends by itself (a call, a headset change) keeps what was said and reports it as ended early', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    FakeRecorder.last.emit(new Blob([FAKE_AUDIO]));
    FakeRecorder.last.lastChunk = '';
    FakeRecorder.last.endByItself();
    await vi.advanceTimersByTimeAsync(0);
    expect(ended).toHaveBeenCalledTimes(1);
    const how = ended.mock.calls[0][0];
    expect(how.early).toBe(true);
    expect(how.failure).toBeUndefined();
    expect(new TextDecoder().decode(how.clip.bytes)).toBe(FAKE_AUDIO);
    expect(mic.streams[0].released).toBe(true);
    expect(r.recording).toBe(false);
  });

  it('a track that ends from outside ends the recording early with the clip so far', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    FakeRecorder.last.emit(new Blob([FAKE_AUDIO]));
    mic.streams[0].tracks[0].endByItself();
    await vi.advanceTimersByTimeAsync(0);
    expect(ended).toHaveBeenCalledTimes(1);
    expect(ended.mock.calls[0][0]).toMatchObject({ early: true });
    expect(mic.streams[0].released).toBe(true);
  });

  it('an early end before anything was said has no clip and says endedEarly, not failed', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    FakeRecorder.last.lastChunk = '';
    FakeRecorder.last.endByItself();
    await vi.advanceTimersByTimeAsync(0);
    expect(ended).toHaveBeenCalledWith({ failure: 'endedEarly' });
    expect(mic.streams[0].released).toBe(true);
  });

  it('a recorder error after some audio keeps the audio; before any it is a failure', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    FakeRecorder.last.emit(new Blob([FAKE_AUDIO]));
    FakeRecorder.last.onerror?.({});
    await vi.advanceTimersByTimeAsync(0);
    expect(ended).toHaveBeenCalledTimes(1);
    expect(ended.mock.calls[0][0]).toMatchObject({ early: true });
    expect(ended.mock.calls[0][0].clip.bytes.length).toBeGreaterThan(0);
  });

  it('a stop, a limit or a cancel the person or the app asked for is never reported as ended early', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    const clip = r.stop();
    await vi.advanceTimersByTimeAsync(0);
    await clip;
    expect(ended).not.toHaveBeenCalled();

    const r2 = new AudioRecorder(fakeMic().env);
    const ended2 = vi.fn();
    r2.onEnded = ended2;
    await r2.start();
    vi.advanceTimersByTime(MAX_AUDIO_MS);
    await vi.advanceTimersByTimeAsync(0);
    expect(ended2.mock.calls[0][0].early).toBeUndefined();
    expect(ended2.mock.calls[0][0].clip.limit).toBe('time');
  });

  it('the page being closed (pagehide) cancels the recording and lets the microphone go', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    window.dispatchEvent(new Event('pagehide'));
    expect(ended).toHaveBeenCalledWith({ failure: 'cancelled' });
    expect(mic.streams[0].released).toBe(true);
  });

  it('the tab being hidden cancels the recording; a visible tab does not', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const ended = vi.fn();
    r.onEnded = ended;
    await r.start();
    const state = vi.spyOn(document, 'visibilityState', 'get');
    state.mockReturnValue('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(r.recording).toBe(true);
    state.mockReturnValue('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(r.recording).toBe(false);
    expect(mic.streams[0].released).toBe(true);
    expect(ended).toHaveBeenCalledWith({ failure: 'cancelled' });
  });

  it('release (a page being destroyed) is safe at any time, twice, and removes the page listeners', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    const removed = vi.spyOn(window, 'removeEventListener').mock.calls;
    const removedDoc = vi.spyOn(document, 'removeEventListener').mock.calls;
    r.release();
    await r.start();
    r.release();
    r.release();
    expect(mic.streams[0].released).toBe(true);
    const ended = vi.fn();
    r.onEnded = ended;
    window.dispatchEvent(new Event('pagehide'));
    expect(ended).not.toHaveBeenCalled();
    expect(removed.map((c) => c[0])).toEqual(expect.arrayContaining(['pagehide']));
    expect(removedDoc.map((c) => c[0])).toEqual(expect.arrayContaining(['visibilitychange']));
  });

  it('a second start while recording is refused and opens no second stream', async () => {
    const mic = fakeMic();
    const r = new AudioRecorder(mic.env);
    await r.start();
    await expect(r.start()).rejects.toBeInstanceOf(RecorderError);
    expect(mic.asked()).toBe(1);
    r.release();
  });

  it('makes no blob address, writes nothing to storage and logs nothing', async () => {
    const url = vi.fn();
    const real = URL.createObjectURL;
    (URL as unknown as { createObjectURL: unknown }).createObjectURL = url;
    onTestFinished(() => {
      (URL as unknown as { createObjectURL: unknown }).createObjectURL = real;
    });
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    const logs = (['log', 'info', 'warn', 'error', 'debug'] as const).map((m) => vi.spyOn(console, m));
    const r = new AudioRecorder(fakeMic().env);
    await r.start();
    await r.stop();
    expect(url).not.toHaveBeenCalled();
    expect(setItem).not.toHaveBeenCalled();
    for (const l of logs) expect(l).not.toHaveBeenCalled();
  });
});
