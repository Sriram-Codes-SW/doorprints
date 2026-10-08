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
 * *Save this walk?* (docs/11 5.27.6, docs/03 section 6.2b row 9, S4b-FR-15): the sheet that opens when a walk ends (or when the
 * Map opens and a walk was never asked about), as a modal `<dialog>` in the shape of `OfflineSave`. Three answers: *Save with
 * a house* (the picker: the house nearest to where the walk stopped preselected, the houses within 150 m, a search box),
 * *Keep for 30 days* (the default and the primary button) and *Delete this walk* (asks first). Esc, a tap outside and any
 * other way of closing it are *Keep for 30 days*: the walk is asked about once and is never lost by dismissing.
 */

import { Component, ElementRef, afterRenderEffect, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { ConfirmService } from '../../core/confirm.service';
import { HouseDto } from '../../core/models';
import { TraceStore } from '../../data/trace-store';
import type { SaveWalkResult } from '../../data/trace-store';
import { MAX_SAVED_WALKS_PER_DEVICE, MAX_SAVED_WALKS_PER_HOUSE } from '../../data/trace-rows';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import { TRACE } from '../../shared/trace-geo';
import { searchText } from './map-list';
import { TraceView } from './trace-view';
import { houseChoices } from './walk-end-sheet-houses';

type Phase = 'choose' | 'pick';

/** The sentence for a save that was refused (nothing changed, the walk stays in the 30-day trace). */
export function refusalMessage(reason: Extract<SaveWalkResult, { ok: false }>['reason']): Msg {
  switch (reason) {
    case 'tooLong':
      return { key: 'trace.save.tooLong', params: { max: TRACE.maxWalkPoints } };
    case 'houseFull':
      return { key: 'trace.save.houseFull', params: { max: MAX_SAVED_WALKS_PER_HOUSE } };
    case 'deviceFull':
      return { key: 'trace.save.allFull', params: { max: MAX_SAVED_WALKS_PER_DEVICE } };
    default:
      return { key: 'trace.save.noWalk' };
  }
}

/**
 * The *Save this walk?* sheet: choose a house for the walk, keep it 30 days (also what dismissing does), or delete it
 * after asking. Never loses a walk by being dismissed.
 */
@Component({
  selector: 'app-walk-end-sheet',
  imports: [TPipe],
  template: `
    <dialog #dlg class="walk-end" aria-labelledby="walk-end-title" (cancel)="onCancel($event)" (close)="onClose()" (click)="onBackdrop($event)">
      @if (walk(); as w) {
        @if (phase() === 'choose') {
          <h2 id="walk-end-title" tabindex="-1">{{ 'trace.end.title' | t }}</h2>
          <p id="walk-end-summary">
            {{ 'trace.end.summary' | t: { distance: i18n.metres(Math.round(w.lengthM)), minutes: i18n.number(minutes()) } }}
          </p>
          <p class="muted small">{{ 'trace.end.bodyWeb' | t }}</p>
          <div class="actions">
            <button type="button" id="walk-end-save" class="btn" (click)="startPick()">{{ 'trace.end.save' | t }}</button>
            <button type="button" id="walk-end-delete" class="btn btn-danger" (click)="remove()">{{ 'trace.end.delete' | t }}</button>
            <button type="button" id="walk-end-keep" class="btn btn-primary" (click)="keep()">{{ 'trace.end.keep' | t }}</button>
          </div>
        } @else {
          <h2 id="walk-end-title" tabindex="-1">{{ 'trace.pick.title' | t }}</h2>
          @if (houses().length === 0) {
            <p id="walk-end-none">{{ 'trace.pick.none' | t }}</p>
          } @else {
            <div class="field">
              <label for="walk-end-search">{{ 'trace.pick.search' | t }}</label>
              <input id="walk-end-search" type="search" autocomplete="off" [value]="query()" (input)="onQuery($event)" />
            </div>
            <fieldset class="houses">
              <legend class="sr-only">{{ 'trace.pick.listLabel' | t }}</legend>
              @for (row of rows(); track row.house.id) {
                <label class="row">
                  <input type="radio" name="walk-house" [value]="row.house.id" [checked]="selected() === row.house.id" (change)="select(row.house.id)" />
                  <span>
                    <strong>{{ row.house.label || ('common.untitled' | t) }}</strong>
                    @if (row.hint; as hint) {
                      <span class="muted small">{{ hint.key | t: hint.params }}</span>
                    }
                  </span>
                </label>
              } @empty {
                <p>{{ 'trace.pick.noMatch' | t }}</p>
              }
            </fieldset>
          }
          <!-- Present before a refusal arrives, so it is announced. -->
          <div role="alert">
            @if (refusal(); as m) {
              <p class="error" id="walk-end-refusal">{{ m.key | t: m.params }}</p>
            }
          </div>
          <div class="actions">
            <button type="button" id="walk-end-back" class="btn" (click)="phase.set('choose')">{{ 'common.cancel' | t }}</button>
            <button type="button" id="walk-end-confirm" class="btn btn-primary" [disabled]="selected() === null" (click)="save()">
              {{ 'trace.pick.confirm' | t }}
            </button>
          </div>
        }
      }
    </dialog>
  `,
  styles: `
    dialog.walk-end {
      width: min(30rem, calc(100vw - 2 * var(--space-4)));
      max-height: calc(100vh - 2 * var(--space-4));
      padding: var(--space-5);
      border: 1px solid var(--border-strong);
      border-radius: var(--radius);
      background: var(--surface);
      color: var(--text);
    }
    dialog.walk-end::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    h2 {
      margin: 0 0 var(--space-3);
      font-size: var(--text-lg);
    }
    h2:focus {
      outline: none;
    }
    p {
      margin: 0 0 var(--space-3);
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: var(--space-1);
      margin-bottom: var(--space-3);
    }
    .houses {
      margin: 0 0 var(--space-3);
      padding: 0;
      border: 0;
      max-height: 40vh;
      overflow-y: auto;
    }
    .row {
      display: flex;
      align-items: flex-start;
      gap: var(--space-2);
      min-height: var(--target);
      padding: var(--space-1) 0;
    }
    .row input {
      width: 1.25rem;
      height: 1.25rem;
      flex: none;
      margin-top: var(--space-1);
    }
    .row span span {
      display: block;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: flex-end;
      gap: var(--space-2);
    }
    .error {
      color: var(--error-text);
    }
    @media (max-width: 480px) {
      .actions {
        flex-direction: column;
        align-items: stretch;
      }
      /* The default answer first, under the thumb's first look. */
      .actions .btn-primary {
        order: -1;
      }
    }
  `,
})
export class WalkEndSheet {
  protected readonly view = inject(TraceView);
  protected readonly i18n = inject(TranslationService);
  private readonly store = inject(TraceStore);
  private readonly confirm = inject(ConfirmService);
  protected readonly Math = Math;

  /** The person's houses (the Map's list): the picker chooses among them. */
  readonly houses = input.required<readonly HouseDto[]>();

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  protected readonly walk = this.view.ask;
  protected readonly phase = signal<Phase>('choose');
  protected readonly query = signal('');
  protected readonly selected = signal<string | null>(null);
  protected readonly refusal = signal<Msg | null>(null);
  private readonly points = signal<readonly { lat: number; lon: number }[]>([]);
  private opener: HTMLElement | null = null;

  protected readonly minutes = computed(() => {
    const w = this.walk();
    return w ? Math.max(1, Math.round((w.endedAt - w.startedAt) / 60_000)) : 0;
  });

  private readonly choices = computed(() => houseChoices(this.points(), this.houses()));

  /** The picker's rows: the nearest house, the houses near the walk, or, once something is typed, every house that matches. */
  protected readonly rows = computed(() => {
    const { nearest, near } = this.choices();
    const hints = new Map<string, Msg>();
    const metres = (d: number) => this.i18n.metres(Math.max(1, Math.ceil(d)));
    if (nearest) hints.set(nearest.house.id, { key: 'trace.pick.nearest', params: { distance: metres(nearest.distanceM) } });
    for (const n of near) hints.set(n.house.id, { key: 'trace.pick.near', params: { distance: metres(n.distanceM) } });
    const q = this.query().trim().toLowerCase();
    const base = [...(nearest ? [nearest.house] : []), ...near.map((n) => n.house)];
    const list = q === '' ? base : this.houses().filter((h) => searchText(h).includes(q));
    return list.map((house) => ({ house, hint: hints.get(house.id) ?? null }));
  });

  constructor() {
    // Opens when a walk is to be asked about; closes when it is answered.
    effect(() => {
      const w = this.walk();
      untracked(() => (w ? this.open(w.walkId) : this.shut()));
    });
    // Focus goes to the title: on opening, and when the picker replaces the three answers.
    afterRenderEffect(() => {
      this.phase();
      if (this.walk()) this.dialog().nativeElement.querySelector<HTMLElement>('#walk-end-title')?.focus();
    });
  }

  /** Opens the sheet for an ended walk and reads its points to preselect the nearest house. */
  private async open(walkId: number): Promise<void> {
    this.phase.set('choose');
    this.query.set('');
    this.refusal.set(null);
    this.selected.set(null);
    this.points.set([]);
    const dlg = this.dialog().nativeElement;
    if (!dlg.open) {
      this.opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      dlg.showModal();
    }
    try {
      this.points.set(await this.store.walkPoints(walkId));
    } catch {
      this.points.set([]);
    }
    this.selected.set(this.choices().nearest?.house.id ?? null);
  }

  private shut(): void {
    const dlg = this.dialog().nativeElement;
    if (dlg.open) dlg.close();
  }

  protected startPick(): void {
    this.refusal.set(null);
    this.phase.set('pick');
  }

  protected onQuery(event: Event): void {
    this.query.set((event.target as HTMLInputElement).value);
  }

  protected select(id: string): void {
    this.selected.set(id);
    this.refusal.set(null);
  }

  protected async keep(): Promise<void> {
    await this.view.answerKeep();
  }

  protected async remove(): Promise<void> {
    const ok = await this.confirm.ask({ key: 'trace.end.deleteConfirm' }, { confirmKey: 'trace.end.delete', danger: true });
    if (ok) await this.view.answerDelete();
  }

  /** Saves the walk with the chosen house; a refused save leaves the sheet open with the reason. */
  protected async save(): Promise<void> {
    const id = this.selected();
    if (id === null) return;
    const house = this.houses().find((h) => h.id === id);
    const result = await this.view.answerSave(id, house?.label ?? '');
    if (!result.ok) this.refusal.set(refusalMessage(result.reason));
  }

  /** Esc is *Keep for 30 days*. */
  protected onCancel(event: Event): void {
    event.preventDefault();
    void this.keep();
  }

  /** A tap outside the sheet is *Keep for 30 days*. */
  protected onBackdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) void this.keep();
  }

  /** The dialog closed: when the walk is still unanswered (closed some other way), that is *Keep for 30 days*; focus goes back. */
  protected onClose(): void {
    if (this.walk()) void this.keep();
    const opener = this.opener;
    this.opener = null;
    const back = opener && opener.isConnected ? opener : document.getElementById('trace-start');
    back?.focus();
  }
}
