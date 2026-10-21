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

import { describe, expect, it } from 'vitest';
import vectorsJson from '../../../../docs/schemas/import-vectors.json';
import { BACKUP_LIMITS } from './backup-export';
import { openBackup } from './backup-reader';
import { crc32 } from './zip';
import { ZipFile } from './zip-read';

/**
 * TC-S-17, the "bomb" half, made executable (docs/06; docs/02 T-T11): the limits of a backup ZIP are checked against what
 * the file claims AND against what inflating really produces, and a refused file is `TOO_LARGE` or `TOO_MANY_ENTRIES`
 * before anything is read into the app. The bombs are made here, small: zeros deflate about 1000 to 1.
 */

const MIB = 1024 * 1024;

interface Member {
  name: string;
  /** The real contents. */
  data: Uint8Array;
  /** What the central directory claims for the uncompressed size (default: the truth). */
  claimSize?: number;
  /** What it claims for the compressed size (default: the truth). */
  claimPacked?: number;
  stored?: boolean;
}

async function deflateRaw(bytes: Uint8Array): Promise<Uint8Array> {
  const deflater = new CompressionStream('deflate-raw');
  const writer = deflater.writable.getWriter();
  void writer.write(bytes as Uint8Array<ArrayBuffer>);
  void writer.close();
  return new Uint8Array(await new Response(deflater.readable).arrayBuffer());
}

function le(value: number, bytes: number): number[] {
  return Array.from({ length: bytes }, (_, i) => Math.floor(value / 2 ** (8 * i)) % 256);
}

/** A ZIP whose central directory can say what it likes about the sizes (a ZIP bomb's lie). */
async function makeZip(members: Member[]): Promise<Blob> {
  const parts: Uint8Array[] = [];
  const central: number[] = [];
  let offset = 0;
  for (const m of members) {
    const name = new TextEncoder().encode(m.name);
    const packed = m.stored ? m.data : await deflateRaw(m.data);
    const method = m.stored ? 0 : 8;
    const crc = crc32(m.data);
    const local = [...le(0x04034b50, 4), ...le(20, 2), ...le(0, 2), ...le(method, 2), ...le(0, 4), ...le(crc, 4), ...le(packed.length, 4), ...le(m.data.length, 4), ...le(name.length, 2), ...le(0, 2)];
    parts.push(new Uint8Array(local), name, packed);
    central.push(
      ...le(0x02014b50, 4), ...le(20, 2), ...le(20, 2), ...le(0, 2), ...le(method, 2), ...le(0, 4), ...le(crc, 4),
      ...le(m.claimPacked ?? packed.length, 4), ...le(m.claimSize ?? m.data.length, 4), ...le(name.length, 2),
      ...le(0, 2), ...le(0, 2), ...le(0, 2), ...le(0, 2), ...le(0, 4), ...le(offset, 4), ...name,
    );
    offset += local.length + name.length + packed.length;
  }
  const end = [...le(0x06054b50, 4), ...le(0, 4), ...le(members.length, 2), ...le(members.length, 2), ...le(central.length, 4), ...le(offset, 4), ...le(0, 2)];
  return new Blob([...parts, new Uint8Array(central), new Uint8Array(end)] as BlobPart[]);
}

const text = (s: string) => new TextEncoder().encode(s);

