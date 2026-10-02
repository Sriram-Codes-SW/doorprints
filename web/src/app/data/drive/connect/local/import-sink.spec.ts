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

import { beforeEach, describe, expect, it } from 'vitest';
import { MemoryStagingSink, StagingSinkError } from './import-sink';

describe('MemoryStagingSink', () => {
  let sink: MemoryStagingSink;

  beforeEach(() => {
    sink = new MemoryStagingSink();
  });

  it('collects written bytes into a blob', async () => {
    const chunk1 = new Uint8Array([1, 2, 3, 4]);
    const chunk2 = new Uint8Array([5, 6, 7, 8]);

    await sink.write(chunk1);
    await sink.write(chunk2);

    const blob = sink.blob;
    expect(blob).not.toBeNull();
    expect(blob!.size).toBe(8);

    const buffer = await blob!.arrayBuffer();
    const result = new Uint8Array(buffer);
    expect(result).toEqual(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]));
  });

  it('returns null blob when discarded', async () => {
    const chunk = new Uint8Array([1, 2, 3]);
    await sink.write(chunk);
    await sink.discard();

    expect(sink.blob).toBeNull();
  });

  it('enforces 200 MB capacity limit', async () => {
    const largeChunk = new Uint8Array(150 * 1024 * 1024); // 150 MB

    await sink.write(largeChunk);
    expect(sink.size).toBe(150 * 1024 * 1024);

    // Writing another 50 MB should succeed (total 200 MB).
    const chunk2 = new Uint8Array(50 * 1024 * 1024);
    await sink.write(chunk2);
    expect(sink.size).toBe(200 * 1024 * 1024);

    // Writing 1 more byte should fail.
    const overLimit = new Uint8Array([1]);
    try {
      await sink.write(overLimit);
      expect.fail('Should have thrown');
    } catch (e) {
      expect(e).toBeInstanceOf(StagingSinkError);
      expect((e as StagingSinkError).kind).toBe('CAPACITY_EXCEEDED');
    }
  });

  it('throws CAPACITY_EXCEEDED error with correct kind', async () => {
    const largeChunk = new Uint8Array(200 * 1024 * 1024 + 1); // Over the limit

    try {
      await sink.write(largeChunk);
      expect.fail('Should have thrown');
    } catch (e) {
      expect(e).toBeInstanceOf(StagingSinkError);
      expect((e as StagingSinkError).kind).toBe('CAPACITY_EXCEEDED');
    }
  });

  it('rejects writes after discard', async () => {
    await sink.discard();

    const chunk = new Uint8Array([1, 2, 3]);

    try {
      await sink.write(chunk);
      expect.fail('Should have thrown');
    } catch (e) {
      expect(e).toBeInstanceOf(StagingSinkError);
      expect((e as StagingSinkError).kind).toBe('WRITE_FAILED');
    }
  });

  it('tracks size correctly', async () => {
    expect(sink.size).toBe(0);

    await sink.write(new Uint8Array(1000));
    expect(sink.size).toBe(1000);

    await sink.write(new Uint8Array(500));
    expect(sink.size).toBe(1500);

    await sink.discard();
    expect(sink.size).toBe(0);
  });

  it('isDiscarded() reports state correctly', async () => {
    expect(sink.isDiscarded()).toBe(false);

    await sink.discard();
    expect(sink.isDiscarded()).toBe(true);
  });

  it('grows buffer as needed', async () => {
    // Write multiple small chunks that exceed the initial 1 MB buffer.
    for (let i = 0; i < 5; i++) {
      await sink.write(new Uint8Array(512 * 1024)); // 512 KB each
    }

    expect(sink.size).toBe(5 * 512 * 1024);
    const blob = sink.blob;
    expect(blob!.size).toBe(5 * 512 * 1024);
  });

  it('returns ZIP-like blob for valid backup', async () => {
    // Write a minimal ZIP file header (PK\x03\x04).
    const zipHeader = new Uint8Array([0x50, 0x4b, 0x03, 0x04]);
    await sink.write(zipHeader);

    const blob = sink.blob;
    expect(blob).not.toBeNull();
    expect(blob!.type).toBe('application/zip');

    const buffer = await blob!.arrayBuffer();
    expect(new Uint8Array(buffer)[0]).toBe(0x50); // 'P'
    expect(new Uint8Array(buffer)[1]).toBe(0x4b); // 'K'
  });

  it('clears buffer on discard to free memory', async () => {
    const chunk = new Uint8Array(10 * 1024 * 1024); // 10 MB
    await sink.write(chunk);

    const sizeBeforeDiscard = sink.size;
    await sink.discard();
    const sizeAfterDiscard = sink.size;

    expect(sizeBeforeDiscard).toBe(10 * 1024 * 1024);
    expect(sizeAfterDiscard).toBe(0);
  });

  it('blob is null when empty', async () => {
    expect(sink.blob).toBeNull();
  });
});
