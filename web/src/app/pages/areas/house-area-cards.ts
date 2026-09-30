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

import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import { TPipe } from '../../i18n/t.pipe';
import { Msg } from '../../i18n/translation.service';
import { MAX_NOTE_TEXT, areasReaching, distancesToPlaces, notesReaching } from '../../shared/area';
import type { Area, AreaNoteRow, HousePoint, Place } from '../../shared/area';

/** Which note form is open: one for the house's street, or one for an area. */
type NoteForm = 'street' | 'area' | null;

/**
 * The *Area notes* card of the house page (slice 4a, docs/11 5.23): the notes that reach this house, newest first, each
 * with where it comes from (the area's name or the street), *Add a note for this street* (the street is the house's;
 * hidden when the house has none) and *Add a note for an area* (the areas the house is in come first in the list, then
 * the others). Notes are edited and deleted on My areas.
 */
@Component({
  selector: 'app-house-area-notes-card',
  imports: [FormsModule, RouterLink, TPipe],
  template: `
    <section class="card" aria-labelledby="house-area-notes-heading">
      <h2 id="house-area-notes-heading">{{ 'areaNotes.heading' | t }}</h2>
      @if (failure(); as f) {
        <p class="error small" role="alert">{{ f.key | t: f.params }}</p>
      }
      @if (reaching().length === 0) {
        <p id="house-area-notes-none" class="muted">{{ 'areaNotes.none' | t }}</p>
      } @else {
        <ul class="notes">
          @for (row of reaching(); track row.id) {
            <li>
              <p class="text">{{ row.note.text }}</p>
              <p class="muted small">{{ sourceOf(row).key | t: sourceOf(row).params }}</p>
            </li>
          }
        </ul>
      }

      @if (open(); as kind) {
        <form class="form" (ngSubmit)="save()" novalidate>
          @if (kind === 'area') {
            <div class="field">
              <label for="house-note-area">{{ 'areaNotes.area' | t }}</label>
              <select
                id="house-note-area"
                name="areaId"
                [(ngModel)]="areaId"
                [attr.aria-invalid]="areaError() ? 'true' : null"
                [attr.aria-describedby]="areaError() ? 'house-note-area-error' : null"
              >
                <option value="">{{ 'areaNotes.areaPick' | t }}</option>
                @if (inReach().length) {
                  <optgroup [label]="'areaNotes.inReach' | t">
                    @for (a of inReach(); track a.id) {
                      <option [value]="a.id">{{ a.name }}</option>
                    }
                  </optgroup>
                }
                @if (others().length) {
                  <optgroup [label]="'areaNotes.otherAreas' | t">
                    @for (a of others(); track a.id) {
                      <option [value]="a.id">{{ a.name }}</option>
                    }
                  </optgroup>
                }
              </select>
              @if (areaError()) {
                <p id="house-note-area-error" class="field-error" role="alert">{{ 'areaNotes.areaRequired' | t }}</p>
              }
            </div>
          } @else {
            <p class="muted small">{{ 'areaNotes.fromStreet' | t: { street: street() } }}</p>
          }
          <div class="field">
            <label for="house-note-text">{{ 'areaNotes.text' | t }}</label>
            <textarea
              id="house-note-text"
              name="text"
              rows="3"
              [attr.maxlength]="maxText"
              [(ngModel)]="text"
              [attr.aria-invalid]="textError() ? 'true' : null"
              [attr.aria-describedby]="textError() ? 'house-note-text-error' : null"
            ></textarea>
            @if (textError()) {
              <p id="house-note-text-error" class="field-error" role="alert">{{ 'areaNotes.textRequired' | t }}</p>
            }
          </div>
          <div class="actions">
            <button type="submit" class="btn btn-primary" [disabled]="saving()">{{ 'areaNotes.save' | t }}</button>
            <button type="button" class="btn" (click)="cancel()">{{ 'areaNotes.cancel' | t }}</button>
          </div>
        </form>
      } @else {
        <div class="actions">
          @if (street() !== '') {
            <button type="button" class="btn btn-sm" (click)="start('street')">{{ 'areaNotes.addStreet' | t }}</button>
          }
          <button type="button" class="btn btn-sm" (click)="start('area')">{{ 'areaNotes.addArea' | t }}</button>
          <a routerLink="/areas">{{ 'areaNotes.all' | t }}</a>
        </div>
        @if (noAreas()) {
          <p id="house-note-no-areas" class="muted small" role="status">
            {{ 'areaNotes.noAreas' | t }} <a routerLink="/areas">{{ 'areas.title' | t }}</a>
          </p>
        }
      }
    </section>
  `,
  styles: `
    :host {
      display: block;
    }
    .notes {
      list-style: none;
      margin: 0 0 var(--space-3);
      padding: 0;
      display: grid;
      gap: var(--space-2);
    }
    .text {
      margin: 0;
      overflow-wrap: anywhere;
      white-space: pre-wrap;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-3);
    }
  `,
})
export class HouseAreaNotesCard {
  private readonly api = inject(LocalDataService);
  private readonly announcer = inject(Announcer);

