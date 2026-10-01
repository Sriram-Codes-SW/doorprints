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

import { crc32 } from './zip';

/**
 * Reads the ZIPs a backup comes in (S4b-BL-75): the website's copy of the common `ZipReader` the iPhone uses
 * (`android/shared/.../export/Zip.kt`). The central directory first, then one entry at a time through
 * {@link ZipFile.readAtMost}, which reads straight from the picked file (`Blob.slice`, so a large backup is never held
 * whole), inflates DEFLATE with the browser's own `DecompressionStream('deflate-raw')` while counting what comes out,
 * and checks the CRC. STORED and DEFLATE only; an encrypted, otherwise compressed or ZIP64 entry is unreadable, never
 * guessed at. No library: the platform has the API.
 */

/** One entry of the central directory as its author wrote it: the two sizes are claims, not facts. */
export interface ZipEntryInfo {
  readonly name: string;
  readonly method: number;
  readonly flags: number;
  readonly crc: number;
  readonly compressedSize: number;
  readonly size: number;
  readonly localOffset: number;
}

export type ZipOpen = { ok: true; zip: ZipFile } | { ok: false; problem: 'NOT_A_ZIP' | 'TOO_MANY_ENTRIES' };

/** An entry that cannot be read: encrypted, compressed another way, cut short, broken or failing its CRC. */
export class ZipEntryError extends Error {}

/** The central directory of 5 000 entries is well under a megabyte. */
const MAX_DIRECTORY_BYTES = 8 * 1024 * 1024;

export async function bytesAt(source: Blob, position: number, count: number): Promise<Uint8Array | null> {
  if (count < 0 || position < 0 || position + count > source.size) return null;
  return new Uint8Array(await source.slice(position, position + count).arrayBuffer());
}

const u16 = (b: Uint8Array, at: number): number => b[at] | (b[at + 1] << 8);
const u32 = (b: Uint8Array, at: number): number => (u16(b, at) | (u16(b, at + 2) << 16)) >>> 0;

export class ZipFile {
  private readonly byName: ReadonlyMap<string, ZipEntryInfo>;

  private constructor(
    private readonly source: Blob,
    readonly entries: readonly ZipEntryInfo[],
  ) {
    this.byName = new Map(entries.map((e) => [e.name, e]));
  }

  entry(name: string): ZipEntryInfo | undefined {
    return this.byName.get(name);
  }

  /** Opens `source` as a ZIP of at most `maxEntries` entries. */
  static async open(source: Blob, maxEntries: number): Promise<ZipOpen> {
    const size = source.size;
    if (size < 22) return { ok: false, problem: 'NOT_A_ZIP' };
    // The end record is the last 22 bytes, unless the archive has a comment (up to 64 KiB) after it.
    const tailSize = Math.min(size, 22 + 0xffff);
    const tail = await bytesAt(source, size - tailSize, tailSize);
    if (!tail) return { ok: false, problem: 'NOT_A_ZIP' };
    let at = tailSize - 22;
    while (at >= 0 && u32(tail, at) !== 0x06054b50) at--;
    if (at < 0) return { ok: false, problem: 'NOT_A_ZIP' };
    const count = u16(tail, at + 10);
    const dirSize = u32(tail, at + 12);
    const dirStart = u32(tail, at + 16);
    // 0xFFFF entries or 0xFFFFFFFF offsets mean ZIP64, which no copy of ours needs.
    if (count === 0xffff || dirStart === 0xffffffff) return { ok: false, problem: 'NOT_A_ZIP' };
    if (count > maxEntries) return { ok: false, problem: 'TOO_MANY_ENTRIES' };
    if (dirSize > MAX_DIRECTORY_BYTES || dirStart + dirSize > size) return { ok: false, problem: 'NOT_A_ZIP' };
    const dir = await bytesAt(source, dirStart, dirSize);
    if (!dir) return { ok: false, problem: 'NOT_A_ZIP' };
    const decoder = new TextDecoder();
    const entries: ZipEntryInfo[] = [];
    let p = 0;
    for (let i = 0; i < count; i++) {
      if (p + 46 > dir.length || u32(dir, p) !== 0x02014b50) return { ok: false, problem: 'NOT_A_ZIP' };
      const nameLength = u16(dir, p + 28);
      const extraLength = u16(dir, p + 30);
      const commentLength = u16(dir, p + 32);
      if (p + 46 + nameLength > dir.length) return { ok: false, problem: 'NOT_A_ZIP' };
      entries.push({
        name: decoder.decode(dir.subarray(p + 46, p + 46 + nameLength)),
        method: u16(dir, p + 10),
        flags: u16(dir, p + 8),
        crc: u32(dir, p + 16),
        compressedSize: u32(dir, p + 20),
        size: u32(dir, p + 24),
        localOffset: u32(dir, p + 42),
      });
      p += 46 + nameLength + extraLength + commentLength;
    }
    return { ok: true, zip: new ZipFile(source, entries) };
  }

