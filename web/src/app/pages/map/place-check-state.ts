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
 * What *Have I been here?* does when the person presses its button (docs/11 5.27.13, docs/03 section 6.2b rows 14 to 17,
 * S4b-FR-24). The answer is held here in memory only, as a signal: never in a URL, `history.state`, a storage or
 * `ListReturn`, and it is withdrawn from the app's live region on {@link PlaceCheckState.close}.
 *
 * {@link PlaceCheckState.compute} is the ONLY caller of `placeCheck`, and only a button handler calls it (the source test
 * of TC-U-154 holds this file to that): no timer, no arrival trigger, no background. It reads the stored walks, compares, and
 * returns; it writes nothing and makes no request. The location fix of *here* is used for this one answer and dropped.
 */

import { Injectable, inject, signal } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { GEOLOCATION, TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import { TranslationService } from '../../i18n/translation.service';
import { locateBest } from '../../shared/locate-once';
import { matchedStretch, placeCheck } from '../../shared/trace-place-check';
import { placeCheckSummary, placeCheckText, placeTextHelpers } from '../../shared/trace-place-text';
import type { PlaceKind, PlaceSummary, PlaceText } from '../../shared/trace-place-text';
import { checkGeoJson } from '../../shared/trace-style';
import { TraceView } from './trace-view';

type Bounds = [[number, number], [number, number]];

/** One answer: what was asked about, what was found, and what to draw. */
export interface CheckAnswer {
  readonly kind: PlaceKind;
  readonly place: { readonly lat: number; readonly lon: number };
  /** Null for a house with only an area (`APPROX`): nothing was compared. */
  readonly summary: PlaceSummary | null;
  /** The matched stretch of every *walked* row, `[lon, lat]` lines (60 m each side of the nearest point). */
  readonly stretches: [number, number][][];
  /** The box that holds the place and the stretches, `[[west, south], [east, north]]`. */
  readonly bounds: Bounds;
}

/** Why *Where I am now* got no fix. */
export type LocateProblem = 'denied' | 'unavailable' | 'timeout';

/** A box around the place and the stretches. */
export function boundsOf(place: { lat: number; lon: number }, stretches: readonly (readonly [number, number][])[]): Bounds {
  let west = place.lon;
  let east = place.lon;
  let south = place.lat;
  let north = place.lat;
  for (const line of stretches) {
    for (const [lon, lat] of line) {
      west = Math.min(west, lon);
      east = Math.max(east, lon);
      south = Math.min(south, lat);
      north = Math.max(north, lat);
    }
  }
  return [
    [west, south],
    [east, north],
  ];
}

@Injectable({ providedIn: 'root' })
export class PlaceCheckState {
  private readonly store = inject(TraceStore);
  private readonly recorder = inject(TraceRecorderService);
  private readonly announcer = inject(Announcer);
  private readonly geolocation = inject(GEOLOCATION);
  private readonly traceView = inject(TraceView);
  private readonly i18n = inject(TranslationService);

  /** The answer on show, or null. Memory only. */
  readonly answer = signal<CheckAnswer | null>(null);
  /** True while *Where I am now* waits for a fix. */
  readonly locating = signal(false);
  readonly locateProblem = signal<LocateProblem | null>(null);
  /** *Show on map*: a request to frame the answer, counted so a second press frames again. */
  readonly showRequest = signal<{ n: number; bounds: Bounds } | null>(null);
  /** False where the browser has no Geolocation: *Where I am now* is then not offered. */
  readonly canLocate = this.geolocation !== null;

  private cancelLocate: (() => void) | null = null;
  private shows = 0;

  /**
   * Compares `place` with the stored walks. For *here* the walk now recording is left out and `fixAccuracyM` is the fix's;
   * a house or a picked spot counts every walk. Called only from a button's handler.
   */
  async compute(kind: PlaceKind, place: { lat: number; lon: number }, fixAccuracyM?: number | null): Promise<CheckAnswer> {
    const walks = await this.store.placeWalks(Date.now(), kind === 'here' ? this.recorder.liveWalkId() : 0);
    const result = placeCheck(place, walks, kind === 'here' ? fixAccuracyM : undefined);
    const summary = placeCheckSummary(result, { hasSaved: walks.some((w) => w.source === 'SAVED'), fixAccuracyM: kind === 'here' ? fixAccuracyM : null });
    const stretches = result.rows.filter((r) => r.walked).map((r) => matchedStretch(walks[r.walkIndex], r));
    return { kind, place: { lat: place.lat, lon: place.lon }, summary, stretches, bounds: boundsOf(place, stretches) };
  }

  /** The Map's answer: compares, shows it (the panel, the halo, the ring) and says the headline once. */
  async run(kind: PlaceKind, place: { lat: number; lon: number }, fixAccuracyM?: number | null): Promise<void> {
    const answer = await this.compute(kind, place, fixAccuracyM);
    this.present(answer);
  }

  /** Makes `answer` the one on show. */
  present(answer: CheckAnswer): void {
    this.answer.set(answer);
    this.traceView.check.set(checkGeoJson(answer.stretches));
    this.requestShow(answer);
    // The shell's polite region says it once; Close withdraws it so the sentence is not left in the page.
    this.announcer.announce({ key: 'trace.here.announce', params: { text: this.text(answer).headline } });
  }

  /** The sentences of an answer in the app language. */
  text(answer: CheckAnswer): PlaceText {
    if (answer.summary === null) return { headline: this.i18n.t('trace.here.approxHouse'), rows: [], rowsMore: null, notes: [] };
    return placeCheckText(answer.summary, answer.kind, placeTextHelpers(this.i18n), true);
  }

  /** *Show on map*: frame the place and the stretches. */
  show(): void {
    const answer = this.answer();
    if (answer) this.requestShow(answer);
  }

  private requestShow(answer: CheckAnswer): void {
    this.showRequest.set({ n: ++this.shows, bounds: answer.bounds });
  }

  /**
   * *Where I am now* and *Check again*: ONE fresh fix (a 15 s watch that stops at the first fix of 50 m or better, or at 15 s
   * with the best so far). Call it INSIDE the click, so the browser still counts it as a user gesture. The fix is used for this
   * answer and dropped.
   */
  locateHere(): void {
    if (this.geolocation === null) {
      this.locateProblem.set('unavailable');
      return;
    }
    this.cancelLocating();
    this.locateProblem.set(null);
    this.locating.set(true);
    this.cancelLocate = locateBest(
      {
        gone: () => false,
        found: (position) => {
          this.finishLocating();
          void this.run('here', { lat: position.coords.latitude, lon: position.coords.longitude }, position.coords.accuracy);
        },
        timedOut: () => {
          this.finishLocating();
          this.locateProblem.set('timeout');
        },
        failed: (error) => {
          this.finishLocating();
          // locateBest reports only a refused permission: an unavailable position keeps waiting until the 15 s end it.
          this.locateProblem.set(error.code === 1 ? 'denied' : 'unavailable');
        },
      },
      this.geolocation,
    );
  }

  private finishLocating(): void {
    this.cancelLocate = null;
    this.locating.set(false);
  }

  /** *Cancel* while finding the location: the watch stops and nothing is reported. */
  cancelLocating(): void {
    this.cancelLocate?.();
    this.cancelLocate = null;
    this.locating.set(false);
  }

  /** *Close*, and the page going away: the answer, the halo and the announcement are withdrawn. */
  close(): void {
    this.cancelLocating();
    this.locateProblem.set(null);
    this.answer.set(null);
    this.showRequest.set(null);
    this.traceView.check.set(null);
    this.announcer.cancel({ key: 'trace.here.announce' });
  }
}
