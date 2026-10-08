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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The shared writer of the filters' problem+json answers (S4b-BL-164): bytes, escaping, headers. */
class ProblemsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void writesTheStatusTheMediaTypeAndTheExactBody() throws Exception {
        var response = new MockHttpServletResponse();
        Problems.write(response, 401, "Missing or wrong X-API-Key");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(response.getContentAsString()).isEqualTo("{\"status\":401,\"detail\":\"Missing or wrong X-API-Key\"}");
    }

    @Test
    void aCodeComesBetweenTheStatusAndTheDetail() throws Exception {
        var response = new MockHttpServletResponse();
        Problems.write(response, 403, "AI_PAUSED", "AI is paused.", Map.of());
        assertThat(response.getContentAsString())
                .isEqualTo("{\"status\":403,\"code\":\"AI_PAUSED\",\"detail\":\"AI is paused.\"}");
    }

    @Test
    void headersAreSet() throws Exception {
        var response = new MockHttpServletResponse();
        Problems.write(response, 429, "Slow down", Map.of("Retry-After", "7"));
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("7");
    }

    @Test
    void theDetailIsEscapedSoTheBodyIsAlwaysValidJson() throws Exception {
        var nasty = "say \"hi\" \\ back\nslash\ttab\u0001 end";
        var response = new MockHttpServletResponse();
        Problems.write(response, 400, "C\"ODE", nasty, Map.of());
        var node = JSON.readTree(response.getContentAsString());
        assertThat(node.get("detail").asString()).isEqualTo(nasty);
        assertThat(node.get("code").asString()).isEqualTo("C\"ODE");
        assertThat(response.getContentAsString()).contains("\\\"hi\\\"", "\\\\", "\\n", "\\t", "\\u0001");
    }

    @Test
    void nonAsciiTextIsKeptAsUtf8() throws Exception {
        var response = new MockHttpServletResponse();
        Problems.write(response, 400, "नमस्ते");
        assertThat(JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .get("detail").asString()).isEqualTo("नमस्ते");
    }
}
