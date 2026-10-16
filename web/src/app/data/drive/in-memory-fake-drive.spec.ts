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
import { errorMsg } from '../../core/format';
import { sha256Hex } from '../../export/sha256';
import { CHUNK_UNIT, DRIVE_LAYOUT, DriveError, DriveRetry, FOLDER_MIME } from './drive-client';
import { ensureFolder, listAll } from './drive-ops';
import { FakeDriveHttp } from './fake-drive-http';
import { DriveFaults, FakeTokenProvider } from './fake-drive-faults';
import { FetchDriveClient, isGoogleApi } from './fetch-drive-client';
import { FakeDriveServer, InMemoryFakeDrive } from './in-memory-fake-drive';

/**
 * The fake Drive itself (S4b-BL-115, docs/06 TC-U-124; Kotlin `InMemoryFakeDriveTest`, the same cases): the fault script
 * does what it says, hand edits act like a person in Drive, Drive's own rules hold; and what only `FetchDriveClient`
 * does (the token only to Google over https, redirects not followed, a non-JSON answer corrupt), and `errorMsg`'s words.
 */

const bytes = (n: number) => Uint8Array.from({ length: n }, (_, i) => i % 7);

async function kindOf(p: Promise<unknown>): Promise<string> {
  try {
    await p;
  } catch (e) {
    if (e instanceof DriveError) return e.kind;
    throw e;
  }
  throw new Error('expected a DriveError');
}

describe('InMemoryFakeDrive', () => {
  it('fails exactly the requests the script names', async () => {
    const server = new FakeDriveServer();
    const drive = new InMemoryFakeDrive(server, undefined, new DriveRetry({ maxAttempts: 1 }));
    server.faults.onCall(3, DriveFaults.server(503));
    await drive.about();
    await drive.about();
    expect(await kindOf(drive.about())).toBe('SERVER');
    await drive.about();
    server.faults.on('LIST', 2, DriveFaults.forbidden);
    await drive.list({});
    expect(await kindOf(drive.list({}))).toBe('FORBIDDEN');
    server.faults.next(DriveFaults.conflict, 'ABOUT', 2);
    await drive.list({});
    expect(await kindOf(drive.about())).toBe('CONFLICT');
    expect(await kindOf(drive.about())).toBe('CONFLICT');
    await drive.about();
    server.faults.always(DriveFaults.cancelled, 'GET');
    for (let i = 0; i < 3; i++) expect(await kindOf(drive.getFile('x'))).toBe('CANCELLED');
    await drive.about();
    server.faults.clear().stopAfter(server.requests.length + 1);
    await drive.about();
    expect(await kindOf(drive.about())).toBe('OFFLINE');
    expect(server.requests[0].op).toBe('ABOUT');
  });

  it('inherits the bin and deletes a folder with everything in it', async () => {
    const server = new FakeDriveServer();
    const drive = new InMemoryFakeDrive(server);
    const root = (await ensureFolder(drive, DRIVE_LAYOUT.root, null, true))!;
    const sync = (await ensureFolder(drive, DRIVE_LAYOUT.sync, root.id, true))!;
    const file = await drive.upload({ kind: 'new', file: { name: 's', mimeType: 'application/octet-stream', parents: [sync.id] } }, bytes(10));
    server.trashByHand(root.id);
    expect((await drive.getFile(file.id)).trashed).toBe(true);
    expect(await listAll(drive, { parentId: sync.id })).toEqual([]);
    server.untrashByHand(root.id);
    expect((await listAll(drive, { parentId: sync.id })).map((f) => f.id)).toEqual([file.id]);
    server.deleteByHand(root.id);
    expect(server.allFiles()).toEqual([]);
  });

  it('keeps Drive’s rules', async () => {
    const drive = new InMemoryFakeDrive();
    expect(await kindOf(drive.list({}, 'bogus'))).toBe('BAD_REQUEST');
    expect(await kindOf(drive.createFile({ name: 'x', mimeType: FOLDER_MIME, parents: ['nope'] }))).toBe('NOT_FOUND');
    const session = await drive.startUpload({ kind: 'new', file: { name: 'big', mimeType: 'application/octet-stream' } }, 3 * CHUNK_UNIT);
    expect(await kindOf(drive.uploadChunk(session, 0, bytes(1000)))).toBe('BAD_REQUEST');
    expect(await drive.uploadStatus(session)).toEqual({ kind: 'incomplete', received: 0 });
    expect(await drive.uploadChunk(session, 0, bytes(CHUNK_UNIT))).toEqual({ kind: 'incomplete', received: CHUNK_UNIT });
    expect(await drive.uploadChunk(session, 0, bytes(2 * CHUNK_UNIT))).toEqual({ kind: 'incomplete', received: 2 * CHUNK_UNIT });
    const done = await drive.uploadChunk(session, 2 * CHUNK_UNIT, bytes(CHUNK_UNIT));
    expect(done.kind).toBe('complete');
    expect(await drive.uploadStatus(session)).toEqual(done);
    const folder = await drive.createFile({ name: 'f', mimeType: FOLDER_MIME });
    expect(await kindOf(drive.download(folder.id))).toBe('FORBIDDEN');
    const small = await drive.upload({ kind: 'new', file: { name: 's', mimeType: 'text/plain' } }, bytes(5));
    expect(await kindOf(drive.download(small.id, { first: 5, last: 9 }))).toBe('BAD_REQUEST');
    expect(await drive.download(small.id, { first: 3, last: 99 })).toEqual(bytes(5).slice(3, 5));
  });

  it('shows hand edits and the clock in the metadata', async () => {
    const server = new FakeDriveServer();
    const drive = new InMemoryFakeDrive(server);
    const file = await drive.upload({ kind: 'new', file: { name: 'a', mimeType: 'text/plain', appProperties: { kind: 'sync' } } }, bytes(3));
    expect(file.modifiedTime).toBe(server.clock.now());
    server.clock.advance(60_000);
    server.editByHand(file.id, bytes(4));
    const edited = await drive.getFile(file.id);
    expect(edited.modifiedTime).toBe(file.modifiedTime + 60_000);
    expect(edited.sha256Checksum).toBe(sha256Hex(bytes(4)));
    server.renameByHand(file.id, 'renamed');
    server.moveByHand(file.id, null);
    expect((await drive.getFile(file.id)).name).toBe('renamed');
    expect((await listAll(drive, { appProperties: { kind: 'sync' } })).map((f) => f.id)).toEqual([file.id]);
    expect((await drive.revisions(file.id)).length).toBe(2);
    expect(server.requests.some((r) => r.op === 'UPDATE')).toBe(false);
  });
});

