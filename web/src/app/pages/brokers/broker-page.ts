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

import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Announcer } from '../../core/announcer.service';
import { ConfirmService } from '../../core/confirm.service';
import { errorMsg, telHref } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import { uuid } from '../../core/models';
import { TPipe } from '../../i18n/t.pipe';
import { TranslationService } from '../../i18n/translation.service';
import type { Msg } from '../../i18n/translation.service';
import { duplicateFlats } from '../../shared/duplicate-flat';
import {
  MAX_BROKER_AGENCY,
  MAX_BROKER_FEE_TERMS,
  MAX_BROKER_NAME,
  MAX_BROKER_NOTES,
  MAX_BROKER_PHONE,
  brokerFromPayload,
} from '../../shared/broker';
import type { Broker } from '../../shared/broker';
import { RunResult, runResult } from '../../shared/run-result';

/** The form's values: text as typed (blank means unknown), the rating 1..5 or null. */
interface BrokerForm {
  name: string;
  phone: string;
  agency: string;
  feeTerms: string;
  notes: string;
  rating: number | null;
}

/**
 * One broker (slice 1b, docs/11 5.25): the fields to edit, Call, the houses from this broker, and Delete broker
 * ("The houses keep their contact details"). `/brokers/new` makes a new one. Saving a broker rewrites the name and
 * phone copies on its houses (`LocalStore.saveBroker`).
 */
@Component({
  selector: 'app-broker-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './broker-page.html',
  styleUrl: './brokers-page.css',
})
export class BrokerPage implements OnInit {
  private readonly api = inject(LocalDataService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly i18n = inject(TranslationService);

  protected id = '';
  protected readonly isNew = signal(false);
  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly saving = signal(false);
  protected readonly nameError = signal(false);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly houses = signal<HouseDto[]>([]);
  /** Every house in this browser, for the duplicate-flat warning under each of this broker's houses (S4b-BL-85). */
  private readonly allHouses = signal<HouseDto[]>([]);
  protected form: BrokerForm = { name: '', phone: '', agency: '', feeTerms: '', notes: '', rating: null };

  protected readonly telHref = telHref;
  protected readonly starValues: readonly number[] = [1, 2, 3, 4, 5];
  protected readonly max = {
    name: MAX_BROKER_NAME,
    phone: MAX_BROKER_PHONE,
    agency: MAX_BROKER_AGENCY,
    feeTerms: MAX_BROKER_FEE_TERMS,
    notes: MAX_BROKER_NOTES,
  };

  /**
   * Starts a new broker (a fresh id) for `/brokers/new`, or loads the broker, its linked houses and all houses (for the
   * same-flat hint); an unknown id shows "not found".
   */
  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id') ?? 'new';
    if (id === 'new') {
      this.id = uuid();
      this.isNew.set(true);
      this.loading.set(false);
      return;
    }
    this.id = id;
    this.api.brokers().subscribe({
      next: (rows) => {
        const row = rows.find((r) => r.id === id);
        if (row) this.form = toForm(row.broker);
        else this.notFound.set(true);
        this.loading.set(false);
      },
      error: (err: unknown) => {
        this.error.set(runResult(errorMsg(err)));
        this.loading.set(false);
      },
    });
    this.api.brokerHouses(id).subscribe({
      next: (list) => this.houses.set(list),
      error: () => this.houses.set([]),
    });
    this.api.houses().subscribe({
      next: (list) => this.allHouses.set(list),
      error: () => this.allHouses.set([]),
    });
  }

  /** "Maybe the same flat as …" for one of this broker's houses (any broker's house may be the other one); null for none. */
  protected sameFlatLine(h: HouseDto): string | null {
    const all = this.allHouses();
    const ids = duplicateFlats(h, all);
    if (ids.length === 0) return null;
    const names = ids.map((id) => all.find((o) => o.id === id)?.label?.trim() || this.i18n.t('common.untitled'));
    return this.i18n.t('house.duplicateFlat', { names: this.i18n.list(names) });
  }

  protected setRating(n: number | null): void {
    this.form = { ...this.form, rating: n };
  }

  /**
   * Saves the broker if it has a valid name (`brokerFromPayload`), then returns to the list; a failure stays on the
   * form.
   */
  protected async save(): Promise<void> {
    const broker = toBroker(this.form);
    if (!brokerFromPayload({ ...broker })) {
      this.nameError.set(true);
      document.getElementById('broker-name')?.focus();
      return;
    }
    this.nameError.set(false);
    this.saving.set(true);
    try {
      await firstValueFrom(this.api.saveBroker(this.id, broker));
      this.error.set(null);
      this.announcer.announce({ key: 'brokers.saved' });
      void this.router.navigate(['/brokers']);
    } catch (err: unknown) {
      this.error.set(runResult(errorMsg(err)));
    } finally {
      this.saving.set(false);
    }
  }

  /** Deletes the broker after asking; its houses keep their contact details and lose the link. */
  protected async remove(): Promise<void> {
    const ok = await this.confirm.ask(
      { key: 'brokers.confirmDelete', params: { name: this.form.name } },
      { confirmKey: 'brokers.delete', danger: true },
    );
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deleteBroker(this.id));
      this.announcer.announce({ key: 'brokers.deleted' });
      void this.router.navigate(['/brokers']);
    } catch (err: unknown) {
      this.error.set(runResult(errorMsg(err)));
    }
  }
}

/** The form values for a stored broker: missing fields become empty text. */
function toForm(b: Broker): BrokerForm {
  return {
    name: b.name,
    phone: b.phone ?? '',
    agency: b.agency ?? '',
    feeTerms: b.feeTerms ?? '',
    notes: b.notes ?? '',
    rating: b.rating ?? null,
  };
}

/** The broker to store from the form: blank fields are left out, so no empty strings are saved. */
function toBroker(f: BrokerForm): Broker {
  const out: Broker = { name: f.name };
  if (f.phone.trim()) out.phone = f.phone;
  if (f.agency.trim()) out.agency = f.agency;
  if (f.feeTerms.trim()) out.feeTerms = f.feeTerms;
  if (f.notes.trim()) out.notes = f.notes;
  if (f.rating !== null) out.rating = f.rating;
  return out;
}
