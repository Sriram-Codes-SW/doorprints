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

import { AI_BASE_URL_KEY, AI_KIND_KEY, AI_MODEL_KEY } from '../storage-keys';

/**
 * The AI provider of the person's choice on this device (docs/03 §13.2, ADR-35): the kind, an OpenAI-compatible base URL
 * and a model, with the rules a base URL must pass and where the three settings are kept. Device-only: nothing here
 * reaches the server, a backup, a copy, a sync file or a share file (T-I43, T-I44, TC-U-170).
 */

/** `anthropic` is Anthropic's Messages API (`anthropic.ts`): a key and a model; its address is its own. */
export type AiKind = 'gemini' | 'openai-compatible' | 'anthropic';

export const AI_KINDS: readonly AiKind[] = ['gemini', 'openai-compatible', 'anthropic'];

/** The person's choice. `baseUrl` and `model` matter to `openai-compatible` only; the key keeps its own slot. */
export interface AiProviderConfig {
  kind: AiKind;
  baseUrl: string;
  model: string;
}

/** A starting point for the base URL (the model is always typed by the person, never guessed). */
export interface AiPreset {
  id: 'openai' | 'openrouter' | 'groq' | 'ollama' | 'lmstudio' | 'custom';
  baseUrl: string;
  /** A local server that usually needs no key. */
  keyOptional: boolean;
}

export const AI_PRESETS: readonly AiPreset[] = [
  { id: 'openai', baseUrl: 'https://api.openai.com/v1', keyOptional: false },
  { id: 'openrouter', baseUrl: 'https://openrouter.ai/api/v1', keyOptional: false },
  { id: 'groq', baseUrl: 'https://api.groq.com/openai/v1', keyOptional: false },
  { id: 'ollama', baseUrl: 'http://localhost:11434/v1', keyOptional: true },
  { id: 'lmstudio', baseUrl: 'http://localhost:1234/v1', keyOptional: true },
  { id: 'custom', baseUrl: '', keyOptional: false },
];

/** Anthropic's address, the one the `anthropic` kind uses (the person picks it from the list, never types it). */
export const ANTHROPIC_BASE_URL = 'https://api.anthropic.com';

/** The first rule a base URL breaks, in the order of docs/03 §13.2. */
export type BaseUrlReason =
  | 'empty'
  | 'notAnUrl'
  | 'scheme'
  | 'userinfo'
  | 'query'
  | 'fragment'
  | 'endpoint'
  | 'insecureHost';

/** `normalised` is what is saved and called; `host` (lower case, no port, an IPv6 address in brackets) is shown to the person. */
export type BaseUrlCheck = { valid: true; normalised: string; host: string } | { valid: false; reason: BaseUrlReason };

const SCHEME = /^([A-Za-z][A-Za-z0-9+.-]*):\/\//;
const AUTHORITY = /^(\[[^\]]*\]|[^:]*)(:\d*)?$/;
/** `http:` is for the device itself only: a browser treats these three as secure, and a LAN address is not (T-I43). */
const LOCAL_HOSTS: readonly string[] = ['localhost', '127.0.0.1', '[::1]'];
/** The Android emulator's name for its computer; only the phone app accepts it (`android`), never the website. */
const EMULATOR_HOST = '10.0.2.2';

/**
 * Whether `input` may be an AI base URL, by the table in docs/03 §13.2 and the `baseUrl` parity vectors. The website
 * never passes `android`; it exists so the shared vectors run unchanged.
 */
