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

import { Component, computed, inject, signal } from '@angular/core';
import { routesFor } from '../../app.routes';
import {
  availableKinds, defaultKind, enabledKinds, experimentalKindsOn, kindById, offeredKinds, setDefaultKind, setExperimentalKinds,
  setKindEnabled, unreleasedKinds,
} from '../../core/kind-setting';
import { LocalDataService } from '../../core/local-data.service';
import { SpaceService, spaceOf } from '../../core/space.service';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';

/** One row of Settings > Kinds: a kind offered here, whether it is shown, whether it is the default, its entries. */
interface KindRow {
  id: string;
  label: string;
  on: boolean;
  isDefault: boolean;
  count: number;
}

/**
 * Settings > Kinds on *Your data* (kinds PR 8, S4b-BL-212; docs/03 §18.12). Drawn only in a build that offers more than
 * the house or ships an unreleased kind; a production build has neither, so a house hunter's page is as it was.
 * *Experimental: more than houses* (off by default) is shown only while the build ships an unreleased kind and is the only
 * way to see one. Then one row per offered kind: *Show* (the last kind shown cannot be turned off), *Default* (only for
 * a shown kind) and how many are saved. Turning a kind off hides nothing silently: its entries stay, and the chooser
 * lists it as hidden. Everything is this browser's own; a change takes effect at once (the router gets the spaces).
 */
@Component({
  selector: 'app-kinds-settings',
  imports: [TPipe],
  template: `
    @if (visible()) {
      <section class="card" aria-labelledby="kinds-heading">
        <h2 id="kinds-heading" tabindex="-1">{{ 'kinds.heading' | t }}</h2>
        <p class="muted">{{ 'kinds.body' | t }}</p>
        @if (unreleased.length) {
          <div class="field checkbox">
            <input id="kinds-experimental" type="checkbox" [checked]="experimental()" (change)="setSwitch($any($event.target).checked)" aria-describedby="kinds-experimental-hint" />
            <label for="kinds-experimental">{{ 'kinds.switch' | t }}</label>
            <p id="kinds-experimental-hint" class="muted small">{{ 'kinds.switchHint' | t }}</p>
          </div>
        }
        @if (rows().length > 1) {
          <ul id="kind-rows" class="rows" role="list" aria-describedby="kinds-default-hint">
            @for (r of rows(); track r.id) {
              <li class="row">
                <p class="name"><strong>{{ r.label }}</strong>&ngsp;<span class="muted small">{{ 'kinds.count' | t: { n: i18n.number(r.count) } }}</span></p>
                <div class="field checkbox">
                  <input type="checkbox" [id]="'kind-on-' + r.id" [checked]="r.on" [disabled]="r.on && shownCount() === 1" (change)="setOn(r.id, $any($event.target).checked)" />
                  <label [for]="'kind-on-' + r.id">{{ 'kinds.show' | t: { kind: r.label } }}</label>
                </div>
                <div class="field checkbox">
                  <input type="radio" name="defaultKind" [id]="'kind-default-' + r.id" [checked]="r.isDefault" [disabled]="!r.on" [attr.aria-label]="'kinds.defaultAria' | t: { kind: r.label }" (change)="setDefault(r.id)" />
                  <label [for]="'kind-default-' + r.id" aria-hidden="true">{{ 'kinds.default' | t }}</label>
                </div>
              </li>
            }
          </ul>
          <p id="kinds-default-hint" class="muted small">{{ 'kinds.defaultHint' | t }} {{ 'kinds.lastOn' | t }}</p>
        }
      </section>
    }
  `,
  styles: `
    /* No box of its own: in a build without other kinds the page's layout is as it was. */
    :host { display: contents; }
    .rows { list-style: none; margin: var(--space-3) 0 0; padding: 0; display: grid; gap: var(--space-2); }
    .row { display: grid; grid-template-columns: minmax(0, 1fr); gap: 0 var(--space-3); padding: var(--space-2) 0; border-top: 1px solid var(--border); }
    .name { margin: 0; overflow-wrap: anywhere; }
    @media (min-width: 40rem) { .row { grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; } }
  `,
})
export class KindsSettings {
  private readonly data = inject(LocalDataService);
  private readonly space = inject(SpaceService);
  protected readonly i18n = inject(TranslationService);
  private readonly available = availableKinds();
  protected readonly unreleased = unreleasedKinds(this.available);
  /** Bumped after every change, so the rows read the settings again. */
  private readonly version = signal(0);
  private readonly counts = signal<ReadonlyMap<string, number>>(new Map());

  protected readonly experimental = computed(() => (this.version(), experimentalKindsOn()));
  protected readonly visible = computed(() => this.unreleased.length > 0 || this.available.length > 1);
  protected readonly rows = computed<KindRow[]>(() => {
    this.version();
    const enabled = enabledKinds(this.available);
    const dflt = defaultKind(this.available);
    return offeredKinds(this.available).map((id) => ({
      id,
      label: this.i18n.t(kindById(id).keys.ref('kind.many')),
      on: enabled.includes(id),
      isDefault: id === dflt,
      count: this.counts().get(id) ?? 0,
    }));
  });
  protected readonly shownCount = computed(() => this.rows().filter((r) => r.on).length);

  constructor() {
    this.data.houses().subscribe({
      next: (list) => {
        const m = new Map<string, number>();
        for (const h of list) {
          if (h.deleted) continue;
          const id = spaceOf(h, true, 'house', this.available);
          m.set(id, (m.get(id) ?? 0) + 1);
        }
        this.counts.set(m);
      },
      error: () => this.counts.set(new Map()),
    });
  }

  protected setSwitch(on: boolean): void {
    setExperimentalKinds(on);
    this.changed();
  }

  protected setOn(id: string, on: boolean): void {
    setKindEnabled(id, on, this.available);
    this.changed();
  }

  protected setDefault(id: string): void {
    setDefaultKind(id, this.available);
    this.changed();
  }

  private changed(): void {
    this.version.update((v) => v + 1);
    this.space.refresh((multi) => routesFor(multi));
  }
}
