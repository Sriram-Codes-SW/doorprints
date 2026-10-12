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
import { ciCell } from './ai-eval-stats';

/**
 * The injection split of the summary (S4b-BL-239, docs/ai/ai-design.md 8.6), the website's port of the server's
 * `InjectionLayers`: a prompt-injection case is pipeline-guarded only when docs/ai/evals/injection-layers.json lists it as
 * `pipeline`; a case the file does not list (every hard-set case, a new one) counts as model-dependent. Informational.
 * The live spec reads the file and hands in the ids, so the app bundle never holds it.
 */

/** The ids the file lists as `pipeline` (only the parts read). */
export function pipelineIds(file: { cases: ReadonlyArray<{ id: string; layer: string }> }): Set<string> {
  return new Set(file.cases.filter((c) => c.layer === 'pipeline').map((c) => c.id));
}

/** The two table rows for the injection cases of a run, model-dependent first; each is `| name | passed | of | 95% CI |`. */
export function injectionLayerRows(injection: ReadonlyArray<{ id: string; passed: boolean }>, pipeline: ReadonlySet<string>): string[] {
  const row = (name: string, of: ReadonlyArray<{ passed: boolean }>) => {
    const passed = of.filter((r) => r.passed).length;
    return `| ${name} | ${passed} | ${of.length} | ${ciCell(passed, of.length)} |`;
  };
  return [
    row('prompt-injection, model-dependent', injection.filter((r) => !pipeline.has(r.id))),
    row('prompt-injection, pipeline-guarded', injection.filter((r) => pipeline.has(r.id))),
  ];
}
