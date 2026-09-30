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

package app.doorprints.server.photo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 5 against a real PostGIS database (see {@code ApiIntegrationTest}): {@code PUT /api/photos/{id}/meta} with
 * last-write-wins on {@code metaUpdatedAt}, the change feed that carries it, the refusals, the tombstone that blanks
 * it, and a house's {@code moveIn} over the sync API. Like the other database tests it wipes the data first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.rate-limit.requests-per-minute=60000", "app.rate-limit.burst=6000",
                "app.rate-limit.auth-failures-per-minute=10000", "app.rate-limit.auth-failure-burst=10000"})
@ResourceLock("database")
class PhotoMetaApiTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final String KEY = "photo-meta-it-" + UUID.randomUUID();
    private static final String SECRET = "my secret words";

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    RestClient api;
    RestClient anonymous;
    UUID houseId;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-API-Key", KEY).build();
        anonymous = RestClient.create("http://localhost:" + port);
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
        houseId = UUID.randomUUID();
        putHouse(houseId, null);
    }

    private Map<String, Object> putHouse(UUID id, Map<String, Object> moveIn) {
        var body = new HashMap<String, Object>();
        body.put("label", "Green View");
        body.put("lat", 12.9);
        body.put("lon", 77.6);
        body.put("status", moveIn == null ? "NEW" : "TAKEN");
        body.put("checklist", Map.of());
        body.put("updatedAt", Instant.now().toString());
        if (moveIn != null) body.put("moveIn", moveIn);
        return api.put().uri("/api/houses/{id}", id).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(MAP);
    }

    private UUID uploadPhoto() {
        var photoId = UUID.randomUUID();
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("id", photoId.toString());
        form.add("file", new ByteArrayResource(ImageSanitizerTest.jpegWithExif()) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        api.post().uri("/api/houses/{id}/photos", houseId).contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .retrieve().toBodilessEntity();
        return photoId;
    }

    private Map<String, Object> meta(String roomId, List<String> tags, String caption, long at) {
        var body = new HashMap<String, Object>();
        body.put("roomId", roomId);
        body.put("tags", tags);
        body.put("caption", caption);
        body.put("metaUpdatedAt", at);
        return body;
    }

    private Map<String, Object> putMeta(UUID photoId, Map<String, Object> body) {
        return api.put().uri("/api/photos/{id}/meta", photoId).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(MAP);
    }

    private Map<String, Object> feedRow(UUID photoId) {
        return api.get().uri("/api/photos?since=0").retrieve().body(LIST).stream()
                .filter(p -> p.get("id").equals(photoId.toString())).findFirst().orElseThrow();
    }

    private static long syncVersion(Map<String, Object> row) {
        return ((Number) row.get("syncVersion")).longValue();
    }

    private static int status(Supplier<?> call) {
        try {
            call.get();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    private static String errorBody(Supplier<?> call) {
        try {
            call.get();
            return "";
        } catch (RestClientResponseException e) {
            return e.getResponseBodyAsString();
        }
    }

    private static final long T0 = Instant.now().minusSeconds(3600).toEpochMilli();

    @Test
    void aNewPhotoHasNoMetaInTheChangeFeed() {
        var id = uploadPhoto();
        var row = feedRow(id);
        assertThat(row.get("roomId")).isNull();
        assertThat(row.get("tags")).isNull();
        assertThat(row.get("caption")).isNull();
        assertThat(((Number) row.get("metaUpdatedAt")).longValue()).isZero();
    }

    /** The photo-meta last-write-wins rule: an edit that is not newer changes nothing and is answered with the current meta. */
    @Test
    void photoMetaLastWriteWinsOnMetaUpdatedAt() {
        var id = uploadPhoto();
        var first = putMeta(id, meta("c1", List.of("KITCHEN_FITTINGS", "damp corner"), "Kitchen at move-in", T0));
        assertThat(first).containsEntry("roomId", "c1").containsEntry("caption", "Kitchen at move-in");
        assertThat(first.get("tags")).isEqualTo(List.of("KITCHEN_FITTINGS", "damp corner"));
        assertThat(((Number) first.get("metaUpdatedAt")).longValue()).isEqualTo(T0);
        var afterFirst = feedRow(id);
        assertThat(afterFirst.get("tags")).isEqualTo(List.of("KITCHEN_FITTINGS", "damp corner"));
        var version = syncVersion(afterFirst);
        assertThat(version).isGreaterThan(0);

        // Older: nothing changes, nothing is bumped, and the answer is what the server holds.
        var older = putMeta(id, meta("c2", List.of("LEAK"), "older words", T0 - 1000));
        assertThat(older).containsEntry("roomId", "c1").containsEntry("caption", "Kitchen at move-in");
        assertThat(((Number) older.get("metaUpdatedAt")).longValue()).isEqualTo(T0);
        assertThat(syncVersion(feedRow(id))).isEqualTo(version);

        // The same stamp is the same edit: nothing changes either.
        var same = putMeta(id, meta("c3", List.of("LEAK"), "same stamp", T0));
        assertThat(same).containsEntry("roomId", "c1");
        assertThat(syncVersion(feedRow(id))).isEqualTo(version);

        // Newer wins, bumps the sync version so the change feed carries it, and can clear the fields.
        var newer = putMeta(id, meta("c2", List.of("LEAK", "MOVE_IN"), "newer words", T0 + 1000));
        assertThat(newer).containsEntry("roomId", "c2").containsEntry("caption", "newer words");
        var afterNewer = feedRow(id);
        assertThat(syncVersion(afterNewer)).isGreaterThan(version);
        assertThat(afterNewer.get("tags")).isEqualTo(List.of("LEAK", "MOVE_IN"));
        var cleared = putMeta(id, meta("", List.of(), "", T0 + 2000));
        assertThat(cleared.get("roomId")).isNull();
        assertThat(cleared.get("tags")).isNull();
        assertThat(cleared.get("caption")).isNull();
        assertThat(((Number) cleared.get("metaUpdatedAt")).longValue()).isEqualTo(T0 + 2000);
    }

    @Test
    void anUnknownOrDeletedPhotoIs404() {
        assertThat(status(() -> putMeta(UUID.randomUUID(), meta("c1", List.of(), null, T0)))).isEqualTo(404);
        var id = uploadPhoto();
        api.delete().uri("/api/photos/{id}", id).retrieve().toBodilessEntity();
        assertThat(status(() -> putMeta(id, meta("c1", List.of(), null, T0)))).isEqualTo(404);
    }

    @Test
    void theMetaRouteNeedsTheApiKeyAndIsNotCached() {
        var id = uploadPhoto();
        assertThat(status(() -> anonymous.put().uri("/api/photos/{id}/meta", id).contentType(MediaType.APPLICATION_JSON)
                .body(meta("c1", List.of(), null, T0)).retrieve().toBodilessEntity())).isEqualTo(401);
        var response = api.put().uri("/api/photos/{id}/meta", id).contentType(MediaType.APPLICATION_JSON)
                .body(meta("c1", List.of(), null, T0)).retrieve().toBodilessEntity();
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("no-store");
    }

    @Test
    void everyRuleBrokenIsRefusedWithoutEchoingTheCallersText() {
        var id = uploadPhoto();
        var eleven = new ArrayList<String>();
        for (int i = 0; i < 11; i++) eleven.add(SECRET + i);
        var bad = new ArrayList<Map<String, Object>>();
        bad.add(meta(null, List.of(SECRET.repeat(3)), null, T0));
        bad.add(meta(null, eleven, null, T0));
        bad.add(meta(null, List.of("damp"), null, T0));
        bad.add(meta(null, List.of("kitchen_fittings"), null, T0));
        bad.add(meta(null, List.of(SECRET, SECRET.toUpperCase()), null, T0));
        bad.add(meta(null, List.of(""), null, T0));
        bad.add(meta(null, null, SECRET.repeat(20), T0));
        bad.add(meta(SECRET.repeat(5), null, null, T0));
        bad.add(meta(null, null, null, -1));
        for (var body : bad) {
            assertThat(status(() -> putMeta(id, body))).as(body.toString()).isEqualTo(400);
            assertThat(errorBody(() -> putMeta(id, body))).doesNotContain("secret").doesNotContain("SECRET")
                    .doesNotContain("damp").doesNotContain("kitchen_fittings");
        }
        assertThat(feedRow(id).get("tags")).as("nothing was written").isNull();
        // An absurd or far-future stamp is a clock bug: refused without echoing it.
        var tooOld = errorBody(() -> putMeta(id, meta(null, null, null, 1000)));
        assertThat(tooOld).contains("metaUpdatedAt").doesNotContain("1000").doesNotContain("1970");
        assertThat(status(() -> putMeta(id, meta(null, null, null, Instant.now().plusSeconds(86400L * 400).toEpochMilli()))))
                .isEqualTo(400);
        // A stamp a little ahead of the server clock is clamped to now, as every client stamp is.
        var clamped = putMeta(id, meta(null, null, "fast clock", Instant.now().plusSeconds(3600).toEpochMilli()));
        assertThat(((Number) clamped.get("metaUpdatedAt")).longValue()).isLessThanOrEqualTo(Instant.now().toEpochMilli());
    }

    /** F-15/F-16: deleting a photo leaves a tombstone without the person's words. */
    @Test
    void deletingAPhotoBlanksItsMeta() {
        var id = uploadPhoto();
        putMeta(id, meta("c1", List.of("MOVE_IN"), "private words", T0));
        api.delete().uri("/api/photos/{id}", id).retrieve().toBodilessEntity();
        var row = feedRow(id);
        assertThat(row.get("deleted")).isEqualTo(true);
        assertThat(row.get("roomId")).isNull();
        assertThat(row.get("tags")).isNull();
        assertThat(row.get("caption")).isNull();
        assertThat(((Number) row.get("metaUpdatedAt")).longValue()).isZero();
    }

    /** The tombstone purge of a house takes its photos' meta and its move-in with it. */
    @Test
    void theTombstonePurgeOfAHouseBlanksTheMoveInAndThePhotoMeta() {
        var moveIn = Map.<String, Object>of("date", T0, "notes", "Keys handed over by Ravi",
                "items", List.of(Map.of("id", "mi_agreement", "text", "Agreement signed", "done", true, "sort", 0)));
        var saved = putHouse(houseId, moveIn);
        assertThat(saved.get("moveIn")).isEqualTo(moveIn);
        var id = uploadPhoto();
        putMeta(id, meta("c1", List.of("MOVE_IN"), "private words", T0));

        api.delete().uri("/api/houses/{id}", houseId).retrieve().toBodilessEntity();
        var house = api.get().uri("/api/houses?since=0").retrieve().body(LIST).stream()
                .filter(h -> h.get("id").equals(houseId.toString())).findFirst().orElseThrow();
        assertThat(house.get("deleted")).isEqualTo(true);
        assertThat(house.get("moveIn")).as("the tombstone keeps no move-in").isNull();
        var photo = feedRow(id);
        assertThat(photo.get("caption")).isNull();
        assertThat(photo.get("tags")).isNull();
        assertThat(photo.get("roomId")).isNull();
    }

    @Test
    void aMoveInOverTheSyncApiRoundTripsAndAnEmptyOneIsNone() {
        var moveIn = new HashMap<String, Object>();
        moveIn.put("date", T0);
        moveIn.put("items", List.of(Map.of("id", "mi_police", "text", "Police verification done", "sort", 1)));
        var saved = putHouse(houseId, moveIn);
        assertThat(saved.get("status")).isEqualTo("TAKEN");
        assertThat(saved.get("moveIn")).isEqualTo(Map.of("date", T0, "items",
                List.of(Map.of("id", "mi_police", "text", "Police verification done", "sort", 1))));
        assertThat(putHouse(houseId, Map.of()).get("moveIn")).as("an empty object is no move-in").isNull();
        assertThat(putHouse(houseId, null).get("moveIn")).isNull();
    }

    @Test
    void aBadMoveInIsRefusedWithoutEchoingTheCallersText() {
        var many = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 31; i++) many.add(Map.of("id", "i" + i, "text", "Item", "sort", i));
        var bad = List.<Map<String, Object>>of(
                Map.of("date", 0),
                Map.of("date", -1),
                Map.of("notes", SECRET.repeat(200)),
                Map.of("items", many),
                Map.of("items", List.of(Map.of("id", "has space " + SECRET, "text", "Item", "sort", 0))),
                Map.of("items", List.of(Map.of("id", "a", "text", "Item", "sort", 0),
                        Map.of("id", "a", "text", "Again", "sort", 1))),
                Map.of("items", List.of(Map.of("id", "a", "text", "  ", "sort", 0))),
                Map.of("items", List.of(Map.of("id", "a", "text", SECRET.repeat(20), "sort", 0))),
                Map.of("items", List.of(Map.of("id", "a", "text", "Item", "sort", -1))));
        for (var moveIn : bad) {
            assertThat(status(() -> putHouse(houseId, moveIn))).as(moveIn.toString()).isEqualTo(400);
            assertThat(errorBody(() -> putHouse(houseId, moveIn))).doesNotContain("secret");
        }
        assertThat(status(() -> putHouse(houseId, Map.of("items", many.subList(0, 30))))).isEqualTo(200);
    }
}
