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
import { AI_KINDS, type AiKind, isLocalHost, validateWebBaseUrl } from './ai-provider-config';

/**
 * The optional provider evals (S4b-BL-153, docs/03 ADR-35, docs/06 TC-AI-23): the golden set (docs/ai/evals/golden-set.json)
 * through the website's own adapters against a real provider the owner chose, run by the manual *AI evals* workflow
 * (`ai-provider.live.spec.ts`). This file is the part that needs no network: when to run, how a case is checked, and the
 * summary. It is a port of the simple checks of docs/ai/ai-design.md 8.2 (per case, not the micro-averaged metrics).
 */

export const EVAL_TYPES = ['extract', 'ask', 'plan'] as const;
/** The three kinds of case in the golden set. */
export type EvalType = (typeof EVAL_TYPES)[number];

/** What the workflow passes in, as environment variables; `DOORPRINTS_EVAL_KEY` is the repository secret. */
export type EvalSetup =
  | { status: 'skip'; line: string }
  | { status: 'invalid'; line: string }
  | { status: 'run'; kind: AiKind; baseUrl: string; model: string; key: string; delayMs: number; timeoutMs: number; types: EvalType[] };

/** The app's request limit, and the bounds of the one the loopback eval may ask for (S4b-BL-190). */
const DEFAULT_TIMEOUT_MS = 60_000;
const MIN_TIMEOUT_MS = 1_000;
const MAX_TIMEOUT_MS = 600_000;

/**
 * Whether the setting names an OpenAI-compatible server on this machine (`localhost`, `127.0.0.1`): the one place a key is
 * not needed (Ollama ignores it), used by the manual workflow's `local-model` suite (S4b-BL-175).
 */
function isKeylessLocal(env: Record<string, string | undefined>): boolean {
  if ((env['AI_EVAL_KIND'] ?? '').trim() !== 'openai-compatible') return false;
  const check = validateWebBaseUrl((env['AI_EVAL_BASE_URL'] ?? '').trim());
  return check.valid && isLocalHost(check.host);
}

/**
 * Whether and how the evals run. No key means `skipped: no key` and nothing else is looked at, so a repository without the
 * secret gets a green, honest job (except for a server on this machine, which needs no key); a key with a wrong setting is
 * `invalid` (a red job, nothing is sent).
 */
export function evalSetup(env: Record<string, string | undefined>): EvalSetup {
  const key = (env['DOORPRINTS_EVAL_KEY'] ?? '').trim();
  if (key === '' && !isKeylessLocal(env)) return { status: 'skip', line: 'skipped: no key' };
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
  // A longer request limit (S4b-BL-190) is for the model on this machine only: a hosted provider keeps the app's 60 s.
  const limit = (env['AI_EVAL_TIMEOUT_MS'] ?? '').trim();
  let timeoutMs = DEFAULT_TIMEOUT_MS;
  if (limit !== '') {
    if (!isKeylessLocal(env)) return invalid('a longer request limit is only for a server on this machine');
    timeoutMs = /^\d{1,7}$/.test(limit) ? Number(limit) : -1;
    if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
      return invalid(`timeout must be a whole number of milliseconds from ${MIN_TIMEOUT_MS} to ${MAX_TIMEOUT_MS}`);
    }
  }
  return { status: 'run', kind: kind as AiKind, baseUrl, model: kind === 'gemini' ? '' : model, key, delayMs: Number(delay), timeoutMs, types: types as EvalType[] };
}

/** One case of golden-set.json (only the parts the checks read). */
export interface GoldenCase {
  id: string;
  type: EvalType;
  category?: string;
  /** The golden set's region tag (v0.7): the zone of the city the case is about. */
  region?: string;
  input: Record<string, unknown>;
  expected: Record<string, unknown>;
}

/** What a case produced: the service's answer, or why there was none. */
export type Outcome =
  | { type: 'extract'; draft: HouseDraft }
  | { type: 'ask'; response: AskResponse }
  | { type: 'plan'; plan: PlanResponse }
  | { type: 'error'; message: string };

/** Whether one golden-set case passed, and why not. */
export interface CaseResult {
  id: string;
  type: EvalType;
  category?: string;
  region?: string;
  passed: boolean;
  failures: string[];
  /** The model's response as compact JSON (a draft, an answer with its citations, or a plan); absent when the call failed. */
  output?: string;
}

