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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The eval's time budget (S4b-BL-202): a pure clock comparison, no sleeping. */
class DeadlineTest {

    /** A clock the test moves by hand. */
    private static final class Manual extends Clock {
        Instant now = Instant.parse("2026-10-10T00:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void doesNotFireBeforeTheBudgetIsUsedUp() {
        var clock = new Manual();
        var deadline = new Deadline(clock, Duration.ofMinutes(35));

        assertThat(deadline.expired()).isFalse();
        clock.now = clock.now.plus(Duration.ofMinutes(35)).minusMillis(1);
        assertThat(deadline.expired()).isFalse();
        assertThatCode(deadline::check).doesNotThrowAnyException();
        assertThat(deadline.remaining()).isEqualTo(Duration.ofMillis(1));
    }

    @Test
    void firesAtTheBudgetAndAfterIt() {
        var clock = new Manual();
        var deadline = new Deadline(clock, Duration.ofMinutes(35));

        clock.now = clock.now.plus(Duration.ofMinutes(35));
        assertThat(deadline.expired()).isTrue();
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
        assertThatThrownBy(deadline::check).isInstanceOf(Deadline.Expired.class).hasMessageContaining("35");

        clock.now = clock.now.plusSeconds(600);
        assertThat(deadline.expired()).isTrue();
        assertThat(deadline.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    void countsFromTheMomentItWasCreated() {
        var clock = new Manual();
        clock.now = clock.now.plus(Duration.ofHours(5));
        var deadline = new Deadline(clock, Duration.ofSeconds(10));

        assertThat(deadline.expired()).isFalse();
        clock.now = clock.now.plusSeconds(10);
        assertThat(deadline.expired()).isTrue();
    }

    @Test
    void theDefaultIsThirtyFiveMinutesAndAnEmptyOrBadValueFallsBackToIt() {
        assertThat(Deadline.DEFAULT_MS).isEqualTo(35L * 60_000);
        assertThat(Deadline.parseMs(null)).isEqualTo(Deadline.DEFAULT_MS);
        assertThat(Deadline.parseMs(" ")).isEqualTo(Deadline.DEFAULT_MS);
        assertThat(Deadline.parseMs("soon")).isEqualTo(Deadline.DEFAULT_MS);
        assertThat(Deadline.parseMs("-5")).isEqualTo(Deadline.DEFAULT_MS);
        assertThat(Deadline.parseMs("90000")).isEqualTo(90_000);
    }
}
