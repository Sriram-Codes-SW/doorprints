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
 * What the Map shows of the person's walks, and what happens to them (docs/11 5.27.1 to 5.27.6, docs/03 section 6.2b rows
 * 6 to 9): one root service the card, the sheet, the place check and the page read, so the page itself only delegates. It
 * loads the 30-day trace and the saved walks from the store, runs the repeat detection (cached by the walks' keys and point
 * counts, at most once every 5 seconds while a walk records), draws the result as GeoJSON for {@link TraceLayers}, and
 * answers *Save this walk?*. Everything is in memory except what the {@link TraceStore} writes. Nothing here logs.
 */

import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { TraceRecorderService } from '../../core/trace-recorder.service';
import { TraceStore } from '../../data/trace-store';
import type { RepeatLook, SaveWalkResult, WalkSummary } from '../../data/trace-store';
import type { TraceWalk } from '../../shared/trace-geo';
import { detectRepeats } from '../../shared/trace-repeats';
import { trackGeoJson } from '../../shared/trace-style';
import type { LineCollection } from '../../shared/trace-style';

/** While a walk records, the repeats are worked out again at most this often (docs/11 5.27.3, cost and limits). */
export const LIVE_REDRAW_MS = 5000;

const EMPTY: LineCollection = { type: 'FeatureCollection', features: [] };

/**
 * The shared state of the walks on the Map: settings, the drawn lines and counts, and the *Save this walk?* question.
 * Read by the card, the sheet, the place check and the page.
 */
@Injectable({ providedIn: 'root' })
export class TraceView {
  private readonly store = inject(TraceStore);
  private readonly recorder = inject(TraceRecorderService);
  private readonly announcer = inject(Announcer);

  readonly traceOn = signal(false);
  readonly look = signal<RepeatLook>('CLEAR');
  readonly alertOn = signal(false);
  readonly keepAwake = signal(false);
  /** Every walk whole and every shown repeat stretch, for the `track` source. */
  readonly walks = signal<LineCollection>(EMPTY);
  /** How many walks are drawn, how many repeated stretches, and how many saved walks the browser holds. */
  readonly walkCount = signal(0);
  readonly repeatCount = signal(0);
  readonly savedCount = signal(0);
  /** The place check's halo for the `track-check` source; null when there is no answer open. */
  readonly check = signal<LineCollection | null>(null);
  /** The walk *Save this walk?* is open for, or null. */
  readonly ask = signal<WalkSummary | null>(null);
  /** True when the walks could not be read from the browser's storage. */
  readonly loadError = signal(false);
  /** False in memory (private browsing): a walk then lives for the page only. */
  readonly kept = signal(true);

  private lastKey = '';
  private lastLiveAt = 0;
  private liveTimer: ReturnType<typeof setTimeout> | undefined;

  constructor() {
    // While a walk records, each kept point may redraw the repeats, at most once per LIVE_REDRAW_MS.
    effect(() => {
      this.recorder.keptCount();
      if (this.recorder.state() === 'idle') return;
      untracked(() => this.liveTick());
    });
  }

  /** The Map opened: settings, prune, draw, and the walk to ask about (the live walk is never asked about). */
  async open(): Promise<void> {
    try {
      const [on, look, alert, awake, kept] = await Promise.all([
        this.store.traceOn(),
        this.store.look(),
        this.store.alertOn(),
        this.store.keepAwake(),
        this.store.persistent(),
      ]);
      this.traceOn.set(on);
      this.look.set(look);
      this.alertOn.set(alert);
      this.keepAwake.set(awake);
      this.kept.set(kept);
      await this.load({ prune: true, ask: this.recorder.liveWalkId() });
    } catch {
      this.loadError.set(true);
    }
  }

  /** Reads the walks and draws them. The repeat detection is skipped when no walk changed. */
  async refresh(): Promise<void> {
    try {
      await this.load({});
    } catch {
      this.loadError.set(true);
    }
  }

  /**
   * One read of each store (S4b-FR-31): draws the walks and, with `ask` (the live walk's id), sets the walk the sheet is for;
   * with `prune`, the points older than 30 days go from the same rows.
   */
  private async load(options: { prune?: boolean; ask?: number }): Promise<void> {
    // A watermark that cannot be read asks about nothing (never about every walk again).
    const askedUpTo = options.ask === undefined ? undefined : await this.store.askedUpTo().catch(() => undefined);
    const { trace, saved, ask } = await this.store.snapshot(Date.now(), { prune: options.prune, liveWalkId: options.ask, askedUpTo });
    if (options.ask !== undefined) this.ask.set(ask);
    const list: TraceWalk[] = [...trace, ...saved.map((s) => s.walk)];
    this.savedCount.set(saved.length);
    this.loadError.set(false);
    const key = list.map((w) => `${w.key}:${w.points.length}`).join('|');
    if (key === this.lastKey) return;
    this.lastKey = key;
    const repeats = detectRepeats(list);
    this.walks.set(trackGeoJson(list, repeats));
    this.walkCount.set(list.length);
    this.repeatCount.set(repeats.reduce((n, r) => n + r.shown.length, 0));
  }

