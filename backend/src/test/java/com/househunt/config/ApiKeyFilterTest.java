package com.househunt.config;

import com.househunt.ai.web.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** F-20: deny by default, canonical paths only, explicit allowlist. */
class ApiKeyFilterTest {

    private static final String KEY = "unit-test-key-0123456789";

    private final ApiKeyFilter filter = new ApiKeyFilter(KEY, new TokenBucketRateLimiter(3, 3));

    private record Outcome(int status, boolean passed) {
    }

    private Outcome run(String method, String uri, String key) throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.7");
        if (key != null) request.addHeader("X-API-Key", key);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response.getStatus(), chain.getRequest() != null);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api;x/houses", "/api/houses;jsessionid=1", "/%61pi/houses", "/api/%2e%2e/houses", "/api%2fhouses",
            "//api/houses", "/api//houses", "/api/./houses", "/api/../api/houses", "/api/houses.", "/api/houses/",
            "/actuator/health;x", "/actuator/health/", "/actuator/health/..%2f..%2fapi%2fhouses", "/api\\houses",
            "/actuator/health/../../api/houses", "/api/houses/%20"})
    void rejectsNonCanonicalPathsEvenWithTheKey(String uri) throws Exception {
        var outcome = run("GET", uri, KEY);
        assertThat(outcome.status()).isEqualTo(400);
        assertThat(outcome.passed()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/houses", "/API/houses", "/mcp", "/", "/error", "/actuator/env", "/api/ai/status",
            "/actuatorx/health", "/actuator/healthz"})
    void everythingOutsideTheAllowlistNeedsTheKey(String uri) throws Exception {
        assertThat(run("GET", uri, null)).isEqualTo(new Outcome(401, false));
        assertThat(ApiKeyFilter.isPublic(new MockHttpServletRequest("GET", uri), uri)).isFalse();
    }

    @Test
    void healthIsPublicForGetAndHeadOnly() throws Exception {
        assertThat(run("GET", "/actuator/health", null)).isEqualTo(new Outcome(200, true));
        assertThat(run("HEAD", "/actuator/health/liveness", null)).isEqualTo(new Outcome(200, true));
        assertThat(run("POST", "/actuator/health", null).status()).isEqualTo(401);
    }

    @Test
    void onlyRealCorsPreflightsSkipTheKey() throws Exception {
        var preflight = new MockHttpServletRequest("OPTIONS", "/api/houses");
        preflight.addHeader("Origin", "https://example.org");
        preflight.addHeader("Access-Control-Request-Method", "PUT");
        var chain = new MockFilterChain();
        filter.doFilter(preflight, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();

        assertThat(run("OPTIONS", "/api/houses", null).status()).isEqualTo(401);
    }

    @Test
    void acceptsTheKeyAndBearerTokens() throws Exception {
        assertThat(run("GET", "/api/houses", KEY)).isEqualTo(new Outcome(200, true));
        var bearer = new MockHttpServletRequest("GET", "/mcp");
        bearer.addHeader("Authorization", "Bearer " + KEY);
        var chain = new MockFilterChain();
        filter.doFilter(bearer, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void throttlesRepeatedWrongKeysButNeverTheRightOne() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(run("GET", "/api/houses", "wrong-key").status()).isEqualTo(401);
        var request = new MockHttpServletRequest("GET", "/api/houses");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-API-Key", "wrong-again");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(run("GET", "/api/houses", KEY)).isEqualTo(new Outcome(200, true));
    }

    @Test
    void canonicalPathRules() {
        assertThat(RequestPaths.isCanonical("/api/houses/6f1c2a9e-0000-4000-8000-000000000000")).isTrue();
        assertThat(RequestPaths.isCanonical("/")).isTrue();
        assertThat(RequestPaths.isCanonical("")).isFalse();
        assertThat(RequestPaths.isCanonical("api/houses")).isFalse();
        assertThat(RequestPaths.isCanonical("/api/houéses")).isFalse();
        assertThat(RequestPaths.isUnder("/api/ai/ask", "/api/ai")).isTrue();
        assertThat(RequestPaths.isUnder("/api/aix", "/api/ai")).isFalse();
    }
}
