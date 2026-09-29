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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Device keys, pairing and the owner page end to end, on the real database (docs/03 §12.1, ADR-25; docs/06 TC-I-39,
 * TC-S-28).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.rate-limit.auth-failures-per-minute=10000",
                "app.rate-limit.auth-failure-burst=10000",
                "app.pairing.requests-per-minute=10000",
                "app.pairing.burst=10000",
                "app.web-url=https://web.example"})
@ResourceLock("database")
class DevicePairingIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    /** Generated per run, never a literal (F-01; nothing for secret scanners). */
    private static final String OWNER_KEY = "it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> OWNER_KEY);
    }

    /** A clock the tests can move forward, to reach the 10-minute and 1-hour expiries. */
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
    OwnerAuth ownerAuth;

    @Autowired
    MovableClock clock;

    RestClient anonymous;

    @BeforeEach
    void setUp() {
        anonymous = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private ResponseEntity<Map<String, Object>> post(String path, Object body, HttpHeaders headers) {
        return anonymous.post().uri(path).headers(h -> h.addAll(headers)).contentType(MediaType.APPLICATION_JSON)
                .body(body == null ? Map.of() : body).retrieve().toEntity(MAP);
    }

    private static HttpHeaders ownerHeaders(String cookie) {
        var h = new HttpHeaders();
        h.add("X-Doorprints-Owner", "1");
        if (cookie != null) h.add(HttpHeaders.COOKIE, "dp_owner=" + cookie);
        return h;
    }

    /** Signs a browser in with a fresh setup link and returns its session cookie value. */
    private String signIn() {
        var response = post("/owner/api/session", Map.of("setup", ownerAuth.newSetupToken()), ownerHeaders(null));
        assertThat(response.getStatusCode().value()).isEqualTo(204);
        var setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/owner");
        return setCookie.substring("dp_owner=".length(), setCookie.indexOf(';'));
    }

    private int statusWithKey(String method, String path, String key) {
        return anonymous.method(org.springframework.http.HttpMethod.valueOf(method)).uri(path)
                .header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON).body(Map.of("question", "x"))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private String pairByCode(String owner, String name) {
        var started = post("/api/pair/start", Map.of("deviceName", name), new HttpHeaders()).getBody();
        var code = (String) started.get("userCode");
        var poll = (String) started.get("pollToken");
        assertThat(post("/owner/api/pairings/approve", Map.of("code", code), ownerHeaders(owner))
                .getStatusCode().value()).isEqualTo(204);
        return (String) post("/api/pair/poll", Map.of("pollToken", poll), new HttpHeaders()).getBody().get("deviceKey");
    }

    // ------------------------------------------------------------------------------------------------ tests

    @Test
    void aDeviceConnectsByItsCodeAndItsKeyWorksOnce() {
        var started = post("/api/pair/start", Map.of("deviceName", "Priya's Pixel"), new HttpHeaders());
        assertThat(started.getStatusCode().value()).isEqualTo(200);
        var code = (String) started.getBody().get("userCode");
        var poll = (String) started.getBody().get("pollToken");
        assertThat(code).matches("[A-Z2-9]{4}-[A-Z2-9]{4}");
        assertThat(started.getBody()).containsEntry("interval", 3).containsEntry("expiresIn", 600);

        assertThat(post("/api/pair/poll", Map.of("pollToken", poll), new HttpHeaders()).getBody())
                .containsEntry("status", "pending");

        var owner = signIn();
        // Typed as a person might: lower case, a space instead of the dash.
        var found = post("/owner/api/pairings/find", Map.of("code", code.toLowerCase().replace('-', ' ')),
                ownerHeaders(owner));
        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(found.getBody()).containsEntry("deviceName", "Priya's Pixel");
        assertThat(post("/owner/api/pairings/approve", Map.of("code", code), ownerHeaders(owner))
                .getStatusCode().value()).isEqualTo(204);

        var approved = post("/api/pair/poll", Map.of("pollToken", poll), new HttpHeaders()).getBody();
        assertThat(approved).containsEntry("status", "approved");
        var key = (String) approved.get("deviceKey");
        assertThat(key).startsWith("dpk_").hasSize(47);
        // Handed over once only.
        var again = post("/api/pair/poll", Map.of("pollToken", poll), new HttpHeaders()).getBody();
        assertThat(again).containsEntry("status", "expired");
        assertThat(again.get("deviceKey")).isNull();

        assertThat(statusWithKey("GET", "/api/stats", key)).isEqualTo(200);

        var overview = anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders(owner)))
                .retrieve().body(MAP);
        @SuppressWarnings("unchecked")
        var devices = (List<Map<String, Object>>) overview.get("devices");
        var device = devices.stream().filter(d -> "Priya's Pixel".equals(d.get("name"))).findFirst().orElseThrow();
        assertThat(device).containsEntry("aiAllowed", false).containsEntry("via", "code")
                .containsEntry("keyLast4", key.substring(key.length() - 4));
        assertThat(device.toString()).doesNotContain(key);
    }

    @Test
    void aDeniedOrExpiredCodeGivesNoKey() {
        var owner = signIn();
        var denied = post("/api/pair/start", Map.of("deviceName", "Unknown"), new HttpHeaders()).getBody();
        assertThat(post("/owner/api/pairings/deny", Map.of("code", denied.get("userCode")), ownerHeaders(owner))
                .getStatusCode().value()).isEqualTo(204);
        assertThat(post("/api/pair/poll", Map.of("pollToken", denied.get("pollToken")), new HttpHeaders()).getBody())
                .containsEntry("status", "denied");

        var late = post("/api/pair/start", Map.of("deviceName", "Slow"), new HttpHeaders()).getBody();
        clock.advance(Duration.ofMinutes(11));
        assertThat(post("/owner/api/pairings/approve", Map.of("code", late.get("userCode")), ownerHeaders(owner))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(post("/api/pair/poll", Map.of("pollToken", late.get("pollToken")), new HttpHeaders()).getBody())
                .containsEntry("status", "expired");
    }

    @Test
    void aQrInviteConnectsOneDeviceOnce() {
        var owner = signIn();
        var invite = post("/owner/api/invites", null, ownerHeaders(owner)).getBody();
        var appLink = (String) invite.get("appLink");
        assertThat(appLink).startsWith("doorprints://connect?server=");
        assertThat((String) invite.get("webLink")).startsWith("https://web.example/connect?server=");
        assertThat((String) invite.get("qr")).startsWith("data:image/svg+xml;base64,");
        var token = java.net.URLDecoder.decode(appLink.substring(appLink.indexOf("invite=") + 7),
                java.nio.charset.StandardCharsets.UTF_8);

        var redeemed = post("/api/pair/redeem", Map.of("invite", token, "deviceName", "Kitchen tablet"),
                new HttpHeaders());
        assertThat(redeemed.getStatusCode().value()).isEqualTo(200);
        var key = (String) redeemed.getBody().get("deviceKey");
        assertThat(statusWithKey("GET", "/api/stats", key)).isEqualTo(200);

        assertThat(post("/api/pair/redeem", Map.of("invite", token, "deviceName", "Second"), new HttpHeaders())
                .getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void aRevokedDeviceKeyStopsWorkingAtOnce() {
        var owner = signIn();
        var key = pairByCode(owner, "Lost phone");
        assertThat(statusWithKey("GET", "/api/stats", key)).isEqualTo(200);
        var overview = anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders(owner)))
                .retrieve().body(MAP);
        @SuppressWarnings("unchecked")
        var id = ((List<Map<String, Object>>) overview.get("devices")).stream()
                .filter(d -> "Lost phone".equals(d.get("name")) && d.get("revokedAt") == null)
                .findFirst().orElseThrow().get("id");
        assertThat(post("/owner/api/devices/" + id + "/revoke", null, ownerHeaders(owner)).getStatusCode().value())
                .isEqualTo(204);
        assertThat(statusWithKey("GET", "/api/stats", key)).isEqualTo(401);
        // The owner key is untouched.
        assertThat(statusWithKey("GET", "/api/stats", OWNER_KEY)).isEqualTo(200);
    }

    @Test
    void aNewDeviceHasAiOffUntilTheOwnerTurnsItOn() {
        var owner = signIn();
        var key = pairByCode(owner, "Partner's phone");
        assertThat(statusWithKey("POST", "/api/ai/ask", key)).isEqualTo(403);
        var body = anonymous.post().uri("/api/ai/ask").header("X-API-Key", key).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "x")).retrieve().body(MAP);
        assertThat(body).containsEntry("code", "AI_OFF_FOR_DEVICE");
        // The owner key is not affected (AI itself is off in tests, so the AI endpoint does not exist: 404).
        assertThat(statusWithKey("POST", "/api/ai/ask", OWNER_KEY)).isEqualTo(404);

        var overview = anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders(owner)))
                .retrieve().body(MAP);
        @SuppressWarnings("unchecked")
        var id = ((List<Map<String, Object>>) overview.get("devices")).stream()
                .filter(d -> "Partner's phone".equals(d.get("name"))).findFirst().orElseThrow().get("id");
        assertThat(post("/owner/api/devices/" + id + "/ai", Map.of("allowed", true), ownerHeaders(owner))
                .getStatusCode().value()).isEqualTo(204);
        assertThat(statusWithKey("POST", "/api/ai/ask", key)).isEqualTo(404);
    }

    @Test
    void theOwnerApiRefusesCrossSiteCallsAndCallsWithoutASession() {
        var owner = signIn();
        var noHeader = new HttpHeaders();
        noHeader.add(HttpHeaders.COOKIE, "dp_owner=" + owner);
        assertThat(anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(noHeader)).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(403);

        var crossSite = ownerHeaders(owner);
        crossSite.add("Sec-Fetch-Site", "cross-site");
        assertThat(anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(crossSite)).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(403);

        var otherOrigin = ownerHeaders(owner);
        otherOrigin.add(HttpHeaders.ORIGIN, "https://evil.example");
        assertThat(post("/owner/api/invites", null, otherOrigin).getStatusCode().value()).isEqualTo(403);

        assertThat(anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders(null))).retrieve()
                .toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders("forged")))
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aSetupLinkWorksOnceAndForAnHourOnly() {
        var token = ownerAuth.newSetupToken();
        assertThat(post("/owner/api/session", Map.of("setup", token), ownerHeaders(null)).getStatusCode().value())
                .isEqualTo(204);
        assertThat(post("/owner/api/session", Map.of("setup", token), ownerHeaders(null)).getStatusCode().value())
                .isEqualTo(401);

        var old = ownerAuth.newSetupToken();
        clock.advance(Duration.ofMinutes(61));
        assertThat(post("/owner/api/session", Map.of("setup", old), ownerHeaders(null)).getStatusCode().value())
                .isEqualTo(401);
    }

    @Test
    void signingOutEverywhereElseClosesOtherBrowsers() {
        var first = signIn();
        var second = signIn();
        assertThat(post("/owner/api/sessions/revoke-others", null, ownerHeaders(first)).getStatusCode().value())
                .isEqualTo(200);
        assertThat(anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders(second)))
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(401);
        assertThat(anonymous.get().uri("/owner/api/overview").headers(h -> h.addAll(ownerHeaders(first)))
                .retrieve().toBodilessEntity().getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void wrongCodesAreLimitedToFiveAMinute() {
        var owner = signIn();
        for (int i = 0; i < 5; i++) {
            assertThat(post("/owner/api/pairings/find", Map.of("code", "AAAA-AAAA"), ownerHeaders(owner))
                    .getStatusCode().value()).isEqualTo(404);
        }
        var sixth = post("/owner/api/pairings/find", Map.of("code", "AAAA-AAAA"), ownerHeaders(owner));
        assertThat(sixth.getStatusCode().value()).isEqualTo(429);
        assertThat(sixth.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void theOwnerPageHasItsOwnStrictPolicy() {
        var page = anonymous.get().uri("/owner").retrieve().toEntity(String.class);
        assertThat(page.getStatusCode().value()).isEqualTo(200);
        assertThat(page.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(page.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(
                app.doorprints.server.config.SecurityHeadersFilter.OWNER_CSP);
        assertThat(page.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(page.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(page.getBody()).contains("/owner/owner.js").doesNotContain("<script>");

        assertThat(anonymous.get().uri("/owner/owner.js").retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(200);
        // The API keeps its own policy, and everything else still needs a key.
        var api = anonymous.get().uri("/api/stats").retrieve().toBodilessEntity();
        assertThat(api.getStatusCode().value()).isEqualTo(401);
        assertThat(api.getHeaders().getFirst("Content-Security-Policy")).startsWith("default-src 'none'");
        assertThat(anonymous.get().uri("/owner/index.html").retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(401);
    }
}
