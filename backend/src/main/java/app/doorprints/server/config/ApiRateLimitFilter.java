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

import app.doorprints.server.common.Problems;
import app.doorprints.server.ai.web.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * General request rate limit per client address for every path except the public health check (F-05). Runs before
 * the API-key check so floods are cut off cheaply whether or not they carry a key. The stricter AI limit
 * ({@code AiRateLimitFilter}) and the failed-key limit ({@link ApiKeyFilter}) apply on top.
 *
 * <p>The client address is {@code getRemoteAddr()}; behind a PaaS proxy it is only the real client when
 * {@code server.forward-headers-strategy=native} and the proxy is trusted (see application.yml).
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter limiter;

    public ApiRateLimitFilter(TokenBucketRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return RequestPaths.isUnder(RequestPaths.path(request), "/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var decision = limiter.tryAcquire("ip:" + request.getRemoteAddr());
        if (!decision.allowed()) {
            Problems.write(response, 429, "Rate limit exceeded, retry in " + decision.retryAfterSeconds() + "s",
                    Map.of("Retry-After", Long.toString(decision.retryAfterSeconds())));
            return;
        }
        chain.doFilter(request, response);
    }
}
