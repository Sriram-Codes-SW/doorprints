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

import { MAX_AUDIO_BYTES, MAX_AUDIO_MS } from '../ai/transcriber';

/**
 * Records one short clip from the microphone, in memory only (voice input, docs/03 ADR-37, docs/ai/voice-input.md §4).
 *
 * The clip is a few `Blob` chunks held by this object until {@link AudioRecorder.stop} turns them into bytes for the
 * `Transcriber`; nothing is written to IndexedDB, local or session storage, the Cache API or the console, no `blob:`
 * address is made and no audio is played back (T-I48). The microphone is asked for only in {@link AudioRecorder.start},
 * which a tap calls, never at start-up. Every exit releases it: a stop, a cancel, an error, the limits, the page being
 * hidden or closed, and the page's own `release()` when its component is destroyed (a route change): the recorder is
 * stopped, each track of the stream is stopped, the chunks are dropped and the listeners are removed. Releasing twice is
 * harmless.
 */

/** Why no clip came: the microphone was refused, there is none or none can record here, it could not start, or it was cancelled. */
export type RecorderFailure = 'denied' | 'unsupported' | 'noDevice' | 'failed' | 'cancelled';

/** A recording that did not produce a clip; the message names the reason only. */
export class RecorderError extends Error {
  constructor(readonly reason: RecorderFailure) {
    super(`recording: ${reason}`);
  }
}

/** Why a recording ended by itself: the time limit ({@link MAX_AUDIO_MS}) or the size cap ({@link MAX_AUDIO_BYTES}). */
export type RecorderLimit = 'time' | 'size';

/** A finished clip, still only in memory. `limit` names the cap that ended it, when one did. */
export interface RecordedClip {
  bytes: Uint8Array;
  /** The recorder's own type, such as `audio/webm;codecs=opus` or `audio/mp4`; the transcriber reads its bare form. */
  mime: string;
  durationMs: number;
  limit: RecorderLimit | null;
}

/** What the recorder needs from the browser (fakes in the specs). */
export interface RecorderEnv {
  getUserMedia: ((constraints: MediaStreamConstraints) => Promise<MediaStream>) | null;
  recorder: typeof MediaRecorder | null;
  now: () => number;
}

/** The browser's microphone API and `MediaRecorder`, or nulls where one is missing (an insecure page, an old browser). */
export function browserRecorderEnv(): RecorderEnv {
  const media = typeof navigator !== 'undefined' ? navigator.mediaDevices : undefined;
  return {
    getUserMedia: media && typeof media.getUserMedia === 'function' ? (c) => media.getUserMedia(c) : null,
    recorder: typeof MediaRecorder !== 'undefined' ? MediaRecorder : null,
    now: () => Date.now(),
  };
}

/** Whether this browser can record at all; the microphone button is not drawn where it cannot. */
export function canRecord(env: RecorderEnv = browserRecorderEnv()): boolean {
  return env.getUserMedia !== null && env.recorder !== null;
}

/** In the order tried: Chrome, Firefox and Edge give webm/opus, Safari gives mp4; the transcriber takes all three. */
const TYPE_ORDER: readonly string[] = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];

/** About 4 KB a second: 60 s is about 240 KB, far under the 2 MB cap, and speech stays clear. */
const BITS_PER_SECOND = 32_000;
/** A chunk every second, so the size cap is checked while recording, not after. */
const SLICE_MS = 1000;

function pickType(ctor: typeof MediaRecorder): string | undefined {
  const supports = typeof ctor.isTypeSupported === 'function' ? (t: string) => ctor.isTypeSupported(t) : () => false;
  return TYPE_ORDER.find(supports);
}

/** `NotAllowedError` and `SecurityError` are the person (or a policy) saying no; `NotFoundError` is no microphone. */
function failureOf(err: unknown): RecorderFailure {
  const name = typeof err === 'object' && err !== null ? String((err as { name?: unknown }).name) : '';
  if (name === 'NotAllowedError' || name === 'SecurityError' || name === 'PermissionDeniedError') return 'denied';
  if (name === 'NotFoundError' || name === 'OverconstrainedError' || name === 'DevicesNotFoundError') return 'noDevice';
  return 'failed';
}

export class AudioRecorder {
  private stream: MediaStream | null = null;
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private size = 0;
  private startedAt = 0;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private limit: RecorderLimit | null = null;
  /** Bumped by every {@link release}, so a `getUserMedia` that answers after a cancel releases its stream at once. */
  private generation = 0;
  private done: { resolve: (clip: RecordedClip) => void; reject: (e: RecorderError) => void } | null = null;
  private active = false;
  /** Fired once when the recording ends by itself (a limit, the page hidden, an error), so the screen can follow. */
  onEnded: ((how: { clip?: RecordedClip; failure?: RecorderFailure }) => void) | null = null;

  private readonly hide = (): void => this.cancel();
  private readonly visibility = (): void => {
    if (typeof document !== 'undefined' && document.visibilityState === 'hidden') this.cancel();
  };

  constructor(private readonly env: RecorderEnv = browserRecorderEnv()) {}

  /** True from a successful {@link start} until the recording ends. */
  get recording(): boolean {
    return this.active;
  }

