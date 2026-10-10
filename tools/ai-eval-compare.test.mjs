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
import { compare, readScorecard } from './ai-eval-compare.mjs';

const card = ({ address, metrics, agreement = '' }) => `# Doorprints AI eval scorecard

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
`;

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

test('refuses a file that is not a scorecard', () => {
  assert.throws(() => readScorecard('hello'), /not a scorecard/);
  assert.throws(() => readScorecard('# Doorprints AI eval scorecard\n\nnothing'), /no Metrics table/);
});
