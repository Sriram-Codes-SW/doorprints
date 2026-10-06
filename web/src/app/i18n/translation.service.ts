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

import { Injectable, computed, signal } from '@angular/core';
import type { PriceType } from '../core/models';
import { TKey, en } from './en';
import { LANGUAGES, Lang, LanguageInfo, dictionary, isLang, loadDictionary } from './languages';
import { joinList } from './list-join';
import { LANG_KEY } from '../core/storage-keys';

/** A translatable message: a key plus its placeholder values. Params may themselves be messages. */
export interface Msg {
  readonly key: TKey;
  readonly params?: Params;
}
export type Param = string | number | Msg;
export type Params = Readonly<Record<string, Param>>;

// Was 'house-hunt.lang' before 2026-09-24; main.ts moves a saved value to this name first (core/storage-keys.ts).
const STORAGE_KEY = LANG_KEY;

/**
 * Runtime i18n: one build, language switchable without a reload.
 * `lang` is a signal, so templates, computed() values and the pipe re-render when it changes.
 */
@Injectable({ providedIn: 'root' })
export class TranslationService {
  private readonly state = signal<Lang>(initialLang());

  readonly languages = LANGUAGES;
  readonly lang = this.state.asReadonly();
  readonly info = computed<LanguageInfo>(() => LANGUAGES.find((l) => l.code === this.state()) ?? LANGUAGES[0]);
  readonly locale = computed(() => this.info().locale);
  /** Bumped when a dictionary finishes loading, so `dict` is read again. */
  private readonly loads = signal(0);
  private readonly dict = computed(() => {
    this.loads();
    return dictionary(this.state()) ?? en;
  });
  /** The language last asked for, so a slow load cannot override a later choice. */
  private requested: Lang = this.state();

  private readonly numberFormats = new Map<string, Intl.NumberFormat>();
  private readonly dateFormats = new Map<string, Intl.DateTimeFormat>();

  constructor() {
    applyDocumentLang(this.state());
    // main.ts loads the saved language before the app starts; if it could not (or in a test), load it now and
    // show English until it arrives.
    if (!dictionary(this.state())) {
      loadDictionary(this.state()).then(
        () => this.loads.update((n) => n + 1),
        () => undefined,
      );
    }
  }

  /**
   * Switches the language. A language not loaded yet is fetched first (its own chunk) and the switch happens when it
   * arrives, so the page never shows a mix; the promise settles then. If it cannot be fetched (offline before it was
   * ever cached), the language stays as it was and the promise rejects.
   */
  setLang(lang: Lang): Promise<void> {
    if (!isLang(lang)) return Promise.resolve();
    this.requested = lang;
    const apply = () => {
      if (this.requested !== lang) return; // a later choice won
      this.state.set(lang);
      applyDocumentLang(lang);
      try {
        localStorage.setItem(STORAGE_KEY, lang);
      } catch {
        // Storage unavailable (private mode etc.): the choice lasts for this session only.
      }
    };
    if (dictionary(lang)) {
      apply();
      return Promise.resolve();
    }
    return loadDictionary(lang).then(() => {
      this.loads.update((n) => n + 1);
      apply();
    });
  }

  /** Translates a key, filling `{name}` placeholders. Reading it inside a template or computed() tracks `lang`. */
  t(key: TKey, params?: Params): string {
    const template = this.dict()[key] ?? en[key] ?? key;
    if (!params) return template;
    return template.replace(/\{(\w+)\}/g, (match, name: string) =>
      Object.prototype.hasOwnProperty.call(params, name) ? this.param(params[name]) : match,
    );
  }

  /** Translates a message object (e.g. an error stored in a signal), so it follows later language changes. */
  msg(m: Msg): string {
    return this.t(m.key, m.params);
  }

  /** "a, b and c" in the app language's own list pattern (see {@link joinList}). */
  list(items: readonly string[]): string {
    return joinList(
      items,
      (a, b) => this.t('list.two', { a, b }),
      (a, b, c) => this.t('list.three', { a, b, c }),
      (a, b) => this.t('list.middle', { a, b }),
    );
  }

