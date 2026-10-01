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

import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import { TPipe } from '../../i18n/t.pipe';
import { Msg } from '../../i18n/translation.service';
import {
  DEFAULT_RADIUS_M,
  MAX_AREAS,
  MAX_AREA_NAME,
  MAX_NOTE_TEXT,
  MAX_RADIUS_M,
  MAX_STREET,
  MIN_RADIUS_M,
  RADIUS_STEP_M,
  sameStreet,
} from '../../shared/area';
import type { Area, AreaNoteRow } from '../../shared/area';
import { PointPicker } from './point-picker';

/** The form's values as typed; `lat`/`lon` are null until a point is chosen. */
interface AreaForm {
  id: string;
  name: string;
  lat: number | null;
  lon: number | null;
  radiusM: number;
  enabled: boolean;
}

/** The notes filter: every note, the street notes, or the notes of one area (its id). */
const ALL = '';
const STREETS = '!streets';

/**
 * My areas (docs/11 "Design of slice 4a", 5.17 data part, 5.23): the list of hunting areas with add, edit (name, the
 * point, a radius of 200..2000 m in 100 m steps, the *Wake me here* switch that is only stored in this slice) and delete,
 * at most 20; below it the area notes (edit, delete, filter by area or street), at most 200. Notes are added from a
 * house's page.
 */
