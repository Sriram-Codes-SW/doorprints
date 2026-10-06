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
 * *Did I walk past this house?* on the house page (docs/11 5.27.13, docs/03 section 6.2b row 17, S4b-FR-24): a card under the
 * location card with one button. The answer appears in place, and its halo and ring are drawn on the page's own location map
 * (an overlay handed to the page, never a navigation). Hidden for a house not saved yet and for one with no location; a house
 * whose spot is only an area (`APPROX`) answers that it has no exact spot and compares nothing. The answer is a component
 * signal: never in a URL, `history.state`, a storage or `ListReturn`; closing it withdraws the announcement.
 */

import { Component, Injector, afterNextRender, effect, inject, input, output, signal, untracked } from '@angular/core';
import { Announcer } from '../../core/announcer.service';
import type { HouseDto } from '../../core/models';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { MapOverlay } from '../../shared/location-map';
import { checkGeoJson } from '../../shared/trace-style';
import { CheckResultView } from '../map/place-check-panel';
import { PlaceCheckState, boundsOf } from '../map/place-check-state';
import type { CheckAnswer } from '../map/place-check-state';

const NO_WALKS = { type: 'FeatureCollection' as const, features: [] };

@Component({
  selector: 'app-house-check-card',
  imports: [TPipe, CheckResultView],
  template: `
    @if (shown()) {
      <section class="card" [attr.aria-labelledby]="answer() ? 'check-title' : 'house-check-heading'">
        @if (answer(); as a) {
          <app-check-result [answer]="a" [showMap]="true" (show)="showOnMap()" (closed)="close()" />
        } @else {
          <h2 id="house-check-heading">{{ 'trace.here.titleHouse' | t }}</h2>
          <button type="button" id="house-check-open" class="btn" [attr.aria-disabled]="busy() ? 'true' : null" (click)="check()">
            {{ 'trace.here.buttonHouse' | t }}
          </button>
        }
      </section>
    }
  `,
})
export class HouseCheckCard {
  private readonly state = inject(PlaceCheckState);
  private readonly announcer = inject(Announcer);
  private readonly i18n = inject(TranslationService);
  private readonly injector = inject(Injector);

  readonly house = input.required<HouseDto>();
  /** A house not saved yet (`/houses/new`) has no stored spot to check. */
  readonly isNew = input(false);
  /** False for a new house with no position yet: there is nothing to check. */
  readonly locationSet = input(true);
  /** What to draw on the page's own map: the halo, the ring and the box to frame, or null to clear. */
  readonly overlay = output<MapOverlay | null>();

  protected readonly answer = signal<CheckAnswer | null>(null);
  protected readonly busy = signal(false);
  protected readonly shown = () => !this.isNew() && this.locationSet();
  private placeKey = '';

  constructor() {
    // The spot moved (a drag, typed coordinates): the answer was about another place. It goes.
    effect(() => {
      const { lat, lon } = this.house();
      const key = `${lat},${lon}`;
      untracked(() => {
        if (this.placeKey !== '' && key !== this.placeKey && this.answer() !== null) this.close(false);
        this.placeKey = key;
      });
    });
    // A new language: the ring's label follows.
    let lastLang = this.i18n.lang();
    effect(() => {
      const lang = this.i18n.lang();
      if (lang === lastLang) return;
      lastLang = lang;
      untracked(() => {
        const answer = this.answer();
        if (answer) this.overlay.emit(this.overlayOf(answer));
      });
    });
  }

  /** The button's handler: the one place this card compares anything. */
  protected async check(): Promise<void> {
    if (this.busy()) return;
    const h = this.house();
    this.busy.set(true);
    try {
      const answer: CheckAnswer =
        h.locationSource === 'APPROX'
          ? { kind: 'house', place: { lat: h.lat, lon: h.lon }, summary: null, stretches: [], bounds: boundsOf(h, []) }
          : await this.state.compute('house', { lat: h.lat, lon: h.lon });
      this.answer.set(answer);
      this.overlay.emit(this.overlayOf(answer));
      this.announcer.announce({ key: 'trace.here.announce', params: { text: this.state.text(answer).headline } });
    } finally {
      this.busy.set(false);
    }
  }

  /** What the page's map shows for an answer; a house with only an area compared nothing, so nothing is drawn and nothing is framed. */
  private overlayOf(answer: CheckAnswer): MapOverlay | null {
    if (answer.summary === null) return null;
    return {
      walks: NO_WALKS,
      check: checkGeoJson(answer.stretches),
      fit: answer.bounds,
      ring: { lat: answer.place.lat, lon: answer.place.lon, label: this.i18n.t('trace.here.labelHouse') },
    };
  }

  /** *Show on map*: the page's own map shows it again, framed. */
  protected showOnMap(): void {
    const answer = this.answer();
    if (answer) this.overlay.emit(this.overlayOf(answer));
  }

  /** *Close*: the answer, the halo, the ring and the announcement go; focus returns to the button. */
  protected close(focus = true): void {
    this.answer.set(null);
    this.overlay.emit(null);
    this.announcer.cancel({ key: 'trace.here.announce' });
    if (focus) afterNextRender(() => document.getElementById('house-check-open')?.focus(), { injector: this.injector });
  }
}
