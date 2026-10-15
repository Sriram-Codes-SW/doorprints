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

import type { RecorderEnv } from '../audio-recorder';

/**
 * A fake microphone for the voice specs: no real device, no real `MediaRecorder`, no provider call. The "audio" is text
 * (`FAKE_AUDIO`), so both its bytes and its base64 can be searched for wherever it must not appear.
 */

/** What the fake recorder "hears": text, searchable. */
export const FAKE_AUDIO = 'FAKE-AUDIO-not-speech-77123-Maninagar';
export const FAKE_AUDIO_B64 = btoa(FAKE_AUDIO);

export class FakeTrack {
  stopped = false;
  onended: (() => void) | null = null;
  stop(): void {
    this.stopped = true;
  }
  /** The browser ends the track from outside (a call took the microphone, a headset was unplugged). */
  endByItself(): void {
    this.stopped = true;
    this.onended?.();
  }
}

export class FakeStream {
  readonly tracks = [new FakeTrack(), new FakeTrack()];
  getTracks(): FakeTrack[] {
    return this.tracks;
  }
  get released(): boolean {
    return this.tracks.every((t) => t.stopped);
  }
}

type Handler<E> = ((e: E) => void) | null;

/** Behaves like `MediaRecorder`: `stop()` delivers a last chunk and then the `stop` event, in a later task. */
export class FakeRecorder {
  static instances: FakeRecorder[] = [];
  static supported = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4'];
  static isTypeSupported(type: string): boolean {
    return FakeRecorder.supported.includes(type);
  }
  state: 'inactive' | 'recording' = 'inactive';
  readonly mimeType: string;
  ondataavailable: Handler<{ data: Blob }> = null;
  onstop: Handler<unknown> = null;
  onerror: Handler<unknown> = null;
  startedWith: number | undefined;
  /** The text of the last chunk `stop()` delivers. */
  lastChunk = FAKE_AUDIO;

  constructor(readonly stream: FakeStream, readonly options: { mimeType?: string; audioBitsPerSecond?: number } = {}) {
    this.mimeType = options.mimeType ?? 'audio/webm';
    FakeRecorder.instances.push(this);
  }

  start(timeslice?: number): void {
    this.state = 'recording';
    this.startedWith = timeslice;
  }

  stop(): void {
    if (this.state === 'inactive') throw new DOMException('not recording', 'InvalidStateError');
    this.state = 'inactive';
    queueMicrotask(() => {
      if (this.lastChunk !== '') this.ondataavailable?.({ data: new Blob([this.lastChunk], { type: this.mimeType }) });
      this.onstop?.({});
    });
  }

  /** The browser stops the recorder by itself (the microphone was taken): a last chunk, then `stop`, nobody having asked. */
  endByItself(): void {
    if (this.state === 'inactive') return;
    this.state = 'inactive';
    if (this.lastChunk !== '') this.ondataavailable?.({ data: new Blob([this.lastChunk], { type: this.mimeType }) });
    this.onstop?.({});
  }

  /** A chunk arrives while recording (a `timeslice` tick). */
  emit(data: Blob): void {
    this.ondataavailable?.({ data });
  }

  static reset(): void {
    FakeRecorder.instances = [];
  }
  static get last(): FakeRecorder {
    return FakeRecorder.instances[FakeRecorder.instances.length - 1];
  }
}

export interface FakeMic {
  env: RecorderEnv;
  streams: FakeStream[];
  /** Times the microphone was asked for. */
  asked: () => number;
}

/**
 * The environment of a recorder: `getUserMedia` answers with a fresh stream, or rejects with a `DOMException` of the given
 * name, or waits for `release()` of the returned gate (a permission prompt left open).
 */
export function fakeMic(behaviour: 'ok' | 'NotAllowedError' | 'NotFoundError' | 'AbortError' | 'wait' = 'ok'): FakeMic & { gate: () => void } {
  const streams: FakeStream[] = [];
  let asked = 0;
  let open: (() => void) | null = null;
  const env: RecorderEnv = {
    getUserMedia: () => {
      asked++;
      if (behaviour === 'ok') {
        const s = new FakeStream();
        streams.push(s);
        return Promise.resolve(s as unknown as MediaStream);
      }
      if (behaviour === 'wait') {
        return new Promise<MediaStream>((resolve) => {
          open = () => {
            const s = new FakeStream();
            streams.push(s);
            resolve(s as unknown as MediaStream);
          };
        });
      }
      return Promise.reject(new DOMException('no', behaviour));
    },
    recorder: FakeRecorder as unknown as typeof MediaRecorder,
    now: () => Date.now(),
  };
  return { env, streams, asked: () => asked, gate: () => open?.() };
}
