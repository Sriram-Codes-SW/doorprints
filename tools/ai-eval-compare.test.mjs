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
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { compare, paired, readScorecard, signTest } from './ai-eval-compare.mjs';

const card = ({ address, metrics, agreement = '', cases = '' }) => `# Doorprints AI eval scorecard

**Result: ${address ? 'NOT GATED' : 'PASS'}** (x)

| | |
|---|---|
| Golden set | v0.9 (2026-10-10), ../docs/ai/evals/golden-set.json |
${address ? `| Address set | ${address} |\n` : ''}| Cases | 3 / 3 passed |

## Metrics

| Metric | Value | n | Threshold | Status | Definition |
|---|---:|---:|---|---|---|
${metrics.map(([n, v]) => `| ${n} | ${v} | 1/2 | >= 0.90 | PASS | a \\| b |`).join('\n')}

${agreement}## Cases

| Case | Type | Category | Region | Result | Checks | Latency (ms) |
|---|---|---|---|---|---:|---:|
${cases}
## Details
`;

/** The Cases rows of a card: `results` maps case id to PASS, FAIL, ERROR or INFRA. */
const rows = (results) => Object.entries(results).map(([id, r]) => `| ${id} | extract | - | south | ${r} | 3/4 | 900 |`).join('\n');
/** n cases that all pass, plus the given overrides. */
const run = (n, overrides = {}) => {
  const results = {};
  for (let i = 1; i <= n; i++) results[`case-${String(i).padStart(2, '0')}`] = 'PASS';
  return { ...results, ...overrides };
};

const agreement = `## Agreement across trials (informational, not gated)

| Type | Most trials | Cases | All trials agree | Agreement | Trial pairs | Pairs that differ |
|---|---:|---:|---:|---:|---:|---:|
| extract | 3 | 34 | 31 | VALUE | 68 | 5 |

`;

test('reads the header, the metrics (n/a as null) and the agreement rows of a scorecard', () => {
  const c = readScorecard(card({ address: 'known-alt (address-variants v0.1, fingerprint 089350e09de9)', metrics: [['citationRecall', '0.80'], ['agentValidity', 'n/a']], agreement: agreement.replace('VALUE', '0.91') }));
  assert.equal(c.header['Address set'], 'known-alt (address-variants v0.1, fingerprint 089350e09de9)');
  assert.deepEqual(c.metrics, { citationRecall: 0.8, agentValidity: null });
  assert.deepEqual(c.agreement, { 'agreement(extract)': 0.91 });
});

test('prints, per metric, the default minus the variant, and n/a when either side has nothing', () => {
  const text = compare(
    card({ metrics: [['extractionFieldAccuracy', '0.95'], ['citationRecall', '0.80'], ['agentValidity', 'n/a']], agreement: agreement.replace('VALUE', '0.91') }),
    card({ address: 'unknown-invented (address-variants v0.1, fingerprint 361631beb5ed)', metrics: [['extractionFieldAccuracy', '0.85'], ['citationRecall', '0.90'], ['agentValidity', '1.00']], agreement: agreement.replace('VALUE', '0.85') }),
  );
  assert.match(text, /variant:  unknown-invented \(address-variants v0\.1, fingerprint 361631beb5ed\)/);
  assert.match(text, /default:  default run/);
  assert.match(text, /extractionFieldAccuracy\s+0\.95\s+0\.85\s+0\.10\n/);
  assert.match(text, /citationRecall\s+0\.80\s+0\.90\s+-0\.10\n/);
  assert.match(text, /agentValidity\s+n\/a\s+1\.00\s+n\/a\n/);
  assert.match(text, /agreement\(extract\)\s+0\.91\s+0\.85\s+0\.06\n/);
});

test('says so when the two files are not a default run and an address-set run', () => {
  const plain = card({ metrics: [['citationRecall', '0.80']] });
  const text = compare(plain, plain);
  assert.match(text, /variant:  NOT an address-set run/);
  assert.match(compare(card({ address: 'messy (x)', metrics: [['citationRecall', '0.8']] }), plain), /NOT a default run/);
});

test('the exact two-sided sign test: 8 of 8 one way is 0.0078, 7 of 8 is 0.0703, none is null', () => {
  // Hand-computed: 2 * (1/256) and 2 * (1 + 8)/256.
  assert.ok(Math.abs(signTest(8, 8) - 0.0078125) < 1e-9);
  assert.ok(Math.abs(signTest(0, 8) - 0.0078125) < 1e-9);
  assert.ok(Math.abs(signTest(7, 8) - 0.0703125) < 1e-9);
  assert.ok(Math.abs(signTest(5, 8) - 0.7265625) < 1e-9);
  assert.equal(signTest(0, 0), null);
  assert.equal(signTest(1, 2), 1);
});

