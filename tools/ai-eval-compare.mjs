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

// The address gap of the AI evals (S4b-BL-227, docs/ai/ai-design.md 8.3b): takes two scorecards (ai-eval-report.md of the
// server eval), the default run first and the run under an address set second, and prints, per metric, the default value,
// the variant value and addressGap = default minus variant. Informational: nothing is gated and the exit code is 0 unless
// a file cannot be read as a scorecard.
//
//   node tools/ai-eval-compare.mjs default-report.md variant-report.md
//
// A positive gap means the variant scored lower. For extractionHallucinationRate and the latency a lower value is better,
// so there a negative gap is the worse variant. Agreement rows ("Agreement across trials") are compared when both have them.
// The paired verdict (S4b-BL-236): the cases both cards scored are paired by id, only the discordant pairs (passed in one,
// failed in the other) are counted, and an exact two-sided sign test says whether the split is more than coin tosses.
// Any two scorecards of the same case set compare this way: a default run against an address set, the hard set on two
// Vertex models (the model-spread matrix of docs/ai/ai-design.md 8.6), or two runs of the default to see the noise floor.
import fs from 'node:fs';

/** The rows of the first markdown table under `## <title>`: arrays of trimmed cells. */
export function tableUnder(text, title) {
  const lines = text.split('\n');
  const start = lines.findIndex((l) => l === `## ${title}` || l.startsWith(`## ${title} `) || l.startsWith(`## ${title}(`));
  if (start < 0) return [];
  const rows = [];
  for (let i = start + 1; i < lines.length; i++) {
    const l = lines[i];
    if (l.startsWith('## ')) break;
    if (!l.startsWith('|')) {
      if (rows.length) break;
      continue;
    }
    const cells = l.replace(/^\||\|$/g, '').split(/(?<!\\)\|/).map((c) => c.trim());
    if (cells.every((c) => /^:?-+:?$/.test(c))) continue;
    rows.push(cells);
  }
  return rows.slice(1); // the header row
}

const number = (cell) => {
  const n = Number.parseFloat(cell);
  return Number.isFinite(n) ? n : null;
};

/** `{ header: { key: value }, metrics: { name: value|null }, agreement: { type: value } }` from a scorecard's text. */
export function readScorecard(text) {
  if (!text.includes('# Doorprints AI eval scorecard')) throw new Error('not a scorecard (no "Doorprints AI eval scorecard" title)');
  const header = {};
  const top = text.split('\n## ')[0];
  for (const line of top.split('\n')) {
    const m = /^\| ([^|]+) \| (.*) \|$/.exec(line);
    if (m && m[1] !== '' && m[1] !== '---') header[m[1].trim()] = m[2].trim();
  }
  const metrics = {};
  for (const row of tableUnder(text, 'Metrics')) metrics[row[0]] = number(row[1]);
  if (Object.keys(metrics).length === 0) throw new Error('no Metrics table');
  const agreement = {};
  for (const row of tableUnder(text, 'Agreement across trials')) agreement[`agreement(${row[0]})`] = number(row[4]);
  // Per case, its result (PASS, FAIL, ERROR or INFRA) for the paired comparison; absent when the card has no Cases table.
  const cases = {};
  for (const row of tableUnder(text, 'Cases')) if (row.length >= 5) cases[row[0]] = row[4];
  return { header, metrics, agreement, cases };
}

/** C(n, k) as a double (n is at most the number of cases, so no overflow). */
function choose(n, k) {
  let r = 1;
  for (let i = 1; i <= k; i++) r = (r * (n - k + i)) / i;
  return r;
}

/**
 * The exact two-sided sign test (S4b-BL-236): the p-value of `k` of `n` discordant pairs falling one way when each is a
 * coin toss. 8 of 8 gives 0.0078, 7 of 8 gives 0.0703; so with the golden set's 75 cases a 7:1 split is not significant at
 * 0.05 and an 8:0 split is. Null when there are no discordant pairs.
 */
export function signTest(k, n) {
  if (n === 0) return null;
  const m = Math.min(k, n - k);
  let tail = 0;
  for (let i = 0; i <= m; i++) tail += choose(n, i);
  return Math.min(1, (2 * tail) / 2 ** n);
}

