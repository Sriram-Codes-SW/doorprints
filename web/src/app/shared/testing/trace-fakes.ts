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
 * Fakes for the specs of the path trace's components: a recorder the test drives (the signals the card reads, the calls
 * the view makes) and a sound that is or is not running. Test tooling, not code under test.
 */

import { signal } from '@angular/core';
import type { RecorderState, TraceProblem } from '../../core/trace-recorder.service';

export class FakeRecorder {
  state = signal<RecorderState>('idle');
  problem = signal<TraceProblem | null>(null);
  pausedNotice = signal(false);
  keptCount = signal(0);
  alertRaised = signal(0);
  keepAwakeFailed = signal(false);
  keptInBrowser = signal(true);
  wakeLockSupported = true;
  live = 0;
  ended = 0;
  starts = 0;
  finishes = 0;
  dismissed = 0;
  start() {
    this.starts++;
    this.state.set('recording');
  }
  finish() {
    this.finishes++;
    this.state.set('idle');
    return this.ended;
  }
  settled() {
    return Promise.resolve();
  }
  liveWalkId() {
    return this.live;
  }
  livePoints() {
    return [];
  }
  dismissPausedNotice() {
    this.dismissed++;
    this.pausedNotice.set(false);
  }
}

export class FakeSound {
  running = signal(false);
}

/** Lets a component's effects, promises and timers of 0 ms settle. */
export async function settle(fixture: { detectChanges(): void; whenStable(): Promise<unknown> }, rounds = 4): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  fixture.detectChanges();
}

/** Like {@link settle}, without `whenStable` (which waits for a pending timer such as a 10 s banner): change detection and microtasks only. */
export async function flush(fixture: { detectChanges(): void }, rounds = 3): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    fixture.detectChanges();
    await Promise.resolve();
    await Promise.resolve();
  }
  fixture.detectChanges();
}
