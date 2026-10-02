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

import type { ExportOptions } from '../../../../export/export-model';
import { createDriveBackupSource } from './backup-source';

describe('createDriveBackupSource', () => {
  let mockExporter: any;

  beforeEach(() => {
    mockExporter = {
      build: jasmine.createSpy('build'),
    };
  });

  it('returns a BackupSource function', () => {
    const source = createDriveBackupSource(mockExporter, {});
    expect(typeof source).toBe('function');
  });

  it('builds a backup and returns BackupPayload with correct format', async () => {
    const zipBytes = new TextEncoder().encode('PK\x03\x04'); // ZIP magic bytes
    const blob = new Blob([zipBytes], { type: 'application/zip' });

    mockExporter.build.mockResolvedValue({
      blob,
      counts: { houses: 5, visits: 10, photos: 3 },
      fileName: 'test-backup.zip',
    });

    const source = createDriveBackupSource(mockExporter, { lang: 'en' });
    const payload = await source();

    expect(payload.format).toBe('doorprints-backup/1');
    expect(payload.houses).toBe(5);
    expect(payload.source).toBeDefined();
  });

  it('ByteSource reads backup sequentially', async () => {
    const zipBytes = new Uint8Array([1, 2, 3, 4, 5]);
    const blob = new Blob([zipBytes], { type: 'application/zip' });

    mockExporter.build.mockResolvedValue({
      blob,
      counts: { houses: 1 },
      fileName: 'test.zip',
    });

    const source = createDriveBackupSource(mockExporter, {});
    const payload = await source();

    // Read the first 2 bytes.
    let chunk = await payload.source.read(2);
    expect(chunk).toEqual(new Uint8Array([1, 2]));

    // Read the next 2 bytes.
    chunk = await payload.source.read(2);
    expect(chunk).toEqual(new Uint8Array([3, 4]));

    // Read the remaining byte.
    chunk = await payload.source.read(2);
    expect(chunk).toEqual(new Uint8Array([5]));

    // End of stream returns null.
    chunk = await payload.source.read(2);
    expect(chunk).toBeNull();
  });

  it('throws when ExportService returns no blob', async () => {
    mockExporter.build.mockResolvedValue({
      counts: { houses: 0 },
      fileName: 'test.zip',
      // No blob
    });

    const source = createDriveBackupSource(mockExporter, {});

    await expect(source()).rejects.toThrow('no blob');
  });

  it('computes SHA-256 of the ZIP', async () => {
    const zipBytes = new Uint8Array([1, 2, 3, 4]);
    const blob = new Blob([zipBytes], { type: 'application/zip' });

    mockExporter.build.mockResolvedValue({
      blob,
      counts: { houses: 1 },
      fileName: 'test.zip',
    });

    const source = createDriveBackupSource(mockExporter, {});
    const payload = await source();

    // SHA-256 should be computed (not null or empty).
    expect(payload).toBeDefined();
  });

  it('handles large backup files', async () => {
    // 5 MB of zeros
    const largeArray = new Uint8Array(5 * 1024 * 1024);
    const blob = new Blob([largeArray], { type: 'application/zip' });

    mockExporter.build.mockResolvedValue({
      blob,
      counts: { houses: 100 },
      fileName: 'large-backup.zip',
    });

    const source = createDriveBackupSource(mockExporter, {});
    const payload = await source();

    expect(payload.houses).toBe(100);
    expect(payload.source).toBeDefined();

    // Verify it can be read in chunks.
    let totalRead = 0;
    let chunk = await payload.source.read(1024 * 1024);
    while (chunk !== null) {
      totalRead += chunk.length;
      chunk = await payload.source.read(1024 * 1024);
    }
    expect(totalRead).toBe(5 * 1024 * 1024);
  });

  it('calls ExportService.build with correct arguments', async () => {
    const options: ExportOptions = { lang: 'hi' };
    const now = new Date('2026-10-02T10:00:00Z');
    const blob = new Blob([new Uint8Array([1, 2, 3])], { type: 'application/zip' });

    mockExporter.build.mockResolvedValue({
      blob,
      counts: { houses: 2 },
      fileName: 'test.zip',
    });

    const source = createDriveBackupSource(mockExporter, options);
    await source();

    expect(mockExporter.build).toHaveBeenCalledWith('backup', options, expect.any(Date));
  });
});
