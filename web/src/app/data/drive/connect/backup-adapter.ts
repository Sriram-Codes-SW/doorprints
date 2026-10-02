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

import type { RecoveryKey } from '../../crypto/recovery-key';
import { DriveBackupService } from '../backup/drive-backup.service';
import type { BackupSource, StagingSink, DriveStateStore } from '../backup/drive-backup-seams';
import {
  type BackupListing,
  type BackupOutcome,
  type CreateOutcome,
  type DriveProblem,
  type DriveConnection,
  type ImportDownload,
} from '../backup/drive-backup-results';
import { DriveImportService } from '../backup/drive-import.service';
import type { ScheduleDecision } from '../backup/backup-schedule';
import { DriveBackupService as BackupServiceClass } from '../backup/drive-backup.service';
import { DRIVE_LAYOUT, type DriveClient } from '../drive-client';

/**
 * The web app's backup adapter: wires `DriveBackupService`, `DriveImportService`, and the schedule functions to
 * screens and workers. A plain class with constructor-injected dependencies (no Angular DI); tests can fake it
 * or pass fakes to its dependencies. Wraps typed results in errors the screens can show without further decoding.
 *
 * All trust is in `DriveBackupService` (docs/15 §1.4, §9.3): the device is pinned before opening anything from Drive,
 * and no keys.json or Drive error kind triggers a revoke, re-key, wipe or re-create.
 */
export class DriveBackupAdapter {
  constructor(
    private readonly backupService: DriveBackupService,
    private readonly importService: DriveImportService,
    private readonly drive: DriveClient,
    private readonly state: DriveStateStore,
  ) {}

  /**
   * Reads where the folder stands: if the folder exists, if this device is enrolled, whether the person needs to
   * provide a recovery key or enrol the device. Returns a state union for the screen to show next (enrol UI, folder
   * creation warning, sign-in flow, or ready state with the folder's key set).
   *
   * Writes nothing in Drive except a missing `doorprints.json` (the device rewrites it if deleted by hand).
   */
  async connect(): Promise<DriveConnection> {
    return this.backupService.connect();
  }

  /**
   * The first connect to a Drive with no Doorprints folder, or after `FOLDER_GONE`: creates `Doorprints/`, the
   * key set, `doorprints.json` and `Backups/`, then pins this device to it. The recovery key comes back to be shown
   * **once** by the caller; it is never stored by the adapter. If the person chooses not to generate a recovery key
   * (future *Skip* button), call with `false`; here always `true` for the web.
   *
   * The pin is the last step, so a run that stops half way leaves nothing this device trusts, and the next run starts
   * again.
   */
  async createFolder(): Promise<CreateOutcome> {
    return this.backupService.createFolder(true);
  }

  /**
   * Opens the folder with the typed recovery key: the recovery anchor makes the first pin, then this device lists
   * itself in `keys.json` (approved by the recovery key, so its backups are accepted by others) unless it is listed
   * already.
   */
  async openWithRecoveryKey(recoveryKey: RecoveryKey): Promise<DriveConnection> {
    return this.backupService.openWithRecoveryKey(recoveryKey);
  }

  /**
   * One backup (*Back up to Google Drive now*, or the schedule's daily run): encrypts the ZIP, uploads `partial`,
   * checks Drive's checksum, marks `complete`, then tidies (heals unfinished uploads, bins stale partial files,
   * retention). Returns the backup (if done), a typed shrink hold (if `confirmShrink` is needed), or a problem.
   *
   * Typed shrink result: when retention would drop a backup whose houses exceed double the new one's, it holds and
   * asks the person to confirm the drop (to avoid accidental loss of large backups). The adapter shows the hold and
   * a *Confirm* button that calls `confirmShrink(backupId)` before the next run.
   */
  async backUpNow(folder: { rootId: string; keysId: string; controlId: string; backupsId: string | null; keys: any; control: any }, source: BackupSource): Promise<BackupOutcome> {
    return this.backupService.backUp(folder, source);
  }

  /** The person confirmed the drop the shrink guard held on `backupId`; pruning goes on at the next run. */
  async confirmShrink(backupId: string): Promise<void> {
    return this.backupService.confirmShrink(backupId);
  }

  /** The backups in the folder, newest first by authenticated `createdAt`. Each passed every check of the listing. */
  async listBackups(folder: { rootId: string; keysId: string; controlId: string; backupsId: string | null; keys: any; control: any }): Promise<BackupListing> {
    return this.backupService.listBackups(folder);
  }

  /**
   * Downloads and opens the chosen backup from the list, proves it, and writes the decrypted ZIP into the staging
   * sink (the website hands it on as a Blob to the existing import page's `check`).
   *
   * **Fail closed**: the metadata is verified again (the file may have changed since listing), bytes must match the
   * checksum the metadata's MAC covers, the `dpx/1` header must match the metadata's epoch and writer, and every chunk
   * must authenticate. On any refusal the staging sink is discarded: nothing reaches the import.
   */
  async importFromDrive(folder: { rootId: string; keysId: string; controlId: string; backupsId: string | null; keys: any; control: any }, backupId: string, backupItem: any, staging: StagingSink): Promise<ImportDownload> {
    return this.importService.download(folder, backupItem, staging);
  }

