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

package app.doorprints.server.sync;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-08: clamp small future skew, reject absurd dates. */
class ClientClockTest {

    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private final ClientClock clock = new ClientClock(Clock.fixed(NOW, ZoneOffset.UTC), 300, 365);

    @Test
    void nullMeansNow() {
        assertThat(clock.accept(null, "updatedAt")).isEqualTo(NOW);
    }

    @Test
    void pastAndSmallSkewAreKept() {
        var past = NOW.minus(Duration.ofDays(3));
        assertThat(clock.accept(past, "updatedAt")).isEqualTo(past);
        var skew = NOW.plusSeconds(299);
        assertThat(clock.accept(skew, "updatedAt")).isEqualTo(skew);
    }

    @Test
    void futureBeyondSkewIsClampedToNow() {
        assertThat(clock.accept(NOW.plus(Duration.ofHours(5)), "updatedAt")).isEqualTo(NOW);
        assertThat(clock.accept(NOW.plus(Duration.ofDays(30)), "updatedAt")).isEqualTo(NOW);
    }

    @Test
    void absurdDatesAreRejected() {
        assertThatThrownBy(() -> clock.accept(Instant.parse("2030-01-02T00:00:00Z"), "updatedAt"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("updatedAt");
        assertThatThrownBy(() -> clock.accept(Instant.parse("1999-12-31T23:59:59Z"), "createdAt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.validate(Instant.EPOCH, "arrivedAt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eventTimesAreValidatedButNotClamped() {
        var soon = NOW.plus(Duration.ofHours(2));
        assertThat(clock.validate(soon, "leftAt")).isEqualTo(soon);
        assertThat(clock.validate(null, "leftAt")).isNull();
    }
}