/**
 * The paired comparison of two scorecards (S4b-BL-236): same case in both, counted only where both scored it (PASS or
 * FAIL; an ERROR or INFRA on either side leaves the pair out). Only discordant pairs carry information: a case both passed
 * or both failed says nothing about the difference. Returns the counts, the ids and the verdict line.
 */
export function paired(a, b, names = ['default', 'variant']) {
  const scored = (r) => r === 'PASS' || r === 'FAIL';
  const ids = Object.keys(a.cases).filter((id) => id in b.cases && scored(a.cases[id]) && scored(b.cases[id]));
  const aOnly = ids.filter((id) => a.cases[id] === 'PASS' && b.cases[id] === 'FAIL');
  const bOnly = ids.filter((id) => a.cases[id] === 'FAIL' && b.cases[id] === 'PASS');
  const discordant = aOnly.length + bOnly.length;
  const p = signTest(aOnly.length, discordant);
  let verdict;
  if (ids.length === 0) verdict = 'PAIRED: no case scored in both files';
  else if (discordant === 0) {
    verdict = `PAIRED: ${ids.length} cases scored in both, 0 discordant pairs: no difference observed (not evidence of equality; the interval on each metric says what ${ids.length} cases can show)`;
  } else {
    const better = p <= 0.05 ? `the ${aOnly.length > bOnly.length ? names[0] : names[1]} is better, significant at 0.05` : 'no significant difference at 0.05';
    verdict = `PAIRED: ${ids.length} cases scored in both, ${discordant} discordant pairs (${aOnly.length} ${names[0]}-only passes, ${bOnly.length} ${names[1]}-only), exact two-sided sign test p = ${p.toFixed(3)}: ${better}`;
  }
  return { compared: ids.length, discordant, aOnly, bOnly, p, verdict };
}

const fmt = (v) => (v === null || v === undefined ? 'n/a' : v.toFixed(2));

/** The comparison as text: one line per metric present in either card. */
export function compare(defaultText, variantText) {
  const a = readScorecard(defaultText);
  const b = readScorecard(variantText);
  const out = [];
  out.push(`default:  ${a.header['Address set'] ? `NOT a default run (Address set ${a.header['Address set']})` : 'default run'}`);
  out.push(`variant:  ${b.header['Address set'] ?? 'NOT an address-set run (no "Address set" row)'}`);
  out.push('addressGap = default minus variant (positive: the variant scored lower; for extractionHallucinationRate a negative gap is worse)');
  out.push('');
  out.push(`${'metric'.padEnd(30)}${'default'.padStart(9)}${'variant'.padStart(9)}${'addressGap'.padStart(12)}`);
  const names = [...new Set([...Object.keys(a.metrics), ...Object.keys(b.metrics), ...Object.keys(a.agreement), ...Object.keys(b.agreement)])];
  for (const name of names) {
    const x = name in a.metrics ? a.metrics[name] : a.agreement[name] ?? null;
    const y = name in b.metrics ? b.metrics[name] : b.agreement[name] ?? null;
    const gap = x === null || y === null ? 'n/a' : (x - y).toFixed(2);
    out.push(`${name.padEnd(30)}${fmt(x).padStart(9)}${fmt(y).padStart(9)}${gap.padStart(12)}`);
  }
  const pairs = paired(a, b);
  out.push('', pairs.verdict);
  if (pairs.aOnly.length) out.push(`default-only passes: ${pairs.aOnly.join(', ')}`);
  if (pairs.bOnly.length) out.push(`variant-only passes: ${pairs.bOnly.join(', ')}`);
  return `${out.join('\n')}\n`;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const [first, second] = process.argv.slice(2);
  if (!first || !second) {
    console.error('usage: node tools/ai-eval-compare.mjs default-report.md variant-report.md');
    process.exit(2);
  }
  try {
    process.stdout.write(compare(fs.readFileSync(first, 'utf8'), fs.readFileSync(second, 'utf8')));
  } catch (e) {
    console.error(`ai-eval-compare: ${e.message}`);
    process.exit(1);
  }
}
