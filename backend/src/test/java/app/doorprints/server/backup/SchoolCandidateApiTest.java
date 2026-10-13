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
package app.doorprints.server.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hidden schools kind (kinds PR 5, S4b-BL-209) against a real PostGIS database, like {@code BackupApiTest}: a
 * school sent to {@code /api/candidates} is stored and read back with its kind and extras; the school of
 * {@code docs/schemas/kinds/school-vectors.json}'s backup is imported, kept as a school, and exported back with the same
 * candidate (before S4b-BL-209 the server's backup rows had no place for a kind, so it came back a house). Shares the
 * database with {@code ApiIntegrationTest} and {@code BackupApiTest}: see the lock note there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.requests-per-minute=60000", "app.rate-limit.burst=6000"})
@ResourceLock("database")
class SchoolCandidateApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Generated per run (never a literal in source, so secret scanners have nothing to flag); >= 32 chars. */
    private static final String KEY = "school-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-API-Key", KEY).build();
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
    }

    private static JsonNode vectors() throws Exception {
        var dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 5 && dir != null; up++, dir = dir.getParent()) {
            var file = dir.resolve("docs/schemas/kinds/school-vectors.json");
            if (Files.isRegularFile(file)) return JSON.readTree(Files.readString(file));
        }
        throw new IllegalStateException("docs/schemas/kinds/school-vectors.json not found");
    }

    @Test
    void aSchoolSentToTheSyncApiIsStoredAndReadBackWithItsKindAndExtras() throws Exception {
        var body = (ObjectNode) vectors().get("candidate").deepCopy();
        var id = UUID.randomUUID();
        body.remove("syncVersion");
        body.put("updatedAt", Instant.now().toString());
        api.put().uri("/api/candidates/{id}", id).contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body))
                .retrieve().toBodilessEntity();
        var back = JSON.readTree(api.get().uri("/api/candidates/{id}", id).retrieve().body(String.class));
        assertThat(back.get("kind").asString()).isEqualTo("school");
        assertThat(back.get("extras")).isEqualTo(body.get("extras"));
        assertThat(back.path("money").isMissingNode() || back.get("money").isNull()).isTrue();
        // A school's own money may be paid yearly (any cadence of the vocabulary; a house's is a rent or a sale).
        body.set("money", JSON.readTree("{\"amount\":96000,\"cadence\":\"year\"}"));
        api.put().uri("/api/candidates/{id}", id).contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(body))
                .retrieve().toBodilessEntity();
        assertThat(api.get().uri("/api/candidates/{id}", id).retrieve().body(MAP).get("money"))
                .isEqualTo(Map.of("amount", 96000, "cadence", "year"));
    }

    @Test
    void aSchoolInABackupIsImportedKeptAsASchoolAndExportedBackTheSame() throws Exception {
        var backup = vectors().get("backup");
        var report = api.post().uri("/api/import?dryRun=false").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(backup)).retrieve().body(MAP);
        @SuppressWarnings("unchecked")
        var houses = (Map<String, Object>) report.get("houses");
        assertThat(((Number) houses.get("created")).intValue()).isEqualTo(1);
        var id = backup.get("candidates").get(0).get("id").asString();
        var stored = JSON.readTree(api.get().uri("/api/candidates/{id}", id).retrieve().body(String.class));
        assertThat(stored.get("kind").asString()).isEqualTo("school");
        assertThat(stored.get("extras")).isEqualTo(backup.get("candidates").get(0).get("extras"));
        var exported = JSON.readTree(api.get().uri("/api/export").retrieve().body(String.class));
        assertThat(JSON.writeValueAsString(exported.get("candidates"))).isEqualTo(JSON.writeValueAsString(backup.get("candidates")));
    }
}