@Component({
  selector: 'app-areas-page',
  imports: [FormsModule, RouterLink, TPipe, PointPicker],
  templateUrl: './areas-page.html',
  styleUrl: './areas-page.css',
})
export class AreasPage {
  private readonly api = inject(LocalDataService);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);

  protected readonly loading = signal(true);
  protected readonly failure = signal<Msg | null>(null);
  protected readonly areas = signal<Area[]>([]);
  protected readonly notes = signal<AreaNoteRow[]>([]);
  protected readonly saving = signal(false);

  /** The open form, or null. */
  protected readonly form = signal<AreaForm | null>(null);
  protected readonly isNewArea = signal(true);
  protected readonly nameError = signal(false);
  protected readonly pointError = signal(false);

  protected readonly maxName = MAX_AREA_NAME;
  protected readonly maxText = MAX_NOTE_TEXT;
  protected readonly maxStreet = MAX_STREET;
  protected readonly minRadius = MIN_RADIUS_M;
  protected readonly maxRadius = MAX_RADIUS_M;
  protected readonly radiusStep = RADIUS_STEP_M;
  protected readonly full = computed(() => this.areas().length >= MAX_AREAS);

  // The notes list.
  protected readonly filter = signal(ALL);
  protected readonly query = signal('');
  protected readonly STREETS = STREETS;
  protected readonly editingNote = signal<string | null>(null);
  protected noteText = '';
  protected noteStreet = '';
  protected readonly noteError = signal(false);

  private readonly names = computed(() => new Map(this.areas().map((a) => [a.id, a.name])));
  protected readonly visibleNotes = computed(() => {
    const filter = this.filter();
    const q = this.query().trim();
    return this.notes().filter((row) => {
      const n = row.note;
      if (filter === STREETS && n.street === undefined) return false;
      if (filter !== ALL && filter !== STREETS && n.areaId !== filter) return false;
      return q === '' || (n.street !== undefined && (sameStreet(n.street, q) || n.street.toLowerCase().includes(q.toLowerCase())));
    });
  });

  constructor() {
    // Again after writes to this browser's store (a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      void this.load();
    });
  }

  private async load(): Promise<void> {
    try {
      this.areas.set(await firstValueFrom(this.api.areas()));
      this.notes.set(await firstValueFrom(this.api.areaNotes()));
      this.failure.set(null);
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    } finally {
      this.loading.set(false);
    }
  }

  protected sourceOf(row: AreaNoteRow): Msg {
    const n = row.note;
    return n.areaId !== undefined
      ? { key: 'areaNotes.fromArea', params: { name: this.names().get(n.areaId) ?? { key: 'areaNotes.areaGone' } } }
      : { key: 'areaNotes.fromStreet', params: { street: n.street ?? '' } };
  }

  // ---- the area form ----

  protected add(): void {
    if (this.full()) return;
    this.isNewArea.set(true);
    this.nameError.set(false);
    this.pointError.set(false);
    this.form.set({ id: '', name: '', lat: null, lon: null, radiusM: DEFAULT_RADIUS_M, enabled: true });
    this.focusSoon('area-name');
  }

  protected edit(area: Area): void {
    this.isNewArea.set(false);
    this.nameError.set(false);
    this.pointError.set(false);
    this.form.set({ id: area.id, name: area.name, lat: area.lat, lon: area.lon, radiusM: area.radiusM, enabled: area.enabled });
    this.focusSoon('area-name');
  }

  protected close(): void {
    this.form.set(null);
  }

  protected patch(changes: Partial<AreaForm>): void {
    const f = this.form();
    if (f) this.form.set({ ...f, ...changes });
  }

  protected setPoint(point: { lat: number; lon: number }): void {
    this.pointError.set(false);
    this.patch(point);
  }

  protected setRadius(event: Event): void {
    this.patch({ radiusM: Number((event.target as HTMLInputElement).value) });
  }

  protected setEnabled(event: Event): void {
    this.patch({ enabled: (event.target as HTMLInputElement).checked });
  }

  protected async save(): Promise<void> {
    const f = this.form();
    if (!f || this.saving()) return;
    const name = f.name.trim();
    this.nameError.set(name === '');
    this.pointError.set(f.lat === null || f.lon === null);
    if (name === '' || f.lat === null || f.lon === null) {
      // The first field that needs fixing: the name, else the position (its latitude field says what is missing).
      document.getElementById(name === '' ? 'area-name' : 'area-point-lat')?.focus();
      return;
    }
    this.saving.set(true);
    try {
      const id = f.id || (await firstValueFrom(this.api.newAreaId()));
      await firstValueFrom(this.api.saveArea({ id, name, lat: f.lat, lon: f.lon, radiusM: f.radiusM, enabled: f.enabled }));
      this.form.set(null);
      this.failure.set(null);
      this.announcer.announce({ key: 'areas.saved' });
      await this.load();
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    } finally {
      this.saving.set(false);
    }
  }

  protected async remove(): Promise<void> {
    const f = this.form();
    if (!f || f.id === '') return;
    const ok = await this.confirm.ask({ key: 'areas.confirmDelete' }, { confirmKey: 'areas.delete', danger: true });
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deleteArea(f.id));
      this.form.set(null);
      this.announcer.announce({ key: 'areas.deleted' });
      await this.load();
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    }
  }

  // ---- the area notes ----

  protected editNote(row: AreaNoteRow): void {
    this.editingNote.set(row.id);
    this.noteText = row.note.text;
    this.noteStreet = row.note.street ?? '';
    this.noteError.set(false);
    this.focusSoon('note-text');
  }

  protected cancelNote(): void {
    this.editingNote.set(null);
  }

  protected async saveNote(row: AreaNoteRow): Promise<void> {
    const text = this.noteText.trim();
    const street = this.noteStreet.trim();
    const isStreet = row.note.street !== undefined;
    this.noteError.set(text === '' || (isStreet && street === ''));
    if (this.noteError()) {
      document.getElementById(text === '' ? 'note-text' : 'note-street')?.focus();
      return;
    }
    try {
      await firstValueFrom(
        this.api.saveAreaNote(isStreet ? { id: row.id, street, text } : { id: row.id, areaId: row.note.areaId, text }),
      );
      this.editingNote.set(null);
      this.failure.set(null);
      this.announcer.announce({ key: 'areaNotes.saved' });
      await this.load();
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    }
  }

  protected async removeNote(row: AreaNoteRow): Promise<void> {
    const ok = await this.confirm.ask({ key: 'areaNotes.confirmDelete' }, { confirmKey: 'areaNotes.delete', danger: true });
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deleteAreaNote(row.id));
      this.announcer.announce({ key: 'areaNotes.deleted' });
      await this.load();
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    }
  }

  private focusSoon(id: string): void {
    setTimeout(() => document.getElementById(id)?.focus(), 0);
  }
}
