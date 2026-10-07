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
import type { AskResponse, HouseDraft, PlanResponse } from '../ai.service';
import { AI_KINDS, type AiKind, validateWebBaseUrl } from './ai-provider-config';

/**
 * The optional provider evals (S4b-BL-153, docs/03 ADR-35, docs/06 TC-AI-23): the golden set (docs/ai/evals/golden-set.json)
 * through the website's own adapters against a real provider the owner chose, run by the manual *AI evals* workflow
 * (`ai-provider.live.spec.ts`). This file is the part that needs no network: when to run, how a case is checked, and the
 * summary. It is a port of the simple checks of docs/ai/ai-design.md 8.2 (per case, not the micro-averaged metrics).
 */

export const EVAL_TYPES = ['extract', 'ask', 'plan'] as const;
export type EvalType = (typeof EVAL_TYPES)[number];

/** What the workflow passes in, as environment variables; `DOORPRINTS_EVAL_KEY` is the repository secret. */
export type EvalSetup =
  | { status: 'skip'; line: string }
  | { status: 'invalid'; line: string }
  | { status: 'run'; kind: AiKind; baseUrl: string; model: string; key: string; delayMs: number; types: EvalType[] };

/**
 * Whether and how the evals run. No key means `skipped: no key` and nothing else is looked at, so a repository without the
 * secret gets a green, honest job; a key with a wrong setting is `invalid` (a red job, nothing is sent).
 */
export function evalSetup(env: Record<string, string | undefined>): EvalSetup {
  const key = (env['DOORPRINTS_EVAL_KEY'] ?? '').trim();
  if (key === '') return { status: 'skip', line: 'skipped: no key' };
  const invalid = (line: string): EvalSetup => ({ status: 'invalid', line });
  const kind = (env['AI_EVAL_KIND'] ?? '').trim();
  if (!AI_KINDS.includes(kind as AiKind)) return invalid('kind must be gemini, openai-compatible or anthropic');
  const model = (env['AI_EVAL_MODEL'] ?? '').trim();
  let baseUrl = (env['AI_EVAL_BASE_URL'] ?? '').trim();
  if (kind === 'gemini') {
    baseUrl = '';
  } else {
    if (model === '') return invalid('a model is required');
    if (kind === 'openai-compatible' || baseUrl !== '') {
      const check = validateWebBaseUrl(baseUrl);
      if (!check.valid) return invalid(`base URL is not allowed: ${check.reason}`);
      baseUrl = check.normalised;
    }
  }
  const types = (env['AI_EVAL_TYPES'] ?? EVAL_TYPES.join(',')).split(',').map((t) => t.trim());
  if (types.some((t) => !EVAL_TYPES.includes(t as EvalType))) return invalid('types must be some of extract, ask, plan');
  const delay = env['AI_EVAL_DELAY_MS'] ?? '4000';
  if (!/^\d{1,6}$/.test(delay)) return invalid('delay must be a whole number of milliseconds');
  return { status: 'run', kind: kind as AiKind, baseUrl, model: kind === 'gemini' ? '' : model, key, delayMs: Number(delay), types: types as EvalType[] };
}

/** One case of golden-set.json (only the parts the checks read). */
export interface GoldenCase {
  id: string;
  type: EvalType;
  category?: string;
  input: Record<string, unknown>;
  expected: Record<string, unknown>;
}

/** What a case produced: the service's answer, or why there was none. */
export type Outcome =
  | { type: 'extract'; draft: HouseDraft }
  | { type: 'ask'; response: AskResponse }
  | { type: 'plan'; plan: PlanResponse }
  | { type: 'error'; message: string };

export interface CaseResult {
  id: string;
  type: EvalType;
  category?: string;
  passed: boolean;
  failures: string[];
}

/** NFKC, lower case, runs of anything but letters and digits as one space (8.2; this also folds curly quotes into straight ones). */
const norm = (s: string): string =>
  s.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const squash = (s: string): string => norm(s).replace(/ /g, '');
const digits = (s: string): string => s.replace(/\D/g, '');
const words = (hay: string, needle: string): boolean => ` ${norm(hay)} `.includes(` ${norm(needle)} `);
const list = (value: unknown): string[] => (Array.isArray(value) ? (value as string[]) : []);
const blank = (v: unknown): boolean => v === null || v === undefined || String(v).trim() === '';

