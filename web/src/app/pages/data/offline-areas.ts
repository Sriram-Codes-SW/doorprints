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

/**
 * *Offline maps* on Your data (docs/11 5.20, S4b-BL-79): the saved areas with their size, a delete for each (after
 * asking), the storage they use and what the browser has left. The Settings > Offline maps of Android's apps; the
 * areas are saved from the Map (`pages/map/offline-save.ts`).
 */

import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import { OfflineMapsService } from '../../offline/offline-maps.service';
import { freeBytes, megabyteDigits, megabytes } from '../../offline/offline-tiles';

@Component({
  selector: 'app-offline-areas',
  imports: [TPipe],
  template: `
    <section class="card" aria-labelledby="offline-heading">
      <h2 id="offline-heading" #heading tabindex="-1">{{ 'offline.settingsHeading' | t }}</h2>
      <p class="muted">{{ 'offline.settingsHint' | t }}</p>
      @if (!offline.supported()) {
        <p class="warn-box small">{{ 'offline.unsupported' | t }}</p>
      } @else if (checking()) {
        <p role="status">{{ 'common.loading' | t }}</p>
      } @else {
        @if (offline.progress(); as pr) {
          <!-- A download from the Map still running: shown here too, not a live region. -->
          <p>{{ 'offline.progress' | t: { name: pr.name, done: i18n.number(pr.done), total: i18n.number(pr.total), mb: mb(pr.bytes) } }}</p>
        }
        @if (offline.areas().length === 0) {
          <p class="empty">{{ 'offline.settingsEmpty' | t }}</p>
        } @else {
          <ul class="areas">
            @for (a of offline.areas(); track a.id) {
              <li>
                <span class="info">
                  <strong>{{ a.name }}</strong>
                  <span class="muted small">
                    @if (a.state === 'ready') {
                      {{ 'offline.sizeMb' | t: { mb: mb(a.bytes) } }}
                    } @else {
                      {{ 'offline.stateFailed' | t }}
                    }
                  </span>
                </span>
                <button
                  type="button"
                  class="btn btn-danger"
                  [attr.aria-label]="'offline.delete' | t: { name: a.name }"
                  [disabled]="offline.saving()"
                  (click)="remove(a.id, a.name)"
                >
                  {{ 'common.delete' | t }}
                </button>
              </li>
            }
          </ul>
          <p class="muted small">{{ usage() }}</p>
        }
      }
    </section>
  `,
  styles: `
    .card > h2 {
      margin-top: 0;
    }
    .areas {
      list-style: none;
      margin: 0 0 var(--space-3);
      padding: 0;
    }
    .areas li {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
      min-height: var(--target);
      padding: var(--space-2) 0;
      border-bottom: 1px solid var(--border);
    }
    .info {
      display: flex;
      flex-direction: column;
      min-width: 0;
      overflow-wrap: anywhere;
    }
  `,
})
export class OfflineAreasCard implements OnInit {
  protected readonly offline = inject(OfflineMapsService);
  protected readonly i18n = inject(TranslationService);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly heading = viewChild.required<ElementRef<HTMLElement>>('heading');

  /** While the saved areas are checked against the cache (a browser may have cleared it). */
  protected readonly checking = signal(true);

  protected readonly usage = computed(() => {
    const used = this.offline.usedBytes();
    const free = freeBytes(this.offline.storage());
    return free === null
      ? this.i18n.t('offline.usedOnly', { mb: this.mb(used) })
      : this.i18n.t('offline.usedFree', { mb: this.mb(used), free: this.mb(free) });
  });

  async ngOnInit(): Promise<void> {
    try {
      await Promise.all([this.offline.verify(), this.offline.refreshStorage()]);
    } finally {
      this.checking.set(false);
    }
  }

  protected mb(bytes: number): string {
    return this.i18n.number(megabytes(bytes), megabyteDigits(bytes));
  }

  protected async remove(id: string, name: string): Promise<void> {
    const ok = await this.confirm.ask({ key: 'offline.deleteConfirm', params: { name } }, { confirmKey: 'common.delete', danger: true });
    if (!ok) return;
    await this.offline.remove(id);
    this.announcer.announce({ key: 'offline.deleted', params: { name } });
    // The row is gone with the button that had focus: it goes to the card's heading, not to <body>.
    this.heading().nativeElement.focus({ preventScroll: true });
  }
}