test('the paired verdict counts only the discordant pairs of cases scored on both sides and reads the sign test', () => {
  // 10 cases: 8 pass by default and fail in the variant, 1 the other way; one ERROR pair and one INFRA pair are left out.
  const a = readScorecard(card({ metrics: [['x', '1']], cases: rows(run(12, { 'case-10': 'FAIL', 'case-11': 'ERROR', 'case-12': 'PASS' })) }));
  const b = readScorecard(card({ address: 'messy (x)', metrics: [['x', '1']], cases: rows(run(12, { 'case-01': 'FAIL', 'case-02': 'FAIL', 'case-03': 'FAIL', 'case-04': 'FAIL', 'case-05': 'FAIL', 'case-06': 'FAIL', 'case-07': 'FAIL', 'case-08': 'FAIL', 'case-10': 'PASS', 'case-11': 'PASS', 'case-12': 'INFRA' })) }));
  const p = paired(a, b);
  assert.equal(p.compared, 10);
  assert.equal(p.discordant, 9);
  assert.deepEqual(p.aOnly, ['case-01', 'case-02', 'case-03', 'case-04', 'case-05', 'case-06', 'case-07', 'case-08']);
  assert.deepEqual(p.bOnly, ['case-10']);
  // 8:1 of 9: 2 * (1 + 9)/512 = 0.0390625, significant.
  assert.ok(Math.abs(p.p - 0.0390625) < 1e-9);
  assert.equal(p.verdict, 'PAIRED: 10 cases scored in both, 9 discordant pairs (8 default-only passes, 1 variant-only), exact two-sided sign test p = 0.039: the default is better, significant at 0.05');

  // 7:1 of 8 is not significant; the verdict says so and names no winner.
  const c = readScorecard(card({ metrics: [['x', '1']], cases: rows(run(9, { 'case-09': 'FAIL' })) }));
  const d = readScorecard(card({ metrics: [['x', '1']], cases: rows(run(9, { 'case-01': 'FAIL', 'case-02': 'FAIL', 'case-03': 'FAIL', 'case-04': 'FAIL', 'case-05': 'FAIL', 'case-06': 'FAIL', 'case-07': 'FAIL' })) }));
  assert.match(paired(c, d).verdict, /8 discordant pairs \(7 default-only passes, 1 variant-only\), exact two-sided sign test p = 0\.070: no significant difference at 0\.05$/);
  // The variant can be the better one.
  const e = readScorecard(card({ metrics: [['x', '1']], cases: rows(run(8, { 'case-01': 'FAIL', 'case-02': 'FAIL', 'case-03': 'FAIL', 'case-04': 'FAIL', 'case-05': 'FAIL', 'case-06': 'FAIL', 'case-07': 'FAIL', 'case-08': 'FAIL' })) }));
  const f = readScorecard(card({ metrics: [['x', '1']], cases: rows(run(8)) }));
  assert.match(paired(e, f).verdict, /8 discordant pairs \(0 default-only passes, 8 variant-only\), exact two-sided sign test p = 0\.008: the variant is better, significant at 0\.05$/);
});

test('no discordant pair is "no difference observed", never "the same", and no common case says so', () => {
  const a = readScorecard(card({ metrics: [['x', '1']], cases: rows(run(3)) }));
  assert.equal(paired(a, a).verdict, 'PAIRED: 3 cases scored in both, 0 discordant pairs: no difference observed (not evidence of equality; the interval on each metric says what 3 cases can show)');
  const none = readScorecard(card({ metrics: [['x', '1']] }));
  assert.equal(paired(a, none).verdict, 'PAIRED: no case scored in both files');
  assert.deepEqual(none.cases, {});
});

test('compare prints the paired verdict and the discordant ids after the metric table', () => {
  const text = compare(
    card({ metrics: [['citationRecall', '0.80']], cases: rows(run(3)) }),
    card({ address: 'messy (x)', metrics: [['citationRecall', '0.90']], cases: rows(run(3, { 'case-02': 'FAIL' })) }),
  );
  assert.match(text, /\nPAIRED: 3 cases scored in both, 1 discordant pairs \(1 default-only passes, 0 variant-only\), exact two-sided sign test p = 1\.000: no significant difference at 0\.05\n/);
  assert.match(text, /\ndefault-only passes: case-02\n/);
  assert.doesNotMatch(text, /variant-only passes:/);
});

test('refuses a file that is not a scorecard', () => {
  assert.throws(() => readScorecard('hello'), /not a scorecard/);
  assert.throws(() => readScorecard('# Doorprints AI eval scorecard\n\nnothing'), /no Metrics table/);
});
