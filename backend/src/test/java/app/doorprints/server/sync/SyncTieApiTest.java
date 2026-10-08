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

package app.doorprints.server.sync;

import app.doorprints.server.house.HouseChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Equal, newer and older {@code updatedAt} on the three push endpoints (S4b-BL-163, docs/03 section 10.1): the stored
 * row, the version the answer carries, the version counter and the published events. Runs against the real PostGIS
 * database like {@code ApiIntegrationTest} (CI starts one); the rule itself is tested without a database in
 * {@link UpsertTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ResourceLock("database")
class SyncTieApiTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** Generated per run (never a literal in source); >= 32 chars. */
    private static final String KEY = "sync-tie-it-" + UUID.randomUUID();

    private static final Instant T = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    private static final Instant NEWER = T.plusSeconds(60);
    private static final Instant OLDER = T.minusSeconds(60);

    static final List<UUID> CHANGED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class HouseEvents {
        @EventListener
        void on(HouseChangedEvent event) {
            CHANGED.add(event.houseId());
        }
    }

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
        CHANGED.clear();
    }

    // ---- houses -------------------------------------------------------------------------------------------------

    @Test
    void aHouseRetriedWithTheSameStampAndContentBurnsNoVersionAndPublishesNoEvent() {
        var id = UUID.randomUUID();
        var first = putHouse(id, "Green View", T);
        var counter = maxSyncVersion();
        CHANGED.clear();

        var again = putHouse(id, "Green View", T);

        assertThat(again).isEqualTo(first);
        assertThat(version(again)).isEqualTo(version(first));
        assertThat(maxSyncVersion()).isEqualTo(counter);
        assertThat(CHANGED).isEmpty();
    }

    @Test
    void aHouseWithTheSameStampAndOtherContentIsOverwrittenWithANewVersionAndAnEvent() {
        var id = UUID.randomUUID();
        var first = putHouse(id, "Green View", T);
        CHANGED.clear();

        var other = putHouse(id, "Blue Gate", T);

        assertThat(other.get("label")).isEqualTo("Blue Gate");
        assertThat(version(other)).isGreaterThan(version(first));
        assertThat(getHouse(id).get("label")).isEqualTo("Blue Gate");
        assertThat(CHANGED).containsExactly(id);
    }

    @Test
    void aNewerHouseOverwritesAnOlderOneIsKept() {
        var id = UUID.randomUUID();
        var first = putHouse(id, "Green View", T);

        var newer = putHouse(id, "Newer", NEWER);
        assertThat(newer.get("label")).isEqualTo("Newer");
        assertThat(version(newer)).isGreaterThan(version(first));

        var counter = maxSyncVersion();
        CHANGED.clear();
        var older = putHouse(id, "Older", OLDER);
        assertThat(older.get("label")).isEqualTo("Newer");
        assertThat(version(older)).isEqualTo(version(newer));
        assertThat(getHouse(id).get("label")).isEqualTo("Newer");
        assertThat(maxSyncVersion()).isEqualTo(counter);
        assertThat(CHANGED).isEmpty();
    }

    @Test
    void aDeletedHouseRetriedWithTheSameStampIsAlsoLeftAlone() {
        var id = UUID.randomUUID();
        putHouse(id, "Green View", T);
        var tomb = new HashMap<String, Object>();
        tomb.put("label", "");
        tomb.put("lat", 0.0);
        tomb.put("lon", 0.0);
        tomb.put("deleted", true);
        tomb.put("updatedAt", NEWER.toString());
        var deleted = api.put().uri("/api/houses/{id}", id).contentType(MediaType.APPLICATION_JSON).body(tomb)
                .retrieve().body(MAP);
        var counter = maxSyncVersion();

        var again = api.put().uri("/api/houses/{id}", id).contentType(MediaType.APPLICATION_JSON).body(tomb)
                .retrieve().body(MAP);

        assertThat(version(again)).isEqualTo(version(deleted));
        assertThat(maxSyncVersion()).isEqualTo(counter);
    }

    // ---- visits -------------------------------------------------------------------------------------------------

    @Test
    void aVisitRetriedWithTheSameStampAndContentBurnsNoVersion() {
        var id = UUID.randomUUID();
        var first = putVisit(id, "MG Road", T);
        var counter = maxSyncVersion();

        var again = putVisit(id, "MG Road", T);

        assertThat(again).isEqualTo(first);
        assertThat(maxSyncVersion()).isEqualTo(counter);
    }

    @Test
    void aVisitWithTheSameStampAndOtherContentIsOverwritten() {
        var id = UUID.randomUUID();
        var first = putVisit(id, "MG Road", T);

        var other = putVisit(id, "Brigade Road", T);

        assertThat(other.get("street")).isEqualTo("Brigade Road");
        assertThat(version(other)).isGreaterThan(version(first));
    }

    @Test
    void aNewerVisitOverwritesAnOlderOneIsKept() {
        var id = UUID.randomUUID();
        putVisit(id, "MG Road", T);
        var newer = putVisit(id, "Newer", NEWER);
        assertThat(newer.get("street")).isEqualTo("Newer");

        var counter = maxSyncVersion();
        var older = putVisit(id, "Older", OLDER);

        assertThat(older.get("street")).isEqualTo("Newer");
        assertThat(version(older)).isEqualTo(version(newer));
        assertThat(maxSyncVersion()).isEqualTo(counter);
    }

    // ---- records ------------------------------------------------------------------------------------------------

    @Test
    void aRecordRetriedWithTheSameStampAndPayloadBurnsNoVersion() {
        var first = putRecord("{\"name\":\"Ravi\"}", T);
        var counter = maxSyncVersion();

        var again = putRecord("{\"name\":\"Ravi\"}", T);

        assertThat(again).isEqualTo(first);
        assertThat(maxSyncVersion()).isEqualTo(counter);
    }

    @Test
    void aRecordWithTheSameStampAndOtherPayloadIsOverwritten() {
        var first = putRecord("{\"name\":\"Ravi\"}", T);

        var other = putRecord("{\"name\":\"Asha\"}", T);

        assertThat(other.get("payload")).isEqualTo(Map.of("name", "Asha"));
        assertThat(version(other)).isGreaterThan(version(first));
    }

    @Test
    void aNewerRecordOverwritesAnOlderOneIsKept() {
        putRecord("{\"name\":\"Ravi\"}", T);
        var newer = putRecord("{\"name\":\"Newer\"}", NEWER);
        assertThat(newer.get("payload")).isEqualTo(Map.of("name", "Newer"));

        var counter = maxSyncVersion();
        var older = putRecord("{\"name\":\"Older\"}", OLDER);

        assertThat(older.get("payload")).isEqualTo(Map.of("name", "Newer"));
        assertThat(version(older)).isEqualTo(version(newer));
        assertThat(maxSyncVersion()).isEqualTo(counter);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Map<String, Object> putHouse(UUID id, String label, Instant updatedAt) {
        var body = new HashMap<String, Object>();
        body.put("label", label);
        body.put("lat", 12.97);
        body.put("lon", 77.59);
        body.put("street", "MG Road");
        body.put("status", "NEW");
        body.put("checklist", Map.of("water", 5));
        body.put("updatedAt", updatedAt.toString());
        return api.put().uri("/api/houses/{id}", id).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(MAP);
    }

    private Map<String, Object> getHouse(UUID id) {
        return api.get().uri("/api/houses/{id}", id).retrieve().body(MAP);
    }

    private Map<String, Object> putVisit(UUID id, String street, Instant updatedAt) {
        var body = new HashMap<String, Object>();
        body.put("lat", 12.97);
        body.put("lon", 77.59);
        body.put("street", street);
        body.put("arrivedAt", T.minusSeconds(600).toString());
        body.put("source", "MANUAL");
        body.put("updatedAt", updatedAt.toString());
        return api.put().uri("/api/visits/{id}", id).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(MAP);
    }

    private Map<String, Object> putRecord(String payload, Instant updatedAt) {
        var body = "{\"type\":\"broker\",\"id\":\"b1\",\"payload\":" + payload + ",\"updatedAt\":\"" + updatedAt
                + "\",\"deleted\":false}";
        return api.put().uri("/api/records/{type}/{id}", "broker", "b1").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(MAP);
    }

    private long maxSyncVersion() {
        return ((Number) api.get().uri("/api/stats").retrieve().body(MAP).get("maxSyncVersion")).longValue();
    }

    private static long version(Map<String, Object> row) {
        return ((Number) row.get("syncVersion")).longValue();
    }
}
