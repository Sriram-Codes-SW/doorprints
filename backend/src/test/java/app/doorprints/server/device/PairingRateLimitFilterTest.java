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

import app.doorprints.server.common.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pairing limit (docs/03 §12.1): the calls that need no key get their own bucket per client address, and a full
 * bucket answers 429 with Retry-After.
 */
class PairingRateLimitFilterTest {

    private final PairingRateLimitFilter filter = new PairingRateLimitFilter(new TokenBucketRateLimiter(3, 3));

    private MockHttpServletResponse run(String method, String uri, String address) throws Exception {
        var request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(address);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aFullBucketAnswers429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(run("POST", "/api/pair/redeem", "203.0.113.7").getStatus()).isEqualTo(200);
        }
        var refused = run("POST", "/api/pair/poll", "203.0.113.7");
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getHeader("Retry-After")).isNotBlank();
        assertThat(refused.getContentAsString()).contains("Too many pairing requests");
        // Owner sign-in shares the bucket; another address has its own.
        assertThat(run("POST", "/owner/api/session", "203.0.113.7").getStatus()).isEqualTo(429);
        assertThat(run("POST", "/api/pair/start", "198.51.100.9").getStatus()).isEqualTo(200);
    }

    @Test
    void otherPathsAndPreflightsAreNotCounted() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(run("GET", "/api/houses", "203.0.113.8").getStatus()).isEqualTo(200);
        }
        for (int i = 0; i < 10; i++) {
            var preflight = new MockHttpServletRequest("OPTIONS", "/api/pair/start");
            preflight.setRemoteAddr("203.0.113.8");
            preflight.addHeader("Origin", "http://localhost:4200");
            preflight.addHeader("Access-Control-Request-Method", "POST");
            var response = new MockHttpServletResponse();
            filter.doFilter(preflight, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(run("POST", "/api/pair/start", "203.0.113.8").getStatus()).isEqualTo(200);
    }
}
