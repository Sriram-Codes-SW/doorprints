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

import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import type { HouseDto } from '../core/models';
import { DEFAULT_EXPORT_OPTIONS } from '../export/export-model';
import type { ExportFormat } from '../export/export-model';
import { ExportService } from '../export/export.service';
import { createDriveBackupSource } from './drive/connect/local/backup-source';
import { LocalRowsAdapter } from './drive/connect/local/local-rows';
import { SYNC_KINDS, encodeSyncFile, syncFile } from './drive/sync-file';
import type { SyncKind, SyncRow } from './drive/sync-file';
import { LocalStore } from './local-store.service';
import { TraceStore } from './trace-store';

/**
 * PRIVACY (docs/11 5.27.7a, docs/02 T-I30, PRV-028, docs/06 TC-U-152): where the person walked, in the 30-day trace and in a
 * saved walk, is in NO export, backup, Drive backup or sync, server sync or share path of the website. The marker
 * coordinates below exist only in walks; a file built from a store that holds them must not carry them in any spelling
 * (decimal degrees, or the micro-degree integers a saved walk stores). A CONTROL house with its own marker coordinate must be
 * found, or the search proves nothing.
 */

const DEG = 1 / 111_194.9266;
const NOW = Date.UTC(2026, 9, 6, 12, 0, 0);
const DAY = 86_400_000;

/** Where the walks are: far from every house, with digits nothing else in a file would hold. */
const TRACE_MARKER = { lat: 41.123457, lon: 76.987654 };
const SAVED_MARKER = { lat: 42.222223, lon: 77.333331 };
const HOUSE_MARKER = { lat: 33.445566, lon: 74.665544 };
/** Every spelling of a walk coordinate that a file could carry. */
const WALK_NEEDLES = ['41.123457', '76.987654', '42.222223', '77.333331', '41123457', '76987654', '42222223', '77333331'];

const house = (id: string, lat: number, lon: number): HouseDto => ({ id, label: `House ${id}`, lat, lon, status: 'SHORTLISTED', checklist: {}, deleted: false, syncVersion: 0 });

function walkAt(marker: { lat: number; lon: number }, t0: number): { lat: number; lon: number; atMs: number; walkId: number }[] {
  return Array.from({ length: 8 }, (_, i) => ({ lat: marker.lat + i * 20 * DEG, lon: marker.lon, atMs: t0 + i * 15_000, walkId: t0 }));
}

/** The bytes of a file as text that keeps every byte (a stored ZIP and a spreadsheet hold their entries uncompressed). */
async function textOf(blob: Blob): Promise<string> {
  return new TextDecoder('latin1').decode(new Uint8Array(await blob.arrayBuffer()));
}

