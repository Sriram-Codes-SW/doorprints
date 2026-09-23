import { Injectable, computed, inject, signal } from '@angular/core';
import { LocalStore } from './local-store.service';
import { SETTING_KEYS } from './records';

/** localStorage key holding when the user last said "Not now" to the storage-risk banner (an ISO date). */
export const RISK_DISMISSED_KEY = 'hh.storageRiskDismissedAt';
/**
 * How long "Not now" on the storage-risk banner lasts: about the iOS eviction window (seven days of use without
 * visiting the site), so the reminder comes back roughly as often as the risk it is about.
 */
export const RISK_SNOOZE_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * True while a dismissal stored as `raw` is still in force at `now`. Anything unreadable counts as "not dismissed",
 * and so does a date in the future (a clock that was wrong must not hide the advice for years).
 */
export function riskSnoozed(raw: string | null, now: number = Date.now()): boolean {
  if (!raw) return false;
  const at = Date.parse(raw);
  if (!Number.isFinite(at) || at > now) return false;
  return now - at < RISK_SNOOZE_MS;
}

function readRiskDismissal(): string | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage.getItem(RISK_DISMISSED_KEY);
  } catch {
    return null;
  }
}

export interface StorageEstimate2 {
  usageBytes: number;
  quotaBytes: number;
}

/**
 * How durable this browser's copy is (docs/11 §5.10 "Storage durability", NFR-027).
 *
 * `navigator.storage.persist()` asks the browser to keep the data even when disk space runs low. Safari and
 * Chromium browsers decide it automatically from the user's interaction history, **without a prompt** (Safari
 * commonly denies it; Chrome tends to grant it for engaged or installed sites); Firefox asks the user. Safari also
 * deletes script-written storage for an origin with **no user interaction in the last seven days of browser use**,
 * and a site added to the Home Screen or the Dock gets the browser app's quota instead of the smaller in-app one
 * (docs/05 §14.5, docs/04 D9, docs/02 RR-10; MDN, *Storage quotas and eviction criteria*). The code does not rely on
 * any of this: it reports whichever answer it gets, and when persistence is not granted the app says so and
 * recommends installing to the Home Screen and taking regular exports, which is the only real protection on iOS.
 */
@Injectable({ providedIn: 'root' })
export class StorageService {
  private readonly store = inject(LocalStore);

  /** null until checked; true = the browser promised to keep the data. */
  readonly persisted = signal<boolean | null>(null);
  readonly estimate = signal<StorageEstimate2 | null>(null);
  /**
   * True once the browser has actually been asked, so the warning is not shown to someone with no data yet.
   * Restored from the stored `persistAsked` flag by {@link refresh}, so a later session in which the user only
   * browses still knows the browser refused.
   */
  readonly asked = signal(false);
  /** "Not now" on the storage-risk banner, remembered for {@link RISK_SNOOZE_MS} across reloads. */
  readonly riskDismissed = signal(riskSnoozed(readRiskDismissal()));

  /** True when we know the data can be thrown away: show the "make regular backups" advice. */
  readonly atRisk = computed(() => this.asked() && this.persisted() === false);

  private get manager(): StorageManager | null {
    try {
      return typeof navigator !== 'undefined' && navigator.storage ? navigator.storage : null;
    } catch {
      return null;
    }
  }

  /** Reads the current state without prompting. */
  async refresh(): Promise<void> {
    if (!this.asked()) {
      try {
        if ((await this.store.setting(SETTING_KEYS.persistAsked)) === 'yes') this.asked.set(true);
      } catch {
        // The store itself is unavailable; the banner for that case is already showing.
      }
    }
    const manager = this.manager;
    if (!manager) {
      this.persisted.set(false);
      return;
    }
    try {
      if (typeof manager.persisted === 'function') this.persisted.set(await manager.persisted());
    } catch {
      this.persisted.set(false);
    }
    try {
      if (typeof manager.estimate === 'function') {
        const e = await manager.estimate();
        this.estimate.set({ usageBytes: e.usage ?? 0, quotaBytes: e.quota ?? 0 });
      }
    } catch {
      this.estimate.set(null);
    }
  }

  /**
   * Asks the browser for persistent storage. Called automatically after the first saved house (docs/11 §5.10), so
   * the prompt some browsers show arrives when the user has shown they mean to keep something — and only once per
   * browser. The "Ask the browser to keep my data" button passes `force`, so a user who changed their mind can
   * try again. Returns the resulting state.
   */
  async requestPersistence(force = false): Promise<boolean> {
    await this.refresh();
    this.asked.set(true);
    if (this.persisted() === true) return true;
    const manager = this.manager;
    if (!manager || typeof manager.persist !== 'function') {
      this.persisted.set(false);
      return false;
    }
    if (!force && (await this.store.setting(SETTING_KEYS.persistAsked)) === 'yes') return false;
    try {
      const granted = await manager.persist();
      this.persisted.set(granted);
      await this.store.setSetting(SETTING_KEYS.persistAsked, 'yes');
      return granted;
    } catch {
      this.persisted.set(false);
      return false;
    }
  }

  /** "Not now" on the storage-risk banner: hidden for a week, across reloads (private mode: for this page only). */
  dismissRisk(now: number = Date.now()): void {
    try {
      localStorage.setItem(RISK_DISMISSED_KEY, new Date(now).toISOString());
    } catch {
      // Storage blocked: the dismissal lasts for this page only.
    }
    this.riskDismissed.set(true);
  }

  /** iOS/iPadOS Safari: no install prompt, no persistent storage grant — the user must add to the Home Screen. */
  static isIos(): boolean {
    if (typeof navigator === 'undefined') return false;
    const ua = navigator.userAgent || '';
    const iPadOs = ua.includes('Macintosh') && typeof document !== 'undefined' && 'ontouchend' in document;
    return /iPad|iPhone|iPod/.test(ua) || iPadOs;
  }

  /** True when the page is already running as an installed app. */
  static isStandalone(): boolean {
    if (typeof window === 'undefined') return false;
    try {
      const iosStandalone = (navigator as { standalone?: boolean }).standalone === true;
      return iosStandalone || window.matchMedia('(display-mode: standalone)').matches;
    } catch {
      return false;
    }
  }
}
