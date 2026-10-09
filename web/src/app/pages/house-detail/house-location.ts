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

// Where the house is, on the house page (S4b-BL-168): the pin and the typed coordinates, the Approximate switch, Use
// my location, Find (the place name looked up on the person's tap), and Fill address from map (the reverse lookup and
// the asking before a typed value is replaced). It is separate from `house-detail-page.ts` because the page reads it in
// several places: the location card of the template, the save (refused until the position is set and the coordinates
// are valid), the arrival (a new house with no position starts unset, at a zoom the page chooses), the restored and
// discarded drafts, and the page's destruction (which drops a late answer). The page owns the draft; this changes it
// only through `patch`, and takes back the page's "Set where the house is" failure through `error`.
import { computed, signal } from '@angular/core';
import type { WritableSignal } from '@angular/core';
import type { Subscription } from 'rxjs';
import type { Announcer } from '../../core/announcer.service';
import type { ConfirmService } from '../../core/confirm.service';
import { errorMsg } from '../../core/format';
import type { GeocodeService } from '../../core/geocode.service';
import type { HouseDto, LocationSource } from '../../core/models';
import type { Msg, TranslationService } from '../../i18n/translation.service';
import { locateOnce } from '../../shared/locate-once';
import type { LatLon } from '../../shared/location-map';
import { round6 } from '../../shared/location-map';
import { locationErrorKey, parseCoordinate } from '../../shared/map-center';
import { runResult } from '../../shared/run-result';
import type { RunResult } from '../../shared/run-result';
import { FIELD_LABEL, addressFill } from './house-draft-merge';
import type { AddressLookup, FillField } from './house-draft-merge';

/** What the location card of the house page needs from the page. */
export interface HouseLocationDeps {
  geocode: Pick<GeocodeService, 'search' | 'reverse'>;
  i18n: TranslationService;
  announcer: Announcer;
  confirm: Pick<ConfirmService, 'choose'>;
  /** The house as edited, or null before it is opened. */
  draft: () => HouseDto | null;
  /** The page's one way to change the draft: merges the fields in and marks the page dirty. */
  patch: (changes: Partial<HouseDto>) => void;
  /** The page's failure at the top; putting the pin takes back "Set where the house is before saving". */
  error: WritableSignal<RunResult<Msg> | null>;
  /** True once the page is destroyed: a late answer then fills no form and announces nothing. */
  gone: () => boolean;
}

export class HouseLocation {
  /**
   * False for a new house opened without a position (the share target, a bookmarked /houses/new) until the user
   * has put the pin: chosen a spot on the map, typed coordinates, or used their location. Saving is refused until
   * then — a house saved at the starting view would be a house in the wrong place.
   */
  readonly placed = signal(true);
  /** The save was refused because the position is not set: the card and both fields say so. */
  readonly required = signal(false);
  readonly locating = signal(false);
  readonly geocoding = signal(false);
  /** *Find “Indiranagar” on the map* is running (S4b-BL-83). */
  readonly finding = signal(false);
  /** A failure of "Use my location", *Find* or "Fill address from map", shown under those buttons. */
  readonly message = signal<RunResult<Msg> | null>(null);
  /** What *Find* put on the map, said under the buttons until the pin is moved or the page is left. */
  readonly foundMessage = signal<RunResult<Msg> | null>(null);
  /** Typed coordinates that are not valid; the typed text stays in the field and the pin stays where it was. */
  readonly coordsInvalid = signal<{ lat: boolean; lon: boolean }>({ lat: false, lon: false });
  readonly coordsError = computed(() => this.coordsInvalid().lat || this.coordsInvalid().lon);
  /** Zoom the location map opens at: street level for a known position, wider for a starting guess. */
  readonly startZoom = signal(16);
  readonly canLocate = typeof navigator !== 'undefined' && 'geolocation' in navigator;
  /**
   * The address lookup and the place lookup in flight: leaving the page ends them, so their late result fills no
   * form, opens no question and announces neither "Form filled in" nor "Location found" on whatever page the user
   * went to ("Locating…" is dropped by {@link HouseLocationDeps.gone}).
   */
  private lookupRequest: Subscription | null = null;
  private findRequest: Subscription | null = null;
  /** The source before *Approximate location* was switched on, put back when it is switched off (MAP by default). */
  private sourceBeforeApprox: LocationSource | null = null;