/** NFKC, lower case, runs of anything but letters and digits as one space (8.2; this also folds curly quotes into straight ones). */
const norm = (s: string): string =>
  s.normalize('NFKC').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
const squash = (s: string): string => norm(s).replace(/ /g, '');
const digits = (s: string): string => s.replace(/\D/g, '');
/**
 * Whether `hay` holds `needle`, read by letters and digits (`norm`) so quotes, case and spacing do not matter. A needle
 * with no letter or digit (the golden set's "![" for a markdown image) is empty after `norm`, and an empty word is in
 * every answer: such a needle is compared as written (compatibility forms such as full-width brackets count as the plain ones).
 */
const mentions = (hay: string, needle: string): boolean =>
  norm(needle) === '' ? hay.normalize('NFKC').includes(needle.normalize('NFKC')) : norm(hay).includes(norm(needle));
const words = (hay: string, needle: string): boolean => ` ${norm(hay)} `.includes(` ${norm(needle)} `);
const list = (value: unknown): string[] => (Array.isArray(value) ? (value as string[]) : []);
const blank = (v: unknown): boolean => v === null || v === undefined || String(v).trim() === '';

/**
 * Checks an extracted draft against what the case expects (fields, amenities, notes) and that nothing it must not
 * contain got through. Returns the failures.
 */
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
    if (!(field in e)) continue;
    // Expected null: the listing does not say, so anything but a blank is an invention (the Java scorer's rule).
    if (e[field] === null) {
      if (!blank(d[field])) out.push(`${field}: expected nothing, got ${d[field]}`);
    } else if (blank(d[field]) || !words(String(d[field]), String(e[field]))) {
      out.push(`${field}: expected ${e[field]}, got ${d[field]}`);
    }
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

/**
 * Checks an Ask answer: a refusal must cite nothing; otherwise the cited houses must be the expected or allowed ones,
 * the answer must hold the required text, and `grounded` must match.
 */
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
  for (const m of list(e['mustContain'])) if (!mentions(r.answer, m)) out.push(`answer lacks "${m}"`);
  for (const m of list(e['mustNotContain'])) if (mentions(r.answer, m)) out.push(`answer contains "${m}"`);
  // "grounded": "any" skips only this check (the refusal or a grounded answer is right); the leak checks above still apply.
  const grounded = typeof e['grounded'] === 'boolean' ? e['grounded'] : expected.length > 0;
  if (e['grounded'] !== 'any' && r.grounded !== grounded) out.push(`grounded: expected ${grounded}, got ${r.grounded}`);
  return out;
}

/**
 * Checks a visit plan: stop count, no repeats, only allowed stops, the expected order and fallback flag, and text the
 * summary must not hold.
 */
function checkPlan(p: PlanResponse, e: Record<string, unknown>): string[] {
  const out: string[] = [];
  const ids = p.stops.map((s) => s.houseId);
  if (typeof e['maxStops'] === 'number' && ids.length > e['maxStops']) out.push(`more than ${e['maxStops']} stops`);
  for (const id of ids.filter((id, i) => ids.indexOf(id) !== i)) out.push(`${id} appears twice`);
  if (Array.isArray(e['stopsSubsetOf'])) for (const id of ids) if (!list(e['stopsSubsetOf']).includes(id)) out.push(`${id} is not an allowed stop`);
  for (const id of list(e['stopsMustNotInclude'])) if (ids.includes(id)) out.push(`${id} must not be a stop`);
  if (Array.isArray(e['stops']) && JSON.stringify(ids) !== JSON.stringify(e['stops'])) out.push(`stops: expected ${JSON.stringify(e['stops'])}, got ${JSON.stringify(ids)}`);
  // An empty plan must not pass vacuously (S4b-BL-203): minStops (0 = fine) and stopsMustInclude.
  if (typeof e['minStops'] === 'number' && ids.length < e['minStops']) out.push(`fewer than ${e['minStops']} stops`);
  for (const id of list(e['stopsMustInclude'])) if (!ids.includes(id)) out.push(`${id} must be a stop`);
  if (typeof e['fallback'] === 'boolean' && p.fallback !== e['fallback']) out.push(`fallback: expected ${e['fallback']}, got ${p.fallback}`);
  for (const m of list(e['summaryMustNotContain'])) if (norm(p.summary ?? '').includes(norm(m))) out.push(`summary contains "${m}"`);
  return out;
}

