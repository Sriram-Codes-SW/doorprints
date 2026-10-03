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
import schemasReadme from '../../../../../docs/schemas/README.md' with { loader: 'text' };
import sprintLog from '../../../../../docs/10-sprint-log.md' with { loader: 'text' };
import testPlan from '../../../../../docs/06-test-plan.md' with { loader: 'text' };
import driveDoc from '../../../../../docs/15-google-drive-backup-and-sharing.md' with { loader: 'text' };
import docsIndex from '../../../../../docs/README.md' with { loader: 'text' };

function changelogVersions(text: string): string[] {
  const block = text.split('## Change log')[1]?.split('\n## ')[0] ?? '';
  return [...block.matchAll(/^\| (\d+\.\d+) \|/gm)].map((m) => m[1]);
}

describe('docs change-log tables this Drive work touches', () => {
  it('schemas README 1.6..1.24 block is ascending and has no duplicate 1.20', () => {
    const versions = changelogVersions(schemasReadme);
    const start = versions.indexOf('1.6');
    const end = versions.indexOf('1.24');
    expect(start).toBeGreaterThanOrEqual(0);
    expect(end).toBeGreaterThan(start);
    const block = versions.slice(start, end + 1);
    expect(block).toEqual([
      '1.6', '1.7', '1.8', '1.9', '1.10', '1.11', '1.12', '1.13', '1.14', '1.15',
      '1.16', '1.17', '1.18', '1.19', '1.20', '1.21', '1.22', '1.23', '1.24',
    ]);
    expect(versions.filter((v) => v === '1.20')).toEqual(['1.20']);
  });

  it('docs this Drive work touches have unique versions; 15 is oldest-first', () => {
    const files: [string, string][] = [
      ['10-sprint-log', sprintLog],
      ['06-test-plan', testPlan],
      ['15-google-drive', driveDoc],
      ['docs/README', docsIndex],
    ];
    for (const [name, text] of files) {
      const versions = changelogVersions(text);
      expect(versions.length, name).toBeGreaterThan(2);
      expect(new Set(versions).size, `duplicates in ${name}`).toBe(versions.length);
    }
    // 10, 06 and README keep a stacked reverse block (same convention as schemas 1.5..1.0).
    const fifteen = changelogVersions(driveDoc);
    expect(fifteen).toEqual(['0.1', '0.2', '0.3', '0.4', '0.5', '0.6', '0.7', '0.8', '0.9', '0.10', '0.11', '0.12', '0.13', '0.14', '0.15', '0.16', '0.17', '0.18', '0.19']);
  });
});