function checkDraft(d: HouseDraft, e: Record<string, unknown>): string[] {
  const out: string[] = [];
  const same = (field: 'price' | 'bedrooms') => {
    if (field in e && d[field] !== e[field]) out.push(`${field}: expected ${e[field]}, got ${d[field]}`);
  };
  same('price');
  if ('priceType' in e && String(d.priceType ?? '').toLowerCase() !== String(e['priceType']).toLowerCase()) {
    out.push(`priceType: expected ${e['priceType']}, got ${d.priceType}`);
  }
  same('bedrooms');
  for (const field of ['locality', 'contactName'] as const) {
    if (field in e && (blank(d[field]) || !words(String(d[field]), String(e[field])))) out.push(`${field}: expected ${e[field]}, got ${d[field]}`);
  }
  const phone = digits(d.contactPhone ?? '');
  if (e['contactPhone'] === null && !blank(d.contactPhone)) out.push(`contactPhone: expected nothing, got ${d.contactPhone}`);
  if (typeof e['contactPhone'] === 'string') {
    const want = digits(e['contactPhone']);
    if (phone !== want && !(want.length >= 10 && phone.endsWith(want))) out.push(`contactPhone: expected ${e['contactPhone']}, got ${d.contactPhone}`);
  }
  if (typeof e['contactPhoneDigits'] === 'string' && phone !== e['contactPhoneDigits']) {
    out.push(`contactPhone digits: expected ${e['contactPhoneDigits']}, got ${phone}`);
  }
  if ('listingUrl' in e) {
    const want = e['listingUrl'] as string | null;
    const got = (d.listingUrl ?? '').trim().replace(/\/$/, '').toLowerCase();
    if (want === null ? !blank(d.listingUrl) : got !== want.replace(/\/$/, '').toLowerCase()) {
      out.push(`listingUrl: expected ${want ?? 'nothing'}, got ${d.listingUrl ?? 'nothing'}`);
    }
  }
  if (typeof e['listingUrlNot'] === 'string' && (d.listingUrl ?? '').trim().replace(/\/$/, '').toLowerCase() === e['listingUrlNot'].toLowerCase()) {
    out.push(`listingUrl is ${e['listingUrlNot']}`);
  }
  const missing = list(e['amenitiesInclude']).filter((a) => !d.amenities.some((have) => squash(have).includes(squash(a))));
  if (missing.length) out.push(`amenities: missing ${missing.join(', ')}`);
  for (const m of list(e['notesMention'])) if (!squash(d.notes ?? '').includes(squash(m))) out.push(`notes lack "${m}"`);
  for (const m of list(e['notesMustNotContain'])) if (norm(d.notes ?? '').includes(norm(m))) out.push(`notes contain "${m}"`);
  const everything = norm([d.label, d.address, d.street, d.locality, d.contactName, d.contactPhone, d.listingUrl, d.notes, ...d.amenities].join(' '));
  for (const m of list(e['draftMustNotContain'])) if (everything.includes(norm(m))) out.push(`draft contains "${m}"`);
  return out;
}

function checkAnswer(r: AskResponse, e: Record<string, unknown>): string[] {
  const out: string[] = [];
  const cited = r.citations.map((c) => c.houseId);
  if (typeof e['answerEquals'] === 'string') {
    if (norm(r.answer) !== norm(e['answerEquals'])) out.push(`answer is not "${e['answerEquals']}"`);
    for (const id of cited) out.push(`cited ${id} on a refusal`);
    if (r.grounded) out.push('grounded: expected false, got true');
    return out;
  }
  const expected = list(e['expectedHouseIds']);
  const allowed = [...expected, ...list(e['allowedCitations'])];
  for (const id of cited) if (!allowed.includes(id)) out.push(`cited ${id}, which is neither expected nor allowed`);
  for (const id of list(e['mustNotCite'])) if (cited.includes(id)) out.push(`cited ${id}, which must not be cited`);
  for (const id of expected) if (!cited.includes(id)) out.push(`did not cite ${id}`);
  for (const m of list(e['mustContain'])) if (!norm(r.answer).includes(norm(m))) out.push(`answer lacks "${m}"`);
  for (const m of list(e['mustNotContain'])) if (norm(r.answer).includes(norm(m))) out.push(`answer contains "${m}"`);
  const grounded = typeof e['grounded'] === 'boolean' ? e['grounded'] : expected.length > 0;
  if (r.grounded !== grounded) out.push(`grounded: expected ${grounded}, got ${r.grounded}`);
  return out;
}

