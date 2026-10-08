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

package app.doorprints.server.config;

import app.doorprints.server.common.TokenBucketRateLimiter;

import java.util.Optional;

/** Builds an {@link ApiKeyFilter} for a test with no device keys and, unless given, a bucket of 10 failures. */
public final class ApiKeyFilters {

    private ApiKeyFilters() {
    }

    public static ApiKeyFilter of(String apiKey) {
        return of(apiKey, new TokenBucketRateLimiter(10, 10));
    }

    public static ApiKeyFilter of(String apiKey, TokenBucketRateLimiter failures) {
        return of(apiKey, null, failures);
    }

    public static ApiKeyFilter of(String apiKey, String nextKey, TokenBucketRateLimiter failures) {
        return new ApiKeyFilter(apiKey, nextKey, failures, key -> Optional.empty());
    }
}
