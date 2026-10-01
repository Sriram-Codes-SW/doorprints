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
import { MAX_PLACES, MAX_PLACE_NAME } from '../../shared/area';
import type { Place } from '../../shared/area';
import { PointPicker } from '../areas/point-picker';

/** The form's values as typed; `lat`/`lon` are null until a point is chosen. */
interface PlaceForm {
  id: string;
  name: string;
  lat: number | null;
  lon: number | null;
}

/**
 * My places (docs/11 "Design of slice 4a", 5.22): work, family and other places the person goes to, at most 10: a list
 * with add, edit (name and the point, by the map, the coordinates or the current location) and delete. The house page
 * and Compare show the distance from each house to these.
 */
@Component({
  selector: 'app-places-page',
  imports: [FormsModule, RouterLink, TPipe, PointPicker],
  templateUrl: './places-page.html',
  styleUrl: '../areas/areas-page.css',
})
export class PlacesPage {
  private readonly api = inject(LocalDataService);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);

  protected readonly loading = signal(true);
  protected readonly failure = signal<Msg | null>(null);
  protected readonly places = signal<Place[]>([]);
  protected readonly saving = signal(false);
  protected readonly form = signal<PlaceForm | null>(null);
  protected readonly isNewPlace = signal(true);
  protected readonly nameError = signal(false);
  protected readonly pointError = signal(false);
  protected readonly maxName = MAX_PLACE_NAME;
  protected readonly full = computed(() => this.places().length >= MAX_PLACES);

  constructor() {
    // Again after writes to this browser's store (a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      void this.load();
    });
  }

  private async load(): Promise<void> {
    try {
      this.places.set(await firstValueFrom(this.api.places()));
      this.failure.set(null);
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    } finally {
      this.loading.set(false);
    }
  }

  protected add(): void {
    if (this.full()) return;
    this.isNewPlace.set(true);
    this.nameError.set(false);
    this.pointError.set(false);
    this.form.set({ id: '', name: '', lat: null, lon: null });
    this.focusSoon();
  }

  protected edit(place: Place): void {
    this.isNewPlace.set(false);
    this.nameError.set(false);
    this.pointError.set(false);
    this.form.set({ id: place.id, name: place.name, lat: place.lat, lon: place.lon });
    this.focusSoon();
  }

  protected close(): void {
    this.form.set(null);
  }

  protected patch(changes: Partial<PlaceForm>): void {
    const f = this.form();
    if (f) this.form.set({ ...f, ...changes });
  }

  protected setPoint(point: { lat: number; lon: number }): void {
    this.pointError.set(false);
    this.patch(point);
  }

  protected async save(): Promise<void> {
    const f = this.form();
    if (!f || this.saving()) return;
    const name = f.name.trim();
    this.nameError.set(name === '');
    this.pointError.set(f.lat === null || f.lon === null);
    if (name === '' || f.lat === null || f.lon === null) {
      // The first field that needs fixing: the name, else the position (its latitude field says what is missing).
      document.getElementById(name === '' ? 'place-name' : 'place-point-lat')?.focus();
      return;
    }
    this.saving.set(true);
    try {
      const id = f.id || (await firstValueFrom(this.api.newPlaceId()));
      await firstValueFrom(this.api.savePlace({ id, name, lat: f.lat, lon: f.lon }));
      this.form.set(null);
      this.failure.set(null);
      this.announcer.announce({ key: 'places.saved' });
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
    const ok = await this.confirm.ask({ key: 'places.confirmDelete' }, { confirmKey: 'places.delete', danger: true });
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deletePlace(f.id));
      this.form.set(null);
      this.announcer.announce({ key: 'places.deleted' });
      await this.load();
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    }
  }

  private focusSoon(): void {
    setTimeout(() => document.getElementById('place-name')?.focus(), 0);
  }
}