  /** Redraws while a walk records, at most once per redraw interval. */
  private liveTick(): void {
    const wait = this.lastLiveAt + LIVE_REDRAW_MS - Date.now();
    if (wait <= 0) {
      this.lastLiveAt = Date.now();
      void this.refresh();
    } else if (this.liveTimer === undefined) {
      this.liveTimer = setTimeout(() => {
        this.liveTimer = undefined;
        this.lastLiveAt = Date.now();
        void this.refresh();
      }, wait);
    }
  }

  // ---- the walk ----

  /**
   * *Start a walk*: the recorder is called first, in the same call as the click, so the sound and the location prompt still
   * count as part of it (nothing is awaited before). Then the walk a closed tab cut is asked about.
   */
  async startWalk(): Promise<void> {
    this.recorder.start();
    await this.findAsk(0);
  }

  /** *Finish walk*: ends it, waits for the last fix to be written, prunes, redraws and asks what to do with it. */
  async finishWalk(): Promise<void> {
    this.recorder.finish();
    await this.recorder.settled();
    try {
      await this.load({ prune: true, ask: 0 });
    } catch {
      this.loadError.set(true);
      this.ask.set(null);
    }
  }

  /** Finds the latest ended walk that has not been asked about; null if none or the store fails. */
  private async findAsk(liveWalkId: number): Promise<void> {
    try {
      this.ask.set(await this.store.lastEndedWalk(liveWalkId, await this.store.askedUpTo()));
    } catch {
      this.ask.set(null);
    }
  }

  // ---- the answers of *Save this walk?* ----

  /** *Keep for 30 days*, and the sheet closed any other way. */
  async answerKeep(): Promise<void> {
    const walk = this.ask();
    if (!walk) return;
    await this.store.setAskedUpTo(walk.walkId);
    this.ask.set(null);
    this.announcer.announce({ key: 'trace.kept.snack' });
  }

  /** *Delete this walk*: removes it from the trace and counts it as asked. */
  async answerDelete(): Promise<void> {
    const walk = this.ask();
    if (!walk) return;
    await this.store.deleteTraceWalk(walk.walkId);
    await this.store.setAskedUpTo(walk.walkId);
    this.ask.set(null);
    await this.refresh();
    this.announcer.announce({ key: 'trace.deleted.snack' });
  }

  /** Saves the walk with a house. A refused save changes nothing: the sheet stays open and the reason is returned. */
  async answerSave(houseId: string, houseName = ''): Promise<SaveWalkResult> {
    const walk = this.ask();
    if (!walk) return { ok: false, reason: 'noWalk' };
    const result = await this.store.saveWalk(walk.walkId, houseId, Date.now());
    if (!result.ok) return result;
    await this.store.setAskedUpTo(walk.walkId);
    this.ask.set(null);
    await this.refresh();
    this.announcer.announce({ key: 'trace.saved.snack', params: { house: houseName } });
    return result;
  }

  // ---- settings and the two deletes ----

  /** Turns the path trace on or off and remembers it. */
  async setTraceOn(on: boolean): Promise<void> {
    this.traceOn.set(on);
    await this.store.setTraceOn(on);
  }
  /** Chooses how repeated stretches look and remembers it. */
  async setLook(look: RepeatLook): Promise<void> {
    this.look.set(look);
    await this.store.setLook(look);
  }
  /** Turns the retrace alert on or off, remembers it and applies it to a walk already recording. */
  async setAlertOn(on: boolean): Promise<void> {
    this.alertOn.set(on);
    await this.store.setAlertOn(on);
    await this.recorder.setAlertOn(on); // a walk now recording follows the switch at once
  }
  /** Turns the screen lock setting on or off and remembers it. */
  async setKeepAwake(on: boolean): Promise<void> {
    this.keepAwake.set(on);
    await this.store.setKeepAwake(on);
  }

  /** *Clear the path*: the 30-day trace only. */
  async clearTrace(): Promise<void> {
    await this.store.clearTrace();
    await this.refresh();
    this.announcer.announce({ key: 'trace.card.cleared' });
  }

  /** *Delete all saved walks* (the caller has confirmed with the count). */
  async deleteAllSaved(): Promise<void> {
    await this.store.deleteAllSavedWalks();
    await this.refresh();
    this.announcer.announce({ key: 'trace.settings.deleted' });
  }
}
