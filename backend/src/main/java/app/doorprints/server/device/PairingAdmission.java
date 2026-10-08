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

package app.doorprints.server.device;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Whether a new pairing request may open (S4b-BL-161, docs/03 section 12.1). {@code POST /api/pair/start} needs no
 * key, so the number of open requests is capped, and a start over a cap is refused: the older requests, one of which a
 * person may be typing at that moment, are never pushed out. Only unexpired pending requests count; they free
 * themselves within {@link PairingService#CODE_LIFETIME}.
 *
 * <p>No source can use up the table: {@value #MAX_OPEN_PER_CLIENT} open requests per source, and
 * {@value #MAX_OPEN} in all. A source is the client address as the server sees it (docs/08 section 1.2), so clients
 * behind one proxy share one allowance.
 */
final class PairingAdmission {

    static final int MAX_OPEN = 50;
    static final int MAX_OPEN_PER_CLIENT = 5;

    private PairingAdmission() {
    }

    /**
     * The open requests now: all of them, and this source's. The expiry is the soonest one of each group (null when
     * the group is empty).
     */
    record Open(int total, Instant earliestExpiry, int fromClient, Instant clientEarliestExpiry) {
    }

    /** Empty when the start may go ahead, else the seconds after which a slot frees up (at least 1). */
    static OptionalLong retryAfterSeconds(Open open, Instant now) {
        long wait = 0;
        if (open.total() >= MAX_OPEN) wait = Math.max(wait, secondsUntil(open.earliestExpiry(), now));
        if (open.fromClient() >= MAX_OPEN_PER_CLIENT) wait = Math.max(wait, secondsUntil(open.clientEarliestExpiry(), now));
        return wait == 0 ? OptionalLong.empty() : OptionalLong.of(wait);
    }

    /** Whole seconds until the instant, rounded up, at least 1, at most the life of a code. */
    private static long secondsUntil(Instant expiry, Instant now) {
        var millis = Duration.between(now, expiry).toMillis();
        var seconds = Math.floorDiv(millis + 999, 1000);
        return Math.min(Math.max(seconds, 1), PairingService.CODE_LIFETIME.toSeconds());
    }
}
