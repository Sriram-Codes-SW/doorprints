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
import { describe, expect, it } from 'vitest';
import layers from '../../../../../docs/ai/evals/injection-layers.json';
import { type CaseResult, formatSummary } from './ai-eval';
import { injectionLayerRows, pipelineIds } from './ai-eval-layers';

const res = (id: string, passed: boolean, category?: string): CaseResult => ({ id, type: 'ask', category, passed, failures: [] });
const meta = { kind: 'k', host: '', model: '' };

describe('the injection split of the summary (S4b-BL-239)', () => {
  it('takes only the cases listed as pipeline; a case in no entry is model-dependent', () => {
    expect([...pipelineIds({ cases: [{ id: 'a', layer: 'pipeline' }, { id: 'b', layer: 'model' }] })]).toEqual(['a']);
    const rows = injectionLayerRows([res('a', true), res('b', false), res('hard-ask-99', true)], new Set(['a']));
    expect(rows).toEqual([
      '| prompt-injection, model-dependent | 1 | 2 | 0.09-0.91 |',
      '| prompt-injection, pipeline-guarded | 1 | 1 | 0.21-1.00 |',
    ]);
  });

  it('prints n/a for a group with no case', () => {
    expect(injectionLayerRows([res('b', true)], new Set(['a']))[1]).toBe('| prompt-injection, pipeline-guarded | 0 | 0 | n/a |');
  });

  it('puts the split right after the combined row in the summary, and only when the layers were handed in', () => {
    const results = [res('ask-09-injection-contact-leak', true, 'prompt-injection'), res('extract-04-injection', false, 'prompt-injection')];
    const ids = pipelineIds(layers);
    expect(ids.has('ask-09-injection-contact-leak')).toBe(true);
    const lines = formatSummary(meta, results, null, 'k', undefined, ids).split('\n');
    const at = lines.findIndex((l) => l.startsWith('| prompt-injection (all types)'));
    expect(at).toBeGreaterThan(0);
    expect(lines[at + 1]).toBe('| prompt-injection, model-dependent | 0 | 1 | 0.00-0.79 |');
    expect(lines[at + 2]).toBe('| prompt-injection, pipeline-guarded | 1 | 1 | 0.21-1.00 |');
    expect(formatSummary(meta, results, null, 'k')).not.toContain('model-dependent');
  });
});
