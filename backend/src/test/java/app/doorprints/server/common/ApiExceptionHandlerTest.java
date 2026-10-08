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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a client is told when a controller throws (SEC-015, S4b-BL-159). The seam is the HTTP answer of
 * {@link ApiExceptionHandler} in front of stub controllers: a deliberate refusal ({@link BadRequestException}) keeps
 * its message; any other {@link IllegalArgumentException}, which a library can throw with internal text, never reaches
 * the body.
 */
class ApiExceptionHandlerTest {

    @RestController
    static class Stub {
        static final String MARKER = "secret-marker-" + UUID.randomUUID();

        @GetMapping("/library")
        String library() {
            throw new IllegalArgumentException(MARKER);
        }

        @GetMapping("/library-with-cause")
        String libraryWithCause() {
            throw new IllegalArgumentException("outer", new IllegalStateException(MARKER));
        }

        @GetMapping("/refusal")
        String refusal() {
            throw new BadRequestException("leftAt must not be before arrivedAt");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Stub())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    private String body(String path, int status) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn();
        assertThat(result.getResponse().getStatus()).as(path).isEqualTo(status);
        return result.getResponse().getContentAsString();
    }

    @Test
    void aLibraryIllegalArgumentExceptionIsAFixedMalformedRequest() throws Exception {
        var body = body("/library", 400);
        assertThat(body).contains("Malformed request").doesNotContain(Stub.MARKER);
        assertThat(body("/library-with-cause", 400)).contains("Malformed request").doesNotContain(Stub.MARKER)
                .doesNotContain("outer");
    }

    @Test
    void aDeliberateRefusalKeepsItsMessage() throws Exception {
        assertThat(body("/refusal", 400)).contains("leftAt must not be before arrivedAt");
    }
}