  constructor(private readonly deps: HouseLocationDeps) {}

  /** Ends the lookups in flight (the page is going away). */
  stop(): void {
    this.lookupRequest?.unsubscribe();
    this.findRequest?.unsubscribe();
  }

  /** "Use my location" (Permissions-Policy allows geolocation for this origin): puts the pin where the user is. */
  useMyLocation(): void {
    if (!this.canLocate || this.locating()) return;
    this.locating.set(true);
    // The last failure under the buttons stays, drawn as being updated, until this run ends (S4b-BL-2).
    // locateOnce drops the answer, found or failed, when this page is gone by then (the destroyed guard).
    locateOnce({
      gone: this.deps.gone,
      found: (pos) => {
        this.locating.set(false);
        this.message.set(null);
        this.coordsInvalid.set({ lat: false, lon: false });
        this.placePin(round6(pos.coords.latitude), round6(pos.coords.longitude), 'GPS');
        this.deps.announcer.announce({ key: 'house.locationFound' });
      },
      failed: (err) => {
        this.locating.set(false);
        this.message.set(runResult({ key: locationErrorKey(err) }));
      },
    });
  }

  /**
   * The user put the pin somewhere: the position now counts as set, and the source says how (a drag, a tap or typed
   * coordinates are MAP; *Use my location* is GPS). Moving the pin of a house marked approximate keeps it approximate:
   * the person said the spot is rough, and a nudge does not make it the building. GPS always wins.
   */
  private placePin(lat: number, lon: number, source: LocationSource = 'MAP'): void {
    this.placed.set(true);
    if (this.required()) {
      this.required.set(false);
      if (this.deps.error()?.value.key === 'house.locationRequired') this.deps.error.set(null);
    }
    const current = this.deps.draft()?.locationSource ?? null;
    this.deps.patch({ lat, lon, locationSource: current === 'APPROX' && source === 'MAP' ? 'APPROX' : source });
  }

  /** The *Approximate location* switch (FR-068): on sets `APPROX`; off goes back to what it was. */
  setApprox(event: Event): void {
    const on = (event.target as HTMLInputElement).checked;
    const current = this.deps.draft()?.locationSource ?? null;
    if (on) {
      if (current !== 'APPROX') this.sourceBeforeApprox = current;
      this.deps.patch({ locationSource: 'APPROX' });
    } else {
      this.deps.patch({ locationSource: this.sourceBeforeApprox ?? 'MAP' });
    }
  }

  /**
   * The pin was dragged or the map tapped: puts the house there; the position now counts as set (see {@link placePin}).
   */
  onMoved(p: LatLon): void {
    this.coordsInvalid.set({ lat: false, lon: false });
    this.placePin(p.lat, p.lon);
  }

  /**
   * Typed coordinates: the keyboard alternative to dragging the pin. An invalid value is kept in the field (the user
   * corrects it rather than retyping it), the field is marked invalid with the reason under the fields, and the pin
   * stays where it was until the value is valid.
   */
  onCoord(axis: 'lat' | 'lon', event: Event): void {
    const input = event.target as HTMLInputElement;
    const value = parseCoordinate(input.value, axis === 'lat' ? 90 : 180);
    const d = this.deps.draft();
    if (!d) return;
    if (value === null) {
      this.coordsInvalid.update((c) => ({ ...c, [axis]: true }));
      return;
    }
    this.coordsInvalid.update((c) => ({ ...c, [axis]: false }));
    const lat = axis === 'lat' ? round6(value) : d.lat;
    const lon = axis === 'lon' ? round6(value) : d.lon;
    this.placePin(lat, lon);
  }

  describedBy(axis: 'lat' | 'lon'): string | null {
    const ids = [this.placed() ? null : 'location-unset', this.coordsInvalid()[axis] ? 'coords-error' : null];
    return ids.filter((x) => !!x).join(' ') || null;
  }

