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

import { Component, OnDestroy, inject, input, output, signal } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import { TPipe } from '../../i18n/t.pipe';
import { LocationMap, round6 } from '../../shared/location-map';
import type { LatLon } from '../../shared/location-map';
import { locateOnce } from '../../shared/locate-once';
import { COUNTRY_VIEW, locationErrorKey, parseCoordinate } from '../../shared/map-center';
import { Msg } from '../../i18n/translation.service';

/**
 * The point of an area or a place (docs/11 "Design of slice 4a"): the map picker of the house page (tap or drag),
 * the latitude and longitude as text, and *Use my location*. It owns no state: the parent holds the point and hears
 * `picked` with the new one (rounded to 6 decimals). With no point yet the map shows India as a starting view.
 */
@Component({
  selector: 'app-point-picker',
  imports: [LocationMap, TPipe],
  template: `
    <app-location-map
      [lat]="lat() ?? start.lat"
      [lon]="lon() ?? start.lon"
      [zoom]="lat() === null ? start.zoom : 15"
      [label]="'point.mapLabel' | t"
      [describedBy]="idPrefix() + '-map-hint'"
      (moved)="pick($event)"
    />
    <p [id]="idPrefix() + '-map-hint'" class="muted small">{{ 'point.mapHint' | t }}</p>
    <div class="coords">
      <div class="field">
        <label [for]="idPrefix() + '-lat'">{{ 'house.lat' | t }}</label>
        <input
          [id]="idPrefix() + '-lat'"
          type="text"
          inputmode="decimal"
          autocomplete="off"
          spellcheck="false"
          [value]="lat() ?? ''"
          [attr.aria-invalid]="invalid() || showRequired() ? 'true' : null"
          [attr.aria-describedby]="invalid() || showRequired() ? idPrefix() + '-error' : null"
          (change)="typed('lat', $event)"
        />
      </div>
      <div class="field">
        <label [for]="idPrefix() + '-lon'">{{ 'house.lon' | t }}</label>
        <input
          [id]="idPrefix() + '-lon'"
          type="text"
          inputmode="decimal"
          autocomplete="off"
          spellcheck="false"
          [value]="lon() ?? ''"
          [attr.aria-invalid]="invalid() || showRequired() ? 'true' : null"
          [attr.aria-describedby]="invalid() || showRequired() ? idPrefix() + '-error' : null"
          (change)="typed('lon', $event)"
        />
      </div>
    </div>
    <div role="alert">
      @if (invalid()) {
        <p [id]="idPrefix() + '-error'" class="field-error">{{ 'house.coordsInvalid' | t }}</p>
      } @else if (showRequired()) {
        <p [id]="idPrefix() + '-error'" class="field-error">{{ 'point.required' | t }}</p>
      }
      @if (failure(); as f) {
        <p class="field-error">{{ f.key | t }}</p>
      }
    </div>
    @if (canLocate) {
      <button type="button" class="btn btn-sm" [attr.aria-disabled]="locating() ? 'true' : null" (click)="useMyLocation()">
        {{ (locating() ? 'house.locating' : 'house.useMyLocation') | t }}
      </button>
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .coords {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-4);
      margin-block-start: var(--space-2);
    }
    .coords .field {
      flex: 1 1 10rem;
    }
  `,
})
export class PointPicker implements OnDestroy {
  private readonly announcer = inject(Announcer);

  /** The point as the parent holds it; null while none is chosen. */
  readonly lat = input<number | null>(null);
  readonly lon = input<number | null>(null);
  /** Makes the field ids unique when two pickers are on a page. */
  readonly idPrefix = input('point');
  /** The parent tried to save without a point. */
  readonly showRequired = input(false);
  readonly picked = output<LatLon>();

  protected readonly start = COUNTRY_VIEW;
  protected readonly invalid = signal(false);
  protected readonly locating = signal(false);
  protected readonly failure = signal<Msg | null>(null);
  protected readonly canLocate = typeof navigator !== 'undefined' && 'geolocation' in navigator;
  private destroyed = false;

  ngOnDestroy(): void {
    this.destroyed = true;
  }

  protected pick(point: LatLon): void {
    this.invalid.set(false);
    this.failure.set(null);
    this.picked.emit({ lat: round6(point.lat), lon: round6(point.lon) });
  }

  protected typed(axis: 'lat' | 'lon', event: Event): void {
    const value = parseCoordinate((event.target as HTMLInputElement).value, axis === 'lat' ? 90 : 180);
    if (value === null) {
      this.invalid.set(true);
      return;
    }
    this.invalid.set(false);
    // Until both are known the other axis starts from the map's starting view, as the house page does for a new house.
    const lat = axis === 'lat' ? value : (this.lat() ?? COUNTRY_VIEW.lat);
    const lon = axis === 'lon' ? value : (this.lon() ?? COUNTRY_VIEW.lon);
    this.pick({ lat, lon });
  }

  protected useMyLocation(): void {
    if (!this.canLocate || this.locating()) return;
    this.locating.set(true);
    locateOnce({
      gone: () => this.destroyed,
      found: (pos) => {
        this.locating.set(false);
        this.pick({ lat: pos.coords.latitude, lon: pos.coords.longitude });
        this.announcer.announce({ key: 'point.found' });
      },
      failed: (err) => {
        this.locating.set(false);
        this.failure.set({ key: locationErrorKey(err) });
      },
    });
  }
}
