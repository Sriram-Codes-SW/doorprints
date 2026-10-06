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
 * The *Saved walks* card of a house (docs/11 5.27.6, docs/03 section 6.2b row 10, S4b-FR-15): the walks the person linked to
 * this house, newest first, each as *date, distance, minutes* with *Show on map* and *Delete walk*. *Show on map* never leaves
 * the page: it hands the walk to the page's own location map as an overlay (the line, a halo for 3 seconds and the box to
 * frame). The walks are read from this browser's store only; they are in no backup, copy, Drive or server.
 */

import { Component, DestroyRef, Injector, afterNextRender, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { TraceStore } from '../../data/trace-store';
import type { SavedWalkRow } from '../../data/trace-store';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { MapOverlay } from '../../shared/location-map';
import type { TraceWalk } from '../../shared/trace-geo';
import { checkGeoJson, trackGeoJson, baseLines } from '../../shared/trace-style';

/** The halo that says *this is the walk you asked for* stays this long (docs/11 5.27.6). */
export const HALO_MS = 3000;

/** The box `[[west, south], [east, north]]` of a walk. */
export function walkBounds(walk: TraceWalk): [[number, number], [number, number]] {
  let west = Infinity;
  let south = Infinity;
  let east = -Infinity;
  let north = -Infinity;
  for (const p of walk.points) {
    west = Math.min(west, p.lon);
    east = Math.max(east, p.lon);
    south = Math.min(south, p.lat);
    north = Math.max(north, p.lat);
  }
  return [
    [west, south],
    [east, north],
  ];
}

@Component({
  selector: 'app-house-walks-card',
  imports: [TPipe],
  template: `
    @if (!isNew()) {
      <section class="card" aria-labelledby="walks-heading">
        <h2 id="walks-heading" tabindex="-1">{{ 'trace.house.title' | t }}</h2>
        @if (loading()) {
          <p class="muted" role="status">{{ 'common.loading' | t }}</p>
        } @else if (failed()) {
          <p class="error" role="alert" id="walks-error">{{ 'trace.house.loadError' | t }}</p>
        } @else if (rows().length === 0) {
          <p class="muted" id="walks-empty">{{ 'trace.house.empty' | t }}</p>
        } @else {
          <ul class="walks" [attr.aria-label]="'trace.house.title' | t">
            @for (row of rows(); track row.id) {
              <li>
                <span class="row-text" [id]="'walk-' + row.id">
                  {{ 'trace.house.row' | t: { date: i18n.dateOnly(row.startedAt), distance: i18n.metres(Math.round(row.lengthM)), minutes: i18n.number(minutes(row)) } }}
                </span>
                <span class="row-actions">
                  <button type="button" class="btn btn-sm" [attr.aria-describedby]="'walk-' + row.id" (click)="show(row)">{{ 'trace.house.show' | t }}</button>
                  <button type="button" class="btn btn-sm btn-danger" [attr.aria-describedby]="'walk-' + row.id" (click)="remove(row)">{{ 'trace.house.delete' | t }}</button>
                </span>
              </li>
            }
          </ul>
        }
        <p class="muted small">{{ 'trace.house.localWeb' | t }}</p>
      </section>
    }
  `,
  styles: `
    .walks {
      list-style: none;
      margin: 0 0 var(--space-3);
      padding: 0;
    }
    li {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-2);
      padding: var(--space-2) 0;
      border-bottom: 1px solid var(--border);
    }
    .row-actions {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2);
    }
    h2:focus {
      outline: none;
    }
  `,
})
export class HouseWalksCard {
  protected readonly i18n = inject(TranslationService);
  private readonly store = inject(TraceStore);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly injector = inject(Injector);
  protected readonly Math = Math;

  /** The house's id; a house not saved yet (`isNew`) has no walks and the card is not shown. */
  readonly houseId = input.required<string>();
  readonly isNew = input(false);
  /** What to draw on the page's own map: the walk and its halo, or null to clear. */
  readonly overlay = output<MapOverlay | null>();

  protected readonly rows = signal<readonly SavedWalkRow[]>([]);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  private haloTimer: ReturnType<typeof setTimeout> | undefined;
  private shownId: string | null = null;
  private destroyed = false;

  constructor() {
    effect(() => {
      const id = this.houseId();
      const isNew = this.isNew();
      untracked(() => void this.load(id, isNew));
    });
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      clearTimeout(this.haloTimer);
    });
  }

  private async load(houseId: string, isNew: boolean): Promise<void> {
    if (isNew || houseId === '') {
      this.rows.set([]);
      this.loading.set(false);
      return;
    }
    try {
      const rows = await this.store.savedWalksOf(houseId);
      if (this.destroyed) return;
      this.rows.set(rows);
      this.failed.set(false);
    } catch {
      if (this.destroyed) return;
      this.failed.set(true);
    }
    this.loading.set(false);
  }

  protected minutes(row: SavedWalkRow): number {
    return Math.max(1, Math.round((row.endedAt - row.startedAt) / 60_000));
  }

  /** *Show on map*: the line on the page's map, framed, with a halo for 3 seconds. */
  protected async show(row: SavedWalkRow): Promise<void> {
    const saved = await this.store.savedWalk(row.id);
    if (!saved || this.destroyed) return;
    clearTimeout(this.haloTimer);
    this.shownId = row.id;
    const walks = trackGeoJson([saved.walk], []);
    const halo = checkGeoJson(baseLines(saved.walk.points));
    this.overlay.emit({ walks, check: halo, fit: walkBounds(saved.walk) });
    this.haloTimer = setTimeout(() => {
      if (!this.destroyed) this.overlay.emit({ walks, check: null, fit: null });
    }, HALO_MS);
  }

  protected async remove(row: SavedWalkRow): Promise<void> {
    const ok = await this.confirm.ask({ key: 'trace.house.deleteConfirm' }, { confirmKey: 'trace.house.delete', danger: true });
    if (!ok) return;
    await this.store.deleteSavedWalk(row.id);
    if (this.shownId === row.id) {
      clearTimeout(this.haloTimer);
      this.shownId = null;
      this.overlay.emit(null);
    }
    await this.load(this.houseId(), this.isNew());
    this.announcer.announce({ key: 'trace.deleted.snack' });
    // The row that held focus is gone: focus goes to the card's heading.
    afterNextRender(() => document.getElementById('walks-heading')?.focus(), { injector: this.injector });
  }
}
