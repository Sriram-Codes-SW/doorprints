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
import { errorMsg } from '../../core/format';
import { LocalDataService } from '../../core/local-data.service';
import type { HouseDto } from '../../core/models';
import type { TKey } from '../../i18n/en';
import { TPipe } from '../../i18n/t.pipe';
import { Msg, TranslationService } from '../../i18n/translation.service';
import {
  DEFAULT_DURATION_MIN,
  DEFAULT_REMIND_MIN,
  MAX_DURATION_MIN,
  MAX_VIEWING_NOTES,
  MAX_WITH_WHOM,
  MIN_DURATION_MIN,
  REMIND_OPTIONS,
  VIEWING_KINDS,
  viewingKind,
} from '../../shared/viewing';
import type { Viewing, ViewingKind } from '../../shared/viewing';
import { viewingIcs } from '../../shared/viewing-ics';

/** The form's values as typed. */
interface ViewingForm {
  houseId: string;
  /** `YYYY-MM-DDTHH:mm`, the local time a `datetime-local` input holds. */
  when: string;
  duration: number;
  kind: ViewingKind;
  remind: number;
  withWhom: string;
  notes: string;
}

/** The local time of an instant as a `datetime-local` value. */
export function toLocalInput(epochMs: number): string {
  const d = new Date(epochMs);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

/** The instant of a `datetime-local` value (read as local time), or null when it is blank or not a time. */
export function fromLocalInput(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return null;
  const ms = new Date(value).getTime();
  return Number.isFinite(ms) && ms > 0 ? ms : null;
}

/**
 * One viewing (slice 3b-1, docs/11 5.8): house, date and time, duration, kind, reminder, with whom and notes; Save,
 * Cancel viewing, Delete and Add to calendar (a `.ics` file, since a browser cannot write to a calendar). `/viewings/new`
 * plans a new one and takes `?houseId=` and `?kind=` (a house's card and *Book a second viewing*). A past time is
 * allowed, to log a viewing afterwards. There is no Hunt reminder switch yet (slice 3c).
 */
@Component({
  selector: 'app-viewing-page',
  imports: [FormsModule, RouterLink, TPipe],
  templateUrl: './viewing-page.html',
  styleUrl: './viewing-page.css',
})
export class ViewingPage implements OnInit {
  private readonly api = inject(LocalDataService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly confirm = inject(ConfirmService);
  private readonly announcer = inject(Announcer);
  private readonly i18n = inject(TranslationService);

  protected readonly loading = signal(true);
  protected readonly notFound = signal(false);
  protected readonly isNew = signal(true);
  protected readonly saving = signal(false);
  protected readonly houses = signal<HouseDto[]>([]);
  protected readonly failure = signal<Msg | null>(null);
  protected readonly houseError = signal(false);
  protected readonly whenError = signal(false);

  protected form: ViewingForm = {
    houseId: '',
    when: '',
    duration: DEFAULT_DURATION_MIN,
    kind: 'FIRST',
    remind: DEFAULT_REMIND_MIN,
    withWhom: '',
    notes: '',
  };
  /** The viewing being edited (its status, visit and hunt flag are kept), or null for a new one. */
  private existing: Viewing | null = null;
  private id = '';
  /** The `updatedAt` of the stored record, for the calendar file's stamp; null before the first save. */
  private stamp: number | null = null;

  protected readonly kinds = VIEWING_KINDS;
  protected readonly reminders = REMIND_OPTIONS;
  protected readonly maxWithWhom = MAX_WITH_WHOM;
  protected readonly maxNotes = MAX_VIEWING_NOTES;
  /** 5-minute steps, plus the stored value when it is not one of them (a file from elsewhere). */
  protected durations: number[] = [];

  /** Loads the form. */
  ngOnInit(): void {
    void this.load();
  }

  /**
   * Reads the houses and either the viewing being edited (not found if unknown) or the defaults for a new one from
   * `?houseId=` and `?kind=`.
   */
  private async load(): Promise<void> {
    try {
      const houses = await firstValueFrom(this.api.houses());
      this.houses.set([...houses].sort((a, b) => (a.label || '').localeCompare(b.label || '')));
      const id = this.route.snapshot.paramMap.get('id');
      if (id) {
        const rows = await firstValueFrom(this.api.viewingRows());
        const row = rows.find((r) => r.id === id);
        if (!row) {
          this.notFound.set(true);
          return;
        }
        this.isNew.set(false);
        this.id = id;
        this.existing = row.viewing;
        this.stamp = Date.parse(row.updatedAt ?? '') || null;
        const v = row.viewing;
        this.form = {
          houseId: v.houseId,
          when: toLocalInput(v.startsAt),
          duration: v.durationMin,
          kind: v.kind,
          remind: v.remindMin,
          withWhom: v.withWhom ?? '',
          notes: v.notes ?? '',
        };
      } else {
        const q = this.route.snapshot.queryParamMap;
        const houseId = q.get('houseId') ?? '';
        this.form.houseId = this.houses().some((h) => h.id === houseId) ? houseId : '';
        this.form.kind = viewingKind(q.get('kind'));
      }
      this.durations = this.durationChoices(this.form.duration);
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    } finally {
      this.loading.set(false);
    }
  }

  /** The durations offered in 5-minute steps, plus the current one if it is not a step. */
  private durationChoices(current: number): number[] {
    const out: number[] = [];
    for (let m = MIN_DURATION_MIN; m <= MAX_DURATION_MIN; m += 5) out.push(m);
    if (!out.includes(current)) out.push(current);
    return out.sort((a, b) => a - b);
  }

  /** The house is gone but the viewing still names it: keep the choice visible instead of silently changing it. */
  protected houseIsGone(): boolean {
    return this.form.houseId !== '' && !this.houses().some((h) => h.id === this.form.houseId);
  }

  protected durationText(m: number): string {
    return m < 60 ? this.i18n.t('duration.minutes', { m }) : this.i18n.t('duration.hoursMinutes', { h: Math.floor(m / 60), m: m % 60 });
  }

  protected kindKey(k: ViewingKind): TKey {
    return `viewings.kind.${k}` as TKey;
  }

  protected remindKey(m: number): TKey {
    return `viewings.remind.${m}` as TKey;
  }

  /** The viewing the form describes, or null (and the field errors set) when the house or the time is missing. */
  private build(status?: Viewing['status']): Viewing | null {
    const startsAt = fromLocalInput(this.form.when);
    this.houseError.set(this.form.houseId === '');
    this.whenError.set(startsAt === null);
    if (this.form.houseId === '' || startsAt === null) {
      document.getElementById(this.form.houseId === '' ? 'viewing-house' : 'viewing-when')?.focus();
      return null;
    }
    const base = this.existing;
    const viewing: Viewing = {
      id: this.id,
      houseId: this.form.houseId,
      startsAt,
      durationMin: this.form.duration,
      kind: this.form.kind,
      status: status ?? base?.status ?? 'PLANNED',
      remindMin: this.form.remind,
    };
    if (base?.huntReminder) viewing.huntReminder = true;
    if (this.form.withWhom.trim()) viewing.withWhom = this.form.withWhom.trim();
    if (this.form.notes.trim()) viewing.notes = this.form.notes.trim();
    if (base?.visitId) viewing.visitId = base.visitId;
    return viewing;
  }

  /**
   * Saves the viewing the form describes (optionally with a new status) and returns to the list. A missing house or
   * time shows the field error and saves nothing.
   */
  protected async save(status?: Viewing['status']): Promise<void> {
    if (this.saving()) return;
    if (this.isNew() && this.id === '') this.id = await firstValueFrom(this.api.newViewingId());
    const viewing = this.build(status);
    if (!viewing) return;
    this.saving.set(true);
    try {
      await firstValueFrom(this.api.saveViewing(viewing));
      this.failure.set(null);
      this.announcer.announce({ key: status === 'CANCELLED' ? 'viewings.cancelled' : 'viewings.saved' });
      await this.router.navigate(['/viewings']);
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    } finally {
      this.saving.set(false);
    }
  }

  /** *Cancel viewing*: the same save with the status CANCELLED. */
  protected cancelViewing(): Promise<void> {
    return this.save('CANCELLED');
  }

  /** Deletes the viewing after asking. */
  protected async remove(): Promise<void> {
    if (this.isNew()) return;
    const ok = await this.confirm.ask({ key: 'viewings.confirmDelete' }, { confirmKey: 'viewings.delete', danger: true });
    if (!ok) return;
    try {
      await firstValueFrom(this.api.deleteViewing(this.id));
      this.announcer.announce({ key: 'viewings.deleted' });
      await this.router.navigate(['/viewings']);
    } catch (err: unknown) {
      this.failure.set(errorMsg(err));
    }
  }

  /** The `.ics` text of the form as it stands, or null when the house or the time is missing. */
  protected calendarText(): string | null {
    if (this.id === '') return null;
    const viewing = this.build();
    if (!viewing) return null;
    const house = this.houses().find((h) => h.id === viewing.houseId);
    return viewingIcs(
      viewing,
      house?.label || this.i18n.t('common.untitled'),
      house?.address,
      this.i18n.t('viewings.icsWord'),
      this.stamp ?? Date.now(),
    );
  }

  /** *Add to calendar*: a browser cannot write to a calendar, so it downloads the file the phone's calendar opens. */
  protected async addToCalendar(): Promise<void> {
    if (this.isNew() && this.id === '') this.id = await firstValueFrom(this.api.newViewingId());
    const text = this.calendarText();
    if (text === null) return;
    const url = URL.createObjectURL(new Blob([text], { type: 'text/calendar;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `${this.id}.ics`;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
    // Revoke a little later: Safari needs the URL to stay alive while the download starts.
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
    this.announcer.announce({ key: 'viewings.calendarDownloaded' });
  }
}