describe('backup import limits (TC-S-17: bomb, 5 001 entries)', () => {
  it('shares its numbers with the phones through the vectors file', () => {
    const limits = (vectorsJson as unknown as { limits: Record<string, number> }).limits;
    expect(limits).toEqual({
      maxEntries: BACKUP_LIMITS.maxEntries,
      maxUncompressedBytes: BACKUP_LIMITS.maxUncompressedBytes,
      maxCompressionRatio: BACKUP_LIMITS.maxCompressionRatio,
      maxDataJsonBytes: BACKUP_LIMITS.maxDataJsonBytes,
    });
  });

  it('refuses more than 5 000 entries before reading any of them', async () => {
    const members = Array.from({ length: BACKUP_LIMITS.maxEntries + 1 }, (_, i) => ({ name: `x${i}`, data: new Uint8Array(0), stored: true }));
    const opened = await openBackup(await makeZip(members));
    expect(opened).toEqual({ ok: false, problem: 'TOO_MANY_ENTRIES' });
  });

  it('refuses a data.json that honestly declares more than 16 MiB', async () => {
    const zip = await makeZip([{ name: 'data.json', data: new Uint8Array(BACKUP_LIMITS.maxDataJsonBytes + 1) }]);
    expect(zip.size).toBeLessThan(100 * 1024);
    expect(await openBackup(zip)).toEqual({ ok: false, problem: 'TOO_LARGE' });
  });

  it('refuses a claimed compression ratio above 100 to 1', async () => {
    const zip = await makeZip([{ name: 'data.json', data: new Uint8Array(MIB) }]);
    expect(await openBackup(zip)).toEqual({ ok: false, problem: 'TOO_LARGE' });
  });

  it('refuses claims that add up to more than 1 GiB, with each entry and its ratio in range', async () => {
    const claim = (name: string) => ({ name, data: new Uint8Array(10), claimSize: 600 * MIB, claimPacked: 7 * MIB });
    const zip = await makeZip([{ name: 'data.json', data: text('{}') }, claim('photos/a.jpg'), claim('photos/b.jpg')]);
    expect(await openBackup(zip)).toEqual({ ok: false, problem: 'TOO_LARGE' });
  });

  it('cuts a data.json that lies about its size: it claims 100 bytes and inflates past 16 MiB', async () => {
    const zip = await makeZip([{ name: 'data.json', data: new Uint8Array(BACKUP_LIMITS.maxDataJsonBytes + MIB), claimSize: 100 }]);
    expect(zip.size).toBeLessThan(100 * 1024);
    expect(await openBackup(zip)).toEqual({ ok: false, problem: 'TOO_LARGE' });
  });

  it('cuts a manifest that lies about its size', async () => {
    const data = text('{}');
    const zip = await makeZip([
      { name: 'data.json', data },
      { name: 'manifest.json', data: new Uint8Array(9 * MIB), claimSize: 100 },
    ]);
    expect(await openBackup(zip)).toEqual({ ok: false, problem: 'TOO_LARGE' });
  });

  it('reads an entry only up to the limit it is given, whatever its header says (stored or deflated)', async () => {
    const zip = await makeZip([
      { name: 'lie.bin', data: new Uint8Array(2 * MIB), claimSize: 100 },
      { name: 'honest.bin', data: new Uint8Array(2 * MIB) },
      { name: 'small.bin', data: text('hello'), stored: true },
    ]);
    const opened = await ZipFile.open(zip, 10);
    if (!opened.ok) throw new Error('should open');
    const lie = opened.zip.entry('lie.bin')!;
    expect(lie.size).toBe(100);
    expect(await opened.zip.readAtMost(lie, 64 * 1024)).toBeNull();
    expect(await opened.zip.readAtMost(opened.zip.entry('honest.bin')!, 64 * 1024)).toBeNull();
    expect((await opened.zip.readAtMost(opened.zip.entry('honest.bin')!, 4 * MIB))?.length).toBe(2 * MIB);
    expect(new TextDecoder().decode((await opened.zip.readAtMost(opened.zip.entry('small.bin')!, 64))!)).toBe('hello');
  });

  it('refuses a stored entry that claims more than the limit before reading a byte of it', async () => {
    const zip = await makeZip([{ name: 's.bin', data: text('hello'), stored: true, claimSize: 1024 * MIB, claimPacked: 1024 * MIB }]);
    const opened = await ZipFile.open(zip, 10);
    if (!opened.ok) throw new Error('should open');
    expect(await opened.zip.readAtMost(opened.zip.entry('s.bin')!, 64)).toBeNull();
  });

  it('refuses a deflated entry whose packed bytes are far beyond what the limit allows, without reading them', async () => {
    const zip = await makeZip([{ name: 'x.bin', data: new Uint8Array(10), claimPacked: 50 * MIB }]);
    const opened = await ZipFile.open(zip, 10);
    if (!opened.ok) throw new Error('should open');
    expect(await opened.zip.readAtMost(opened.zip.entry('x.bin')!, MIB)).toBeNull();
  });
});
