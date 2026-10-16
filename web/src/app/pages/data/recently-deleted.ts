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

import { Component, ElementRef, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { DeleteUndoService } from '../../core/delete-undo.service';
import type { BinEntry, PhotoRestorer, PhotoRetry, Restored } from '../../core/delete-undo.service';
import { DriveConnectService } from '../../data/drive/connect/drive-connect.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import { SpaceService } from '../../core/space.service';
import type { PhotoRestoreReason } from '../../data/drive/photo-restore-rules';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';

/** The words for each reason a photo did not come back. */
const WHY: Record<PhotoRestoreReason, TKey> = {
  NOT_CONNECTED: 'delete.why.notConnected',
  OFFLINE: 'delete.why.offline',
  PAST_WINDOW: 'delete.why.pastWindow',
  MISSING: 'delete.why.missing',
  UNREADABLE: 'delete.why.unreadable',
};

/**
 * *Recently deleted* on *Your data* (deletion safeguards PR 1, docs/11 5.27.6): this space's candidates deleted in the last
 * 30 days, each with its name, when it was deleted and a *Restore* button. With spaces on, the card shows the current space
 * only (its name is the sub-heading) and never touches another space's rows; with houses only it is just *Recently deleted*.
 * It is a plain list: a restored entry leaves it, the words say what came back, and focus goes to the next *Restore*
 * button, or to the heading when none is left. Saved walks are not kept (they have no tombstone), so the card says so.
 */
@Component({
  selector: 'app-recently-deleted',
  imports: [TPipe],
  template: `
    <section class="card" aria-labelledby="recently-deleted-heading">
      <h2 id="recently-deleted-heading" #heading tabindex="-1">{{ 'delete.recentlyDeleted' | t }}</h2>
      <p class="muted">{{ 'delete.binBody' | t }}</p>
      @if (space.multi()) {
        <h3 class="space-name">{{ space.current().keys.ref('kind.many') | t }}</h3>
      }
      <div role="status" aria-atomic="true">
        @if (note(); as m) {
          <p class="note">{{ m.key | t: m.params }}</p>
        }
      </div>
      <!-- Outside the live region: a control inside role="status" would be announced as part of its text. -->
      @if (retry(); as r) {
        <div class="retry-row">
          <button type="button" class="btn retry" [disabled]="busy()" [attr.aria-label]="'delete.retryAria' | t: { name: r.name || ('delete.unnamed' | t) }" (click)="retryPhotos()">
            {{ 'delete.retryPhotos' | t }}
          </button>
        </div>
      }
      @if (error(); as e) {
        <p class="error" role="alert">{{ e.key | t: e.params }}</p>
      }
      @if (entries(); as list) {
        @if (list.length === 0) {
          <p class="muted empty">{{ 'delete.binEmpty' | t }}</p>
        } @else {
          <ul class="rows" role="list">
            @for (e of list; track e.id) {
              <li class="row">
                <div class="what">
                  <p class="name"><strong>{{ e.name || ('delete.unnamed' | t) }}</strong></p>
                  <p class="muted small">{{ 'delete.deletedOn' | t: { when: i18n.dateTime(e.deletedAt) } }}</p>
                  @if (e.photos > 0) {
                    <p class="muted small">{{ 'delete.rowPhotos' | t: { n: i18n.number(e.photos) } }}</p>
                  }
                </div>
                <button type="button" class="btn restore" [disabled]="busy()" [attr.aria-label]="'delete.restoreAria' | t: { name: e.name || ('delete.unnamed' | t) }" (click)="restore(e)">
                  {{ 'delete.restore' | t }}
                </button>
              </li>
            }
          </ul>
        }
      } @else {
        <p class="muted">{{ 'common.loading' | t }}</p>
      }
    </section>
  `,
  styles: `
    .space-name { margin: var(--space-3) 0 0; }
    .rows { list-style: none; margin: var(--space-3) 0 0; padding: 0; display: grid; gap: var(--space-2); }
    .row { display: grid; grid-template-columns: minmax(0, 1fr); gap: var(--space-2) var(--space-3); padding: var(--space-2) 0; border-top: 1px solid var(--border); }
    .what p { margin: 0; overflow-wrap: anywhere; }
    .note { margin: var(--space-2) 0 0; }
    .retry-row { margin: var(--space-2) 0 0; }
    .empty { margin-top: var(--space-3); }
    @media (min-width: 40rem) { .row { grid-template-columns: minmax(0, 1fr) auto; align-items: center; } }
  `,
})
export class RecentlyDeleted {
  private readonly undo = inject(DeleteUndoService);
  private readonly data = inject(LocalDataService);
  /** The Data page's Google Drive service: where photos with no bytes left here are read back from (S4b-BL-293). */
  private readonly drive: PhotoRestorer | null = inject(DriveConnectService, { optional: true });
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');
  protected readonly space = inject(SpaceService);
  protected readonly i18n = inject(TranslationService);
  /** The list, or null while it is being read. */
  protected readonly entries = signal<readonly BinEntry[] | null>(null);
  protected readonly note = signal<Msg | null>(null);
  protected readonly error = signal<Msg | null>(null);
  protected readonly busy = signal(false);
  /** The photos of the entry just restored that a retry can still bring back, or null (S4b-BL-293). */
  protected readonly retry = signal<PhotoRetry | null>(null);

  constructor() {
    // Read again after every write to the store (a sync pull, a delete elsewhere) and when the space changes.
    effect(() => {
      this.data.settled();
      this.space.kind();
      untracked(() => void this.load());
    });
  }

  private async load(): Promise<void> {
    try {
      this.entries.set(await this.undo.bin());
      this.error.set(null);
    } catch (err: unknown) {
      this.entries.set([]);
      this.error.set({ key: 'delete.loadFailed', params: { reason: errorMsg(err) } });
    }
  }

  /** Restores one entry, says what came back, and keeps focus in the list. */
  protected async restore(entry: BinEntry): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.note.set(null);
    this.error.set(null);
    const before = this.entries() ?? [];
    const at = before.findIndex((e) => e.id === entry.id);
    let failed: Msg | null = null;
    try {
      const result = await this.undo.restore(entry, Date.now(), this.drive);
      if (result) this.note.set(this.words(result, entry.name || this.i18n.t('delete.unnamed')));
      this.retry.set(result?.retry ?? null);
    } catch (err: unknown) {
      failed = { key: 'delete.restoreFailed', params: { reason: errorMsg(err) } };
    }
    await this.load();
    if (failed) this.error.set(failed);
    this.busy.set(false);
    this.focusAfter(at);
  }

  /**
   * Tries the just-restored entry's photos again, after the person reconnected Google Drive (S4b-BL-293). The candidate is
   * already back, so only its photos are asked for; the words and the Retry button are updated from the new result.
   */
  protected async retryPhotos(): Promise<void> {
    const retry = this.retry();
    if (!retry || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    try {
      const result = await this.undo.retryPhotos(retry, Date.now(), this.drive);
      this.note.set(this.words(result, retry.name));
      this.retry.set(result.retry);
    } catch (err: unknown) {
      this.error.set({ key: 'delete.restoreFailed', params: { reason: errorMsg(err) } });
    }
    this.busy.set(false);
  }

  /** What the person is told about the photos: all back from Google Drive, some, or none and why; nothing special when no photo was lost. */
  private words(r: Restored, name: string): Msg {
    if (r.photosTotal === 0 || (r.reasons.length === 0 && r.photosFromDrive === 0)) return { key: 'delete.restored', params: { name } };
    if (r.reasons.length === 0) return { key: 'delete.photosAllDrive', params: { name, n: this.i18n.number(r.photosTotal) } };
    const why = this.i18n.list(r.reasons.map((reason) => this.i18n.t(WHY[reason])));
    const rest = r.photosTotal - r.photosBack;
    return r.photosBack === 0
      ? { key: 'delete.photosNone', params: { name, n: this.i18n.number(r.photosTotal), why } }
      : { key: 'delete.photosSome', params: { name, back: this.i18n.number(r.photosBack), n: this.i18n.number(r.photosTotal), rest: this.i18n.number(rest), why } };
  }

  /** The Restore button now at `index` (the entry after the restored one), else the last one, else the heading. */
  private focusAfter(index: number): void {
    setTimeout(() => {
      const buttons = this.host.nativeElement.querySelectorAll<HTMLElement>('button.restore');
      const next = buttons.length ? buttons[Math.min(Math.max(index, 0), buttons.length - 1)] : this.heading().nativeElement;
      next.focus();
    });
  }
}
