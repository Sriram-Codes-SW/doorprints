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

import { Injectable, computed, inject, signal } from '@angular/core';
import { GuardsCheckEnd, Router, type Routes } from '@angular/router';
import type { BuiltInKind } from './dev-kinds';
import { availableKinds, defaultKind, enabledKinds, kindById, spacesOn } from './kind-setting';
import type { HouseDto } from './models';
import { type SpaceWords, spaceWords } from './space-words';

/** The path of a space's own pages: `/k/<kind>`, `/k/<kind>/new`, `/k/<kind>/<id>`. */
const SPACE_PATH = /^\/k\/([a-z][a-z0-9_]{1,31})(?:\/|$)/;
/** The house's own pages, which keep today's paths in every mode. */
const HOUSE_PATH = /^\/(?:houses|compare|ask|plan)(?:\/|$)/;

/** The kind of a space path, or null for a path that is no space's own (the chooser, Your data, Connect, ...). */
export function kindOfPath(path: string): string | null {
  const m = SPACE_PATH.exec(path);
  if (m) return m[1];
  return HOUSE_PATH.test(path) ? 'house' : null;
}

/** The space a candidate belongs to: its own kind when that kind has a space here, else the default space. */
export function spaceOf(h: Pick<HouseDto, 'kind'>, multi: boolean, dflt: string, available: readonly string[]): string {
  const kind = h.kind ?? 'house';
  return multi && available.includes(kind) ? kind : dflt;
}

/**
 * The current *space* (kinds PR 8, S4b-BL-212; docs/03 §18.12): the kind whose candidates the list, the map, the counters,
 * the search and the Add button show. A space is a filter over the one store, read from the address: with one kind
 * enabled there is one space, the default, on today's paths (the router config is today's, `routesFor(false)`); with two
 * or more, `/` is the chooser, `/k/<kind>` a space's list and map, `/k/<kind>/new` and `/k/<kind>/<id>` its pages, and
 * the house keeps `/houses/...`, `/compare`, `/ask` and `/plan` (its list is `/k/house`). A page that belongs to no space
 * (Your data, Connect, Brokers) keeps the last space.
 */
@Injectable({ providedIn: 'root' })
export class SpaceService {
  private readonly router = inject(Router);
  private readonly path = signal(pathOf(this.router.url));
  private readonly last = signal<string | null>(null);

  /** The kinds the person enabled (Settings > Kinds), the default kind, and whether there are spaces (two or more). */
  readonly enabled = signal<readonly string[]>(enabledKinds());
  readonly dflt = signal(defaultKind());
  readonly multi = signal(spacesOn());
  readonly available = availableKinds();

  /** The chooser is on screen: spaces are on and the address is `/`. */
  readonly inChooser = computed(() => this.multi() && this.path() === '/');
  /** The current space's kind: from the address, else the last space, else the default. */
  readonly kind = computed(() => (this.multi() ? (kindOfPath(this.path()) ?? this.last() ?? this.dflt()) : this.dflt()));
  readonly current = computed<BuiltInKind>(() => kindById(this.kind()));
  readonly words = computed<SpaceWords>(() => spaceWords(this.kind()));
  /** The space is one the person turned off (it still opens, with a note: its entries are never hidden). */
  readonly hidden = computed(() => this.multi() && !this.enabled().includes(this.kind()));
  /** Compare, Ask and Plan read the house space only, until they are scoped per space (S4b-BL-279). */
  readonly houseTools = computed(() => this.kind() === 'house');

  constructor() {
    // GuardsCheckEnd, not NavigationEnd: the new page is built before the navigation ends and reads its space at once.
    this.router.events.subscribe((e) => {
      if (!(e instanceof GuardsCheckEnd) || !e.shouldActivate) return;
      const p = pathOf(e.urlAfterRedirects);
      this.path.set(p);
      const k = kindOfPath(p);
      if (k) this.last.set(k);
    });
  }

  /** The space's list and map. */
  home(kind: string = this.kind()): string {
    if (!this.multi()) return '/';
    return `/k/${kind}`;
  }

  /** A candidate's page in its space (a house keeps `/houses/<id>`). */
  detailLink(id: string, kind: string = this.kind()): string[] {
    return this.multi() && kind !== 'house' ? ['/k', kind, id] : ['/houses', id];
  }

  /** The new-candidate form of the current space; `/houses/new` is the default kind's (deep links, the share target). */
  newLink(kind: string = this.kind()): string {
    return this.multi() ? `/k/${kind}/new` : '/houses/new';
  }

  /** The kind a new candidate is: the space's on `/k/<kind>/new`, else the default kind. */
  newKind(): string {
    const m = this.multi() ? SPACE_PATH.exec(this.path()) : null;
    return m ? m[1] : this.dflt();
  }

  /** Whether the current space shows candidate `h`. With one space it shows every candidate, as it always did. */
  readonly holds = (h: Pick<HouseDto, 'kind'>): boolean =>
    !this.multi() || spaceOf(h, true, this.dflt(), this.available) === this.kind();

  /**
   * Reads the settings again after Settings > Kinds changed them; when spaces turn on or off the router gets the other
   * config (`routes` passes `routesFor`, so the app module needs no import cycle).
   */
  refresh(routesFor: (multi: boolean) => Routes): void {
    const was = this.multi();
    this.enabled.set(enabledKinds());
    this.dflt.set(defaultKind());
    this.multi.set(spacesOn());
    if (was !== this.multi()) this.router.resetConfig(routesFor(this.multi()));
  }
}

function pathOf(url: string): string {
  const p = url.split(/[?#]/)[0] || '/';
  return p.length > 1 && p.endsWith('/') ? p.slice(0, -1) : p;
}
