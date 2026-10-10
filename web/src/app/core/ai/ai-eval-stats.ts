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

/**
 * The Wilson score interval of a pass rate (S4b-BL-236, docs/ai/ai-design.md 8.6), the website's port of the server's
 * `Interval`: what a handful of cases can and cannot show. Informational, never compared with a threshold. 25 of 25 gives
 * a lower bound of 0.87; 0 of 35 an upper bound of 0.10.
 */

/** z for a two-sided 95% interval. */
const Z = 1.959963984540054;

/** `[lower, upper]` of the Wilson 95% interval of `k` passes in `n` cases; null when `n` is 0. */
export function wilson(k: number, n: number): [number, number] | null {
  if (n <= 0) return null;
  const p = k / n;
  const z2 = Z * Z;
  const denominator = 1 + z2 / n;
  const centre = (p + z2 / (2 * n)) / denominator;
  const half = (Z * Math.sqrt((p * (1 - p)) / n + z2 / (4 * n * n))) / denominator;
  return [snap(centre - half), snap(centre + half)];
}

/** Clamped to [0, 1]; a bound within rounding of 0 or 1 is that number. */
function snap(x: number): number {
  if (x < 1e-12) return 0;
  if (x > 1 - 1e-12) return 1;
  return x;
}

/** `0.97 (95% CI 0.83-1.00)`, or `n/a` when nothing was measured. */
export function ciLabel(k: number, n: number): string {
  const ci = wilson(k, n);
  if (!ci) return 'n/a';
  return `${(k / n).toFixed(2)} (95% CI ${ci[0].toFixed(2)}-${ci[1].toFixed(2)})`;
}

/** The interval alone, `0.83-1.00`, for a table column next to the counts; `n/a` for nothing measured. */
export function ciCell(k: number, n: number): string {
  const ci = wilson(k, n);
  return ci ? `${ci[0].toFixed(2)}-${ci[1].toFixed(2)}` : 'n/a';
}
