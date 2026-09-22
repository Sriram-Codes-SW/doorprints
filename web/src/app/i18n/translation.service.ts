import { Injectable, computed, signal } from '@angular/core';
import type { PriceType } from '../core/models';
import { TKey, en } from './en';
import { DICTIONARIES, LANGUAGES, Lang, LanguageInfo, isLang } from './languages';

/** A translatable message: a key plus its placeholder values. Params may themselves be messages. */
export interface Msg {
  readonly key: TKey;
  readonly params?: Params;
}
export type Param = string | number | Msg;
export type Params = Readonly<Record<string, Param>>;

const STORAGE_KEY = 'house-hunt.lang';

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
  private readonly dict = computed(() => DICTIONARIES[this.state()]);

  private readonly numberFormats = new Map<string, Intl.NumberFormat>();
  private readonly dateFormats = new Map<string, Intl.DateTimeFormat>();

  constructor() {
    applyDocumentLang(this.state());
  }

  setLang(lang: Lang): void {
    if (!isLang(lang)) return;
    this.state.set(lang);
    applyDocumentLang(lang);
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      // Storage unavailable (private mode etc.): the choice lasts for this session only.
    }
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

function initialLang(): Lang {
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
