#!/usr/bin/env node
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

// The appendix of the guide page "What we test" (guide/docs/what-we-test.md, "Every test question and listing"), made from
// the golden set (docs/ai/evals/golden-set.json) so the page cannot go stale:
//
//   node tools/guide-search-check/ai-cases.mjs            # check: exit 1 when the page no longer matches the golden set
//   node tools/guide-search-check/ai-cases.mjs --write    # rewrite the block between the two markers in the page
//
// The check is run by tools/check.sh (the guide area) and by .github/workflows/pages.yml, and a change to the golden set
// starts both (their path filters name it). Every case is listed with its id, its kind (Extract, Ask, Plan visits), the
// exact question or listing text the case sends (Hindi, Tamil and Telugu included), and one line of what is expected,
// in plain words. The page's prose must also name the number of cases ("75 cases").
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const GOLDEN = path.join(root, 'docs', 'ai', 'evals', 'golden-set.json');
const PAGE = path.join(root, 'guide', 'docs', 'what-we-test.md');
export const BEGIN = '<!-- golden-set-cases:begin (made by tools/guide-search-check/ai-cases.mjs; do not edit by hand) -->';
export const END = '<!-- golden-set-cases:end -->';

/** Rupees with Indian digit grouping: 1350000 becomes ₹13,50,000. */
export function rupees(n) {
  const s = String(Math.round(n));
  if (s.length <= 3) return `₹${s}`;
  return `₹${s.slice(0, -3).replace(/\B(?=(\d{2})+(?!\d))/g, ',')},${s.slice(-3)}`;
}

