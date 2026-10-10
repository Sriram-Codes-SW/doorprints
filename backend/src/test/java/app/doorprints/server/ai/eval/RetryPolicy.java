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

import java.util.regex.Pattern;

/**
 * What the eval harness does with a failed call (S4b-BL-200), pure so {@link RetryPolicyTest} covers it. The server
 * says why it answered 503 in the problem's {@code cause} property:
 * <ul>
 *   <li>{@code provider} (HTTP error, timeout or connect failure of the model provider): retried while attempts remain,
 *   then {@link Decision#INFRA}: the case says nothing about the model, so it is excluded from every metric and the
 *   run is INCOMPLETE;</li>
 *   <li>{@code model} (output that could not be parsed): never retried, {@link Decision#SCORE}d as a failure;</li>
 *   <li>no cause (an older server, the rate limiter's own 429, a proxy): retried like a provider failure, but never
 *   excluded: after the last attempt it is scored, because nothing proves it was not the model;</li>
 *   <li>any other status: scored at once.</li>
 * </ul>
 * A provider quota error (code {@code AI_QUOTA_EXHAUSTED}) is decided before this policy and stops the run.
 */
final class RetryPolicy {

    static final String PROVIDER = "provider";
    static final String MODEL = "model";
    private static final Pattern CAUSE = Pattern.compile("\"cause\"\\s*:\\s*\"([a-z]*)\"");

    enum Decision { RETRY, INFRA, SCORE }

    private RetryPolicy() {
    }

    /** {@code attempt} counts from 1; {@code cause} is {@link #PROVIDER}, {@link #MODEL} or anything else (unknown). */
    static Decision decide(int status, String cause, int attempt, int maxAttempts) {
        if (status != 503 && status != 429) return Decision.SCORE;
        if (MODEL.equals(cause)) return Decision.SCORE;
        boolean last = attempt >= maxAttempts;
        if (!last) return Decision.RETRY;
        return PROVIDER.equals(cause) ? Decision.INFRA : Decision.SCORE;
    }

    /** The {@code cause} property of a problem-detail body, or null (no body, not JSON, property absent). */
    static String causeOf(String body) {
        if (body == null) return null;
        var m = CAUSE.matcher(body);
        return m.find() ? m.group(1) : null;
    }
}
