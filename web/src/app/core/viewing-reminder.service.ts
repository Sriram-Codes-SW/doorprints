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

import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { TranslationService } from '../i18n/translation.service';
import { dueReminders } from '../shared/viewing-reminders';
import { Announcer } from './announcer.service';
import { LocalDataService } from './local-data.service';

/** How often the open app looks for a reminder that has come due (no long timers: a sleeping tab cannot be trusted with one). */
export const REMINDER_TICK_MS = 60_000;

/** A reminder shown as a banner because the browser notification is not allowed or could not be made. */
export interface ReminderBanner {
  id: string;
  startsAt: number;
}

export type NotificationState = 'default' | 'granted' | 'denied' | 'unsupported';

/**
 * Viewing reminders on the website (docs/11 5.8, slice 3b-2): only while the app is open. Every minute the viewings
 * whose reminder came due since the last look are shown, once per viewing in this session: as a browser notification
 * through the service worker when the permission is granted, otherwise as an in-page banner. The words carry the
 * time only, never the house, the address or who the viewing is with, because a browser notification has no private
 * version. The setting `viewings.remind` off shows nothing.
 */
@Injectable({ providedIn: 'root' })
export class ViewingReminderService {
  private readonly api = inject(LocalDataService);
  private readonly i18n = inject(TranslationService);
  private readonly announcer = inject(Announcer);

  /** Banners on screen, oldest first. */
  readonly banners = signal<ReminderBanner[]>([]);

  private readonly fired = new Set<string>();
  private timer: ReturnType<typeof setInterval> | undefined;
  private lastTick = 0;
  private busy = false;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** The browser's notification permission, read without asking. */
  permission(): NotificationState {
    return typeof Notification === 'undefined' ? 'unsupported' : (Notification.permission as NotificationState);
  }

  /** Asks the browser for permission. Call it only from a tap; it never asks when the answer is already known. */
  async requestPermission(): Promise<NotificationState> {
    if (typeof Notification === 'undefined' || Notification.permission !== 'default') return this.permission();
    try {
      await Notification.requestPermission();
    } catch {
      // An old browser that only takes a callback, or a blocked prompt: the permission below is what it is.
    }
    return this.permission();
  }

  /** Starts the minute timer; a second call does nothing. The first look covers only what comes after now. */
  start(nowMs: number = Date.now()): void {
    if (this.timer !== undefined) return;
    this.lastTick = nowMs;
    this.timer = setInterval(() => void this.tick(Date.now()), REMINDER_TICK_MS);
  }

  stop(): void {
    if (this.timer !== undefined) clearInterval(this.timer);
    this.timer = undefined;
  }

  dismiss(id: string): void {
    this.banners.update((list) => list.filter((b) => b.id !== id));
  }

  /** One look: shows the reminders that came due in (last look, nowMs]. Exposed for the tests. */
  async tick(nowMs: number): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    const after = this.lastTick;
    try {
      if (await firstValueFrom(this.api.viewingsRemind())) {
        const viewings = await firstValueFrom(this.api.viewings());
        for (const { viewing } of dueReminders(viewings, after, nowMs)) {
          if (this.fired.has(viewing.id)) continue;
          this.fired.add(viewing.id);
          await this.show(viewing.id, viewing.startsAt);
        }
      }
      this.lastTick = nowMs;
    } catch {
      // The store was unreadable this minute; the next look covers the same span again.
    } finally {
      this.busy = false;
    }
  }

  private time(startsAt: number): string {
    return new Intl.DateTimeFormat(this.i18n.locale(), { timeStyle: 'short' }).format(new Date(startsAt));
  }

  private text(startsAt: number): string {
    return this.i18n.t('viewings.remind.text', { time: this.time(startsAt) });
  }

  private async show(id: string, startsAt: number): Promise<void> {
    if (this.permission() === 'granted' && (await this.notify(id, this.text(startsAt)))) return;
    this.banners.update((list) => [...list, { id, startsAt }]);
    this.announcer.announce({ key: 'viewings.remind.text', params: { time: this.time(startsAt) } });
  }

  /** The browser notification, through the worker (a page-made `Notification` does not work on Android Chrome). */
  private async notify(id: string, body: string): Promise<boolean> {
    try {
      const registration = await navigator.serviceWorker?.getRegistration();
      if (!registration) return false;
      await registration.showNotification(this.i18n.t('app.name'), { body, tag: id, data: { url: 'viewings' } });
      return true;
    } catch {
      return false;
    }
  }

  /** The banner's words, worked out in the current language. */
  bannerText(b: ReminderBanner): string {
    return this.text(b.startsAt);
  }
}
