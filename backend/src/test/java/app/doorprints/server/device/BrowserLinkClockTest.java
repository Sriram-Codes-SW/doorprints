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
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The expiry the owner page shows for an *Add another browser* link is one hour after the injected clock's time
 * (S4b-BL-166).
 */
class BrowserLinkClockTest {

    @Test
    void theLinkExpiresOneHourAfterTheClocksTime() {
        var now = Instant.parse("2026-03-05T04:30:00Z");
        var auth = mock(OwnerAuth.class);
        when(auth.newSetupToken()).thenReturn("setup-token");
        var controller = new OwnerApiController(auth, null, null, null, null, null, null, false, "aistudio",
                Clock.fixed(now, ZoneOffset.UTC));
        var request = new MockHttpServletRequest("POST", "/owner/api/browser-links");
        request.setServerName("doorprints.example");
        request.setServerPort(443);
        request.setScheme("https");

        var view = controller.browserLink(request);

        assertThat(view.expiresAt()).isEqualTo(Instant.parse("2026-03-05T05:30:00Z"));
        assertThat(view.link()).isEqualTo("https://doorprints.example/owner#setup=setup-token");
    }
}
