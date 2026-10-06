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
 * Records a walk on the website (docs/11 5.27.8, docs/03 section 6.2b row 5, S4b-FR-17): the browser's Geolocation API
 * (`watchPosition`), ONLY after the person has pressed *Start a walk*, ONLY while the page is open and visible. The service,
 * not the page, owns the watch, the screen lock and the beep, so the Map page may be left mid-walk and its card re-binds
 * on return.
 *
 * - The permission is asked only by {@link TraceRecorderService.start} (a user gesture); nothing asks on page load (PRV-031).
 * - Fixes go through {@link TraceRecorder}: the 50 m accuracy gate, the 20 m or 5 minutes thinning, the walk id.
 * - A hidden page pauses the walk; coming back after more than 5 minutes marks the next point *resumed* (no line across
 *   the pause); a gap of 30 minutes or more is a new walk.
 * - The Screen Wake Lock is optional (`trace.keepAwake`): requested at the start, released at the finish, requested again when
 *   the page is visible again (the browser drops it when the page is hidden).
 * - With the alert switched on, each kept point goes through {@link RepeatAlert} against the other walks (the 30-day trace and
 *   every saved walk, except the live one); a ring beeps (or vibrates), raises {@link TraceRecorderService.alertRaised} and
 *   calls {@link TraceRecorderService.onAlert}.
 * - A storage error stops the walk with a message ({@link TraceRecorderService.problem}); it never retries in a loop.
 *
 * Nothing here logs, and no fix or point is sent anywhere (PRV-028, PRV-031): the only writes are the trace store's.
 */

import { DOCUMENT } from '@angular/common';
import { Injectable, InjectionToken, OnDestroy, inject, signal } from '@angular/core';
import { TraceStore, holdsWalkId } from '../data/trace-store';
import { TRACE } from '../shared/trace-geo';
import type { TracePoint, TraceWalk } from '../shared/trace-geo';
import { TraceRecorder } from '../shared/trace-recorder';
import type { RecorderOptions } from '../shared/trace-recorder';
import { RepeatAlert } from '../shared/trace-repeats';
import { AlertSound } from './alert-sound.service';
import { isQuotaError } from './format';

/** The browser's Geolocation, or null where there is none; a token so a test can drive it. */
export const GEOLOCATION = new InjectionToken<Geolocation | null>('GEOLOCATION', {
  providedIn: 'root',
  factory: () => (typeof navigator !== 'undefined' && 'geolocation' in navigator ? navigator.geolocation : null),
});

/** The Screen Wake Lock API, or null where the browser has none. */
export const WAKE_LOCK = new InjectionToken<WakeLock | null>('WAKE_LOCK', {
  providedIn: 'root',
  factory: () => (typeof navigator !== 'undefined' && 'wakeLock' in navigator ? navigator.wakeLock : null),
});

/** Overrides the recorder's thinning (a test); the app uses the defaults (20 m, 5 minutes). */
export const RECORDER_OPTIONS = new InjectionToken<RecorderOptions>('RECORDER_OPTIONS', { providedIn: 'root', factory: () => ({}) });

/** The options `watchPosition` is called with (docs/11 5.27.8). */
export const WATCH_OPTIONS: PositionOptions = { enableHighAccuracy: true, maximumAge: 0, timeout: 30_000 };

export type RecorderState = 'idle' | 'recording' | 'paused';

/** Why a walk is not recording, for the card to say in words. */
export type TraceProblem =
  | 'unsupported' // the browser cannot give a location
  | 'denied' // the location is blocked for this site
  | 'unavailable' // no position right now (it may come back)
  | 'full' // the browser's storage is full: the walk stopped
  | 'storage'; // another storage failure: the walk stopped

@Injectable({ providedIn: 'root' })
export class TraceRecorderService implements OnDestroy {
  private readonly store = inject(TraceStore);
  private readonly sound = inject(AlertSound);
  private readonly doc = inject(DOCUMENT);
  private readonly geolocation = inject(GEOLOCATION);
  private readonly wakeLockApi = inject(WAKE_LOCK);

  /** Which fixes are kept; public so a test can replace the thinning, never the gate. */
  private recorder = new TraceRecorder(inject(RECORDER_OPTIONS));
  private readonly alert = new RepeatAlert();

