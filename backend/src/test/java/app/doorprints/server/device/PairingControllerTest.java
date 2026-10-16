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

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@code POST /api/pair/start} hands to the service and how it answers a refusal (S4b-BL-161, docs/06 TC-S-48):
 * the caller's address is the one the server saw, and a full table is 429 with a whole-second Retry-After.
 */
class PairingControllerTest {

    private final PairingService service = mock(PairingService.class);
    private final PairingController controller = new PairingController(service);

    @Test
    void startPassesTheAddressTheServerSawToTheService() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.9");
        var started = new PairingService.Started("ABCD-2345", "poll", 600, 3);
        when(service.start("Pixel", "198.51.100.9")).thenReturn(started);

        var answer = controller.start(new PairingController.StartRequest("Pixel"), request);

        assertThat(answer).isSameAs(started);
        verify(service).start("Pixel", "198.51.100.9");
    }

    @Test
    void aRefusedStartIsA429WithRetryAfterAndAFixedProblemBody() {
        var answer = controller.busy(new PairingBusyException(37));

        assertThat(answer.getStatusCode().value()).isEqualTo(429);
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("37");
        assertThat(answer.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(answer.getBody()).containsEntry("status", 429)
                .containsEntry("detail", "Too many devices are waiting to connect, retry in 37s");
    }
}
