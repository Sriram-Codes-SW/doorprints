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

package app.doorprints.server.secrets;

import app.doorprints.server.device.OwnerAuth;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Gemini key set on the owner page, end to end (docs/03 §12.1, ADR-25; docs/06 TC-I-40): a server with AI on and no
 * key in its settings starts, reads AI as off, and after the owner saves a key sends exactly that key to Gemini (a fake
 * here), with no restart; the key is stored encrypted; pausing AI stops everyone; removing the key turns AI off.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.ai.enabled=true",
                "spring.ai.openai.api-key=",
                "app.rate-limit.auth-failures-per-minute=10000",
                "app.rate-limit.auth-failure-burst=10000"})
@ResourceLock("database")
class OwnerGeminiKeyIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final String OWNER_KEY = "it-" + UUID.randomUUID();
    private static final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private static final HttpServer fakeGemini = startFakeGemini();

    private static HttpServer startFakeGemini() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                var body = """
                        {"id":"x","object":"chat.completion","created":0,"model":"fake","choices":[{"index":0,
                        "message":{"role":"assistant","content":"{}"},"finish_reason":"stop"}],
                        "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        fakeGemini.stop(0);
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        var base = "http://127.0.0.1:" + fakeGemini.getAddress().getPort();
        registry.add("app.api-key", () -> OWNER_KEY);
        registry.add("spring.ai.openai.base-url", () -> base + "/v1beta/openai");
        registry.add("app.ai.embedding.base-url", () -> base + "/v1beta");
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    OwnerAuth ownerAuth;

    @Autowired
    JdbcClient jdbc;

    RestClient http;

    @BeforeEach
    void setUp() {
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(s -> true, (req, res) -> { }).build();
    }

    private String signIn() {
        var h = new HttpHeaders();
        h.add("X-Doorprints-Owner", "1");
        var response = http.post().uri("/owner/api/session").headers(x -> x.addAll(h))
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("setup", ownerAuth.newSetupToken()))
                .retrieve().toBodilessEntity();
        var cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return cookie.substring("dp_owner=".length(), cookie.indexOf(';'));
    }

    private int owner(String path, Object body, String session) {
        return http.post().uri(path).header("X-Doorprints-Owner", "1").header(HttpHeaders.COOKIE, "dp_owner=" + session)
                .contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body)
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    private Map<String, Object> aiStatus() {
        return http.get().uri("/api/ai/status").header("X-API-Key", OWNER_KEY).retrieve().body(MAP);
    }

    private int extract() {
        return http.post().uri("/api/ai/extract-listing").header("X-API-Key", OWNER_KEY)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("text", "2BHK in Indiranagar for rent 30000"))
                .retrieve().toBodilessEntity().getStatusCode().value();
    }

    @Test
    void theOwnerSetsTheGeminiKeyAndItIsUsedAtOnceStoredEncrypted() {
        var session = signIn();
        owner("/owner/api/ai/key/remove", null, session);
        assertThat(aiStatus()).containsEntry("enabled", false);

        var key = "AIzaFakeKeyForTests-" + UUID.randomUUID();
        assertThat(owner("/owner/api/ai/key", Map.of("key", key), session)).isEqualTo(204);
        assertThat(aiStatus()).containsEntry("enabled", true);

        lastAuthorization.set(null);
        extract();
        assertThat(lastAuthorization.get()).isEqualTo("Bearer " + key);

        // Stored encrypted: the database holds neither the key nor any long part of it.
        var stored = jdbc.sql("SELECT ciphertext FROM server_secret WHERE name = 'gemini_api_key'")
                .query(byte[].class).single();
        assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(key.substring(4, 20));
        var view = http.get().uri("/owner/api/ai").header("X-Doorprints-Owner", "1")
                .header(HttpHeaders.COOKIE, "dp_owner=" + session).retrieve().body(MAP);
        assertThat(view).containsEntry("keySource", "owner_page")
                .containsEntry("keyLast4", key.substring(key.length() - 4)).containsEntry("enabledOnServer", true);
        assertThat(view.toString()).doesNotContain(key);

        // Paused for the whole server: the owner key too.
        assertThat(owner("/owner/api/ai/paused", Map.of("paused", true), session)).isEqualTo(204);
        assertThat(extract()).isEqualTo(403);
        assertThat(aiStatus()).containsEntry("enabled", false);
        assertThat(owner("/owner/api/ai/paused", Map.of("paused", false), session)).isEqualTo(204);

        assertThat(owner("/owner/api/ai/key/remove", null, session)).isEqualTo(204);
        assertThat(aiStatus()).containsEntry("enabled", false);
    }

    @Test
    void aKeyWithSpacesIsRefused() {
        var session = signIn();
        assertThat(owner("/owner/api/ai/key", Map.of("key", "not a key but a sentence with spaces"), session))
                .isEqualTo(400);
    }
}
