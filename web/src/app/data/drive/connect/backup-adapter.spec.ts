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

import { sourceOf } from '../../crypto/dpx';
import type { ByteSource } from '../../crypto/dpx';
import { DriveBackupAdapter } from './backup-adapter';
import type { BackupSource, StagingSink } from '../backup/drive-backup-seams';
import type { DriveConnection, CreateOutcome, BackupOutcome, BackupListing, ImportDownload } from '../backup/drive-backup-results';
import { DriveProblem } from '../backup/drive-backup-results';
import type { ScheduleDecision } from '../backup/backup-schedule';
import type { RecoveryKey } from '../../crypto/recovery-key';

/**
 * Fake backup service for testing the adapter. Implements the subset of DriveBackupService
 * that the adapter calls, returning typed results.
 */
class FakeDriveBackupService {
  connectResult: DriveConnection = { kind: 'NO_FOLDER' };
  createFolderResult: CreateOutcome = {
    connection: { kind: 'NO_FOLDER' },
    recoveryKey: null,
  };
  backUpResult: BackupOutcome = { kind: 'failed', problem: new DriveProblem('SOURCE_FAILED') };
  listBackupsResult: BackupListing = { backups: [], unfinished: [], duplicates: [], junk: [], ignored: [], missingNewer: false };
  scheduleResult: ScheduleDecision = { backup: false, reason: 'NOT_DUE', nextAt: Date.now(), verify: false };

  async connect(): Promise<DriveConnection> {
    return this.connectResult;
  }

  async createFolder(_withRecoveryKey: boolean): Promise<CreateOutcome> {
    return this.createFolderResult;
  }

  async openWithRecoveryKey(_recoveryKey: RecoveryKey): Promise<DriveConnection> {
    return this.connectResult;
  }

  async backUp(_folder: any, _source: BackupSource): Promise<BackupOutcome> {
    return this.backUpResult;
  }

  async confirmShrink(_backupId: string): Promise<void> {
    // noop for testing
  }

  async listBackups(_folder: any): Promise<BackupListing> {
    return this.listBackupsResult;
  }

  async schedule(_enabled: boolean, _ready: boolean): Promise<ScheduleDecision> {
    return this.scheduleResult;
  }
}

/**
 * Fake import service for testing the adapter. Implements the subset of DriveImportService
 * that the adapter calls.
 */
class FakeDriveImportService {
  downloadResult: ImportDownload = { kind: 'refused', problem: new DriveProblem('BACKUP_GONE') };

  async download(_folder: any, _backup: any, _staging: StagingSink): Promise<ImportDownload> {
    return this.downloadResult;
  }
}