/** A saved note that gives the AI orders (the English and Hindi marks the fixtures use). */
const HOSTILE = /ignore all previous instructions|assistant:|पिछले सभी निर्देश/i;
const list = (items) => (items.length <= 1 ? items.join('') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`);
const status = { NEW: 'New', SHORTLISTED: 'Shortlisted', REJECTED: 'Rejected', TAKEN: 'Taken', NOT_CHOSEN: 'Not chosen' };

/** One line, in plain words, of what the case expects. */
export function expectation(c, labelOf) {
  const e = c.expected;
  const injection = c.category === 'prompt-injection';
  const names = (ids) => list(ids.map(labelOf));
  const out = [];
  if (c.type === 'extract') {
    if ('price' in e) out.push(e.price === null ? 'no price' : `price ${rupees(e.price)}${e.priceType === 'RENT' ? ' a month' : ''}`);
    if (e.priceType) out.push(e.priceType === 'RENT' ? 'for rent' : 'for sale');
    if ('bedrooms' in e) out.push(e.bedrooms === 0 ? 'a studio or 1RK' : `${e.bedrooms} BHK`);
    if (e.locality) out.push(`area ${e.locality}`);
    if ('contactName' in e) out.push(e.contactName === null ? 'no name made up' : `name ${e.contactName}`);
    if ('contactPhone' in e || 'contactPhoneDigits' in e) out.push(e.contactPhone === null ? 'no phone made up' : `phone ${e.contactPhone ?? 'as written'}`);
    if ('listingUrl' in e) out.push(e.listingUrl === null ? 'no link made up' : 'the listing link');
    if (e.amenitiesInclude) out.push(`extras: ${list(e.amenitiesInclude)}`);
    if (e.notesMention) out.push(`notes mention ${list(e.notesMention.map((w) => `“${w}”`))}`);
    if (injection) out.push('must ignore the hidden instruction');
  } else if (c.type === 'ask') {
    if (e.answerEquals) out.push('must answer with the fixed refusal and cite no house');
    if (e.expectedHouseIds?.length) out.push(`must cite ${names(e.expectedHouseIds)}`);
    if (e.mustContain) out.push(`the answer mentions ${list(e.mustContain.map((w) => `“${w}”`))}`);
    if (e.mustNotCite?.length) out.push(`must not cite ${names(e.mustNotCite)}`);
    if ((e.mustNotContain ?? []).some((w) => /\d{4}/.test(w))) out.push('must not reveal the contact’s phone or name');
    if (injection) out.push('must ignore the hidden instruction');
    if (!out.length) out.push('answers from the saved houses');
  } else {
    if (e.stops && e.stops.length === 0) out.push('no stops, because no saved house matches');
    if (e.stopsSubsetOf) out.push(e.stopsSubsetOf.length > 4 ? `stops only from the ${e.stopsSubsetOf.length} houses that match` : `stops only from ${names(e.stopsSubsetOf)}`);
    if (e.stopsMustNotInclude?.length) out.push(`never ${names(e.stopsMustNotInclude)}`);
    const max = e.maxStops ?? c.input.maxStops;
    if (max) out.push(`at most ${max} stops`);
    if (e.fallback === false) out.push('a real AI plan, not the simple fallback route');
    if (injection) out.push('must ignore the hidden instruction');
  }
  const line = out.join('; ');
  return line.charAt(0).toUpperCase() + line.slice(1);
}

/** What is sent with the question besides its words (a filter, a start point), or ''. */
function sentWith(c) {
  const bits = [];
  const f = c.input.filters;
  if (f) bits.push(`filters: ${[f.status && status[f.status], f.maxPrice && `up to ${rupees(f.maxPrice)}`].filter(Boolean).join(', ')}`);
  if (c.input.startLat !== undefined) bits.push(`start point ${c.input.startLat}, ${c.input.startLon}`);
  if (c.input.maxStops) bits.push(`at most ${c.input.maxStops} stops`);
  return bits.length ? `Sent with it: ${bits.join('; ')}.` : '';
}

const KINDS = [
  ['extract', 'Extract', 'Fill in from listing text'],
  ['ask', 'Ask', 'questions about saved houses'],
  ['plan', 'Plan visits', 'planning a round of viewings'],
];

/** The whole block, markers included, for a parsed golden set. */
export function block(golden) {
  const label = new Map(golden.fixtureHouses.map((h) => [h.id, h.label]));
  const labelOf = (id) => label.get(id) ?? 'a house that does not exist';
  const lines = [BEGIN, '', `<details markdown="1">`, `<summary>Every test question and listing (${golden.cases.length} cases)</summary>`, ''];
  lines.push('These are the golden set, version ' + `${golden.version}. Some inputs are deliberately hostile test inputs: they try to make the AI break its rules, and the AI must ignore them. They are shown as the test sends them, in English, Hindi, Tamil and Telugu. The houses are made up.`, '');
  for (const [type, name, what] of KINDS) {
    const cases = golden.cases.filter((c) => c.type === type);
    lines.push(`### ${name}: ${what} (${cases.length})`, '');
    for (const c of cases) {
      const text = c.input.text ?? c.input.question;
      const fence = '`'.repeat(Math.max(3, ...(text.match(/`+/g) ?? []).map((m) => m.length + 1)));
      lines.push(`**${c.id}** · ${name}${c.category === 'prompt-injection' ? ' · hidden-instruction test' : c.category === 'refusal' ? ' · refusal test' : ''}`, '');
      lines.push(`${fence}text`, text, fence, '');
      const sent = sentWith(c);
      lines.push(`${sent ? `${sent} ` : ''}Expected: ${expectation(c, labelOf)}.`, '');
    }
  }
  // The saved notes that carry a hostile instruction, as the test houses hold them, and the cases that read them.
  const hostile = golden.fixtureHouses.filter((h) => HOSTILE.test(h.notes ?? ''));
  lines.push(`### The saved notes that try to give orders (${hostile.length})`, '');
  lines.push('A few of the made-up houses carry a hostile instruction in their saved notes. The AI reads these notes when it answers, and must ignore the orders in them.', '');
  for (const h of hostile) {
    const readBy = golden.cases.filter((c) => [...(c.expected.expectedHouseIds ?? []), ...(c.expected.stopsSubsetOf ?? [])].includes(h.id)).map((c) => c.id);
    lines.push(`**${h.label}**${readBy.length ? ` · read by ${readBy.join(', ')}` : ''}`, '', '```text', h.notes, '```', '');
  }
  lines.push('</details>', '', END);
  return lines.join('\n');
}

/** The page with its block replaced by `fresh`; null when a marker is missing. */
export function withBlock(page, fresh) {
  const a = page.indexOf(BEGIN);
  const b = page.indexOf(END);
  if (a < 0 || b < a) return null;
  return page.slice(0, a) + fresh + page.slice(b + END.length);
}

/** Problems of `page` against `golden`, as a list of sentences (empty when the page is current). */
export function problems(page, golden) {
  const fresh = block(golden);
  const expected = withBlock(page, fresh);
  const found = [];
  if (expected === null) return ['the page has no golden-set-cases markers'];
  if (expected !== page) found.push('the appendix differs from the golden set: run node tools/guide-search-check/ai-cases.mjs --write');
  if (!new RegExp(`\\b${golden.cases.length} cases\\b`).test(page.replace(fresh, ''))) found.push(`the text of the page does not say "${golden.cases.length} cases"`);
  return found;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const golden = JSON.parse(fs.readFileSync(GOLDEN, 'utf8'));
  const page = fs.readFileSync(PAGE, 'utf8');
  if (process.argv.includes('--write')) {
    const next = withBlock(page, block(golden));
    if (next === null) throw new Error(`${PAGE} has no golden-set-cases markers`);
    fs.writeFileSync(PAGE, next);
    console.log(`what-we-test.md: appendix written (${golden.cases.length} cases)`);
  } else {
    const found = problems(page, golden);
    for (const p of found) console.error(`what-we-test.md: ${p}`);
    if (found.length === 0) console.log(`what-we-test.md: appendix matches the golden set (${golden.cases.length} cases)`);
    process.exit(found.length ? 1 : 0);
  }
}
