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

package app.doorprints.server.ai.eval;

/**
 * The Wilson score interval of a proportion (S4b-BL-236, docs/ai/ai-design.md 8.6): what the small golden set can and
 * cannot show. Informational: printed next to every metric value, never compared with a threshold. 25 of 25 injection
 * cases resisted gives 1.00 with a lower bound of 0.87; 0 of 35 hallucinated gives an upper bound of 0.10.
 */
final class Interval {

    /** z for a two-sided 95% interval. */
    static final double Z = 1.959963984540054;

    private Interval() {
    }

    /** {lower, upper} of the Wilson 95% interval of k successes in n trials; null when n is 0. */
    static double[] wilson(int k, int n) {
        if (n <= 0) return null;
        double p = (double) k / n;
        double z2 = Z * Z;
        double denominator = 1 + z2 / n;
        double centre = (p + z2 / (2 * n)) / denominator;
        double half = Z * Math.sqrt(p * (1 - p) / n + z2 / (4.0 * n * n)) / denominator;
        return new double[] {snap(centre - half), snap(centre + half)};
    }

    /** Clamped to [0, 1]; a bound within rounding of 0 or 1 is that number (0 of n has a lower bound of exactly 0). */
    private static double snap(double x) {
        if (x < 1e-12) return 0;
        if (x > 1 - 1e-12) return 1;
        return x;
    }

    /** {@code 0.97 (95% CI 0.83-1.00)}, or {@code n/a} when nothing was measured. */
    static String label(int k, int n) {
        var ci = wilson(k, n);
        if (ci == null) return "n/a";
        return EvalScorer.fmt((double) k / n) + " (95% CI " + EvalScorer.fmt(ci[0]) + "-" + EvalScorer.fmt(ci[1]) + ")";
    }
}
