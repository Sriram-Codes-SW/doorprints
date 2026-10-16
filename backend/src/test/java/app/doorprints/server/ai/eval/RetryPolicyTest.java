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

import app.doorprints.server.ai.eval.RetryPolicy.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** S4b-BL-200: the harness retries provider failures only, and a provider failure that persists is infra, not a score. */
class RetryPolicyTest {

    private static final int MAX = 3;

    @Test
    void aProviderFailureIsRetriedThenRecordedAsInfra() {
        assertThat(RetryPolicy.decide(503, "provider", 1, MAX)).isEqualTo(Decision.RETRY);
        assertThat(RetryPolicy.decide(503, "provider", 2, MAX)).isEqualTo(Decision.RETRY);
        assertThat(RetryPolicy.decide(503, "provider", 3, MAX)).isEqualTo(Decision.INFRA);
        assertThat(RetryPolicy.decide(429, "provider", 3, MAX)).isEqualTo(Decision.INFRA);
    }

    @Test
    void aModelFailureIsNeverRetriedAndIsScored() {
        for (int attempt = 1; attempt <= MAX; attempt++) {
            assertThat(RetryPolicy.decide(503, "model", attempt, MAX)).as("attempt %d", attempt).isEqualTo(Decision.SCORE);
        }
    }

    @Test
    void aFailureWithoutACauseIsRetriedButNeverExcludedFromTheScore() {
        // An older server, the rate limiter's own 429, a proxy: nothing says it was the provider, so it counts.
        assertThat(RetryPolicy.decide(503, null, 1, MAX)).isEqualTo(Decision.RETRY);
        assertThat(RetryPolicy.decide(503, null, 3, MAX)).isEqualTo(Decision.SCORE);
        assertThat(RetryPolicy.decide(429, "unknown", 3, MAX)).isEqualTo(Decision.SCORE);
    }

    @Test
    void otherStatusesAreScoredAtOnce() {
        for (int status : new int[] {400, 401, 404, 413, 500, 502, 504}) {
            assertThat(RetryPolicy.decide(status, "provider", 1, MAX)).as("status %d", status).isEqualTo(Decision.SCORE);
        }
    }

    @Test
    void theCauseIsReadFromTheProblemBody() {
        assertThat(RetryPolicy.causeOf("{\"status\":503,\"cause\":\"provider\",\"retryable\":true}")).isEqualTo("provider");
        assertThat(RetryPolicy.causeOf("{\"cause\" : \"model\"}")).isEqualTo("model");
        assertThat(RetryPolicy.causeOf("{\"status\":503}")).isNull();
        assertThat(RetryPolicy.causeOf("not json")).isNull();
        assertThat(RetryPolicy.causeOf(null)).isNull();
        // A word in the detail text is not the property.
        assertThat(RetryPolicy.causeOf("{\"detail\":\"the cause is provider\"}")).isNull();
    }
}