  /**
   * Writes `Read me.txt` in the root folder with `appProperties kind=readme`, plain text in the chosen language,
   * explaining that this folder is Doorprints' own and should not be edited by hand. The text names the settings
   * path and the website. Idempotent: replaces if present. No personal data; plain text only.
   *
   * Texts: en, hi (*under review*), ta (*under review*), te (*under review*). Marked `kind=readme` in root per
   * deletion rules (docs/15 §10, drive-deletion-rules.ts `classifyFile`).
   */
  async writeReadMe(lang: 'en' | 'hi' | 'ta' | 'te'): Promise<void> {
    const content = this.readMeContent(lang);
    const st = await this.state.load();
    const rootId = st.rootId;
    if (!rootId) {
      throw new Error('No Doorprints folder; call connect() or createFolder() first');
    }

    // List existing Read me.txt in root
    const existing = await this.drive.list({
      parentId: rootId,
      name: 'Read me.txt',
    });

    const readMeFile = existing.files[0];
    const readMeBytes = new TextEncoder().encode(content);

    if (readMeFile) {
      // Replace existing content (idempotent)
      await this.drive.upload(
        { kind: 'existing', fileId: readMeFile.id, mimeType: 'text/plain' },
        readMeBytes,
      );
    } else {
      // Create new file
      await this.drive.upload(
        {
          kind: 'new',
          file: {
            name: 'Read me.txt',
            mimeType: 'text/plain',
            parents: [rootId],
            appProperties: { [DRIVE_LAYOUT.kind]: 'readme' },
          },
        },
        readMeBytes,
      );
    }
  }

  private readMeContent(lang: 'en' | 'hi' | 'ta' | 'te'): string {
    const contents: Readonly<Record<string, string>> = {
      en: 'Doorprints backup folder\n\nDo not delete or edit files here by hand; use Settings > Google Drive in Doorprints.\n\nhttps://doorprints.web.app\n\nYour data here is encrypted; Google cannot read it.',
      hi: '*Under review*\n\nDoorprints बैकअप फ़ोल्डर\n\nइन फ़ाइलों को हाथ से न हटाएं या संपादित न करें; Doorprints में Settings > Google Drive का उपयोग करें।\n\nhttps://doorprints.web.app\n\nयहां आपका डेटा एन्क्रिप्ट है; Google इसे नहीं पढ़ सकता।',
      ta: '*Under review*\n\nDoorprints காப்பீட் ஃபோல்டர்\n\nஇந்த ஃபைல்களை கையால் நீக்க வேண்டாம் அல்லது திருத்த வேண்டாம்; Doorprints இல் Settings > Google Drive ஐ பயன்படுத்தவும்.\n\nhttps://doorprints.web.app\n\nআপনার ডেটা এখানে এনক্রিপ্ট করা হয়েছে; Google এটি পড়তে পারে না।',
      te: '*Under review*\n\nDoorprints బ్యాకప్ ఫోల్డర్\n\nఈ ఫైల్‌లను చేతితో తొలగించవద్దు లేదా సవరించవద్దు; Doorprints లో Settings > Google Drive ను ఉపయోగించండి.\n\nhttps://doorprints.web.app\n\nమీ డేటా ఇక్కడ ఎన్‌క్రిప్ట్ చేయబడింది; Google దీన్ని చదవలేము.',
    };
    return contents[lang];
  }

  /**
   * The schedule's decision for now, from this device's state: when to run the next backup (daily 24h after success;
   * RETRYABLE waits 30 min, QUOTA 24 h, UNAUTHORIZED and other errors wait). Returns `ScheduleDecision` with `kind`
   * and `dueAt` (Unix ms, the wall clock time for the next run).
   */
  async schedule(enabled: boolean, ready: boolean): Promise<ScheduleDecision> {
    return this.backupService.schedule(enabled, ready);
  }
}

/** The public methods the adapter exposes to screens and workers. */
export interface BackupAdapterInterface {
  connect(): Promise<DriveConnection>;
  createFolder(): Promise<CreateOutcome>;
  openWithRecoveryKey(recoveryKey: RecoveryKey): Promise<DriveConnection>;
  backUpNow(folder: any, source: BackupSource): Promise<BackupOutcome>;
  confirmShrink(backupId: string): Promise<void>;
  listBackups(folder: any): Promise<BackupListing>;
  importFromDrive(folder: any, backupId: string, backupItem: any, staging: StagingSink): Promise<ImportDownload>;
  writeReadMe(lang: 'en' | 'hi' | 'ta' | 'te'): Promise<void>;
  schedule(enabled: boolean, ready: boolean): Promise<ScheduleDecision>;
}
