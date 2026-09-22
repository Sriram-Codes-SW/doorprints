package com.househunt.config;

import com.househunt.ai.web.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(decision.retryAfterSeconds()));
            response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":429,\"detail\":\"Rate limit exceeded, retry in "
                    + decision.retryAfterSeconds() + "s\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
