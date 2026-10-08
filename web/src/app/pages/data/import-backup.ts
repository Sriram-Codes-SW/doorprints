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

import { Component, ElementRef, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Announcer } from '../../core/announcer.service';
import { LaunchFilesService } from '../../core/launch-files.service';
import { errorMsg } from '../../core/format';
import type { BackupProblem } from '../../export/backup-check';
import { openBackup } from '../../export/backup-reader';
import type { BackupArchive } from '../../export/backup-reader';
import { copyDuplicates, preview } from '../../export/import-plan';
import type { ImportFlags, ImportMode, ImportPreview, LocalVersions } from '../../export/import-plan';
import { ImportService } from '../../export/import.service';
import type { CopyUndo, ImportOutcome } from '../../export/import.service';
import { isUpdate } from '../../export/backup-reader';
import type { TKey } from '../../i18n/en';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import { TPipe } from '../../i18n/t.pipe';

/** One line of the preview: its label, its number, and whether the person loses something by it. */
export interface PreviewLine {
  readonly key: TKey;
  readonly count: number;
  readonly loss?: boolean;
}

/**
 * The preview's lines in the order Android's Import screen shows them (`previewGroups`), without the zero ones: houses,
 * visits, photos, brokers, then everything else in one line.
 */
export function previewLines(p: ImportPreview, duplicates: number): PreviewLine[] {
  const others = p.newCriteria + p.updatedCriteria + p.newPreferences + p.updatedPreferences + p.newQuestions + p.updatedQuestions +
    p.newViewings + p.updatedViewings + p.newAreas + p.updatedAreas + p.newPlaces + p.updatedPlaces + p.newAreaNotes + p.updatedAreaNotes;
  const lines: PreviewLine[] = [
    { key: 'imp.copyDuplicates', count: p.mode === 'COPY' ? duplicates : 0, loss: true },
    { key: 'imp.newHouses', count: p.newHouses },
    { key: 'imp.updatedHouses', count: p.updatedHouses },
    { key: 'imp.checklistsCleared', count: p.checklistsCleared, loss: true },
    // S4b-BL-104 (d): a floor outside -5..200 in the file lands blank (the server refuses such a file).
    { key: 'imp.floorsLeftBlank', count: p.floorsLeftBlank, loss: true },
    { key: 'imp.restoredHouses', count: p.restoredHouses },
    { key: 'imp.newerHere', count: p.newerHereHouses },
    { key: 'imp.deletedHere', count: p.deletedHereHouses },
    { key: 'imp.removedHouses', count: p.removedHouses, loss: true },
    { key: 'imp.newVisits', count: p.newVisits },
    { key: 'imp.updatedVisits', count: p.updatedVisits },
    { key: 'imp.newPhotos', count: p.newPhotos },
    { key: 'imp.photosMissing', count: p.photosMissingFromFile, loss: true },
    { key: 'imp.newBrokers', count: p.newBrokers },
    { key: 'imp.updatedBrokers', count: p.updatedBrokers },
    { key: 'imp.otherRecords', count: others },
  ];
  return lines.filter((l) => l.count > 0);
}

type Phase = 'idle' | 'checking' | 'refused' | 'ready' | 'importing' | 'done';

/**
 * *Import a backup* on Your data (S4b-BL-75): pick a file, see what it would change (the same preview as Android's), choose
 * merge or copies, import. The file is read in this browser only; nothing is written before *Import*. A copy can be undone
 * while this page is open (Android keeps its undo for a day, ImportUndo; the website keeps none across a reload).
 */
@Component({
  selector: 'app-import-backup',
  imports: [RouterLink, TPipe],
  templateUrl: './import-backup.html',
  styleUrl: './import-backup.css',
})
export class ImportBackupCard {
  private readonly importer = inject(ImportService);
  private readonly announcer = inject(Announcer);
  protected readonly i18n = inject(TranslationService);
  private readonly launch = inject(LaunchFilesService);

  private readonly card = viewChild<ElementRef<HTMLElement>>('card');
  private readonly fileInput = viewChild<ElementRef<HTMLInputElement>>('fileInput');
  private readonly resultEl = viewChild<ElementRef<HTMLElement>>('result');

  protected readonly phase = signal<Phase>('idle');
  protected readonly fileName = signal<string | null>(null);
  protected readonly problem = signal<BackupProblem | null>(null);
  protected readonly mode = signal<ImportMode>('MERGE');
  protected readonly restore = signal(false);
  protected readonly keepMine = signal(false);
  protected readonly outcome = signal<ImportOutcome | null>(null);
  protected readonly failure = signal<Msg | null>(null);
  protected readonly undoState = signal<'none' | 'running' | 'done'>('none');
  protected readonly undone = signal<Msg | null>(null);

  private readonly archive = signal<BackupArchive | null>(null);
  private readonly local = signal<LocalVersions | null>(null);
  private undo: CopyUndo | null = null;

  protected readonly busy = computed(() => this.phase() === 'checking' || this.phase() === 'importing' || this.undoState() === 'running');

  constructor() {
    // S4b-BL-108: a backup the system opened the installed app with (LaunchFilesService) is checked as if picked here,
    // once nothing else is running; the card is brought into view, since it sits in the middle of Your data.
    effect(() => {
      if (!this.launch.pending() || this.busy()) return;
      untracked(() => {
        const file = this.launch.take();
        if (!file) return;
        void this.check(file);
        queueMicrotask(() => {
          const el = this.card()?.nativeElement;
          if (el && typeof el.scrollIntoView === 'function') el.scrollIntoView({ block: 'start' });
        });
      });
    });
  }

