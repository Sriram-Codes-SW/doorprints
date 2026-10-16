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
 * Seam for running async functions under an exclusive lock to prevent concurrent access.
 * Folder creation uses `doorprints-drive-create`. A sync pass uses `doorprints-drive-sync`,
 * so two tabs of one browser sync one at a time (docs/15 §5.1).
 */

/** Web Locks name for a Drive sync pass. Two tabs share one device, so only one pass runs. */
export const DRIVE_SYNC_LOCK = 'doorprints-drive-sync';
/**
 * Runs a function while holding a named exclusive lock; the browser version uses Web Locks, tests use an in-process one.
 */
export interface LockRunner {
  /**
   * Acquire an exclusive lock and run the function. The function may not run immediately
   * if other callers hold the lock; it will run once its turn comes.
   * @param lockName The lock name (e.g., 'doorprints-drive-create')
   * @param fn The async function to run
   * @returns The result of fn()
   */
  request<T>(lockName: string, fn: () => Promise<T>): Promise<T>;
}

/**
 * Production LockRunner using the browser's Web Locks API.
 * If Web Locks is not available, runs unlocked (fallback for older browsers).
 *
 * Docs: https://developer.mozilla.org/en-US/docs/Web/API/Lock
 */
export class WebLockRunner implements LockRunner {
  async request<T>(lockName: string, fn: () => Promise<T>): Promise<T> {
    // Feature-detect Web Locks
    if (!navigator.locks) {
      return fn();
    }
    // Acquire exclusive lock and run the function
    return navigator.locks.request(lockName, { mode: 'exclusive' }, fn);
  }
}
