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

// Pentest observation O-1 (docs/ops/pentest-2026-10-21.md): the Markdown copy escaped a question's answer twice (`\\\<img`), because
// `row()` already escapes every cell and the question, room, viewing, area-note and distance rows were escaped before it. A cell is
// escaped once, and the characters that make markup, a link or a table cell are still neutralised.
import { describe, expect, it } from 'vitest';
import { DICTIONARIES } from '../i18n/all-dictionaries';
import { collect } from './export-model';
import { buildMarkdown } from './markdown-export';
import { FIXTURE_EXPORTED_AT, FIXTURE_OPTIONS, FIXTURE_PLAIN_HOUSES, FIXTURE_PLAIN_PHOTOS, FIXTURE_VISITS } from './golden/fixture';

const en = DICTIONARIES.en;

function copyWith(answer: string): string {
  const [first, ...others] = FIXTURE_PLAIN_HOUSES;
  const house = { ...first, answers: [{ id: 'a1111111-1111-4111-8111-111111111111', questionId: 'qd_x', text: 'Is it quiet?', answer, status: 'ANSWERED' as const, sort: 0 }] };
  return buildMarkdown(collect({ houses: [house, ...others], visits: FIXTURE_VISITS, photos: FIXTURE_PLAIN_PHOTOS, exportedAt: FIXTURE_EXPORTED_AT, options: FIXTURE_OPTIONS }), en);
}
const answerLine = (md: string) => md.split('\n').find((l) => l.startsWith('| Is it quiet?'))!;

describe('a question’s answer in the Markdown copy is escaped once', () => {
  it('writes `<img src=x` as `\\<img src=x`, not `\\\\\\<img`', () => {
    const line = answerLine(copyWith('<img src=x'));
    expect(line).toContain('| \\<img src=x |');
    expect(line).not.toContain('\\\\');
  });

  it('keeps markup, a link, a table pipe, a formula and a heading marker neutralised', () => {
    const line = answerLine(copyWith('[click](javascript:alert(1)) | *bold* `x` =SUM(A1)'));
    expect(line).toContain('\\[click\\](javascript:alert(1)) \\| \\*bold\\* \\`x\\` =SUM(A1)');
    // The pipe is escaped, so the row still has the question, the answer and the status: four pipes in all.
    expect(line.replace(/\\\|/g, '').split('|').length - 1).toBe(4);
    expect(answerLine(copyWith('# not a heading'))).toContain('| \\# not a heading |');
    expect(answerLine(copyWith('a\\b'))).toContain('| a\\\\b |');
  });
});