describe('FetchDriveClient', () => {
  it('sends the token only to Google over https', async () => {
    const server = new FakeDriveServer();
    const http = new FakeDriveHttp(server);
    const drive = new FetchDriveClient(new FakeTokenProvider(), { fetch: http.fetch, retry: new DriveRetry({ maxAttempts: 1 }) });
    for (const uri of ['http://www.googleapis.com/upload/drive/v3/files?upload_id=x', 'https://evil.example/upload?upload_id=x']) {
      expect(await kindOf(drive.uploadChunk({ uri, size: 10, fileId: null }, 0, bytes(10)))).toBe('CORRUPT');
    }
    expect(http.seen).toEqual([]);
    expect(isGoogleApi('https://www.googleapis.com/drive/v3/files')).toBe(true);
    expect(isGoogleApi('https://www.googleapis.com.evil.example/x')).toBe(false);
    expect(isGoogleApi('https://www.googleapis.com:8443/x')).toBe(false);
    await drive.about();
    expect(http.seen[0].headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('does not follow a redirect and calls a non-JSON answer corrupt', async () => {
    let answer = 0;
    const fetch: typeof globalThis.fetch = async (_input, init) => {
      answer++;
      expect(init?.redirect).toBe('manual');
      expect(init?.credentials).toBe('omit');
      return answer === 1
        ? new Response(null, { status: 302, headers: { Location: 'https://portal.example/' } })
        : new Response('<html>sign in</html>', { status: 200, headers: { 'Content-Type': 'text/html' } });
    };
    const drive = new FetchDriveClient(new FakeTokenProvider(), { fetch, retry: new DriveRetry({ maxAttempts: 1 }) });
    expect(await kindOf(drive.about())).toBe('OFFLINE');
    expect(await kindOf(drive.about())).toBe('CORRUPT');
    expect(answer).toBe(2);
  });

  it('words a Drive failure as the server’s failures are worded', () => {
    expect(errorMsg(new DriveError('UNAUTHORIZED', 401)).key).toBe('error.auth');
    expect(errorMsg(new DriveError('FORBIDDEN', 403)).key).toBe('error.auth');
    expect(errorMsg(new DriveError('RATE_LIMITED', 429, 7000))).toEqual({ key: 'error.rateLimited', params: { s: 7 } });
    expect(errorMsg(new DriveError('NOT_FOUND', 404)).key).toBe('error.notFound');
    expect(errorMsg(new DriveError('QUOTA_EXCEEDED', 403)).key).toBe('error.server');
    expect(errorMsg(new DriveError('SERVER', 503)).key).toBe('error.server');
    expect(['error.offline', 'error.network']).toContain(errorMsg(new DriveError('OFFLINE')).key);
  });
});
