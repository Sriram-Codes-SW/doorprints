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

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ImageResizeError, resizeImage } from './image-resize';

/**
 * `resizeImage` against a fake canvas and a fake `createImageBitmap`: jsdom has neither. docs/01 PRV-008, docs/06
 * TC-U-11: a stored photo carries no GPS tag because the pixels are drawn on a canvas and the canvas is re-encoded;
 * the original bytes (with their Exif block) are never passed through, not even when no downscale is needed. What
 * these tests cannot see: that a real browser's `toBlob` writes no Exif (the platform's documented behaviour, not
 * checked in a real browser by any test here).
 */

/** The marker bytes stand for the camera's APP1 Exif segment (`FF E1 .. "Exif"`) with a GPS position. */
const EXIF_JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe1, 0x00, 0x10, 0x45, 0x78, 0x69, 0x66, 0x00, 0x00, 0x47, 0x50, 0x53, 0xff, 0xd9]);
const REENCODED = new Uint8Array([0xff, 0xd8, 0xff, 0xdb, 0xff, 0xd9]);

interface FakeCanvas {
  width: number;
  height: number;
  drawn: { source: unknown; w: number; h: number }[];
  encoded: { type?: string; quality?: unknown }[];
  getContext: () => unknown;
  toBlob: (cb: (b: Blob | null) => void, type?: string, quality?: unknown) => void;
}

let canvas: FakeCanvas;
let closed: number;
let contextAvailable: boolean;
let encodeFails: boolean;

function bitmapOf(width: number, height: number) {
  const bmp = { width, height, close: () => void closed++ };
  vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue(bmp));
  return bmp;
}

async function bytes(blob: Blob): Promise<number[]> {
  return [...new Uint8Array(await blob.arrayBuffer())];
}

describe('resizeImage (PRV-008, TC-U-11)', () => {
  beforeEach(() => {
    closed = 0;
    contextAvailable = true;
    encodeFails = false;
    canvas = {
      width: 0,
      height: 0,
      drawn: [],
      encoded: [],
      getContext: () =>
        contextAvailable
          ? { fillStyle: '', fillRect: () => undefined, drawImage: (source: unknown, _x: number, _y: number, w: number, h: number) => canvas.drawn.push({ source, w, h }) }
          : null,
      toBlob: (cb, type, quality) => {
        canvas.encoded.push({ type, quality });
        cb(encodeFails ? null : new Blob([REENCODED], { type: type ?? '' }));
      },
    };
    const real = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag: string, options?: ElementCreationOptions) =>
      tag === 'canvas' ? (canvas as unknown as HTMLElement) : real(tag, options),
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('returns the canvas re-encode, never the original bytes, when no downscale is needed', async () => {
    const bmp = bitmapOf(800, 600);
    const original = new Blob([EXIF_JPEG], { type: 'image/jpeg' });
    expect(await bytes(original)).toContain(0xe1); // the source really has an Exif segment

    const out = await resizeImage(original);

    expect(out).not.toBe(original);
    expect(await bytes(out)).toEqual([...REENCODED]);
    expect(canvas.drawn).toEqual([{ source: bmp, w: 800, h: 600 }]); // 800 x 600 is under 1600: same size, still re-drawn
    expect(canvas.encoded).toEqual([{ type: 'image/jpeg', quality: 0.8 }]);
    expect(out.type).toBe('image/jpeg');
  });

  it('shrinks the longest side to 1600 px and re-encodes', async () => {
    bitmapOf(3200, 2400);
    await resizeImage(new Blob([EXIF_JPEG]));
    expect([canvas.width, canvas.height]).toEqual([1600, 1200]);
    expect(canvas.drawn.map((d) => [d.w, d.h])).toEqual([[1600, 1200]]);
  });

  it('asks the browser to apply the Exif orientation to the pixels', async () => {
    bitmapOf(100, 100);
    const original = new Blob([EXIF_JPEG]);
    await resizeImage(original);
    expect(vi.mocked(createImageBitmap)).toHaveBeenCalledWith(original, { imageOrientation: 'from-image' });
  });

  it('fails with the "canvas" reason when there is no 2d context, and still releases the bitmap', async () => {
    bitmapOf(10, 10);
    contextAvailable = false;
    await expect(resizeImage(new Blob([EXIF_JPEG]))).rejects.toEqual(new ImageResizeError('canvas'));
    expect(closed).toBe(1);
  });

  it('fails with the "encode" reason when the canvas gives no blob, never falling back to the original', async () => {
    bitmapOf(10, 10);
    encodeFails = true;
    await expect(resizeImage(new Blob([EXIF_JPEG]))).rejects.toEqual(new ImageResizeError('encode'));
    expect(closed).toBe(1);
  });
});