  /** The options the person chose, as the import rules read them. */
  private flags(): Omit<ImportFlags, 'applyDeletions'> {
    return { mode: this.mode(), restoreDeleted: this.restore(), skipUpdates: this.keepMine() };
  }

  protected readonly preview = computed<ImportPreview | null>(() => {
    const archive = this.archive();
    const local = this.local();
    if (!archive || !local) return null;
    return preview(archive.data, archive.photoEntries, local, { ...this.flags(), applyDeletions: isUpdate(archive.manifest) });
  });

  /** The merge preview without the opt-ins: whether to offer them at all. */
  private readonly plain = computed<ImportPreview | null>(() => {
    const archive = this.archive();
    const local = this.local();
    return archive && local ? preview(archive.data, archive.photoEntries, local, { mode: 'MERGE', applyDeletions: isUpdate(archive.manifest) }) : null;
  });

  protected readonly deletedHere = computed(() => this.plain()?.deletedHereHouses ?? 0);
  protected readonly overwrites = computed(() => this.plain()?.overwrites ?? 0);

  protected readonly lines = computed(() => {
    const p = this.preview();
    const archive = this.archive();
    const local = this.local();
    return p && archive && local ? previewLines(p, copyDuplicates(archive.data, local)) : [];
  });

  /** "Updates for Priya, made on …" for an update file, "Backup made on …" for a backup, nothing for a bare data.json. */
  protected readonly madeOn = computed<Msg | null>(() => {
    const m = this.archive()?.manifest;
    if (!m) return null;
    const date = this.i18n.dateTime(m.createdAt);
    const made: Msg = m.sharedTo ? { key: 'imp.updatesFor', params: { name: m.sharedTo, date } } : { key: 'imp.backupOf', params: { date } };
    return made;
  });

  protected pick(): void {
    if (this.busy()) return;
    this.fileInput()?.nativeElement.click();
  }

  /** Checks the file chosen in the file picker. */
  protected async picked(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    await this.check(file);
  }

  /** Reads and checks `file`, then works out the preview; nothing is written. */
  async check(file: Blob & { name?: string }): Promise<void> {
    this.reset();
    this.fileName.set(file.name ?? null);
    this.phase.set('checking');
    const opened = await openBackup(file);
    if (!opened.ok) {
      this.problem.set(opened.problem);
      this.phase.set('refused');
      return;
    }
    this.local.set(await this.importer.localVersions());
    this.archive.set(opened.archive);
    this.phase.set('ready');
  }

  protected setMode(mode: ImportMode): void {
    this.mode.set(mode);
  }

  protected toggle(which: 'restore' | 'keepMine', event: Event): void {
    (which === 'restore' ? this.restore : this.keepMine).set((event.target as HTMLInputElement).checked);
  }

  /** Writes the confirmed import. */
  async run(): Promise<void> {
    const archive = this.archive();
    const p = this.preview();
    if (!archive || !p || p.isEmpty || this.busy()) return;
    this.phase.set('importing');
    this.failure.set(null);
    try {
      const done = await this.importer.apply(archive, this.flags());
      this.outcome.set(done);
      this.undo = done.undo && done.undo.houses.size + done.undo.visits.size + done.undo.records.size > 0 ? done.undo : null;
      this.announcer.announce({ key: 'imp.done', params: { houses: done.houses, visits: done.visits, photos: done.photos } });
    } catch (err) {
      this.failure.set({ key: 'imp.writeFailed', params: { reason: this.i18n.msg(errorMsg(err)) } });
    }
    this.phase.set('done');
    this.archive.set(null);
    this.local.set(null);
    queueMicrotask(() => this.resultEl()?.nativeElement.focus());
  }

  protected get canUndo(): boolean {
    return this.undo !== null && this.undoState() === 'none';
  }

  /** Removes what a copy import added and reports how many were removed and how many were kept. */
  async undoCopy(): Promise<void> {
    if (!this.undo || this.undoState() !== 'none') return;
    this.undoState.set('running');
    try {
      const r = await this.importer.undoCopy(this.undo);
      this.undone.set({ key: 'imp.undone', params: { n: r.removed, kept: r.kept } });
      this.announcer.announce({ key: 'imp.undone', params: { n: r.removed, kept: r.kept } });
      this.undo = null;
      this.undoState.set('done');
    } catch (err) {
      this.undone.set({ key: 'imp.undoFailed', params: { reason: this.i18n.msg(errorMsg(err)) } });
      this.undoState.set('none');
    }
  }

  protected cancel(): void {
    this.reset();
  }

  /** Returns the card to its start and forgets the file, the preview and any undo. */
  private reset(): void {
    this.phase.set('idle');
    this.problem.set(null);
    this.archive.set(null);
    this.local.set(null);
    this.outcome.set(null);
    this.failure.set(null);
    this.mode.set('MERGE');
    this.restore.set(false);
    this.keepMine.set(false);
    this.undo = null;
    this.undoState.set('none');
    this.undone.set(null);
  }

  /** The sentence for why a file was refused. */
  protected problemKey(p: BackupProblem): TKey {
    return `imp.problem.${p}` as TKey;
  }
}
