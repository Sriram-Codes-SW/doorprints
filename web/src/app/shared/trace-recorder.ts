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
 * Which fixes the website keeps while a walk records (docs/11 5.27.2, 5.27.8; the TypeScript twin of the phones'
 * `TrackRecorder`, same rules): the accuracy gate, the thinning, the walk id and the *resumed* mark. Pure: no
 * clock, no DOM, no storage; `core/trace-recorder.service.ts` feeds it the browser's fixes.
 */

import { TRACE, haversineM, isValidLatLon } from './trace-geo';
import type { TracePoint } from './trace-geo';

/** A location reading as the Geolocation API gives it. */
export interface Fix {
  readonly lat: number;
  readonly lon: number;
  /** Epoch ms of the reading. */
  readonly atMs: number;
  /** The reported accuracy in metres. */
  readonly accuracyM: number;
}

export interface RecorderOptions {
  /** A fix is kept when at least this far from the last kept one (default {@link TRACE.thinDistanceM}). */
  readonly minDistanceM?: number;
  /** ... or at least this long after it (default {@link TRACE.thinGapMs}). */
  readonly minGapMs?: number;
}

export class TraceRecorder {
  private last: { lat: number; lon: number; atMs: number } | null = null;
  private walkId = 0;
  private resumeNext = false;
  private readonly minDistanceM: number;
  private readonly minGapMs: number;

  constructor(options: RecorderOptions = {}) {
    this.minDistanceM = options.minDistanceM ?? TRACE.thinDistanceM;
    this.minGapMs = options.minGapMs ?? TRACE.thinGapMs;
  }

  /** The walk id of the walk in progress (the `atMs` of its first kept point), or 0 before one is kept. */
  get liveWalkId(): number {
    return this.walkId;
  }

  /** The next kept point starts a new walk id (*Finish walk*, a closed page, the start of a walk). */
  finish(): void {
    this.last = null;
    this.walkId = 0;
    this.resumeNext = false;
  }

  /** The page was hidden for more than 5 minutes: the next kept point carries `resumed`, so no line joins it to the last. */
  markResumed(): void {
    this.resumeNext = true;
  }

  /**
   * The kept point for a fix, or null when it is dropped: a fix worse than 50 m (50 passes), one that is not finite or
   * not on the globe, one not newer than the last kept point, one under 20 m and under 5 minutes from the last kept point.
   * The first kept point of a walk, and the first after a gap of 30 minutes or more, begins a new walk id.
   */
  accept(fix: Fix): TracePoint | null {
    if (!Number.isFinite(fix.atMs) || !isValidLatLon(fix.lat, fix.lon)) return null;
    if (!Number.isFinite(fix.accuracyM) || fix.accuracyM < 0 || fix.accuracyM > TRACE.maxFixAccuracyM) return null;
    const last = this.last;
    if (last !== null) {
      if (fix.atMs <= last.atMs) return null;
      const far = haversineM(last.lat, last.lon, fix.lat, fix.lon) >= this.minDistanceM;
      if (!far && fix.atMs - last.atMs < this.minGapMs) return null;
    }
    const newWalk = last === null || fix.atMs - last.atMs >= TRACE.walkGapMs;
    if (newWalk) {
      this.walkId = fix.atMs;
      this.resumeNext = false;
    }
    const resumed = !newWalk && this.resumeNext;
    this.resumeNext = false;
    this.last = { lat: fix.lat, lon: fix.lon, atMs: fix.atMs };
    return { lat: fix.lat, lon: fix.lon, atMs: fix.atMs, walkId: this.walkId, ...(resumed ? { resumed: true } : {}) };
  }
}
