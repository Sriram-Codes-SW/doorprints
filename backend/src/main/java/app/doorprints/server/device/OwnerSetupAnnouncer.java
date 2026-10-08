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
 * At a start, while <b>no browser is signed in</b>, writes a one-time link to the owner page to the log (docs/03
 * §12.1): the owner opens it once to sign in their browser. The token sits after {@code #}, so it never reaches a
 * server log or a {@code Referer} when the page is opened. The server does not know the address the owner uses
 * (Tailscale, a LAN name), so the log names the local one and says to put the owner's own address in front of
 * {@code /owner#setup=...} otherwise.
 *
 * <p>Once an owner session is open, the log carries <b>no token and no link</b>, only a sentence saying where to get
 * one (*Add another browser*), and no setup token is made: a log is read by more people and tools than the owner
 * (S4b-BL-171, finding B10; owner decision of 2026-10-08, ADR-25). "Signed in" means an <em>open</em> session (not
 * signed out, not past its 30 idle days), so an owner whose every browser was signed out or expired gets a link again
 * at the next start, the only way back in. If the check itself fails, nothing secret is written (fail closed).
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
        try {
            if (auth.hasOpenSession()) {
                log.info("Doorprints owner page: a browser is already signed in. To sign in another browser, open the "
                        + "owner page in a signed-in browser and choose \"Add another browser\"; it makes a "
                        + "one-time link.");
                return;
            }
            writeLink(auth.newSetupToken());
        } catch (RuntimeException e) {
            // Fail closed: nothing secret, and not the exception's message either (a driver may echo a query).
            log.warn("Doorprints owner page: could not check whether a browser is signed in ({}); no link was written.",
                    e.getClass().getSimpleName());
        }
    }

    private void writeLink(String token) {
        var port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        log.info("""

                ==== Doorprints owner page ====
                Open this link in your browser within 1 hour to manage your devices (it works once):
                  http://localhost:{}/owner#setup={}
                If you reach this server by another address (Tailscale, another computer), use that address instead:
                  https://<your-server-address>/owner#setup={}
                No browser is signed in yet, so a new link is written here at every start until one is.
                ===============================""", port, token, token);
    }
}
