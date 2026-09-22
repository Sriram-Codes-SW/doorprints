package com.househunt.config;

import com.househunt.ai.web.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-20: deny by default, canonical paths only, explicit allowlist. */
class ApiKeyFilterTest {

    /** Generated per run: no key-like literal in source for secret scanners to flag. 41 chars (>= 32, F-01). */
    private static final String KEY = "unit-" + UUID.randomUUID();
    /** Second key for the rotation tests (SEC-017). */
    private static final String NEXT_KEY = "next-" + UUID.randomUUID();

    private final ApiKeyFilter filter = new ApiKeyFilter(KEY, new TokenBucketRateLimiter(3, 3));

    private record Outcome(int status, boolean passed) {
    }

    private Outcome run(String method, String uri, String key) throws Exception {
        return run(filter, method, uri, key);
    }

    private static Outcome run(ApiKeyFilter filter, String method, String uri, String key) throws Exception {
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
    void testKeysMeetTheMinimumLength() {
        assertThat(KEY.length()).isGreaterThanOrEqualTo(ApiKeyFilter.MIN_KEY_LENGTH);
        assertThat(NEXT_KEY.length()).isGreaterThanOrEqualTo(ApiKeyFilter.MIN_KEY_LENGTH);
    }

    /** F-01: missing or short keys stop startup with a message that names the variable but not the value. */
    @Test
    void refusesMissingOrShortKeys() {
        var shortKey = "s".repeat(ApiKeyFilter.MIN_KEY_LENGTH - 1);
        assertThatThrownBy(() -> ApiKeyFilter.validateKeys(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_API_KEY is not set");
        assertThatThrownBy(() -> ApiKeyFilter.validateKeys("   ", null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_API_KEY is not set");
        assertThatThrownBy(() -> ApiKeyFilter.validateKeys(shortKey, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_API_KEY is too short")
                .hasMessageContaining("at least 32 characters")
                .hasMessageNotContaining(shortKey);
        assertThatThrownBy(() -> new ApiKeyFilter(shortKey)).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ApiKeyFilter.validateKeys("k".repeat(ApiKeyFilter.MIN_KEY_LENGTH), null))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesShortNextKey() {
        var shortNext = "n".repeat(ApiKeyFilter.MIN_KEY_LENGTH - 1);
        assertThatThrownBy(() -> ApiKeyFilter.validateKeys(KEY, shortNext))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_API_KEY_NEXT is too short")
                .hasMessageNotContaining(shortNext);
    }

    /** SEC-017: an unset APP_API_KEY_NEXT (empty from the ${APP_API_KEY_NEXT:} placeholder) means one key only. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankNextKeyIsIgnored(String next) throws Exception {
        var single = new ApiKeyFilter(KEY, next, new TokenBucketRateLimiter(100, 100));
        assertThat(run(single, "GET", "/api/houses", KEY)).isEqualTo(new Outcome(200, true));
        assertThat(run(single, "GET", "/api/houses", "")).isEqualTo(new Outcome(401, false));
        assertThat(run(single, "GET", "/api/houses", "   ")).isEqualTo(new Outcome(401, false));
    }

    /** SEC-017: during a rotation both the current and the next key work; anything else is still refused. */
    @Test
    void acceptsBothKeysDuringRotation() throws Exception {
        var rotating = new ApiKeyFilter(KEY, NEXT_KEY, new TokenBucketRateLimiter(100, 100));
        assertThat(run(rotating, "GET", "/api/houses", KEY)).isEqualTo(new Outcome(200, true));
        assertThat(run(rotating, "GET", "/api/houses", NEXT_KEY)).isEqualTo(new Outcome(200, true));

        var bearer = new MockHttpServletRequest("GET", "/mcp");
        bearer.addHeader("Authorization", "Bearer " + NEXT_KEY);
        var chain = new MockFilterChain();
        rotating.doFilter(bearer, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();

        assertThat(run(rotating, "GET", "/api/houses", null)).isEqualTo(new Outcome(401, false));
        assertThat(run(rotating, "GET", "/api/houses", "wrong-key")).isEqualTo(new Outcome(401, false));
        // Prefixes, concatenations and case variants of a valid key are not valid.
        assertThat(run(rotating, "GET", "/api/houses", KEY.substring(0, KEY.length() - 1)).status()).isEqualTo(401);
        assertThat(run(rotating, "GET", "/api/houses", KEY + NEXT_KEY).status()).isEqualTo(401);
        assertThat(run(rotating, "GET", "/api/houses", NEXT_KEY.toUpperCase()).status()).isEqualTo(401);
    }

    /** Without a next key configured, a would-be next key is just a wrong key. */
    @Test
    void nextKeyIsNotAcceptedUnlessConfigured() throws Exception {
        assertThat(run("GET", "/api/houses", NEXT_KEY)).isEqualTo(new Outcome(401, false));
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
