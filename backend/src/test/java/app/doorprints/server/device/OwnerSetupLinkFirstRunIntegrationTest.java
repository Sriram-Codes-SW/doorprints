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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner page's setup link across two starts, on the real database (docs/03 §12.1, ADR-25; S4b-BL-171, docs/06
 * TC-I-47): the first start writes a usable link; once it is redeemed (a session row exists) the next start writes no
 * token and makes no setup token. The context starts once, so a "start" here is the same listener the application
 * runs on {@code ApplicationReadyEvent}, called on the real bean with the real tables behind it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.auth-failures-per-minute=10000",
                "app.rate-limit.auth-failure-burst=10000"})
@ResourceLock("database")
class OwnerSetupLinkFirstRunIntegrationTest {

    /** Generated per run, never a literal (nothing for secret scanners). */
    private static final String OWNER_KEY = "it-" + UUID.randomUUID();
    private static final Pattern LINK = Pattern.compile("http://localhost:\\d+/owner#setup=([A-Za-z0-9_-]+)");

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> OWNER_KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    OwnerSetupAnnouncer announcer;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OwnerAuth ownerAuth;

    private final Logger logger = (Logger) LoggerFactory.getLogger(OwnerSetupAnnouncer.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Level before;

    @BeforeEach
    void freshServer() {
        // Other tests of this suite sign browsers in on the same database: start from a server nobody has signed in to.
        jdbc.sql("DELETE FROM owner_token").update();
        before = logger.getLevel();
        logger.setLevel(Level.ALL);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(before);
        jdbc.sql("DELETE FROM owner_token").update();
    }

    private String startAndRead() {
        appender.list.clear();
        announcer.announce();
        var out = new StringBuilder();
        appender.list.forEach(e -> out.append(e.getFormattedMessage()).append('\n'));
        return out.toString();
    }

    private int setupRows() {
        return jdbc.sql("SELECT count(*) FROM owner_token WHERE kind = 'setup'").query(Integer.class).single();
    }

    private int redeem(String token) {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build()
                .post().uri("/owner/api/session").header("X-Doorprints-Owner", "1")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("setup", token))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private static void assertNothingSecret(String out) {
        assertThat(out).doesNotContain("#setup").doesNotContain("/owner").doesNotContain("http")
                .contains("already signed in").contains("\"Add another browser\"");
    }

    @Test
    void firstStartWritesAUsableLinkTheSecondStartAfterSignInWritesNone() {
        var first = startAndRead();
        var link = LINK.matcher(first);
        assertThat(link.find()).as("the first start's log has the local link:%n%s", first).isTrue();
        var token = link.group(1);
        assertThat(setupRows()).isEqualTo(1);

        // The link is the usable one: it signs a browser in, which creates the session row.
        assertThat(redeem(token)).isEqualTo(204);
        assertThat(ownerAuth.hasOpenSession()).isTrue();
        var setupRowsAfterSignIn = setupRows();

        var second = startAndRead();
        assertThat(second).doesNotContain(token);
        assertNothingSecret(second);
        assertThat(setupRows()).as("no new setup token on the second start").isEqualTo(setupRowsAfterSignIn);

        var third = startAndRead();
        assertThat(third).doesNotContain(token);
        assertNothingSecret(third);
        assertThat(setupRows()).isEqualTo(setupRowsAfterSignIn);
    }

    @Test
    void anUnredeemedLinkFromAnEarlierStartDoesNotStopTheNextStartFromWritingANewOne() {
        var first = startAndRead();
        var second = startAndRead();

        var a = LINK.matcher(first);
        var b = LINK.matcher(second);
        assertThat(a.find() && b.find()).isTrue();
        assertThat(b.group(1)).isNotEqualTo(a.group(1));
        assertThat(setupRows()).isEqualTo(2);
    }

    @Test
    void whenTheOnlyBrowserIsSignedOutTheNextStartWritesALinkAgain() {
        var token = LINK.matcher(startAndRead());
        assertThat(token.find()).isTrue();
        assertThat(redeem(token.group(1))).isEqualTo(204);
        var session = ownerAuth.sessions().getFirst().id();
        assertThat(ownerAuth.revoke(session)).isTrue();

        assertThat(LINK.matcher(startAndRead()).find()).isTrue();
    }

    @Test
    void whenTheOnlySessionHasExpiredTheNextStartWritesALinkAgain() {
        var token = LINK.matcher(startAndRead());
        assertThat(token.find()).isTrue();
        assertThat(redeem(token.group(1))).isEqualTo(204);
        jdbc.sql("UPDATE owner_token SET expires_at = :past WHERE kind = 'session'")
                .param("past", Timestamp.from(Instant.now().minusSeconds(60))).update();

        assertThat(LINK.matcher(startAndRead()).find()).isTrue();
    }
}