export function validateBaseUrl(input: string, android = false): BaseUrlCheck {
  const raw = input.trim();
  if (raw === '') return { valid: false, reason: 'empty' };
  const schemeMatch = SCHEME.exec(raw);
  if (!schemeMatch) return { valid: false, reason: 'notAnUrl' };
  try {
    new URL(raw);
  } catch {
    return { valid: false, reason: 'notAnUrl' };
  }
  const scheme = schemeMatch[1].toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') return { valid: false, reason: 'scheme' };
  const rest = raw.slice(schemeMatch[0].length);
  const end = rest.search(/[/?#]/);
  const authority = end < 0 ? rest : rest.slice(0, end);
  if (authority.includes('@')) return { valid: false, reason: 'userinfo' };
  if (raw.includes('?')) return { valid: false, reason: 'query' };
  if (raw.includes('#')) return { valid: false, reason: 'fragment' };
  const path = (end < 0 ? '' : rest.slice(end)).replace(/\/+$/, '');
  if (path.toLowerCase().endsWith('/chat/completions')) return { valid: false, reason: 'endpoint' };
  const parts = AUTHORITY.exec(authority);
  if (!parts || parts[1] === '') return { valid: false, reason: 'notAnUrl' };
  const host = parts[1].toLowerCase();
  if (scheme === 'http' && !LOCAL_HOSTS.includes(host) && !(android && host === EMULATOR_HOST)) {
    return { valid: false, reason: 'insecureHost' };
  }
  return { valid: true, normalised: `${scheme}://${host}${parts[2] ?? ''}${path}`, host };
}

/**
 * {@link validateBaseUrl} for the website: the same rules, except that `[::1]` is refused. The page's CSP `connect-src` can
 * name `localhost` and `127.0.0.1` but has no source expression for an IPv6 literal (Chrome logs "invalid source" for
 * `http://[::1]:*`), so a `[::1]` address would be blocked by the browser; the person is told to use `localhost`.
 */
export function validateWebBaseUrl(input: string): BaseUrlCheck {
  const check = validateBaseUrl(input);
  return check.valid && check.host === '[::1]' && check.normalised.startsWith('http://') ? { valid: false, reason: 'insecureHost' } : check;
}

/** Whether `host` (as `validateBaseUrl` returns it) is this device itself: a local AI server, which usually needs no key and a CORS setting. */
export function isLocalHost(host: string): boolean {
  return LOCAL_HOSTS.includes(host);
}

/** The host Google's Gemini API is called on (`GEMINI_URL`), named to the person before anything is sent. */
export const GEMINI_HOST = 'generativelanguage.googleapis.com';

/** The host the person's text and key go to for `config`: Gemini's, or the saved base URL's; '' when there is none (yet). */
export function aiHostOf(config: AiProviderConfig): string {
  if (config.kind === 'gemini') return GEMINI_HOST;
  const check = validateWebBaseUrl(config.kind === 'anthropic' ? config.baseUrl.trim() || ANTHROPIC_BASE_URL : config.baseUrl);
  return check.valid ? check.host : '';
}

/**
 * Whether `config` can answer: Gemini needs its key (`hasKey`); an OpenAI-compatible one a valid URL and a model (its key is
 * optional); Anthropic a model, a valid address (its own when none is saved) and the key.
 */
export function isUsable(config: AiProviderConfig, hasKey: boolean): boolean {
  if (config.kind === 'gemini') return hasKey;
  if (config.kind === 'openai-compatible') return config.model.trim() !== '' && validateWebBaseUrl(config.baseUrl).valid;
  return hasKey && config.model.trim() !== '' && validateWebBaseUrl(config.baseUrl.trim() || ANTHROPIC_BASE_URL).valid;
}

function read(key: string): string {
  try {
    return localStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
}

/**
 * The saved choice. With no `aiKind` saved the kind is `gemini`: a person who chose their own Gemini key under ADR-26
 * (provider "device", a key) reads as `gemini` with nothing rewritten, and anyone on the server is unaffected.
 */
export function readAiConfig(): AiProviderConfig {
  const kind = read(AI_KIND_KEY);
  return {
    kind: AI_KINDS.includes(kind as AiKind) ? (kind as AiKind) : 'gemini',
    baseUrl: read(AI_BASE_URL_KEY),
    model: read(AI_MODEL_KEY),
  };
}

/** Saves the choice in localStorage (a preference, not a secret); the base URL is saved normalised when it is valid. */
export function saveAiConfig(config: AiProviderConfig): AiProviderConfig {
  const check = validateWebBaseUrl(config.baseUrl);
  const saved: AiProviderConfig = {
    kind: config.kind,
    baseUrl: check.valid ? check.normalised : config.baseUrl.trim(),
    model: config.model.trim(),
  };
  try {
    localStorage.setItem(AI_KIND_KEY, saved.kind);
    localStorage.setItem(AI_BASE_URL_KEY, saved.baseUrl);
    localStorage.setItem(AI_MODEL_KEY, saved.model);
  } catch {
    // Storage unavailable: the choice holds for this page only.
  }
  return saved;
}

/** Removes the three settings (*Remove key* and "Remove all data"). */
export function clearAiConfig(): void {
  for (const key of [AI_KIND_KEY, AI_BASE_URL_KEY, AI_MODEL_KEY]) {
    try {
      localStorage.removeItem(key);
    } catch {
      // ignore
    }
  }
}
