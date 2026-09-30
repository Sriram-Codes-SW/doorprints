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

import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ViewingReminderService } from '../core/viewing-reminder.service';
import { TPipe } from '../i18n/t.pipe';

/**
 * The in-page viewing reminder (slice 3b-2) for when the browser notification is not allowed: a labelled
 * `role="status"` banner under the header with a link to the Viewings page and a Dismiss button. The same words are
 * also announced once through the app's live region, because a region inserted together with its text is not reliably read.
 */
@Component({
  selector: 'app-reminder-banners',
  imports: [RouterLink, TPipe],
  template: `
    @for (b of reminders.banners(); track b.id) {
      <div class="reminder" role="status" [attr.aria-label]="'viewings.remind.region' | t">
        <span class="reminder-text">{{ reminders.bannerText(b) }}</span>
        <a class="btn btn-sm btn-primary" routerLink="/viewings" (click)="reminders.dismiss(b.id)">{{ 'viewings.remind.open' | t }}</a>
        <button type="button" class="btn btn-sm" (click)="reminders.dismiss(b.id)">{{ 'viewings.remind.dismiss' | t }}</button>
      </div>
    }
  `,
  styles: `
    .reminder {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--space-2);
      padding: var(--space-2) var(--space-3);
      background: var(--surface-2, var(--surface));
      border-bottom: 1px solid var(--border);
    }
    .reminder-text {
      flex: 1 1 14rem;
      overflow-wrap: anywhere;
    }
  `,
})
export class ReminderBanners {
  protected readonly reminders = inject(ViewingReminderService);
}
