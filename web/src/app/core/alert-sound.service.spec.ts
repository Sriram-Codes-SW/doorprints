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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ALERT_BEEP, AUDIO_CONTEXT_FACTORY, AlertSound, VIBRATE } from './alert-sound.service';
import type { AudioContextLike } from './alert-sound.service';

/** The beep of docs/11 5.27.5: two 0.15 s sine tones at 880 Hz, 0.1 s apart, volume 0.3; vibration when the sound cannot play. */
class FakeContext implements AudioContextLike {
  state: AudioContextState = 'suspended';
  currentTime = 10;
  resumed = 0;
  closed = false;
  readonly tones: { type: string; freq: number; start: number; stop: number; gain: number }[] = [];
  private listener: (() => void) | null = null;
  readonly destination = {};
  resume(): Promise<void> {
    this.resumed++;
    this.state = 'running';
    this.listener?.();
    return Promise.resolve();
  }
  close(): Promise<void> {
    this.closed = true;
    this.state = 'closed';
    return Promise.resolve();
  }
  addEventListener(_type: 'statechange', listener: () => void): void {
    this.listener = listener;
  }
  createGain() {
    const gain = { value: 0 };
    return { gain, connect: vi.fn() };
  }
  createOscillator() {
    const tone = { type: '', frequency: { value: 0 }, start: 0, stop: 0, gainValue: 0 };
    const osc = {
      set type(v: string) {
        tone.type = v;
      },
      get type() {
        return tone.type;
      },
      frequency: tone.frequency,
      connect: (node: { gain?: { value: number } }) => {
        tone.gainValue = node.gain?.value ?? -1;
      },
      start: (t: number) => {
        tone.start = t;
      },
      stop: (t: number) => {
        tone.stop = t;
        this.tones.push({ type: tone.type, freq: tone.frequency.value, start: tone.start, stop: tone.stop, gain: tone.gainValue });
      },
    };
    return osc;
  }
}

describe('AlertSound', () => {
  let ctx: FakeContext;
  let created: number;
  let vibrate: ReturnType<typeof vi.fn>;
  let sound: AlertSound;

  function build(opts: { audio?: boolean; vibrate?: boolean } = {}) {
    created = 0;
    ctx = new FakeContext();
    vibrate = vi.fn();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        { provide: AUDIO_CONTEXT_FACTORY, useValue: opts.audio === false ? null : () => (created++, ctx) },
        { provide: VIBRATE, useValue: opts.vibrate === false ? null : vibrate },
      ],
    });
    sound = TestBed.inject(AlertSound);
  }
  beforeEach(() => build());

  it('creates nothing until prepare() is called (the context is made inside the Start a walk click, never on page load)', () => {
    expect(created).toBe(0);
    expect(sound.running()).toBe(false);
  });

  it('prepare() creates the context once and resumes it, so it is running', () => {
    sound.prepare();
    sound.prepare();
    expect(created).toBe(1);
    expect(ctx.resumed).toBe(2); // resumed on each click: a browser may have suspended it again
    expect(sound.running()).toBe(true);
  });

  it('plays two 0.15 s sine tones at 880 Hz, 0.1 s apart, at volume 0.3 when it is running', () => {
    sound.prepare();
    expect(sound.beep()).toBe(true);
    expect(ctx.tones).toEqual([
      { type: 'sine', freq: 880, start: 10, stop: 10.15, gain: 0.3 },
      { type: 'sine', freq: 880, start: 10.25, stop: 10.4, gain: 0.3 },
    ]);
    expect(ALERT_BEEP).toEqual({ hz: 880, toneS: 0.15, gapS: 0.1, volume: 0.3 });
    expect(vibrate).not.toHaveBeenCalled();
  });

  it('does not play when it is not running, and vibrates 200-100-200 instead (only the banner and the buzz)', () => {
    sound.prepare();
    ctx.state = 'suspended';
    sound.syncState();
    expect(sound.running()).toBe(false);
    expect(sound.beep()).toBe(false);
    expect(ctx.tones).toEqual([]);
    expect(vibrate).toHaveBeenCalledWith([200, 100, 200]);
  });

  it('does not play, and does not throw, when the page never prepared it', () => {
    expect(sound.beep()).toBe(false);
    expect(created).toBe(0);
    expect(vibrate).toHaveBeenCalledTimes(1);
  });

  it('follows the context state: a browser that suspends it turns running off', () => {
    sound.prepare();
    expect(sound.running()).toBe(true);
    ctx.state = 'suspended';
    sound.syncState();
    expect(sound.running()).toBe(false);
  });

  it('is silent and safe where there is no audio at all, or no vibration', () => {
    build({ audio: false, vibrate: false });
    sound.prepare();
    expect(sound.running()).toBe(false);
    expect(sound.beep()).toBe(false);
  });

  it('survives a context that refuses to resume', () => {
    ctx.resume = () => Promise.reject(new Error('blocked'));
    sound.prepare();
    expect(sound.running()).toBe(false);
  });

  it('close() releases the context', () => {
    sound.prepare();
    sound.close();
    expect(ctx.closed).toBe(true);
    expect(sound.running()).toBe(false);
    sound.prepare();
    expect(created).toBe(2); // a new context for the next walk
  });
});
