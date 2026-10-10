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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The Wilson intervals (S4b-BL-236). The expected bounds were computed apart from the code under test (an independent
 * Python calculation with z = 1.959964, four decimals), not read back from it.
 */
class IntervalTest {

    @Test
    void everyPerfectScoreOfTheGoldenSetHasTheLowerBoundTheConsultStated() {
        assertThat(Interval.wilson(25, 25)[0]).isCloseTo(0.8668, within(0.0001)); // injection cases
        assertThat(Interval.wilson(10, 10)[0]).isCloseTo(0.7225, within(0.0001)); // plan cases
        assertThat(Interval.wilson(5, 5)[0]).isCloseTo(0.5655, within(0.0001));   // refusal cases
        assertThat(Interval.wilson(100, 100)[0]).isCloseTo(0.9630, within(0.0001));
        assertThat(Interval.wilson(217, 217)[0]).isCloseTo(0.9826, within(0.0001)); // expected fields
        assertThat(Interval.wilson(25, 25)[1]).isEqualTo(1.0);
    }

    @Test
    void zeroHallucinationsIn35FieldsBoundsTheRateAtTenPercent() {
        var ci = Interval.wilson(0, 35);
        assertThat(ci[0]).isEqualTo(0.0);
        assertThat(ci[1]).isCloseTo(0.0989, within(0.0001));
    }

    @Test
    void aMiddlingScoreHasBothBoundsInside() {
        var ci = Interval.wilson(8, 10);
        assertThat(ci[0]).isCloseTo(0.4902, within(0.0001));
        assertThat(ci[1]).isCloseTo(0.9433, within(0.0001));
        var agreement = Interval.wilson(31, 34);
        assertThat(agreement[0]).isCloseTo(0.7704, within(0.0001));
        assertThat(agreement[1]).isCloseTo(0.9695, within(0.0001));
    }

    @Test
    void theLabelIsTheValueThenTheIntervalAndNothingWhenNothingWasMeasured() {
        assertThat(Interval.label(31, 34)).isEqualTo("0.91 (95% CI 0.77-0.97)");
        assertThat(Interval.label(25, 25)).isEqualTo("1.00 (95% CI 0.87-1.00)");
        assertThat(Interval.label(0, 35)).isEqualTo("0.00 (95% CI 0.00-0.10)");
        assertThat(Interval.label(0, 0)).isEqualTo("n/a");
        assertThat(Interval.wilson(3, 0)).isNull();
    }
}