  /** A size in megabytes or gigabytes, with the unit written the language's own way (Intl unit formatting). */
  size(bytes: number): string {
    const mb = bytes / (1024 * 1024);
    const giga = mb >= 1024;
    const unit = giga ? 'gigabyte' : 'megabyte';
    const value = giga ? mb / 1024 : mb;
    return this.numberFormat(`unit-${unit}`, { style: 'unit', unit, maximumFractionDigits: 1 }).format(value);
  }

  number(n: number, fractionDigits = 0): string {
    return this.numberFormat(`n${fractionDigits}`, {
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    }).format(n);
  }

  /** ₹ with Indian grouping (₹1,25,000), plus "/month" for rent. */
  price(price: number | null | undefined, type?: PriceType | null): string {
    if (price === null || price === undefined) return '–';
    const base = this.numberFormat('inr', { style: 'currency', currency: 'INR', maximumFractionDigits: 0 }).format(price);
    return type === 'RENT' ? this.t('price.perMonth', { price: base }) : base;
  }

  /** 0–5 score with one decimal, or an en dash when nothing is scored. */
  score(score: number | null | undefined): string {
    return score === null || score === undefined ? '–' : this.number(score, 1);
  }

  dateTime(iso: string | null | undefined): string {
    if (!iso) return '–';
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '–';
    const locale = this.locale();
    let fmt = this.dateFormats.get(locale);
    if (!fmt) {
      fmt = new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' });
      this.dateFormats.set(locale, fmt);
    }
    return fmt.format(d);
  }

  /** The date alone of an epoch time, in the language's own style (Intl: no Angular locale data is needed or loaded). */
  dateOnly(epochMs: number | null | undefined): string {
    return this.formatOf('date', epochMs);
  }

  /** The time of day alone of an epoch time, in the language's own style. */
  timeOnly(epochMs: number | null | undefined): string {
    return this.formatOf('time', epochMs);
  }

  private formatOf(kind: 'date' | 'time', epochMs: number | null | undefined): string {
    if (epochMs === null || epochMs === undefined || !Number.isFinite(epochMs)) return '–';
    const locale = this.locale();
    const key = `${kind}:${locale}`;
    let fmt = this.dateFormats.get(key);
    if (!fmt) {
      fmt = new Intl.DateTimeFormat(locale, kind === 'date' ? { dateStyle: 'medium' } : { timeStyle: 'short' });
      this.dateFormats.set(key, fmt);
    }
    return fmt.format(new Date(epochMs));
  }

  duration(fromIso: string, toIso: string | null | undefined): string {
    if (!toIso) return '';
    const ms = new Date(toIso).getTime() - new Date(fromIso).getTime();
    if (!Number.isFinite(ms) || ms <= 0) return '';
    const min = Math.round(ms / 60000);
    if (min < 60) return this.t('duration.minutes', { m: min });
    return this.t('duration.hoursMinutes', { h: Math.floor(min / 60), m: min % 60 });
  }

  private param(p: Param | undefined): string {
    if (p === undefined) return '';
    if (typeof p === 'number') return this.number(p);
    if (typeof p === 'string') return p;
    return this.msg(p);
  }

  private numberFormat(id: string, options: Intl.NumberFormatOptions): Intl.NumberFormat {
    const cacheKey = `${this.locale()}|${id}`;
    let fmt = this.numberFormats.get(cacheKey);
    if (!fmt) {
      fmt = new Intl.NumberFormat(this.locale(), options);
      this.numberFormats.set(cacheKey, fmt);
    }
    return fmt;
  }
}

/**
 * The language the app starts in: the saved choice, else the first supported browser language, else English.
 * Exported for `main.ts`, which needs it before Angular starts (the frame refusal, `core/frame-guard.ts`).
 */
export function initialLang(): Lang {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (isLang(saved)) return saved;
  } catch {
    // ignore unavailable storage
  }
  const candidates = typeof navigator !== 'undefined' ? (navigator.languages ?? [navigator.language]) : [];
  for (const tag of candidates) {
    const base = (tag ?? '').toLowerCase().split('-')[0];
    if (isLang(base)) return base;
  }
  return 'en';
}

function applyDocumentLang(lang: Lang): void {
  if (typeof document !== 'undefined') {
    document.documentElement.lang = lang;
  }
}
