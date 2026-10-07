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

import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TOUR_STEPS } from '../shared/tour-steps';
import type { TourStep } from '../shared/tour-steps';
import { TOUR_KEY } from './storage-keys';

/** How the tour ended: finished to the last step, or left early. Either way it is not offered again by itself. */
export type TourEnd = 'done' | 'skipped';

/** The tour is only ever offered on the Map, where a first visit starts. */
const OFFER_ROUTE = '/';

function read(): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(TOUR_KEY);
  } catch {
    return null; // storage blocked: the offer then shows every visit, which is harmless
  }
}

function write(value: TourEnd): void {
  try {
    localStorage.setItem(TOUR_KEY, value);
  } catch {
    // not remembered; the tour is simply offered again next time
  }
}

/**
 * The guided tour (S4b-FR-38): which step is showing, and the one-time offer. The pages know nothing about it; the
 * overlay (`TourOverlay`) reads {@link step}, moves to its route and highlights its target. Finishing or skipping is
 * remembered in localStorage, so the offer comes once; "Take the tour" on Your data starts it again at any time.
 */
@Injectable({ providedIn: 'root' })
export class TourService {
  private readonly router = inject(Router);
  private readonly index = signal<number | null>(null);
  private readonly ended = signal(read() !== null);

  readonly steps: readonly TourStep[] = TOUR_STEPS;
  readonly active = computed(() => this.index() !== null);
  readonly position = computed(() => this.index() ?? 0);
  readonly step = computed<TourStep | null>(() => {
    const i = this.index();
    return i === null ? null : this.steps[i];
  });
  readonly isFirst = computed(() => this.position() === 0);
  readonly isLast = computed(() => this.position() === this.steps.length - 1);

  /** True when the Map should show "Take a quick tour": never seen the tour, and not in it now. */
  offered(url: string): boolean {
    return !this.ended() && !this.active() && url.split(/[?#]/)[0] === OFFER_ROUTE;
  }

  start(): void {
    this.go(0);
  }

  next(): void {
    const i = this.index();
    if (i === null) return;
    if (i >= this.steps.length - 1) this.finish('done');
    else this.go(i + 1);
  }

  back(): void {
    const i = this.index();
    if (i !== null && i > 0) this.go(i - 1);
  }

  /** "Not now" on the offer, or Skip / Escape in the tour. */
  skip(): void {
    this.finish('skipped');
  }

  private go(i: number): void {
    this.index.set(i);
    const route = this.steps[i].route;
    if (this.router.url.split(/[?#]/)[0] !== route) void this.router.navigateByUrl(route);
  }

  private finish(how: TourEnd): void {
    this.index.set(null);
    this.ended.set(true);
    write(how);
  }
}