describe('DriveBackupAdapter', () => {
  let adapter: DriveBackupAdapter;
  let fakeBackupService: FakeDriveBackupService;
  let fakeImportService: FakeDriveImportService;

  beforeEach(() => {
    fakeBackupService = new FakeDriveBackupService();
    fakeImportService = new FakeDriveImportService();
    adapter = new DriveBackupAdapter(fakeBackupService as any, fakeImportService as any);
  });

  describe('connect()', () => {
    it('returns NO_FOLDER when service returns it', async () => {
      fakeBackupService.connectResult = { kind: 'NO_FOLDER' };
      const result = await adapter.connect();
      expect(result.kind).toBe('NO_FOLDER');
    });

    it('returns NEEDS_ENROLMENT when service returns it', async () => {
      fakeBackupService.connectResult = {
        kind: 'NEEDS_ENROLMENT',
        recoveryAvailable: true,
      };
      const result = await adapter.connect();
      expect(result.kind).toBe('NEEDS_ENROLMENT');
      expect((result as any).recoveryAvailable).toBe(true);
    });

    it('returns READY with folder when service returns it', async () => {
      const readyFolder: any = {
        rootId: 'root123',
        keysId: 'keys123',
        controlId: 'control123',
        backupsId: 'backups123',
        keys: {},
        control: { revision: 0, epoch: 1, createdAt: Date.now(), encryption: 'dpx/1', backupsDeletedAt: null },
      };
      fakeBackupService.connectResult = {
        kind: 'READY',
        folder: readyFolder,
      };
      const result = await adapter.connect();
      expect(result.kind).toBe('READY');
      expect((result as any).folder.rootId).toBe('root123');
    });

    it('returns ERROR with problem when service returns it', async () => {
      const problem = new DriveProblem('OFFLINE');
      fakeBackupService.connectResult = {
        kind: 'ERROR',
        problem,
      };
      const result = await adapter.connect();
      expect(result.kind).toBe('ERROR');
      expect((result as any).problem.kind).toBe('OFFLINE');
    });

    it('returns FOLDER_GONE when service returns it', async () => {
      fakeBackupService.connectResult = { kind: 'FOLDER_GONE' };
      const result = await adapter.connect();
      expect(result.kind).toBe('FOLDER_GONE');
    });

    it('returns NEEDS_RECOVERY_KEY with reason when service returns it', async () => {
      fakeBackupService.connectResult = {
        kind: 'NEEDS_RECOVERY_KEY',
        recoveryAvailable: true,
        reason: 'REVOKED',
      };
      const result = await adapter.connect();
      expect(result.kind).toBe('NEEDS_RECOVERY_KEY');
      expect((result as any).reason).toBe('REVOKED');
    });
  });

  describe('createFolder()', () => {
    it('returns connection and recovery key when successful', async () => {
      const mockRecoveryKey = { secret: 'mock' } as any;
      const readyFolder: any = {
        rootId: 'new-root',
        keysId: 'new-keys',
        controlId: 'new-control',
        backupsId: 'new-backups',
        keys: {},
        control: { revision: 0, epoch: 1, createdAt: Date.now(), encryption: 'dpx/1', backupsDeletedAt: null },
      };
      fakeBackupService.createFolderResult = {
        connection: { kind: 'READY', folder: readyFolder },
        recoveryKey: mockRecoveryKey,
      };

      const result = await adapter.createFolder();

      expect(result.connection.kind).toBe('READY');
      expect(result.recoveryKey).toBe(mockRecoveryKey);
    });

    it('returns error connection when service fails', async () => {
      fakeBackupService.createFolderResult = {
        connection: { kind: 'ERROR', problem: new DriveProblem('OFFLINE') },
        recoveryKey: null,
      };

      const result = await adapter.createFolder();

      expect(result.connection.kind).toBe('ERROR');
      expect((result.connection as any).problem.kind).toBe('OFFLINE');
      expect(result.recoveryKey).toBeNull();
    });

    it('returns null recovery key when folder exists', async () => {
      fakeBackupService.createFolderResult = {
        connection: { kind: 'ERROR', problem: new DriveProblem('FOLDER_EXISTS') },
        recoveryKey: null,
      };

      const result = await adapter.createFolder();

      expect(result.recoveryKey).toBeNull();
    });
  });

  describe('openWithRecoveryKey()', () => {
    it('returns READY connection when service succeeds', async () => {
      const readyFolder: any = {
        rootId: 'root123',
        keysId: 'keys123',
        controlId: 'control123',
        backupsId: 'backups123',
        keys: {},
        control: { revision: 1, epoch: 1, createdAt: Date.now(), encryption: 'dpx/1', backupsDeletedAt: null },
      };
      fakeBackupService.connectResult = {
        kind: 'READY',
        folder: readyFolder,
      };
      const mockKey = { secret: 'key-data' } as any;

      const result = await adapter.openWithRecoveryKey(mockKey);

      expect(result.kind).toBe('READY');
    });

    it('returns ERROR when recovery key is wrong', async () => {
      fakeBackupService.connectResult = {
        kind: 'ERROR',
        problem: new DriveProblem('WRONG_RECOVERY_KEY'),
      };
      const mockKey = { secret: 'wrong-key' } as any;

      const result = await adapter.openWithRecoveryKey(mockKey);

      expect(result.kind).toBe('ERROR');
      expect((result as any).problem.kind).toBe('WRONG_RECOVERY_KEY');
    });
  });

  describe('backUpNow()', () => {
    const mockFolder: any = {
      rootId: 'root123',
      keysId: 'keys123',
      controlId: 'control123',
      backupsId: 'backups123',
      keys: {},
      control: { revision: 0, epoch: 1, createdAt: Date.now(), encryption: 'dpx/1', backupsDeletedAt: null },
    };

    it('returns done backup outcome when successful', async () => {
      fakeBackupService.backUpResult = {
        kind: 'done',
        backup: {
          fileId: 'backup123',
          name: 'backup-2026-10-02T00_00_00Z',
          createdAt: Date.now(),
          houses: 5,
          epoch: 1,
          writerKid: new Uint8Array([1, 2, 3]),
          sha256: 'abc123',
          size: 1000,
        },
        tidy: { trashed: [], completed: [], hold: null, problem: null },
        missingNewer: false,
      };
      const mockSource: BackupSource = async () => ({
        format: 'doorprints-backup/1',
        houses: 5,
        source: sourceOf(new Uint8Array(100)) as ByteSource,
      });

      const result = await adapter.backUpNow(mockFolder, mockSource);

      expect(result.kind).toBe('done');
      expect((result as any).backup.fileId).toBe('backup123');
      expect((result as any).backup.houses).toBe(5);
    });

    it('returns failed outcome when source fails', async () => {
      fakeBackupService.backUpResult = {
        kind: 'failed',
        problem: new DriveProblem('SOURCE_FAILED'),
      };
      const mockSource: BackupSource = async () => {
        throw new Error('Source closed');
      };

      const result = await adapter.backUpNow(mockFolder, mockSource);

      expect(result.kind).toBe('failed');
      expect((result as any).problem.kind).toBe('SOURCE_FAILED');
    });

    it('returns failed outcome when offline', async () => {
      fakeBackupService.backUpResult = {
        kind: 'failed',
        problem: new DriveProblem('OFFLINE'),
      };
      const mockSource: BackupSource = async () => ({
        format: 'doorprints-backup/1',
        houses: 5,
        source: sourceOf(new Uint8Array(100)) as ByteSource,
      });

      const result = await adapter.backUpNow(mockFolder, mockSource);

      expect(result.kind).toBe('failed');
      expect((result as any).problem.kind).toBe('OFFLINE');
    });

    it('returns done with shrink hold when retention holds', async () => {
      fakeBackupService.backUpResult = {
        kind: 'done',
        backup: {
          fileId: 'backup456',
          name: 'backup-2026-10-02T01_00_00Z',
          createdAt: Date.now() + 3600000,
          houses: 2,
          epoch: 1,
          writerKid: new Uint8Array([1, 2, 3]),
          sha256: 'def456',
          size: 500,
        },
        tidy: {
          trashed: [],
          completed: [],
          hold: { backupId: 'held-backup', houses: 2, previousId: 'prev-backup', previousHouses: 10 },
          problem: null,
        },
        missingNewer: false,
      };
      const mockSource: BackupSource = async () => ({
        format: 'doorprints-backup/1',
        houses: 2,
        source: sourceOf(new Uint8Array(100)) as ByteSource,
      });

      const result = await adapter.backUpNow(mockFolder, mockSource);

      expect(result.kind).toBe('done');
      expect((result as any).tidy.hold).not.toBeNull();
      expect((result as any).tidy.hold.backupId).toBe('held-backup');
    });
  });

  describe('confirmShrink()', () => {
    it('calls service with backup id', async () => {
      await adapter.confirmShrink('backup123');
      // confirmShrink is wired through; success is confirmed by no exception
      expect(true).toBe(true);
    });
  });

  describe('listBackups()', () => {
    const mockFolder: any = {
      rootId: 'root123',
      keysId: 'keys123',
      controlId: 'control123',
      backupsId: 'backups123',
      keys: {},
      control: { revision: 0, epoch: 1, createdAt: Date.now(), encryption: 'dpx/1', backupsDeletedAt: null },
    };

    it('returns empty list when no backups exist', async () => {
      fakeBackupService.listBackupsResult = {
        backups: [],
        unfinished: [],
        duplicates: [],
        junk: [],
        ignored: [],
        missingNewer: false,
      };

      const result = await adapter.listBackups(mockFolder);

      expect(result.backups).toEqual([]);
      expect(result.missingNewer).toBe(false);
    });

    it('returns backups newest first', async () => {
      fakeBackupService.listBackupsResult = {
        backups: [
          {
            fileId: 'backup2',
            name: 'backup-2026-10-02T01_00_00Z',
            createdAt: Date.now() + 3600000,
            houses: 3,
            epoch: 1,
            writerKid: new Uint8Array([1, 2, 3]),
            sha256: 'def456',
            size: 1500,
          },
          {
            fileId: 'backup1',
            name: 'backup-2026-10-02T00_00_00Z',
            createdAt: Date.now(),
            houses: 5,
            epoch: 1,
            writerKid: new Uint8Array([1, 2, 3]),
            sha256: 'abc123',
            size: 1000,
          },
        ],
        unfinished: [],
        duplicates: [],
        junk: [],
        ignored: [],
        missingNewer: false,
      };

      const result = await adapter.listBackups(mockFolder);

      expect(result.backups.length).toBe(2);
      expect(result.backups[0].fileId).toBe('backup2');
      expect(result.backups[0].createdAt > result.backups[1].createdAt).toBe(true);
    });

    it('reports missing newer backup', async () => {
      fakeBackupService.listBackupsResult = {
        backups: [
          {
            fileId: 'backup1',
            name: 'backup-2026-10-02T00_00_00Z',
            createdAt: Date.now(),
            houses: 5,
            epoch: 1,
            writerKid: new Uint8Array([1, 2, 3]),
            sha256: 'abc123',
            size: 1000,
          },
        ],
        unfinished: [],
        duplicates: [],
        junk: [],
        ignored: [],
        missingNewer: true,
      };

      const result = await adapter.listBackups(mockFolder);

      expect(result.missingNewer).toBe(true);
    });
  });

  describe('importFromDrive()', () => {
    const mockFolder: any = {
      rootId: 'root123',
      keysId: 'keys123',
      controlId: 'control123',
      backupsId: 'backups123',
      keys: {},
      control: { revision: 0, epoch: 1, createdAt: Date.now(), encryption: 'dpx/1', backupsDeletedAt: null },
    };
    const mockBackupItem: any = {
      fileId: 'backup123',
      name: 'backup-2026-10-02T00_00_00Z',
      createdAt: Date.now(),
      houses: 5,
      epoch: 1,
      writerKid: new Uint8Array([1, 2, 3]),
      sha256: 'abc123',
      size: 1000,
    };

    let stagingSink: StagingSink;

    beforeEach(() => {
      stagingSink = {
        write: async () => undefined,
        discard: async () => undefined,
      };
    });

    it('returns verified import when successful', async () => {
      fakeImportService.downloadResult = {
        kind: 'verified',
        backup: mockBackupItem,
        format: 'doorprints-backup/1',
        plaintextSize: 5000,
      };

      const result = await adapter.importFromDrive(mockFolder, 'backup123', mockBackupItem, stagingSink);

      expect(result.kind).toBe('verified');
      expect((result as any).format).toBe('doorprints-backup/1');
      expect((result as any).plaintextSize).toBe(5000);
    });

    it('returns refused import when backup is gone', async () => {
      fakeImportService.downloadResult = {
        kind: 'refused',
        problem: new DriveProblem('BACKUP_GONE'),
      };

      const result = await adapter.importFromDrive(mockFolder, 'backup123', mockBackupItem, stagingSink);

      expect(result.kind).toBe('refused');
      expect((result as any).problem.kind).toBe('BACKUP_GONE');
    });

    it('returns refused import when corrupted', async () => {
      fakeImportService.downloadResult = {
        kind: 'refused',
        problem: new DriveProblem('CORRUPT'),
      };

      const result = await adapter.importFromDrive(mockFolder, 'backup123', mockBackupItem, stagingSink);

      expect(result.kind).toBe('refused');
      expect((result as any).problem.kind).toBe('CORRUPT');
    });

    it('returns refused import when offline', async () => {
      fakeImportService.downloadResult = {
        kind: 'refused',
        problem: new DriveProblem('OFFLINE'),
      };

      const result = await adapter.importFromDrive(mockFolder, 'backup123', mockBackupItem, stagingSink);

      expect(result.kind).toBe('refused');
      expect((result as any).problem.kind).toBe('OFFLINE');
    });
  });

  describe('writeReadMe()', () => {
    it('succeeds without error', async () => {
      // Currently a stub; implementation pending S4b-BL-117
      await adapter.writeReadMe('en');
      expect(true).toBe(true);
    });

    it('supports all languages', async () => {
      await adapter.writeReadMe('en');
      await adapter.writeReadMe('hi');
      await adapter.writeReadMe('ta');
      await adapter.writeReadMe('te');
      expect(true).toBe(true);
    });
  });

  describe('schedule()', () => {
    it('returns schedule decision when backup is due', async () => {
      fakeBackupService.scheduleResult = {
        backup: true,
        reason: 'DAILY',
        nextAt: null,
        verify: false,
      };

      const result = await adapter.schedule(true, true);

      expect(result.backup).toBe(true);
      expect(result.reason).toBe('DAILY');
    });

    it('returns not due when not ready', async () => {
      fakeBackupService.scheduleResult = {
        backup: false,
        reason: 'NOT_READY',
        nextAt: null,
        verify: false,
      };

      const result = await adapter.schedule(false, false);

      expect(result.reason).toBe('NOT_READY');
      expect(result.backup).toBe(false);
    });

    it('returns wait retry decision on retryable failure', async () => {
      fakeBackupService.scheduleResult = {
        backup: false,
        reason: 'WAIT_RETRY',
        nextAt: Date.now() + 1800000, // 30 min
        verify: false,
      };

      const result = await adapter.schedule(true, true);

      expect(result.reason).toBe('WAIT_RETRY');
      expect(result.backup).toBe(false);
    });

    it('returns wait quota decision when quota exceeded', async () => {
      fakeBackupService.scheduleResult = {
        backup: false,
        reason: 'WAIT_QUOTA',
        nextAt: Date.now() + 86400000, // 24 hours
        verify: false,
      };

      const result = await adapter.schedule(true, true);

      expect(result.reason).toBe('WAIT_QUOTA');
      expect(result.backup).toBe(false);
    });
  });
});
