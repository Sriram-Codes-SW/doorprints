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

import type { ByteSink } from '../../crypto/dpx';
import type { StagingSink } from '../backup/drive-backup-seams';

/** Error thrown when the staging sink's size cap is exceeded. */
export class StagingSinkError extends Error {
  constructor(readonly kind: 'CAPACITY_EXCEEDED' | 'WRITE_FAILED', message: string) {
    super(message);
    this.name = 'StagingSinkError';
  }
}

/**
 * Production {@link StagingSink} for Drive backup import: collects the decrypted backup ZIP in memory
 * (bounded by a 200 MB cap) and provides it for validation and import.
 *
 * The ZIP is held in memory while the {@link ImportService.preview()} and {@link ImportService.apply()}
 * validate and import it. On any error or cancellation, {@link discard()} clears it.
 */
export class MemoryStagingSink implements StagingSink {
  private buffer: Uint8Array;
  private filled = 0;
  private readonly capacity = 200 * 1024 * 1024; // 200 MB limit
  private discarded = false;

  readonly write: ByteSink = {
    write: async (bytes: Uint8Array) => {
      if (this.discarded) {
        throw new StagingSinkError('WRITE_FAILED', 'StagingSink has been discarded');
      }
      if (this.filled + bytes.length > this.capacity) {
        throw new StagingSinkError('CAPACITY_EXCEEDED', `Staged backup would exceed ${this.capacity} bytes`);
      }
      if (this.buffer.length < this.filled + bytes.length) {
        const newSize = Math.max(this.buffer.length * 2, this.filled + bytes.length);
        const grown = new Uint8Array(newSize);
        grown.set(this.buffer.subarray(0, this.filled));
        this.buffer = grown;
      }
      this.buffer.set(bytes, this.filled);
      this.filled += bytes.length;
    },
  };

  constructor() {
    this.buffer = new Uint8Array(1024 * 1024); // Start with 1 MB, grow as needed.
  }

  /** The staged ZIP as a Blob, ready for import validation. Returns null if discarded. */
  get blob(): Blob | null {
    if (this.discarded || this.filled === 0) return null;
    const data = new Uint8Array(this.buffer.buffer, this.buffer.byteOffset, this.filled);
    return new Blob([data as BlobPart], { type: 'application/zip' });
  }

  /** Clear the buffer and mark as discarded (no more writes allowed). */
  async discard(): Promise<void> {
    this.discarded = true;
    this.buffer.fill(0);
    this.buffer = new Uint8Array(0);
    this.filled = 0;
  }

  /** Check if this sink has been discarded. */
  isDiscarded(): boolean {
    return this.discarded;
  }

  /** The number of bytes written so far. */
  get size(): number {
    return this.filled;
  }
}