  readonly state = signal<RecorderState>('idle');
  readonly problem = signal<TraceProblem | null>(null);
  /** True after the page came back from being hidden mid-walk: the card says *Recording paused while this page was hidden.* until dismissed. */
  readonly pausedNotice = signal(false);
  /** The points kept in this walk so far. */
  readonly keptCount = signal(0);
  /** Counts the alerts of this page; the Map's banner shows when it changes. */
  readonly alertRaised = signal(0);
  /** True when the screen lock was asked for and the browser refused. */
  readonly keepAwakeFailed = signal(false);
  /** False where the browser has no Wake Lock: the switch is then not shown. */
  readonly wakeLockSupported = this.wakeLockApi !== null;
  /** True where the walk is being kept in the browser; false in memory (private browsing): the walk then lives for this page only. */
  readonly keptInBrowser = signal(true);

  /** Called with the length in metres of the run when the alert rings (also {@link alertRaised} and the beep). */
  onAlert: ((runM: number) => void) | null = null;

  private watchId: number | null = null;
  private wakeLock: WakeLockSentinel | null = null;
  private live: TracePoint[] = [];
  private others: readonly TraceWalk[] = [];
  private alertOn = false;
  private keepAwake = false;
  private ready: Promise<void> = Promise.resolve();
  private chain: Promise<void> = Promise.resolve();
  private hiddenAt = 0;
  private listening = false;
  private readonly onVisibility = () => this.visibilityChanged();
  private readonly onPageHide = () => this.endWalk();

  /** The walk id of the walk now recording, or 0: the place check leaves it out for *here*. */
  liveWalkId(): number {
    return this.recorder.liveWalkId;
  }

  /** The points kept in this walk so far (a copy). */
  livePoints(): TracePoint[] {
    return [...this.live];
  }

  /**
   * *Start a walk*: call it from the click. Prepares the sound and asks the browser for the location (its own permission
   * prompt) in the same call, before anything is awaited, so both still count as part of the click.
   */
  start(): void {
    if (this.state() !== 'idle') return;
    this.problem.set(null);
    this.pausedNotice.set(false);
    this.keepAwakeFailed.set(false);
    this.sound.prepare();
    if (this.geolocation === null) {
      this.problem.set('unsupported');
      return;
    }
    this.recorder.finish();
    this.alert.reset();
    this.live = [];
    this.keptCount.set(0);
    this.state.set('recording');
    this.watch();
    this.listen(true);
    this.ready = this.loadContext();
  }

  /**
   * *Finish walk* (or a closed page): stops the watch, releases the screen lock and ends the walk, so the next *Start a walk* is
   * a new walk id. Returns the id of the walk that ended, 0 when it never kept a point. The caller then asks the store for the
   * walk to ask about.
   */
  finish(): number {
    const id = this.recorder.liveWalkId;
    this.endWalk();
    return id;
  }

  /** Resolves when every fix received so far has been handled (kept, stored, tested for the alert): for the tests and for a caller that must read the store after a fix. */
  async settled(): Promise<void> {
    await this.ready;
    await this.chain;
  }

  /** Hides the *Recording paused* line. */
  dismissPausedNotice(): void {
    this.pausedNotice.set(false);
  }

  ngOnDestroy(): void {
    this.endWalk();
  }

  // ---- the watch ----

  private watch(): void {
    const geo = this.geolocation;
    if (geo === null) return;
    this.watchId = geo.watchPosition(
      (position) => this.enqueue(position),
      (error) => this.failed(error),
      WATCH_OPTIONS,
    );
  }

  private unwatch(): void {
    if (this.watchId !== null) this.geolocation?.clearWatch(this.watchId);
    this.watchId = null;
  }

  private failed(error: GeolocationPositionError): void {
    if (this.state() === 'idle') return;
    if (error.code === 1) {
      this.problem.set('denied');
      this.endWalk();
    } else {
      this.problem.set('unavailable'); // a position may still come: the watch stays
    }
  }

  private enqueue(position: GeolocationPosition): void {
    this.chain = this.chain.then(() => this.handleFix(position)).catch(() => undefined);
  }

