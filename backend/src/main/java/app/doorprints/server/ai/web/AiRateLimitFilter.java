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

package app.doorprints.server.ai.web;

import app.doorprints.server.config.ApiKeyFilter;
import app.doorprints.server.config.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Rate-limits the paths that cost LLM quota ({@code /api/ai/**}) and the MCP endpoint. Runs after
 * {@link ApiKeyFilter}, so the caller is already authenticated; the bucket key is a hash of the presented API key
 * (the raw key is never kept in memory maps or logs), falling back to the remote address.
 */
public class AiRateLimitFilter extends OncePerRequestFilter {

    private final TokenBucketRateLimiter aiLimiter;
    private final TokenBucketRateLimiter mcpLimiter;

    /**
     * @param aiLimiter  for /api/ai/** (every request may cost an LLM call)
     * @param mcpLimiter for /mcp, separate and more generous: one MCP session makes several protocol requests
     *                   (initialize, tools/list, notifications) before any tool runs
     */
    public AiRateLimitFilter(TokenBucketRateLimiter aiLimiter, TokenBucketRateLimiter mcpLimiter) {
        this.aiLimiter = aiLimiter;
        this.mcpLimiter = mcpLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // ApiKeyFilter has already rejected non-canonical paths, so plain prefix checks are safe here.
        var uri = RequestPaths.path(request);
        boolean limited = (RequestPaths.isUnder(uri, "/api/ai") && !uri.equals("/api/ai/status"))
                || RequestPaths.isUnder(uri, "/mcp");
        return !limited || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var limiter = RequestPaths.isUnder(RequestPaths.path(request), "/mcp") ? mcpLimiter : aiLimiter;
        var decision = limiter.tryAcquire(bucketKey(request));
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":429,\"detail\":\"AI rate limit exceeded, retry in "
                    + decision.retryAfterSeconds() + "s\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    static String bucketKey(HttpServletRequest request) {
        var key = ApiKeyFilter.presentedKey(request);
        if (key == null) return "ip:" + request.getRemoteAddr();
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "key:" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