describe('a walk is in no export, backup or sync path of the website', () => {
  let local: LocalStore;
  let trace: TraceStore;
  let exporter: ExportService;

  beforeEach(async () => {
    local = new LocalStore();
    await local.ready();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: LocalStore, useValue: local }] });
    trace = TestBed.inject(TraceStore);
    exporter = TestBed.inject(ExportService);
    await local.saveHouse(house('h1', 13.0001, 80.0001));
    await local.saveHouse(house('control', HOUSE_MARKER.lat, HOUSE_MARKER.lon));
    // A walk in the 30-day trace, and a walk saved to a house.
    for (const p of walkAt(TRACE_MARKER, NOW - DAY)) await trace.putPoint(p, 9);
    for (const p of walkAt(SAVED_MARKER, NOW - 2 * DAY)) await trace.putPoint(p, 9);
    expect((await trace.saveWalk(NOW - 2 * DAY, 'h1', NOW, () => 'saved-1')).ok).toBe(true);
    expect((await trace.traceWalks(NOW)).length).toBe(1);
    expect((await trace.allWalks(NOW)).saved.length).toBe(1);
  });

  const FORMATS: ExportFormat[] = ['backup', 'html', 'pdf', 'csv', 'xlsx', 'markdown'];

  it.each(FORMATS)('%s: the file holds the houses (the control coordinate is found) and no coordinate of a walk', async (format) => {
    const result = await exporter.build(format, { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' }, new Date(NOW));
    expect(result.blob).toBeTruthy();
    const text = await textOf(result.blob!);
    if (format === 'backup' || format === 'html' || format === 'pdf' || format === 'markdown' || format === 'csv') {
      expect(text, `${format} must carry the control house's coordinate, or the search proves nothing`).toContain(String(HOUSE_MARKER.lat));
    }
    for (const needle of WALK_NEEDLES) expect(text, `${format} carries a walk coordinate (${needle})`).not.toContain(needle);
  });

  it('the search is sensitive: the same digits written into a house ARE found in every readable format', async () => {
    await local.saveHouse(house('planted', 13, 80));
    const planted = await local.getHouse('planted');
    await local.saveHouse({ ...house('planted', 13, 80), label: `Planted ${TRACE_MARKER.lat}` });
    expect(planted).toBeDefined();
    for (const format of ['backup', 'html', 'csv', 'markdown'] as const) {
      const text = await textOf((await exporter.build(format, { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' }, new Date(NOW))).blob!);
      expect(text, format).toContain('41.123457');
    }
  });

  it('the Save a copy file (the full backup) names no walk store, no walk id and no walk row either', async () => {
    const result = await exporter.build('backup', { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' }, new Date(NOW));
    const text = await textOf(result.blob!);
    for (const word of ['trace_points', 'saved_walks', 'savedWalk', 'saved-1', String(NOW - 2 * DAY)]) expect(text, word).not.toContain(word);
  });

  it('the Google Drive backup payload (the same full backup, read in slices) carries no walk', async () => {
    const payload = await createDriveBackupSource(exporter, { ...DEFAULT_EXPORT_OPTIONS, lang: 'en' })();
    let all = '';
    for (;;) {
      const chunk = await payload.source.read(64 * 1024);
      if (chunk === null) break;
      all += new TextDecoder('latin1').decode(chunk);
    }
    expect(all).toContain(String(HOUSE_MARKER.lat));
    for (const needle of WALK_NEEDLES) expect(all, needle).not.toContain(needle);
  });

  it('the Drive sync rows and the sync file carry the houses and no walk', async () => {
    const adapter = new LocalRowsAdapter(local, 'device-test-1');
    const rows = [...(await adapter.all()), ...(await adapter.changedRows())];
    const json = JSON.stringify(rows);
    expect(json).toContain(String(HOUSE_MARKER.lat));
    const byKind = Object.fromEntries(SYNC_KINDS.map((k) => [k, rows.filter((r) => r.kind === k)])) as Record<SyncKind, SyncRow[]>;
    const all = await adapter.all();
    const file = encodeSyncFile(syncFile('device-test-1', 1, NOW, Object.fromEntries(SYNC_KINDS.map((k) => [k, all.filter((r) => r.kind === k)]))));
    expect(file).toContain(String(HOUSE_MARKER.lat));
    expect(byKind.houses.length).toBeGreaterThan(0);
    for (const needle of WALK_NEEDLES) {
      expect(json, needle).not.toContain(needle);
      expect(file, needle).not.toContain(needle);
    }
    expect(json + file).not.toMatch(/trace_points|saved_walks/);
  });

  it('the server sync reads only the dirty records, and none of them is a walk', async () => {
    const text = JSON.stringify([await local.dirtyHouses(), await local.dirtyVisits(), await local.records.dirty(), await local.allHouses(), await local.allVisits(), await local.photos.all(), await local.records.all()]);
    expect(text).toContain(String(HOUSE_MARKER.lat));
    for (const needle of WALK_NEEDLES) expect(text, needle).not.toContain(needle);
  });

  it('writing and deleting walks leaves LocalStore.revision alone, so no sync is woken by a walk', async () => {
    const before = local.revision();
    await trace.deleteAllSavedWalks();
    await trace.clearTrace();
    expect(local.revision()).toBe(before);
  });
});
