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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The eval's time budget (S4b-BL-202): a point in time, fixed when the run starts, after which no new case or retry
 * begins. Pure: it only compares the injected clock, so it is unit-tested without sleeping ({@link DeadlineTest}).
 * The workflow's job limit sits above it, so a run that stops here still has time to write and upload its scorecard.
 */
final class Deadline {

    /** 35 minutes: a full run at default thinking takes about that long, and the job limit is 45 (ai-evals.yml). */
    static final long DEFAULT_MS = 35L * 60_000;

    private final Clock clock;
    private final Instant end;
    private final Duration budget;

    Deadline(Clock clock, Duration budget) {
        this.clock = clock;
        this.budget = budget;
        this.end = clock.instant().plus(budget);
    }

    /** True at the deadline and after it, never before. */
    boolean expired() {
        return !clock.instant().isBefore(end);
    }

    /** Time left, never negative. */
    Duration remaining() {
        var left = Duration.between(clock.instant(), end);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Throws {@link Expired} once the deadline has passed. */
    void check() {
        if (expired()) throw new Expired(budget);
    }

    /** {@code AI_EVAL_DEADLINE_MS}: a positive whole number of milliseconds, else the default. */
    static long parseMs(String value) {
        if (value == null || value.isBlank()) return DEFAULT_MS;
        try {
            long ms = Long.parseLong(value.strip());
            return ms > 0 ? ms : DEFAULT_MS;
        } catch (NumberFormatException e) {
            return DEFAULT_MS;
        }
    }

    /** The budget is used up: the run stops with {@link EvalScorer#TIME_STOPPED}; the case in flight is not scored. */
    static final class Expired extends RuntimeException {
        Expired(Duration budget) {
            super("time budget of " + budget.toMinutes() + " min (" + budget.toMillis() + " ms) used up");
        }
    }
}