  /**
   * The entry's bytes, or `null` when they would be more than `limit`. The declared sizes are a first filter only: the
   * inflater is stopped at `limit` whatever they say. Throws {@link ZipEntryError} for an entry that cannot be read.
   */
  async readAtMost(entry: ZipEntryInfo, limit: number): Promise<Uint8Array | null> {
    if (entry.flags & 1) throw new ZipEntryError('encrypted entry');
    if (entry.size > limit) return null;
    const header = await bytesAt(this.source, entry.localOffset, 30);
    if (!header || u32(header, 0) !== 0x04034b50) throw new ZipEntryError('no local header');
    const start = entry.localOffset + 30 + u16(header, 26) + u16(header, 28);
    let bytes: Uint8Array | null;
    if (entry.method === 0) {
      if (entry.compressedSize !== entry.size) throw new ZipEntryError('stored sizes differ');
      bytes = await bytesAt(this.source, start, entry.compressedSize);
      if (!bytes) throw new ZipEntryError('entry cut short');
    } else if (entry.method === 8) {
      // DEFLATE seldom grows data by more than a few bytes per block; far beyond the limit is not an honest entry.
      if (entry.compressedSize > limit + limit / 64 + 1024) return null;
      const packed = await bytesAt(this.source, start, entry.compressedSize);
      if (!packed) throw new ZipEntryError('entry cut short');
      bytes = await inflateRaw(packed, limit);
      if (!bytes) return null;
    } else {
      throw new ZipEntryError(`compression method ${entry.method}`);
    }
    if (bytes.length > limit) return null;
    if (crc32(bytes) !== entry.crc) throw new ZipEntryError('CRC mismatch');
    return bytes;
  }
}

/**
 * Raw DEFLATE (RFC 1951) through `DecompressionStream`, counted as it comes out: `null` once past `max`, so a lying size
 * costs `max` bytes and no more. Throws {@link ZipEntryError} for data that is not valid DEFLATE or is cut short.
 */
export async function inflateRaw(packed: Uint8Array, max: number): Promise<Uint8Array | null> {
  const inflater = new DecompressionStream('deflate-raw');
  const writer = inflater.writable.getWriter();
  // The reader below sees a broken or cut-short stream as an error; these two promises would only repeat it.
  writer.write(packed as Uint8Array<ArrayBuffer>).catch(() => undefined);
  writer.close().catch(() => undefined);
  const reader = inflater.readable.getReader();
  const parts: Uint8Array[] = [];
  let total = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.length;
      if (total > max) {
        await reader.cancel().catch(() => undefined);
        return null;
      }
      parts.push(value);
    }
  } catch {
    throw new ZipEntryError('broken DEFLATE');
  }
  const out = new Uint8Array(total);
  let at = 0;
  for (const part of parts) {
    out.set(part, at);
    at += part.length;
  }
  return out;
}