  /** The house as it stands on the page (its point and street), so the notes follow an edit. */
  readonly house = input.required<HousePoint>();

  protected readonly areas = signal<Area[]>([]);
  private readonly notes = signal<AreaNoteRow[]>([]);
  protected readonly failure = signal<Msg | null>(null);
  protected readonly open = signal<NoteForm>(null);
  protected readonly saving = signal(false);
  protected readonly textError = signal(false);
  protected readonly areaError = signal(false);
  /** Set when *Add a note for an area* was tried with no area to choose. */
  protected readonly noAreas = signal(false);
  protected areaId = '';
  protected text = '';
  protected readonly maxText = MAX_NOTE_TEXT;

  protected readonly reaching = computed(() => notesReaching(this.house(), this.areas(), this.notes()));
  protected readonly street = computed(() => (this.house().street ?? '').trim());
  protected readonly inReach = computed(() => areasReaching(this.house(), this.areas()));
  protected readonly others = computed(() => {
    const ids = new Set(this.inReach().map((a) => a.id));
    return this.areas().filter((a) => !ids.has(a.id));
  });

  constructor() {
    // Again after writes to this browser's store (a note saved here, an edit on My areas, a sync pull).
    effect(() => {
      this.api.settled();
      void this.reload();
    });
  }

  private async reload(): Promise<void> {
    try {
      this.areas.set(await firstValueFrom(this.api.areas()));
      this.notes.set(await firstValueFrom(this.api.areaNotes()));
    } catch {
      this.areas.set([]);
      this.notes.set([]);
    }
  }

  protected sourceOf(row: AreaNoteRow): Msg {
    const n = row.note;
    const name = n.areaId !== undefined ? this.areas().find((a) => a.id === n.areaId)?.name : undefined;
    return n.areaId !== undefined
      ? { key: 'areaNotes.fromArea', params: { name: name ?? '' } }
      : { key: 'areaNotes.fromStreet', params: { street: n.street ?? '' } };
  }

  protected start(kind: 'street' | 'area'): void {
    this.noAreas.set(false);
    if (kind === 'area' && this.areas().length === 0) {
      this.noAreas.set(true);
      return;
    }
    this.text = '';
    this.areaId = '';
    this.textError.set(false);
    this.areaError.set(false);
    this.open.set(kind);
    setTimeout(() => document.getElementById(kind === 'area' ? 'house-note-area' : 'house-note-text')?.focus(), 0);
  }

  protected cancel(): void {
    this.open.set(null);
  }

  protected async save(): Promise<void> {
    const kind = this.open();
    if (!kind || this.saving()) return;
    const text = this.text.trim();
    this.areaError.set(kind === 'area' && this.areaId === '');
    this.textError.set(text === '');
    if (this.areaError() || this.textError()) {
      document.getElementById(this.areaError() ? 'house-note-area' : 'house-note-text')?.focus();
      return;
    }
    this.saving.set(true);
    try {
      const id = await firstValueFrom(this.api.newAreaNoteId());
      await firstValueFrom(this.api.saveAreaNote(kind === 'area' ? { id, areaId: this.areaId, text } : { id, street: this.street(), text }));
      this.open.set(null);
      this.failure.set(null);
      this.announcer.announce({ key: 'areaNotes.saved' });
      await this.reload();
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    } finally {
      this.saving.set(false);
    }
  }
}

/**
 * The *Distances* card of the house page (slice 4a, docs/11 5.22): per place of mine, the straight-line kilometres with
 * one decimal and the Plan estimate on foot. Hidden when there are no places or the house has no point.
 */
@Component({
  selector: 'app-house-distances-card',
  imports: [TPipe],
  template: `
    @if (rows().length) {
      <section class="card" aria-labelledby="house-distances-heading">
        <h2 id="house-distances-heading">{{ 'distances.heading' | t }}</h2>
        <ul class="distances">
          @for (d of rows(); track d.place.id) {
            <li>{{ 'distances.row' | t: { name: d.place.name, km: d.km, min: d.minutes } }}</li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .distances {
      margin: 0;
      padding-inline-start: var(--space-4);
      overflow-wrap: anywhere;
    }
  `,
})
export class HouseDistancesCard {
  private readonly api = inject(LocalDataService);

  readonly house = input.required<HousePoint>();
  private readonly places = signal<Place[]>([]);
  protected readonly rows = computed(() => distancesToPlaces(this.house(), this.places()));

  constructor() {
    effect(() => {
      this.api.settled();
      this.api.places().subscribe({
        next: (list) => this.places.set(list),
        error: () => this.places.set([]),
      });
    });
  }
}
