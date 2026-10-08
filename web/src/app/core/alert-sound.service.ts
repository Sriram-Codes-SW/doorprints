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

/**
 * The website's alert sound (docs/11 5.27.5, S4b-FR-17): a short beep made with the Web Audio API, two 0.15 s sine tones at
 * 880 Hz, 0.1 s apart, volume 0.3, and a vibration where the browser has one. Browsers play audio only after the person has
 * interacted with the page, so {@link AlertSound.prepare} is called INSIDE the click of *Start a walk* (it creates the
 * `AudioContext` and resumes it); if the context is not `running` when an alert comes, only the banner shows and, where
 * `navigator.vibrate` exists, the phone vibrates 200-100-200 ms. Nothing here is stored or sent.
 */

import { Injectable, InjectionToken, inject, signal } from '@angular/core';

/** The beep, from docs/11 5.27.5. */
export const ALERT_BEEP = { hz: 880, toneS: 0.15, gapS: 0.1, volume: 0.3 } as const;
const VIBRATION = [200, 100, 200];

/** The part of `AudioContext` the beep uses (the unit test fakes it). */
export interface AudioContextLike {
  readonly state: AudioContextState;
  readonly currentTime: number;
  readonly destination: unknown;
  resume(): Promise<void>;
  close(): Promise<void>;
  addEventListener(type: 'statechange', listener: () => void): void;
  createGain(): { gain: { value: number }; connect(node: unknown): unknown };
  createOscillator(): {
    type: OscillatorType | string;
    frequency: { value: number };
    connect(node: unknown): unknown;
    start(when: number): void;
    stop(when: number): void;
  };
}

/** Makes an audio context, or null where the browser has none. The default is the browser's `AudioContext`. */
export const AUDIO_CONTEXT_FACTORY = new InjectionToken<(() => AudioContextLike) | null>('AUDIO_CONTEXT_FACTORY', {
  providedIn: 'root',
  factory: () => {
    const g = globalThis as { AudioContext?: new () => AudioContext; webkitAudioContext?: new () => AudioContext };
    const Ctor = g.AudioContext ?? g.webkitAudioContext;
    return Ctor ? () => new Ctor() as unknown as AudioContextLike : null;
  },
});

/** `navigator.vibrate`, or null where the browser has none (iPhone Safari). */
export const VIBRATE = new InjectionToken<((pattern: number[]) => unknown) | null>('VIBRATE', {
  providedIn: 'root',
  factory: () => (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function' ? (p: number[]) => navigator.vibrate(p) : null),
});

/**
 * Plays the walk alert (beep and vibration) so a person on a recorded walk notices it without looking at the screen.
 * {@link prepare} must run in a user gesture; {@link running} tells the settings card whether sound will play.
 */
@Injectable({ providedIn: 'root' })
export class AlertSound {
  private readonly factory = inject(AUDIO_CONTEXT_FACTORY);
  private readonly vibrate = inject(VIBRATE);
  private ctx: AudioContextLike | null = null;
  private readonly runningState = signal(false);

  /** True while the audio context is running: the settings card says *Sound is off until you start a walk from this page* otherwise. */
  readonly running = this.runningState.asReadonly();

  /**
   * Call it INSIDE the click of *Start a walk* (a user gesture): creates the audio context on first use and resumes it. It never
   * throws; a browser that refuses leaves {@link running} false, and the alert is then a banner and a vibration.
   */
  prepare(): void {
    try {
      if (this.ctx === null && this.factory !== null) {
        this.ctx = this.factory();
        this.ctx.addEventListener('statechange', () => this.syncState());
      }
      const ctx = this.ctx;
      if (ctx === null) return;
      ctx.resume().then(
        () => this.syncState(),
        () => this.syncState(),
      );
      this.syncState();
    } catch {
      this.ctx = null;
      this.runningState.set(false);
    }
  }

  /** Reads the context's state into {@link running}. */
  syncState(): void {
    this.runningState.set(this.ctx !== null && this.ctx.state === 'running');
  }

  /**
   * Plays the beep when the context is running and returns true; otherwise vibrates (where the browser can) and returns false,
   * so the caller shows the banner either way.
   */
  beep(): boolean {
    const ctx = this.ctx;
    if (ctx !== null && ctx.state === 'running') {
      try {
        const start = ctx.currentTime;
        for (const offset of [0, ALERT_BEEP.toneS + ALERT_BEEP.gapS]) {
          const osc = ctx.createOscillator();
          const gain = ctx.createGain();
          osc.type = 'sine';
          osc.frequency.value = ALERT_BEEP.hz;
          gain.gain.value = ALERT_BEEP.volume;
          osc.connect(gain);
          gain.connect(ctx.destination);
          osc.start(start + offset);
          osc.stop(start + offset + ALERT_BEEP.toneS);
        }
        return true;
      } catch {
        // fall through to the vibration
      }
    }
    this.vibrate?.(VIBRATION);
    return false;
  }

  /** Releases the audio context (the next walk makes a new one inside its click). */
  close(): void {
    const ctx = this.ctx;
    this.ctx = null;
    this.runningState.set(false);
    ctx?.close().catch(() => undefined);
  }
}