  /**
   * The place name a shared listing (or the person) gave, while the house has no position yet: *Find* looks it up
   * (S4b-BL-83, docs/11 5.29 item 4). The locality first, else the address.
   */
  placeQuery(): string | null {
    // A method, not a computed: the form's fields write into the draft object in place (ngModel).
    const d = this.deps.draft();
    if (!d || this.placed()) return null;
    return d.locality?.trim() || d.address?.trim() || null;
  }

  /**
   * *Find “…” on the map*, on the person's tap only: Nominatim's `/search` (one request a second, `GeocodeService`)
   * puts the pin at the place, marked approximate, for the person to drag to the house; a name it does not know says so.
   */
  findPlace(): void {
    const place = this.placeQuery();
    if (!place || this.finding()) return;
    this.finding.set(true);
    this.findRequest = this.deps.geocode.search(place, this.deps.i18n.lang()).subscribe({
      next: (found) => {
        this.findRequest = null;
        this.finding.set(false);
        if (!found) {
          this.message.set(runResult({ key: 'house.placeNotFound', params: { place } }));
          return;
        }
        this.message.set(null);
        this.placePin(round6(found.lat), round6(found.lon), 'APPROX');
        this.foundMessage.set(runResult({ key: 'house.placeFound', params: { place } }));
        this.deps.announcer.announce({ key: 'house.placeFound', params: { place } });
      },
      error: (err: unknown) => {
        this.findRequest = null;
        this.finding.set(false);
        this.message.set(runResult({ key: 'house.lookupFailed', params: { reason: errorMsg(err) } }));
      },
    });
  }

  /**
   * "Fill address from map" fills the empty Address, Street and Locality (and the name from the street when there is
   * none). A value the user typed is only replaced after asking, with the old and new values shown; afterwards the
   * filled fields are named.
   */
  fillAddress(): void {
    const d = this.deps.draft();
    if (!d || this.geocoding() || !this.placed()) return;
    this.geocoding.set(true);
    // As for "Use my location": the last failure stays, drawn as being updated, until the lookup ends (S4b-BL-2).
    this.lookupRequest = this.deps.geocode.reverse(d.lat, d.lon).subscribe({
      next: (r) => {
        this.lookupRequest = null;
        this.geocoding.set(false);
        this.message.set(null);
        void this.applyAddress(r);
      },
      error: (err: unknown) => {
        this.lookupRequest = null;
        this.message.set(runResult({ key: 'house.lookupFailed', params: { reason: errorMsg(err) } }));
        this.geocoding.set(false);
      },
    });
  }

  private async applyAddress(found: AddressLookup): Promise<void> {
    const cur = this.deps.draft();
    if (!cur) return;
    const fill = addressFill(cur, found);
    const changes: Partial<HouseDto> = { ...fill.emptyOnly };
    const filled: FillField[] = [...fill.filled];
    if (fill.conflicts.length > 0) {
      const lines = fill.conflicts
        .map((c) => this.deps.i18n.t('house.addressChange', { field: { key: FIELD_LABEL[c.field] }, old: c.old, new: c.incoming }))
        .join('\n');
      const answer = await this.deps.confirm.choose(
        { key: 'house.addressReplaceAsk', params: { changes: lines } },
        { confirmKey: 'house.addressReplace', altKey: filled.length > 0 ? 'house.addressFillEmpty' : null },
      );
      if (answer === 'cancel') return;
      if (answer === 'confirm') {
        for (const c of fill.conflicts) {
          changes[c.field] = c.incoming;
          filled.push(c.field);
        }
      }
    }
    if (filled.length === 0) {
      this.deps.announcer.announce({ key: 'house.addressNothing' });
      return;
    }
    this.deps.patch(changes);
    const fields = this.deps.i18n.list(filled.map((f) => this.deps.i18n.t(FIELD_LABEL[f])));
    this.deps.announcer.announce({ key: 'house.addressFilledFields', params: { fields } });
  }
}
