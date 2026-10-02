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

import type { TokenProvider } from './drive-client';
import type { FakeDriveServer } from './in-memory-fake-drive';

/*
 * The fault script of the fake Drive (S4b-BL-115; Kotlin `FakeDriveFaults.kt`, the same names): deterministic, so a
 * later ticket's test can say "the 3rd call answers 503" and get exactly that.
 */

/** The kinds of request the fake counts, one per DriveClient request. */
export type DriveOp =
  | 'ABOUT' | 'LIST' | 'GET' | 'CREATE' | 'UPLOAD' | 'UPLOAD_START' | 'UPLOAD_CHUNK' | 'UPLOAD_STATUS'
  | 'UPDATE' | 'DOWNLOAD' | 'DELETE' | 'TRASH' | 'REVISIONS';

/** What the fake does instead of (or as well as) answering one request; see the Kotlin `DriveFault` for each. */
export type DriveFault =
  | { readonly kind: 'offline' }
  | { readonly kind: 'tokenExpired' }
  | { readonly kind: 'forbidden' }
  | { readonly kind: 'quotaExceeded' }
  | { readonly kind: 'rateLimited'; readonly retryAfterMs?: number | null; readonly as403?: boolean }
  | { readonly kind: 'server'; readonly status?: number; readonly retryAfterMs?: number | null }
  | { readonly kind: 'notFound' }
  | { readonly kind: 'conflict' }
  | { readonly kind: 'cancelled' }
  /** Done, then the connection drops: a retried create makes a duplicate. */
  | { readonly kind: 'responseLost' }
  /** An upload chunk: the first `bytes` arrive, then the connection drops. */
  | { readonly kind: 'dropAfter'; readonly bytes: number }
  /** One byte changed on the way: Drive stores and checksums other bytes than were sent. */
  | { readonly kind: 'corruptContent' }
  /** A metadata answer without `sha256Checksum`. */
  | { readonly kind: 'lateChecksum' }
  /** Another writer (or the person) acts just before this request. */
  | { readonly kind: 'interleave'; readonly action: (server: FakeDriveServer) => void };

/** Shorthands, as the Kotlin objects: `DriveFaults.server(503)`. */
export const DriveFaults = {
  offline: { kind: 'offline' } as DriveFault,
  tokenExpired: { kind: 'tokenExpired' } as DriveFault,
  forbidden: { kind: 'forbidden' } as DriveFault,
  quotaExceeded: { kind: 'quotaExceeded' } as DriveFault,
  notFound: { kind: 'notFound' } as DriveFault,
  conflict: { kind: 'conflict' } as DriveFault,
  cancelled: { kind: 'cancelled' } as DriveFault,
  responseLost: { kind: 'responseLost' } as DriveFault,
  corruptContent: { kind: 'corruptContent' } as DriveFault,
  lateChecksum: { kind: 'lateChecksum' } as DriveFault,
  rateLimited: (retryAfterMs: number | null = null, as403 = false): DriveFault => ({ kind: 'rateLimited', retryAfterMs, as403 }),
  server: (status = 503, retryAfterMs: number | null = null): DriveFault => ({ kind: 'server', status, retryAfterMs }),
  dropAfter: (bytes: number): DriveFault => ({ kind: 'dropAfter', bytes }),
  interleave: (action: (server: FakeDriveServer) => void): DriveFault => ({ kind: 'interleave', action }),
};

/**
 * Which request gets which fault. Requests are counted from 1 since the server was made, overall and per op; the first
 * rule that matches wins, in this order: `onCall`, `on`, `next`, `always`, `stopAfter`.
 */
export class FaultScript {
  private readonly byCall = new Map<number, DriveFault>();
  private readonly byOp = new Map<string, DriveFault>();
  private readonly queued: { op: DriveOp | null; fault: DriveFault; left: number }[] = [];
  private readonly standing: { op: DriveOp | null; fault: DriveFault }[] = [];
  private stopAt: number | null = null;
  private stopFault: DriveFault = DriveFaults.offline;

  onCall(n: number, fault: DriveFault): this {
    this.byCall.set(n, fault);
    return this;
  }

  on(op: DriveOp, nth: number, fault: DriveFault): this {
    this.byOp.set(`${op}#${nth}`, fault);
    return this;
  }

  next(fault: DriveFault, op: DriveOp | null = null, times = 1): this {
    this.queued.push({ op, fault, left: times });
    return this;
  }

  always(fault: DriveFault, op: DriveOp | null = null): this {
    this.standing.push({ op, fault });
    return this;
  }

  stopAfter(n: number, fault: DriveFault = DriveFaults.offline): this {
    this.stopAt = n;
    this.stopFault = fault;
    return this;
  }

  clear(): this {
    this.byCall.clear();
    this.byOp.clear();
    this.queued.length = 0;
    this.standing.length = 0;
    this.stopAt = null;
    return this;
  }

  /** @internal */
  take(op: DriveOp, call: number, opCall: number): DriveFault | null {
    const byCall = this.byCall.get(call);
    if (byCall) {
      this.byCall.delete(call);
      return byCall;
    }
    const key = `${op}#${opCall}`;
    const byOp = this.byOp.get(key);
    if (byOp) {
      this.byOp.delete(key);
      return byOp;
    }
    const q = this.queued.find((e) => e.op == null || e.op === op);
    if (q) {
      if (--q.left <= 0) this.queued.splice(this.queued.indexOf(q), 1);
      return q.fault;
    }
    const s = this.standing.find((e) => e.op == null || e.op === op);
    if (s) return s.fault;
    if (this.stopAt != null && call > this.stopAt) return this.stopFault;
    return null;
  }
}

/** A clock that moves only by `advance` and `sleep` (which records what the retry waited). */
export class FakeClock {
  readonly slept: number[] = [];

  constructor(public nowMs = 1_790_000_000_000) {}

  now(): number {
    return this.nowMs;
  }

  advance(ms: number): void {
    this.nowMs += ms;
  }

  sleep(ms: number): void {
    this.slept.push(ms);
    this.advance(ms);
  }
}

/** Tokens `token-1`, `token-2`, …: `accessToken` gives the current one, `onRejected` moves to the next. */
export class FakeTokenProvider implements TokenProvider {
  private n = 1;
  readonly rejected: string[] = [];
  failWith: unknown = null;

  async accessToken(): Promise<string> {
    if (this.failWith) throw this.failWith;
    return `token-${this.n}`;
  }

  async onRejected(token: string): Promise<void> {
    this.rejected.push(token);
    if (token === `token-${this.n}`) this.n++;
  }
}
