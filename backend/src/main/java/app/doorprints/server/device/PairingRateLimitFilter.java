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

import app.doorprints.server.common.Problems;
import app.doorprints.server.common.TokenBucketRateLimiter;
import app.doorprints.server.config.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * The calls anyone can make without a key, {@code /api/pair/**} and signing in to the owner page, get their own bucket
 * per client address (docs/03 §12.1), besides the general limit: enough for a device polling every 3 seconds, not for
 * guessing.
 */
public class PairingRateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter limiter;

    public PairingRateLimitFilter(TokenBucketRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = RequestPaths.path(request);
        return !RequestPaths.isUnder(path, "/api/pair") && !path.equals("/owner/api/session");
    }

    /**
     * Answers 429 with Retry-After when the client address has used up its pairing allowance; preflights are not
     * counted.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (RequestPaths.isPreflight(request)) {
            chain.doFilter(request, response);
            return;
        }
        var decision = limiter.tryAcquire("pair:" + request.getRemoteAddr());
        if (!decision.allowed()) {
            Problems.write(response, 429, "Too many pairing requests, retry in " + decision.retryAfterSeconds() + "s",
                    Map.of("Retry-After", Long.toString(decision.retryAfterSeconds())));
            return;
        }
        chain.doFilter(request, response);
    }
}
