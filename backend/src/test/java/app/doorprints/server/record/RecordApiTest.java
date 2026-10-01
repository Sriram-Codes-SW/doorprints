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

package app.doorprints.server.record;

import app.doorprints.server.house.HouseChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /api/records}, the one sync endpoint pair of the Sprint 4b record envelope (docs/11 section 5.30, ADR-28).
 * Runs against the real PostGIS database like {@code ApiIntegrationTest}; the wipe in {@link #setUp} is shared
 * state, hence {@code @ResourceLock("database")} (see {@code BackupApiTest}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ResourceLock("database")
class RecordApiTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** Generated per run (never a literal in source, so secret scanners have nothing to flag); >= 32 chars. */
    private static final String KEY = "record-it-" + UUID.randomUUID();

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    RecordRepository repo;

    /** Every {@code HouseChangedEvent} published, as the AI indexer would receive it (S4b-BL-92d). */
    static final List<UUID> CHANGED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class HouseEvents {
        @EventListener
        void on(HouseChangedEvent event) {
            CHANGED.add(event.houseId());
        }
    }

    RestClient api;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-API-Key", KEY).build();
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
    }

    @Test
    void putStoresThePayloadOpaquelyAndTheFeedReturnsItAfterTheCursor() {
        var before = maxSyncVersion();
        var saved = put("broker", "b1", "{\"name\":\"Ravi\",\"phone\":\"98\",\"nested\":{\"a\":[1,2]}}", null, false);
        assertThat(saved).containsEntry("type", "broker").containsEntry("id", "b1").containsEntry("deleted", false);
        assertThat(saved.get("updatedAt")).isNotNull();
        assertThat(version(saved)).isGreaterThan(before);
        @SuppressWarnings("unchecked")
        var payload = (Map<String, Object>) saved.get("payload");
        assertThat(payload).containsEntry("name", "Ravi").containsKey("nested");

        put("place", "p1", "{\"lat\":12.9}", null, false);
        var all = list(before, null);
        assertThat(all).extracting(r -> r.get("type")).containsExactly("broker", "place");
        assertThat(list(version(saved), null)).extracting(r -> r.get("id")).containsExactly("p1");
        assertThat(list(before, "place")).extracting(r -> r.get("id")).containsExactly("p1");
        assertThat(list(version(all.get(1)), null)).isEmpty();
    }

    @Test
    void aViewingRefreshesTheAiDocumentOfItsHouseAndOfTheHouseItLeft() {
        // S4b-BL-92d: a viewing is part of its house's document, so writing or deleting one re-indexes that house.
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        CHANGED.clear();
        put("viewing", "v_00000001", "{\"houseId\":\"" + a + "\",\"startsAt\":1}", null, false);
        assertThat(CHANGED).containsExactly(a);
        CHANGED.clear();
        put("viewing", "v_00000001", "{\"houseId\":\"" + b + "\",\"startsAt\":1}", null, false);
        assertThat(CHANGED).as("moved to another house: both documents change").containsExactly(a, b);
        CHANGED.clear();
        api.delete().uri("/api/records/viewing/v_00000001").retrieve().toBodilessEntity();
        assertThat(CHANGED).containsExactly(b);
        CHANGED.clear();
        // Other record types, and a viewing whose house is not an id, publish nothing.
        put("broker", "b1", "{\"name\":\"Ravi\"}", null, false);
        put("viewing", "v_00000002", "{\"houseId\":\"not-a-uuid\"}", null, false);
        assertThat(CHANGED).isEmpty();
    }

    @Test
    void lastWriteWinsKeepsTheNewerRow() {
        var newer = Instant.now().minusSeconds(10);
        var older = newer.minusSeconds(60);
        var first = put("criterion", "c1", "{\"weight\":3}", newer, false);
        var second = put("criterion", "c1", "{\"weight\":1}", older, false);
        assertThat(second).containsEntry("payload", Map.of("weight", 3)).containsEntry("updatedAt", first.get("updatedAt"));
        assertThat(version(second)).isEqualTo(version(first));
    }

    @Test
    void deleteLeavesATombstoneWithAnEmptyPayloadInTheFeed() {
        var saved = put("viewing", "v1", "{\"at\":1}", null, false);
        var cursor = version(saved);
        assertThat(api.delete().uri("/api/records/viewing/v1").retrieve().toBodilessEntity().getStatusCode().value())
                .isEqualTo(204);
        var feed = list(cursor, null);
        assertThat(feed).hasSize(1);
        assertThat(feed.get(0)).containsEntry("id", "v1").containsEntry("deleted", true)
                .containsEntry("payload", Map.of());
        assertThat(status(() -> api.delete().uri("/api/records/viewing/nope").retrieve().toBodilessEntity()))
                .isEqualTo(404);
        // A tombstone sent by a client stores {} whatever payload it carries.
        var gone = put("viewing", "v2", "{\"at\":2}", null, true);
        assertThat(gone).containsEntry("deleted", true).containsEntry("payload", Map.of());
    }

    @Test
    void anInvalidPayloadTypeOrIdIsRefused() {
        assertThat(status(() -> put("broker", "b1", "[1,2]", null, false))).isEqualTo(400);
        assertThat(status(() -> put("broker", "b1", "\"text\"", null, false))).isEqualTo(400);
        assertThat(status(() -> put("broker", "b1", "{\"big\":\"" + "x".repeat(70 * 1024) + "\"}", null, false)))
                .isEqualTo(400);
        // The path and the body must name the same record.
        assertThat(status(() -> api.put().uri("/api/records/broker/b1").contentType(MediaType.APPLICATION_JSON)
                .body(body("broker", "b2", "{}", null, false)).retrieve().body(MAP))).isEqualTo(400);
        assertThat(status(() -> api.put().uri("/api/records/place/b1").contentType(MediaType.APPLICATION_JSON)
                .body(body("broker", "b1", "{}", null, false)).retrieve().body(MAP))).isEqualTo(400);
        assertThat(status(() -> put("Broker", "b1", "{}", null, false))).isEqualTo(400);
        assertThat(status(() -> put("bro-ker", "b1", "{}", null, false))).isEqualTo(400);
        assertThat(status(() -> put("broker", "b/1", "{}", null, false))).isEqualTo(400);
        assertThat(status(() -> api.get().uri("/api/records").retrieve().body(LIST))).isEqualTo(400);
        assertThat(status(() -> api.get().uri("/api/records?since=-1").retrieve().body(LIST))).isEqualTo(400);
    }

    @Test
    void aTypeIsCappedAtFiveThousandLiveRows() {
        var rows = new ArrayList<Record>(5_000);
        for (int i = 0; i < 5_000; i++) {
            var r = new Record(new RecordKey("place", "p" + i));
            r.setPayload("{\"n\":" + i + "}");
            r.setUpdatedAt(Instant.now());
            rows.add(r);
        }
        repo.saveAll(rows);
        assertThat(status(() -> put("place", "one-more", "{}", null, false))).isEqualTo(409);
        // An existing row can still change, another type is not affected, and a delete still goes through.
        assertThat(status(() -> put("place", "p1", "{\"n\":-1}", null, false))).isEqualTo(200);
        assertThat(status(() -> put("broker", "b1", "{}", null, false))).isEqualTo(200);
        assertThat(status(() -> put("place", "one-more", "{}", null, true))).isEqualTo(200);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private Map<String, Object> put(String type, String id, String payload, Instant updatedAt, boolean deleted) {
        return api.put().uri("/api/records/{type}/{id}", type, id).contentType(MediaType.APPLICATION_JSON)
                .body(body(type, id, payload, updatedAt, deleted)).retrieve().body(MAP);
    }

    private static String body(String type, String id, String payload, Instant updatedAt, boolean deleted) {
        return "{\"type\":\"" + type + "\",\"id\":\"" + id + "\",\"payload\":" + payload
                + (updatedAt == null ? "" : ",\"updatedAt\":\"" + updatedAt + "\"") + ",\"deleted\":" + deleted + "}";
    }

    private List<Map<String, Object>> list(long since, String type) {
        var uri = "/api/records?since=" + since + (type == null ? "" : "&type=" + type);
        return api.get().uri(uri).retrieve().body(LIST);
    }

    private long maxSyncVersion() {
        return ((Number) api.get().uri("/api/stats").retrieve().body(MAP).get("maxSyncVersion")).longValue();
    }

    private static long version(Map<String, Object> record) {
        return ((Number) record.get("syncVersion")).longValue();
    }

    private static int status(Supplier<?> call) {
        try {
            call.get();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }
}
