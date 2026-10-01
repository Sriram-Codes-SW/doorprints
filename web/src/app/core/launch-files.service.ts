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

import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';

/** The part of a `FileSystemFileHandle` the consumer uses. */
export interface LaunchFileHandle {
  readonly kind?: string;
  getFile(): Promise<File>;
}

/** The part of Chromium's `LaunchParams` the consumer uses (File Handling API). */
export interface LaunchParamsLike {
  readonly files?: readonly LaunchFileHandle[];
}

/** The part of `window.launchQueue` the consumer uses. */
export interface LaunchQueueLike {
  setConsumer(consumer: (params: LaunchParamsLike) => void): void;
}

/** Where *Import a backup* lives (the manifest's `file_handlers[0].action` is the same page, `./data`). */
export const IMPORT_ROUTE = '/data';

/** A Doorprints backup or update file is a ZIP (`Doorprints-backup-<date>.zip`); the reader checks the rest. */
export function isBackupFileName(name: string | undefined | null): boolean {
  return typeof name === 'string' && /\.zip$/i.test(name.trim());
}

/** `window.launchQueue` when the browser has the File Handling API (Chromium on a computer, installed app), else null. */
export function launchQueueOf(win: unknown): LaunchQueueLike | null {
  const queue = (win as { launchQueue?: { setConsumer?: unknown } } | null | undefined)?.launchQueue;
  return queue && typeof queue.setConsumer === 'function' ? (queue as LaunchQueueLike) : null;
}

/**
 * Opening a backup from the system (S4b-BL-108): the installed website on a computer is offered as an app for `.zip`
 * files (`file_handlers` in `public/manifest.webmanifest`). Chromium opens the app at `./data` and puts the file's
 * handle on `window.launchQueue`; this hands that one file to *Import a backup* ({@link pending}), which checks it and
 * shows its preview. Nothing is written before the person presses *Import*, and the file is read in this browser only:
 * no network. Two or more files, or a file that is not a `.zip`, are ignored (the card imports one backup at a time).
 * Without the API (Firefox, Safari, a phone, a browser tab) {@link start} does nothing.
 */
@Injectable({ providedIn: 'root' })
export class LaunchFilesService {
  private readonly router = inject(Router);

  /** The file the system opened the app with, until *Import a backup* takes it. */
  readonly pending = signal<File | null>(null);

  /** Registers the consumer; false (and nothing done) when the browser has no launch queue. */
  start(win: unknown = typeof window === 'undefined' ? undefined : window): boolean {
    const queue = launchQueueOf(win);
    if (!queue) return false;
    queue.setConsumer((params) => void this.receive(params));
    return true;
  }

  /** Takes the pending file, once. */
  take(): File | null {
    const file = this.pending();
    if (file) this.pending.set(null);
    return file;
  }

  /** One launch: true when a backup was handed on. */
  async receive(params: LaunchParamsLike | null | undefined): Promise<boolean> {
    const handles = params?.files;
    if (!handles || handles.length !== 1) return false;
    const handle = handles[0];
    if (!handle || (handle.kind !== undefined && handle.kind !== 'file') || typeof handle.getFile !== 'function') return false;
    let file: File;
    try {
      file = await handle.getFile();
    } catch {
      return false; // The file went away or the permission was withdrawn: nothing to import.
    }
    if (!isBackupFileName(file?.name)) return false;
    this.pending.set(file);
    if (this.router.url.split(/[?#]/)[0] === IMPORT_ROUTE) return true;
    // "Keep editing" in the house page's unsaved-changes dialog cancels the move: the file is then dropped, so a
    // later visit to Your data does not open a preview the person no longer expects.
    const moved = await this.router.navigateByUrl(IMPORT_ROUTE).catch(() => false);
    if (!moved) this.pending.set(null);
    return moved;
  }
}