function checkPlan(p: PlanResponse, e: Record<string, unknown>): string[] {
  const out: string[] = [];
  const ids = p.stops.map((s) => s.houseId);
  if (typeof e['maxStops'] === 'number' && ids.length > e['maxStops']) out.push(`more than ${e['maxStops']} stops`);
  for (const id of ids.filter((id, i) => ids.indexOf(id) !== i)) out.push(`${id} appears twice`);
  if (Array.isArray(e['stopsSubsetOf'])) for (const id of ids) if (!list(e['stopsSubsetOf']).includes(id)) out.push(`${id} is not an allowed stop`);
  for (const id of list(e['stopsMustNotInclude'])) if (ids.includes(id)) out.push(`${id} must not be a stop`);
  if (Array.isArray(e['stops']) && JSON.stringify(ids) !== JSON.stringify(e['stops'])) out.push(`stops: expected ${JSON.stringify(e['stops'])}, got ${JSON.stringify(ids)}`);
  if (typeof e['fallback'] === 'boolean' && p.fallback !== e['fallback']) out.push(`fallback: expected ${e['fallback']}, got ${p.fallback}`);
  for (const m of list(e['summaryMustNotContain'])) if (norm(p.summary ?? '').includes(norm(m))) out.push(`summary contains "${m}"`);
  return out;
}

/** The case's checks (docs/ai/ai-design.md 8.2). A failed call is a failed case. */
export function scoreCase(c: GoldenCase, outcome: Outcome): CaseResult {
  let failures: string[];
  if (outcome.type === 'error') failures = [`no answer: ${outcome.message}`];
  else if (outcome.type === 'extract') failures = checkDraft(outcome.draft, c.expected);
  else if (outcome.type === 'ask') failures = checkAnswer(outcome.response, c.expected);
  else failures = checkPlan(outcome.plan, c.expected);
  return { id: c.id, type: c.type, category: c.category, passed: failures.length === 0, failures };
}

const MAX_REASON = 200;
const MAX_REASONS = 3;

/**
 * The job summary in markdown. The provider is named by kind, host and model, never by key; any appearance of `key` in
 * a reason (a model can echo what it was sent) is replaced by `***`. `stopped` says why the run ended early, or null.
 */
export function formatSummary(meta: { kind: string; host: string; model: string }, results: CaseResult[], stopped: string | null, key: string): string {
  const hide = (s: string) => (key === '' ? s : s.split(key).join('***'));
  const passed = results.filter((r) => r.passed).length;
  const row = (name: string, of: CaseResult[]) => `| ${name} | ${of.filter((r) => r.passed).length} | ${of.length} |`;
  const lines = [
    '## AI evals: own provider',
    '',
    `Provider: ${meta.kind}${meta.host ? ` at ${meta.host}` : ''}${meta.model ? `, model \`${meta.model}\`` : ''}. The key is not shown.`,
    `Result: ${passed} of ${results.length} cases passed. Reported, not gating.`,
  ];
  if (stopped) lines.push(`STOPPED: ${stopped} after ${results.length} cases.`);
  lines.push('', '| Group | Passed | Of |', '|---|---|---|');
  for (const type of EVAL_TYPES) {
    const of = results.filter((r) => r.type === type);
    if (of.length) lines.push(row(type, of));
  }
  const injection = results.filter((r) => r.category === 'prompt-injection');
  if (injection.length) lines.push(row('prompt-injection (all types)', injection));
  const failed = results.filter((r) => !r.passed);
  if (failed.length) {
    lines.push('', 'Failed cases:');
    for (const r of failed) {
      // Hide first: shortening could cut the key in two and leave its start showing.
      const reasons = r.failures.slice(0, MAX_REASONS).map((f) => hide(f)).map((f) => (f.length > MAX_REASON ? `${f.slice(0, MAX_REASON)}...` : f));
      lines.push(`- ${r.id}: ${reasons.join('; ')}`);
    }
  }
  return `${lines.join('\n')}\n`;
}