  /** Milliseconds since the recording began (0 when none is running). */
  elapsedMs(): number {
    return this.active ? this.env.now() - this.startedAt : 0;
  }

  /**
   * Asks for the microphone (the browser's own permission prompt, the first time) and starts recording. Rejects with a
   * {@link RecorderError}; nothing is left open when it does.
   */
  async start(): Promise<void> {
    if (this.active || this.stream !== null) throw new RecorderError('failed');
    const { getUserMedia, recorder: Ctor } = this.env;
    if (getUserMedia === null || Ctor === null) throw new RecorderError('unsupported');
    const generation = ++this.generation;
    let stream: MediaStream;
    try {
      stream = await getUserMedia({ audio: true });
    } catch (err) {
      throw new RecorderError(failureOf(err));
    }
    if (generation !== this.generation) {
      // Cancelled or released while the permission prompt was open: the microphone is let go at once.
      for (const t of stream.getTracks()) t.stop();
      throw new RecorderError('cancelled');
    }
    this.stream = stream;
    try {
      const type = pickType(Ctor);
      const rec = new Ctor(stream, type ? { mimeType: type, audioBitsPerSecond: BITS_PER_SECOND } : { audioBitsPerSecond: BITS_PER_SECOND });
      this.recorder = rec;
      rec.ondataavailable = (e: BlobEvent) => this.take(e.data);
      rec.onerror = () => this.end('failed');
      rec.onstop = () => this.finish();
      this.chunks = [];
      this.size = 0;
      this.limit = null;
      this.startedAt = this.env.now();
      rec.start(SLICE_MS);
      this.active = true;
      this.timer = setTimeout(() => this.stopFor('time'), MAX_AUDIO_MS);
      window.addEventListener('pagehide', this.hide);
      document.addEventListener('visibilitychange', this.visibility);
    } catch {
      this.release();
      throw new RecorderError('failed');
    }
  }

  /** Ends the recording and gives the clip; rejects with `cancelled` when {@link cancel} or the page hiding ended it first. */
  stop(): Promise<RecordedClip> {
    if (!this.active || this.recorder === null) return Promise.reject(new RecorderError('failed'));
    return new Promise<RecordedClip>((resolve, reject) => {
      this.done = { resolve, reject };
      this.halt();
    });
  }

  /** Throws the recording away: nothing is kept and the microphone is let go. A pending {@link stop} rejects with `cancelled`. */
  cancel(): void {
    const pending = this.done;
    const ended = this.onEnded;
    const was = this.active;
    this.release();
    pending?.reject(new RecorderError('cancelled'));
    if (was && !pending) ended?.({ failure: 'cancelled' });
  }

  /** Lets everything go: recorder, tracks, timer, chunks and listeners. Safe to call at any time, any number of times. */
  release(): void {
    this.generation++;
    clearTimeout(this.timer);
    this.timer = undefined;
    const rec = this.recorder;
    if (rec !== null) {
      rec.ondataavailable = null;
      rec.onerror = null;
      rec.onstop = null;
      try {
        if (rec.state !== 'inactive') rec.stop();
      } catch {
        // Already stopped.
      }
    }
    if (this.stream !== null) for (const t of this.stream.getTracks()) t.stop();
    this.recorder = null;
    this.stream = null;
    this.chunks = [];
    this.size = 0;
    this.done = null;
    this.active = false;
    if (typeof window !== 'undefined') window.removeEventListener('pagehide', this.hide);
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', this.visibility);
  }

  /** A chunk arrived: kept while the total stays under the cap; the one that would pass it ends the recording instead. */
  private take(blob: Blob): void {
    if (!this.active || blob.size === 0) return;
    if (this.size + blob.size > MAX_AUDIO_BYTES) {
      this.stopFor('size');
      return;
    }
    this.chunks.push(blob);
    this.size += blob.size;
  }

  private stopFor(limit: RecorderLimit): void {
    if (!this.active) return;
    this.limit ??= limit;
    this.halt();
  }

  private halt(): void {
    clearTimeout(this.timer);
    this.timer = undefined;
    const rec = this.recorder;
    if (rec === null) return;
    try {
      if (rec.state !== 'inactive') rec.stop();
      else this.finish();
    } catch {
      this.end('failed');
    }
  }

  /** The recorder's `stop` event: the clip is made from the chunks, then everything is let go. */
  private finish(): void {
    const rec = this.recorder;
    if (rec === null) return;
    const durationMs = Math.max(0, this.env.now() - this.startedAt);
    const parts = this.chunks;
    const mime = rec.mimeType || parts[0]?.type || '';
    const limit = this.limit;
    const waiting = this.done;
    const ended = this.onEnded;
    this.release();
    new Blob(parts, { type: mime })
      .arrayBuffer()
      .then((buf) => {
        const clip: RecordedClip = { bytes: new Uint8Array(buf), mime, durationMs, limit };
        if (waiting) waiting.resolve(clip);
        else ended?.({ clip });
      })
      .catch(() => {
        if (waiting) waiting.reject(new RecorderError('failed'));
        else ended?.({ failure: 'failed' });
      });
  }

  private end(failure: RecorderFailure): void {
    const waiting = this.done;
    const ended = this.onEnded;
    this.release();
    if (waiting) waiting.reject(new RecorderError(failure));
    else ended?.({ failure });
  }
}
