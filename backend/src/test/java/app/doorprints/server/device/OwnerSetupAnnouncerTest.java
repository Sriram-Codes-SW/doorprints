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

import app.doorprints.server.config.AppProperties;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the server writes to its log at a start about the owner page (docs/03 §12.1, ADR-25; S4b-BL-171, docs/06
 * TC-S-46): a setup link only while no browser is signed in, never a token afterwards. The assertions read the
 * formatted log output a person would see, through a captured appender.
 */
class OwnerSetupAnnouncerTest {

    /** Generated per run, never a literal (nothing for secret scanners). */
    private final String token = "tok-" + UUID.randomUUID().toString().replace("-", "");

    private final OwnerAuth auth = mock(OwnerAuth.class);
    private final MockEnvironment env = new MockEnvironment().withProperty("local.server.port", "8123");
    private final OwnerSetupAnnouncer announcer = new OwnerSetupAnnouncer(auth, env, props(null));

    /** Settings with only the recovery switch set; {@code null} leaves it to its default. */
    private static AppProperties props(Boolean setupLinkInLog) {
        return new AppProperties(null, null, null, null, null, null, null, null, null,
                new AppProperties.Owner(setupLinkInLog));
    }

    private static OwnerSetupAnnouncer announcerWithSwitch(OwnerAuth auth, MockEnvironment env, boolean on) {
        return new OwnerSetupAnnouncer(auth, env, props(on));
    }

    private final Logger logger = (Logger) LoggerFactory.getLogger(OwnerSetupAnnouncer.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level before;

    @BeforeEach
    void capture() {
        before = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender.start();
        logger.addAppender(appender);
        when(auth.newSetupToken()).thenReturn(token);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(before);
    }

    /** Everything logged so far, formatted as written, with the throwables too. */
    private String output() {
        var all = new StringBuilder();
        for (var event : appender.list) {
            all.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                all.append(event.getThrowableProxy().getClassName()).append(' ')
                        .append(event.getThrowableProxy().getMessage()).append('\n');
            }
        }
        return all.toString();
    }

    private static void assertNoSecretOrLink(String out, String token) {
        assertThat(out).doesNotContain(token).doesNotContain(token.substring(4, 16)).doesNotContain("#setup")
                .doesNotContain("/owner").doesNotContain("localhost").doesNotContain("http");
    }

    @Test
    void noBrowserSignedInWritesTheUsableLink() {
        when(auth.hasOpenSession()).thenReturn(false);

        announcer.announce();

        var out = output();
        assertThat(out).contains("http://localhost:8123/owner#setup=" + token)
                .contains("https://<your-server-address>/owner#setup=" + token);
        verify(auth, times(1)).newSetupToken();
    }

    @Test
    void aBrowserSignedInWritesNoTokenAndNoLinkOnlyWhereToGetOne() {
        when(auth.hasOpenSession()).thenReturn(true);

        announcer.announce();

        var out = output();
        assertNoSecretOrLink(out, token);
        assertThat(out).contains("already signed in").contains("\"Add another browser\"");
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void aBrowserSignedInMakesNoSetupTokenAtAll() {
        when(auth.hasOpenSession()).thenReturn(true);

        announcer.announce();

        verify(auth, never()).newSetupToken();
    }

    @Test
    void startingTwiceWithABrowserSignedInStillWritesNothingSecret() {
        when(auth.hasOpenSession()).thenReturn(true);

        announcer.announce();
        announcer.announce();

        assertNoSecretOrLink(output(), token);
        assertThat(appender.list).hasSize(2);
        verify(auth, never()).newSetupToken();
    }

    @Test
    void whenEverySessionWasSignedOutOrExpiredTheLinkComesBack() {
        // "Signed in" is an open session (not revoked, not expired), so this is the same case as no browser at all:
        // the owner who signed every browser out is not locked out of their own server.
        when(auth.hasOpenSession()).thenReturn(true, false);

        announcer.announce();
        announcer.announce();

        var out = output();
        assertThat(out).contains("#setup=" + token);
        assertThat(out.split("#setup=" + token, -1).length - 1).isEqualTo(2); // the local and the remote form
        verify(auth, times(1)).newSetupToken();
    }

    @Test
    void aFailingCheckWritesNothingSecretAndMakesNoToken() {
        var leak = "db-password-" + UUID.randomUUID();
        when(auth.hasOpenSession()).thenThrow(new IllegalStateException(leak + " " + token));

        announcer.announce();

        var out = output();
        assertNoSecretOrLink(out, token);
        assertThat(out).doesNotContain(leak).contains("no link was written");
        verify(auth, never()).newSetupToken();
    }

    @Test
    void aFailingTokenWriteDoesNotStopTheStartAndLeaksNothing() {
        var leak = "db-password-" + UUID.randomUUID();
        when(auth.hasOpenSession()).thenReturn(false);
        when(auth.newSetupToken()).thenThrow(new IllegalStateException(leak));

        announcer.announce();

        assertThat(output()).doesNotContain(leak).doesNotContain("#setup").contains("no link was written");
    }

    // ------------------------------------------------------------------ recovery switch (S4b-BL-188)

    private static final String SWITCH_WARNING = "OWNER_SETUP_LINK_IN_LOG is on, so this link is written";

    @Test
    void theSwitchIsOffByDefault() {
        assertThat(props(null).owner().setupLinkInLog()).isFalse();
        assertThat(new AppProperties(null, null, null, null, null, null, null, null, null, null)
                .owner().setupLinkInLog()).isFalse();
    }

    @Test
    void switchOffWithASessionOpenWritesNothingSecret() {
        when(auth.hasOpenSession()).thenReturn(true);

        announcerWithSwitch(auth, env, false).announce();

        assertNoSecretOrLink(output(), token);
        assertThat(output()).doesNotContain(SWITCH_WARNING);
        verify(auth, never()).newSetupToken();
    }

    @Test
    void switchOnWithASessionOpenWritesTheLinkAndSaysToSwitchItOff() {
        when(auth.hasOpenSession()).thenReturn(true);

        announcerWithSwitch(auth, env, true).announce();

        var out = output();
        assertThat(out).contains("http://localhost:8123/owner#setup=" + token)
                .contains("https://<your-server-address>/owner#setup=" + token)
                .contains(SWITCH_WARNING).contains("off again and restart");
        verify(auth, times(1)).newSetupToken();
    }

    @Test
    void switchOnWithNoSessionWritesTheLinkOnceAndNoWarning() {
        when(auth.hasOpenSession()).thenReturn(false);

        announcerWithSwitch(auth, env, true).announce();

        var out = output();
        assertThat(out.split("#setup=" + token, -1).length - 1).isEqualTo(2); // the local and the remote form
        assertThat(out).doesNotContain(SWITCH_WARNING);
        verify(auth, times(1)).newSetupToken();
    }

    @Test
    void switchOnDoesNotWriteTheLinkWhenTheSessionCheckFails() {
        when(auth.hasOpenSession()).thenThrow(new IllegalStateException("db down"));

        announcerWithSwitch(auth, env, true).announce();

        assertNoSecretOrLink(output(), token);
        verify(auth, never()).newSetupToken();
    }
}
