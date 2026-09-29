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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * At every start, writes a one-time link to the owner page to the log (docs/03 §12.1): the owner opens it once to
 * sign in their browser. The token sits after {@code #}, so it never reaches a server log or a {@code Referer} when the
 * page is opened. The server does not know the address the owner uses (Tailscale, a LAN name), so the log names the
 * local one and says to put the owner's own address in front of {@code /owner#setup=...} otherwise.
 */
@Component
public class OwnerSetupAnnouncer {

    private static final Logger log = LoggerFactory.getLogger(OwnerSetupAnnouncer.class);

    private final OwnerAuth auth;
    private final Environment env;

    public OwnerSetupAnnouncer(OwnerAuth auth, Environment env) {
        this.auth = auth;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void announce() {
        var token = auth.newSetupToken();
        var port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        log.info("""

                ==== Doorprints owner page ====
                Open this link in your browser within 1 hour to manage your devices (it works once):
                  http://localhost:{}/owner#setup={}
                If you reach this server by another address (Tailscale, another computer), use that address instead:
                  https://<your-server-address>/owner#setup={}
                A new link is written here every time the server starts.
                ===============================""", port, token, token);
    }
}
