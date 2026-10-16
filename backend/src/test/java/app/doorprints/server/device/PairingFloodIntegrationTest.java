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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A flood of pairing starts must not push out a real device's code (S4b-BL-161, independent review finding B2;
 * docs/03 section 12.1, docs/06 TC-I-48, TC-S-48): at most 50 open requests in all and 5 per source, a start over a
 * cap refused (429 with Retry-After), the older ones kept. The service runs on the real database with a clock the
 * test moves; the 429 is read over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.auth-failures-per-minute=10000",
                "app.rate-limit.auth-failure-burst=10000",
                "app.pairing.requests-per-minute=10000",
                "app.pairing.burst=10000"})
@ResourceLock("database")
class PairingFloodIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    /** Generated per run, never a literal (nothing for secret scanners). */
    private static final String OWNER_KEY = "it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> OWNER_KEY);
    }

    /** A clock the tests can move forward, to reach the 10-minute expiry. */
    static final class MovableClock extends Clock {
        private volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    PairingService pairing;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MovableClock clock;

    @BeforeEach
    void emptyTable() {
        jdbc.sql("DELETE FROM pairing_request").update();
    }

    private static String source(int n) {
        return "203.0.113." + n;
    }

    private long rows() {
        return jdbc.sql("SELECT count(*) FROM pairing_request").query(Long.class).single();
    }

    /** Fifty open requests from ten sources, five each; returns the first one's start answer. */
    private PairingService.Started fillTheTable() {
        PairingService.Started first = null;
        for (int s = 1; s <= 10; s++) {
            for (int i = 0; i < 5; i++) {
                var started = pairing.start("Device " + s + "-" + i, source(s));
                if (first == null) first = started;
            }
        }
        assertThat(rows()).isEqualTo(50);
        return first;
    }

    @Test
    void theFiftyFirstStartIsRefusedAndTheFirstCodeStillPollsAndRedeems() {
        var first = fillTheTable();

        assertThatThrownBy(() -> pairing.start("Late device", source(11)))
                .isInstanceOf(PairingBusyException.class)
                .satisfies(e -> assertThat(((PairingBusyException) e).retryAfterSeconds()).isBetween(1L, 600L));

        assertThat(rows()).isEqualTo(50); // nothing was inserted, nothing was deleted
        assertThat(pairing.poll(first.pollToken()).status()).isEqualTo(PairingService.PollStatus.PENDING);
        assertThat(pairing.approve(first.userCode())).isTrue();
        var polled = pairing.poll(first.pollToken());
        assertThat(polled.status()).isEqualTo(PairingService.PollStatus.APPROVED);
        assertThat(polled.deviceKey()).startsWith("dpk_");
    }

    @Test
    void theFiftiethStartStillWorks() {
        for (int s = 1; s <= 9; s++) {
            for (int i = 0; i < 5; i++) pairing.start("Device", source(s));
        }
        for (int i = 0; i < 4; i++) pairing.start("Device", source(10));

        var fiftieth = pairing.start("Device", source(10));

        assertThat(fiftieth.userCode()).isNotBlank();
        assertThat(rows()).isEqualTo(50);
    }

    @Test
    void startsRacingForTheLastFreeSlotAdmitExactlyOne() throws Exception {
        for (int s = 1; s <= 9; s++) {
            for (int i = 0; i < 5; i++) pairing.start("Device", source(s));
        }
        for (int i = 0; i < 4; i++) pairing.start("Device", source(10));
        assertThat(rows()).isEqualTo(49);

        var racers = 8;
        var barrier = new java.util.concurrent.CyclicBarrier(racers);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(racers);
        try {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int r = 0; r < racers; r++) {
                var address = source(20 + r);
                results.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        pairing.start("Racer", address);
                        return true;
                    } catch (PairingBusyException e) {
                        return false;
                    }
                }));
            }
            var admitted = 0;
            for (var f : results) if (f.get()) admitted++;
            assertThat(admitted).isEqualTo(1);
            assertThat(rows()).isEqualTo(50);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSourceAtItsCapIsRefusedWhileAnotherSourceStillStarts() {
        var firsts = new ArrayList<PairingService.Started>();
        for (int i = 0; i < 5; i++) firsts.add(pairing.start("Device " + i, source(1)));

        assertThatThrownBy(() -> pairing.start("One too many", source(1))).isInstanceOf(PairingBusyException.class);
        var other = pairing.start("Someone else", source(2));

        assertThat(other.userCode()).isNotBlank();
        assertThat(rows()).isEqualTo(6);
        for (var f : firsts) {
            assertThat(pairing.poll(f.pollToken()).status()).isEqualTo(PairingService.PollStatus.PENDING);
        }
    }

    @Test
    void afterTheCodesExpireAStartWorksAgainFromTheTableAndFromTheSource() {
        fillTheTable();
        assertThatThrownBy(() -> pairing.start("Late device", source(1))).isInstanceOf(PairingBusyException.class);

        clock.advance(PairingService.CODE_LIFETIME.plusSeconds(1));

        assertThat(pairing.start("After expiry", source(1)).userCode()).isNotBlank();
    }

    @Test
    void aCodeStillOpenJustBeforeItExpiresStillCounts() {
        fillTheTable();

        clock.advance(PairingService.CODE_LIFETIME.minusSeconds(2));

        assertThatThrownBy(() -> pairing.start("Late device", source(11))).isInstanceOf(PairingBusyException.class);
    }

    @Test
    void aRequestTheOwnerAnsweredNoLongerCountsAsOpen() {
        PairingService.Started denied = null;
        for (int i = 0; i < 5; i++) {
            var s = pairing.start("Device " + i, source(1));
            if (i == 0) denied = s;
        }
        assertThat(pairing.deny(denied.userCode())).isTrue();

        assertThat(pairing.start("Replacement", source(1)).userCode()).isNotBlank();
    }

    @Test
    void theRetryAfterNamesTheWaitForTheOldestOpenRequest() {
        pairing.start("Device 0", source(1));
        clock.advance(Duration.ofMinutes(4));
        for (int i = 1; i < 5; i++) pairing.start("Device " + i, source(1));

        // The oldest expires in 6 minutes; that is when the source's first slot frees up.
        assertThatThrownBy(() -> pairing.start("One too many", source(1)))
                .isInstanceOf(PairingBusyException.class)
                .satisfies(e -> assertThat(((PairingBusyException) e).retryAfterSeconds()).isEqualTo(360L));
    }

    @Test
    void theTableHoldsAHashOfTheSourceNeverTheAddress() {
        pairing.start("Device", source(7));

        var stored = jdbc.sql("SELECT client_hash FROM pairing_request").query(byte[].class).single();
        assertThat(stored).hasSize(32).isNotEqualTo(source(7).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var asText = jdbc.sql("SELECT encode(client_hash, 'escape') FROM pairing_request").query(String.class).single();
        assertThat(asText).doesNotContain("203.0.113");
    }

    @Test
    void overHttpTheSixthStartFromOneAddressIs429WithRetryAfterAndTheFirstCodeLives() {
        var http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
        Map<String, Object> first = null;
        for (int i = 0; i < 5; i++) {
            var ok = start(http, "Device " + i);
            assertThat(ok.getStatusCode().value()).isEqualTo(200);
            if (i == 0) first = ok.getBody();
        }

        var refused = start(http, "Device 6");

        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        var retryAfter = Long.parseLong(refused.getHeaders().getFirst("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 600L);
        assertThat(refused.getBody()).containsEntry("status", 429);
        assertThat(String.valueOf(refused.getBody().get("detail"))).doesNotContain("Exception").doesNotContain("pairing_request");
        var poll = http.post().uri("/api/pair/poll").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("pollToken", first.get("pollToken"))).retrieve().toEntity(MAP);
        assertThat(poll.getBody()).containsEntry("status", "pending");
    }

    private static ResponseEntity<Map<String, Object>> start(RestClient http, String name) {
        return http.post().uri("/api/pair/start").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("deviceName", name)).retrieve().toEntity(MAP);
    }
}
