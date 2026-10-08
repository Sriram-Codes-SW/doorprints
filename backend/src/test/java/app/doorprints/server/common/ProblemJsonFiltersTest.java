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

package app.doorprints.server.common;

import app.doorprints.server.config.ApiKeyFilters;
import app.doorprints.server.config.ApiKeyFilter;
import app.doorprints.server.config.ApiRateLimitFilter;
import app.doorprints.server.config.RequestSizeLimitFilter;
import app.doorprints.server.device.DeviceAiGuard;
import app.doorprints.server.device.DeviceKeyStore;
import app.doorprints.server.device.OwnerAuth;
import app.doorprints.server.device.OwnerFilter;
import app.doorprints.server.device.PairingRateLimitFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each of the six filters that refuse a call answers (S4b-BL-164): the status, the media type, the body fields
 * and the headers. Written against the hand-copied writers first and kept green when they became one
 * {@link Problems}, so a drift between the copies (TC-S-08 needs every wrong key to get the same bytes) shows as a
 * failure here. The expected texts are the ones the apps and docs/03 quote.
 */
class ProblemJsonFiltersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "problem-json-" + UUID.randomUUID();

    private static MockHttpServletResponse run(Filter filter, MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static MockHttpServletRequest request(String method, String uri) {
        var request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.5");
        return request;
    }

    private static String mediaType(MockHttpServletResponse response) {
        return response.getContentType().split(";")[0].trim();
    }

    private static JsonNode body(MockHttpServletResponse response) throws Exception {
        return JSON.readTree(response.getContentAsString());
    }

    private static void assertProblem(MockHttpServletResponse response, int status, String detail) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(mediaType(response)).isEqualTo("application/problem+json");
        var node = body(response);
        assertThat(node.get("status").asInt()).isEqualTo(status);
        assertThat(node.get("detail").asString()).isEqualTo(detail);
        assertThat(node.has("code")).isFalse();
        assertThat(node.size()).isEqualTo(2);
    }

    private static long retryAfter(MockHttpServletResponse response) {
        return Long.parseLong(response.getHeader("Retry-After"));
    }

    @Test
    void apiRateLimitFilterAnswers429WithRetryAfter() throws Exception {
        var filter = new ApiRateLimitFilter(new TokenBucketRateLimiter(1, 1));
        assertThat(run(filter, request("GET", "/api/houses")).getStatus()).isEqualTo(200);
        var refused = run(filter, request("GET", "/api/houses"));
        assertProblem(refused, 429, "Rate limit exceeded, retry in " + retryAfter(refused) + "s");
        assertThat(retryAfter(refused)).isPositive();
    }

    @Test
    void apiKeyFilterAnswers401ForAMissingOrWrongKey() throws Exception {
        var filter = ApiKeyFilters.of(KEY, new TokenBucketRateLimiter(100, 100));
        var missing = run(filter, request("GET", "/api/houses"));
        assertProblem(missing, 401, "Missing or wrong X-API-Key");
        assertThat(missing.getHeader("Retry-After")).isNull();
        var wrong = request("GET", "/api/houses");
        wrong.addHeader("X-API-Key", "not-the-key");
        assertThat(run(filter, wrong).getContentAsString()).isEqualTo(missing.getContentAsString());
    }

    @Test
    void apiKeyFilterAnswers429WithRetryAfterOnceTheFailuresAreUsedUp() throws Exception {
        var filter = ApiKeyFilters.of(KEY, new TokenBucketRateLimiter(1, 1));
        assertThat(run(filter, request("GET", "/api/houses")).getStatus()).isEqualTo(401);
        var refused = run(filter, request("GET", "/api/houses"));
        assertProblem(refused, 429, "Too many failed attempts, retry in " + retryAfter(refused) + "s");
        assertThat(retryAfter(refused)).isPositive();
    }

    @Test
    void apiKeyFilterAnswers400ForANonCanonicalPath() throws Exception {
        var filter = ApiKeyFilters.of(KEY, new TokenBucketRateLimiter(100, 100));
        var response = run(filter, request("GET", "/api/%2e%2e/houses"));
        assertProblem(response, 400, "Malformed request path");
    }

    @Test
    void requestSizeLimitFilterAnswers413WithTheCap() throws Exception {
        var filter = new RequestSizeLimitFilter(10);
        var big = request("POST", "/api/houses");
        big.setContent(new byte[11]);
        var response = run(filter, big);
        assertProblem(response, 413, "Request body too large (max 10 bytes)");
    }

    @Test
    void pairingRateLimitFilterAnswers429WithRetryAfter() throws Exception {
        var filter = new PairingRateLimitFilter(new TokenBucketRateLimiter(1, 1));
        assertThat(run(filter, request("POST", "/api/pair/start")).getStatus()).isEqualTo(200);
        var refused = run(filter, request("POST", "/api/pair/start"));
        assertProblem(refused, 429, "Too many pairing requests, retry in " + retryAfter(refused) + "s");
        assertThat(retryAfter(refused)).isPositive();
    }

    @Test
    void ownerFilterAnswers405403And401() throws Exception {
        var filter = new OwnerFilter(new OwnerAuth(null, Clock.systemUTC()));
        assertProblem(run(filter, request("DELETE", "/owner/api/devices")), 405, "Method not allowed");
        assertProblem(run(filter, request("GET", "/owner/api/devices")), 403, "Use the owner page");
        var noSession = request("GET", "/owner/api/devices");
        noSession.addHeader(OwnerFilter.HEADER, "1");
        assertProblem(run(filter, noSession), 401, "Sign in to the owner page with the link from the server's log");
    }

    @Test
    void deviceAiGuardAnswers403WithACodeWhileAiIsPaused() throws Exception {
        var filter = new DeviceAiGuard(() -> true);
        var response = run(filter, request("POST", "/api/ai/ask"));
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(mediaType(response)).isEqualTo("application/problem+json");
        var node = body(response);
        assertThat(node.get("status").asInt()).isEqualTo(403);
        assertThat(node.get("code").asString()).isEqualTo("AI_PAUSED");
        assertThat(node.get("detail").asString()).isEqualTo("AI is paused on this server's owner page.");
        assertThat(node.size()).isEqualTo(3);
    }

    @Test
    void deviceAiGuardAnswers403WithACodeForADeviceWhoseAiIsOff() throws Exception {
        var filter = new DeviceAiGuard(() -> false);
        var request = request("POST", "/api/ai/ask");
        request.setAttribute(ApiKeyFilter.DEVICE_ATTRIBUTE, new DeviceKeyStore.Caller(UUID.randomUUID(), false));
        var response = run(filter, request);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(mediaType(response)).isEqualTo("application/problem+json");
        var node = body(response);
        assertThat(node.get("status").asInt()).isEqualTo(403);
        assertThat(node.get("code").asString()).isEqualTo("AI_OFF_FOR_DEVICE");
        assertThat(node.get("detail").asString()).isEqualTo("AI is turned off for this device on the server's owner page.");
        assertThat(node.size()).isEqualTo(3);
    }
}
