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
import { RouterLink } from '@angular/router';
import { LocalDataService } from '../../core/local-data.service';
import { errorMsg, telHref } from '../../core/format';
import { TPipe } from '../../i18n/t.pipe';
import type { Msg } from '../../i18n/translation.service';
import type { BrokerRow } from '../../shared/broker';
import { GLYPHS } from '../../shared/glyphs';
import { RunResult, nextRunResult } from '../../shared/run-result';

/** One line of the list: a broker and how many live houses use it. */
export interface BrokerItem {
  row: BrokerRow;
  houses: number;
}

/**
 * The Brokers list (slice 1b, docs/11 5.25), reached from Your data: name, agency, phone, stars and how many houses
 * each broker has. Brokers appear when a house with a phone number is saved; "Add broker" makes one by hand, and a
 * tap opens the broker's page ({@link BrokerPage}).
 */
@Component({
  selector: 'app-brokers-page',
  imports: [RouterLink, TPipe],
  templateUrl: './brokers-page.html',
  styleUrl: './brokers-page.css',
})
export class BrokersPage {
  private readonly api = inject(LocalDataService);

  protected readonly loading = signal(true);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  private readonly rows = signal<BrokerRow[]>([]);
  private readonly counts = signal<ReadonlyMap<string, number>>(new Map());
  protected readonly glyphs = GLYPHS;
  protected readonly telHref = telHref;
  protected readonly starValues: readonly number[] = [1, 2, 3, 4, 5];

  /** By name, then id, so the order is fixed. */
  protected readonly items = computed<BrokerItem[]>(() =>
    this.rows()
      .map((row) => ({ row, houses: this.counts().get(row.id) ?? 0 }))
      .sort((a, b) => a.row.broker.name.localeCompare(b.row.broker.name) || (a.row.id < b.row.id ? -1 : 1)),
  );

  constructor() {
    // Again after writes to this browser's store (a sync pull, an edit in another tab).
    effect(() => {
      this.api.settled();
      this.reload();
    });
  }

  /**
   * Reads the brokers and counts the live houses linked to each. `userAsked` marks a Retry, so a repeated failure is
   * announced again.
   */
  protected reload(userAsked = false): void {
    this.api.brokers().subscribe({
      next: (rows) => {
        this.rows.set(rows);
        this.error.set(null);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.update((previous) => nextRunResult(previous, errorMsg(err), userAsked));
        this.loading.set(false);
      },
    });
    this.api.houses().subscribe({
      next: (list) => {
        const counts = new Map<string, number>();
        for (const h of list) if (!h.deleted && h.brokerId) counts.set(h.brokerId, (counts.get(h.brokerId) ?? 0) + 1);
        this.counts.set(counts);
      },
      error: () => this.counts.set(new Map()),
    });
  }
}