  private async handleFix(position: GeolocationPosition): Promise<void> {
    if (this.state() !== 'recording') return;
    await this.ready;
    if (this.state() !== 'recording') return;
    const { latitude, longitude, accuracy } = position.coords;
    const before = this.recorder.liveWalkId;
    const point = this.recorder.accept({ lat: latitude, lon: longitude, atMs: position.timestamp, accuracyM: accuracy });
    if (point === null) return;
    if (this.problem() === 'unavailable') this.problem.set(null);
    try {
      await this.store.putPoint(point, accuracy);
    } catch (err: unknown) {
      this.problem.set(isQuotaError(err) ? 'full' : 'storage');
      this.endWalk(); // stop instead of looping on a store that refuses
      return;
    }
    if (this.state() === 'idle') return;
    if (before !== 0 && before !== point.walkId) {
      // A new walk began (a gap of 30 minutes or more): the walk that ended is now one of the others.
      this.live = [];
      this.keptCount.set(0);
      this.alert.reset();
      this.ready = this.loadContext();
      await this.ready;
    }
    this.live.push(point);
    this.keptCount.update((n) => n + 1);
    if (this.alertOn && this.alert.onPoint(this.live, this.others)) this.raiseAlert(this.alert.lastRunM);
  }

  private raiseAlert(runM: number): void {
    this.sound.beep();
    this.alertRaised.update((n) => n + 1);
    this.onAlert?.(runM);
  }

  /**
   * The *Warn me when I walk a path again* switch, applied at once to a walk now recording (as on Android), not only at the
   * next walk: turning it on loads the other walks, turning it off drops the alert's state. With no walk recording the next
   * *Start a walk* reads the stored setting.
   */
  setAlertOn(on: boolean): Promise<void> {
    this.alertOn = on;
    if (this.state() === 'idle') return Promise.resolve();
    this.alert.reset();
    this.ready = this.ready.then(() => (on ? this.loadOthers() : undefined));
    if (!on) this.others = [];
    return this.ready;
  }

  /** Reads what a walk needs once: the settings, and the other walks for the alert (never the live one). */
  private async loadContext(): Promise<void> {
    try {
      this.alertOn = await this.store.alertOn();
      this.keepAwake = await this.store.keepAwake();
      this.keptInBrowser.set(await this.store.persistent());
      await this.loadOthers();
      if (this.keepAwake) await this.requestWakeLock();
    } catch {
      this.others = [];
    }
  }

  /** The other walks for the alert: the trace and the saved walks, never any walk holding the live walk's points. */
  private async loadOthers(): Promise<void> {
    this.others = [];
    if (!this.alertOn) return;
    try {
      const { trace, saved } = await this.store.allWalks(Date.now());
      const live = this.recorder.liveWalkId;
      this.others = [...trace.filter((w) => !holdsWalkId(w.points, live)), ...saved.map((s) => s.walk)];
    } catch {
      this.others = [];
    }
  }

  // ---- visibility, the end of the page, the screen lock ----

  private listen(on: boolean): void {
    if (on === this.listening) return;
    this.listening = on;
    const win = this.doc.defaultView;
    if (on) {
      this.doc.addEventListener('visibilitychange', this.onVisibility);
      win?.addEventListener('pagehide', this.onPageHide);
    } else {
      this.doc.removeEventListener('visibilitychange', this.onVisibility);
      win?.removeEventListener('pagehide', this.onPageHide);
    }
  }

  private visibilityChanged(): void {
    const state = this.state();
    if (this.doc.visibilityState === 'hidden') {
      if (state !== 'recording') return;
      this.state.set('paused');
      this.hiddenAt = Date.now();
      this.unwatch(); // a hidden page gets no more fixes; do not keep a stale watch
    } else if (state === 'paused') {
      // More than 5 minutes hidden (not exactly 5): no line is drawn across the pause.
      if (Date.now() - this.hiddenAt > TRACE.pauseSplitMs) this.recorder.markResumed();
      this.pausedNotice.set(true);
      this.state.set('recording');
      this.watch();
      if (this.keepAwake) void this.requestWakeLock();
    }
  }

  private async requestWakeLock(): Promise<void> {
    const api = this.wakeLockApi;
    if (api === null) return;
    try {
      this.wakeLock = await api.request('screen');
      this.keepAwakeFailed.set(false);
    } catch {
      this.wakeLock = null;
      this.keepAwakeFailed.set(true);
    }
  }

  private releaseWakeLock(): void {
    const lock = this.wakeLock;
    this.wakeLock = null;
    lock?.release().catch(() => undefined);
  }

  /** Ends the walk: no watch, no lock, no listeners; the next kept point would begin a new walk id. */
  private endWalk(): void {
    this.unwatch();
    this.releaseWakeLock();
    this.listen(false);
    this.recorder.finish();
    this.alert.reset();
    this.live = [];
    this.keptCount.set(0);
    if (this.state() !== 'idle') this.state.set('idle');
  }
}
