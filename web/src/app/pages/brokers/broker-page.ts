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
import type { Msg } from '../../i18n/translation.service';
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

  protected id = '';
  protected readonly isNew = signal(false);
  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly saving = signal(false);
  protected readonly nameError = signal(false);
  protected readonly error = signal<RunResult<Msg> | null>(null);
  protected readonly houses = signal<HouseDto[]>([]);
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
  }

  protected setRating(n: number | null): void {
    this.form = { ...this.form, rating: n };
  }

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

function toBroker(f: BrokerForm): Broker {
  const out: Broker = { name: f.name };
  if (f.phone.trim()) out.phone = f.phone;
  if (f.agency.trim()) out.agency = f.agency;
  if (f.feeTerms.trim()) out.feeTerms = f.feeTerms;
  if (f.notes.trim()) out.notes = f.notes;
  if (f.rating !== null) out.rating = f.rating;
  return out;
}