/** The case's checks (docs/ai/ai-design.md 8.2). A failed call is a failed case. */
export function scoreCase(c: GoldenCase, outcome: Outcome): CaseResult {
  let failures: string[];
  let output: string | undefined;
  if (outcome.type === 'error') failures = [`no answer: ${outcome.message}`];
  else if (outcome.type === 'extract') {
    failures = checkDraft(outcome.draft, c.expected);
    output = JSON.stringify(outcome.draft);
  } else if (outcome.type === 'ask') {
    failures = checkAnswer(outcome.response, c.expected);
    const { answer, citations, grounded } = outcome.response;
    output = JSON.stringify({ answer, citations, grounded });
  } else {
    failures = checkPlan(outcome.plan, c.expected);
    output = JSON.stringify(outcome.plan);
  }
  return { id: c.id, type: c.type, category: c.category, region: c.region, passed: failures.length === 0, failures, output };
}

/**
 * A row per region and the spread of their pass rates (best minus worst), informational like the whole run: a region has
 * a handful of cases. Nothing when no case carries a region. Regions in name order, as the Java report lists them; level
 * regions name the first and the last one, so one region is never named twice. The same arithmetic as the server's
 * `EvalScorer.regionSpread`, on pass counts (this port does not compute the micro-averaged metrics).
 */
function regionLines(results: CaseResult[]): string[] {
  const names = [...new Set(results.map((r) => r.region).filter((r): r is string => !!r))].sort();
  if (names.length === 0) return [];
  const rows = names.map((name) => {
    const of = results.filter((r) => r.region === name);
    return { name, passed: of.filter((r) => r.passed).length, of: of.length };
  });
  const rate = (r: { passed: number; of: number }) => r.passed / r.of;
  const best = rows.reduce((a, b) => (rate(b) > rate(a) ? b : a));
  const worst = rows.reduce((a, b) => (rate(b) <= rate(a) ? b : a));
  const spread =
    rows.length < 2
      ? 'n/a (one region)'
      : `${(rate(best) - rate(worst)).toFixed(2)} (${best.name} ${rate(best).toFixed(2)}, ${worst.name} ${rate(worst).toFixed(2)})`;
  return [
    '',
    '| Region | Passed | Of |',
    '|---|---|---|',
    ...rows.map((r) => `| ${r.name} | ${r.passed} | ${r.of} |`),
    '',
    `Region spread (informational, not gating; best region pass rate minus worst region): ${spread}`,
  ];
}

const MAX_REASON = 200;
const MAX_REASONS = 3;
const MAX_OUTPUT = 3000;
/** Failed cases that print their output; the summary stays far below the 1 MiB the job summary allows. */
const MAX_OUTPUTS = 20;

/**
 * The job summary in markdown. The provider is named by kind, host and model, never by key; any appearance of `key` in
 * a reason or in a failed case's output (a model can echo what it was sent) is replaced by `***`. `stopped` says why the run ended early, or null.
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
  lines.push(...regionLines(results));
  const failed = results.filter((r) => !r.passed);
  if (failed.length) {
    lines.push('', 'Failed cases:');
    let shown = 0;
    let unseen = 0;
    for (const r of failed) {
      // Hide first: shortening could cut the key in two and leave its start showing.
      const reasons = r.failures.slice(0, MAX_REASONS).map((f) => hide(f)).map((f) => (f.length > MAX_REASON ? `${f.slice(0, MAX_REASON)}...` : f));
      lines.push(`- ${r.id}: ${reasons.join('; ')}`);
      if (r.output !== undefined && shown < MAX_OUTPUTS) {
        shown++;
        // Hide before cutting (as above). A backtick is written as the JSON escape \u0060, so no answer can close the fence.
        const out = hide(r.output).replace(/`/g, '\\u0060');
        lines.push('  ```json', `  ${out.length > MAX_OUTPUT ? `${out.slice(0, MAX_OUTPUT)} ... (cut)` : out}`, '  ```');
      } else if (r.output !== undefined) unseen++;
    }
    if (unseen) lines.push(`Output of ${unseen} more failed ${unseen === 1 ? 'case' : 'cases'} not shown.`);
  }
  return `${lines.join('\n')}\n`;
}
