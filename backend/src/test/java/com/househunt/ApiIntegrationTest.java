package com.househunt;

import com.househunt.photo.ImageSanitizerTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against a real PostGIS database (see DB_URL; CI starts one as a service container).
 *
 * <p>That database is shared with {@code backup.BackupApiTest}, whose set-up wipes every row with
 * {@code DELETE /api/data}. Surefire runs test classes one after another today, so the two never overlap;
 * {@code @ResourceLock("database")} on both keeps it that way if JUnit parallel execution is ever switched on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Many tests deliberately fail authentication from 127.0.0.1; keep the brute-force throttle out of
                // their way (it has its own unit test in ApiKeyFilterTest).
                "app.rate-limit.auth-failures-per-minute=10000",
                "app.rate-limit.auth-failure-burst=10000",
                "app.limits.max-photos-per-house=3"})
@ResourceLock("database")
class ApiIntegrationTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    /** Generated per run (never a literal in source, so secret scanners have nothing to flag); 39 chars, >= 32 (F-01). */
    private static final String KEY = "it-" + UUID.randomUUID();
    /** Second key (APP_API_KEY_NEXT) so the rotation path is exercised end to end (SEC-017). */
    private static final String NEXT_KEY = "it-next-" + UUID.randomUUID();

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
        registry.add("app.api-key-next", () -> NEXT_KEY);
    }

    @Value("${local.server.port}")
    int port;

    RestClient api;
    RestClient anonymous;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-API-Key", KEY).build();
        anonymous = RestClient.create("http://localhost:" + port);
    }

    private Map<String, Object> house(String label, double lat, double lon, String street) {
        var body = new HashMap<String, Object>();
        body.put("label", label);
        body.put("lat", lat);
        body.put("lon", lon);
        body.put("street", street);
        body.put("status", "NEW");
        body.put("rating", 4);
        body.put("checklist", Map.of("water", 5, "parking", 2));
        body.put("updatedAt", Instant.now().toString());
        return body;
    }

    private Map<String, Object> put(UUID id, Map<String, Object> body) {
        return api.put().uri("/api/houses/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(MAP);
    }

    /** HTTP status of a call, whether it succeeded or failed. */
    private static int status(Supplier<?> call) {
        try {
            call.get();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    private long maxVersion(String path) {
        return api.get().uri(path + "?since=0").retrieve().body(LIST).stream()
                .mapToLong(h -> ((Number) h.get("syncVersion")).longValue()).max().orElse(0);
    }

    private UUID uploadPhoto(UUID houseId, byte[] bytes) {
        return uploadPhoto(houseId, UUID.randomUUID(), bytes);
    }

    /** Uploads under a client-chosen id, as the apps do, and checks that the server answers with that id. */
    private UUID uploadPhoto(UUID houseId, UUID photoId, byte[] bytes) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("id", photoId.toString());
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        var result = api.post().uri("/api/houses/{id}/photos", houseId).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).retrieve().body(MAP);
        assertThat(result).containsEntry("id", photoId.toString());
        return photoId;
    }

    @Test
    void rejectsRequestsWithoutKey() {
        assertThatThrownBy(() -> anonymous.get().uri("/api/houses").retrieve().body(String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(401)));
    }

    /** SEC-017: while APP_API_KEY_NEXT is set, both keys are accepted (header and bearer). */
    @Test
    void acceptsTheNextKeyDuringRotation() {
        var base = "http://localhost:" + port;
        var withNext = RestClient.builder().baseUrl(base).defaultHeader("X-API-Key", NEXT_KEY).build();
        assertThat(withNext.get().uri("/api/stats").retrieve().toEntity(String.class).getStatusCode().value())
                .isEqualTo(200);
        var bearerNext = RestClient.builder().baseUrl(base).defaultHeader("Authorization", "Bearer " + NEXT_KEY).build();
        assertThat(bearerNext.get().uri("/api/stats").retrieve().toEntity(String.class).getStatusCode().value())
                .isEqualTo(200);
        var wrong = RestClient.builder().baseUrl(base).defaultHeader("X-API-Key", NEXT_KEY + "x").build();
        assertThatThrownBy(() -> wrong.get().uri("/api/stats").retrieve().body(String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(401)));
    }

    @Test
    void healthIsPublic() {
        assertThat(anonymous.get().uri("/actuator/health").retrieve().body(String.class)).contains("UP");
    }

    /** F-20 (TC-S-10): path tricks never reach a controller without the key, and are refused even with it. */
    @Test
    void pathTricksCannotBypassTheKeyFilter() {
        var base = "http://localhost:" + port;
        // The JDK client sends the path exactly as given (no dot-segment normalisation on the client side).
        var rawAnonymous = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
        var rawWithKey = RestClient.builder().requestFactory(new JdkClientHttpRequestFactory())
                .defaultHeader("X-API-Key", KEY).build();
        for (var path : List.of("/api;x/houses", "/api/houses;jsessionid=1", "/%61pi/houses", "//api/houses",
                "/api//houses", "/api/./houses", "/api/houses.", "/actuator/health;x/../../api/houses",
                "/API/houses", "/api/houses/")) {
            var uri = URI.create(base + path);
            assertThat(status(() -> rawAnonymous.get().uri(uri).retrieve().body(String.class)))
                    .as("anonymous %s", path).isIn(400, 401);
            assertThat(status(() -> rawWithKey.get().uri(uri).retrieve().body(String.class)))
                    .as("with key %s", path).isIn(400, 404);
        }
    }

    @Test
    void securityHeadersAndNoStoreOnJson() {
        var response = api.get().uri("/api/stats").retrieve().toEntity(String.class);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
    }

    @Test
    void createsHouseAndFindsItNearbyAndOnStreet() {
        var id = UUID.randomUUID();
        var street = "Test Street " + id;
        var saved = put(id, house("Blue gate house", 12.97160, 77.59460, street));
        assertThat(saved.get("label")).isEqualTo("Blue gate house");
        assertThat(((Map<?, ?>) saved.get("checklist")).get("water")).isEqualTo(5);

        // ~20 m away: found within 50 m, not within 5 m
        var near = api.get().uri("/api/houses/nearby?lat=12.97178&lon=77.59460&radius=50").retrieve().body(LIST);
        assertThat(near).anySatisfy(h -> {
            assertThat(h.get("id")).isEqualTo(id.toString());
            assertThat(((Number) h.get("distanceMeters")).doubleValue()).isBetween(15.0, 25.0);
        });
        var tooFar = api.get().uri("/api/houses/nearby?lat=12.97178&lon=77.59460&radius=5").retrieve().body(LIST);
        assertThat(tooFar).noneMatch(h -> h.get("id").equals(id.toString()));

        var onStreet = api.get().uri("/api/houses/street?name={s}", street.toUpperCase()).retrieve().body(LIST);
        assertThat(onStreet).hasSize(1);
    }

    /** OSI L6: Indic scripts survive JSON, the query string, the database and case-insensitive street search. */
    @Test
    void indicTextRoundTripsEndToEnd() {
        var id = UUID.randomUUID();
        var street = "காந்தி தெரு " + id; // Tamil
        var body = house("నీలం గేటు ఇల్లు", 13.0827, 80.2707, street); // Telugu label
        body.put("notes", "पानी का दबाव अच्छा है"); // Hindi
        var saved = put(id, body);
        assertThat(saved.get("label")).isEqualTo("నీలం గేటు ఇల్లు");
        assertThat(saved.get("notes")).isEqualTo("पानी का दबाव अच्छा है");
        var onStreet = api.get().uri("/api/houses/street?name={s}", street).retrieve().body(LIST);
        assertThat(onStreet).singleElement().satisfies(h -> assertThat(h.get("street")).isEqualTo(street));
    }

    @Test
    void nearbyRejectsOutOfRangeParameters() {
        assertThat(status(() -> api.get().uri("/api/houses/nearby?lat=95&lon=77.6").retrieve().body(String.class)))
                .isEqualTo(400);
        assertThat(status(() -> api.get().uri("/api/houses/nearby?lat=12&lon=77.6&radius=-1").retrieve()
                .body(String.class))).isEqualTo(400);
    }

    @Test
    void olderEditDoesNotOverwriteNewer() {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var newer = house("Newer name", 12.9, 77.6, null);
        newer.put("updatedAt", now.minus(Duration.ofMinutes(1)).toString());
        put(id, newer);

        var older = house("Older name", 12.9, 77.6, null);
        older.put("updatedAt", now.minus(Duration.ofMinutes(2)).toString());
        var result = put(id, older);
        assertThat(result.get("label")).isEqualTo("Newer name");
    }

    /** F-08: a fast client clock is clamped to server time, an absurd one is rejected. */
    @Test
    void clientClockIsClampedOrRejected() {
        var id = UUID.randomUUID();
        var ahead = house("Fast clock", 12.9, 77.6, null);
        ahead.put("updatedAt", Instant.now().plus(Duration.ofHours(3)).toString());
        var saved = put(id, ahead);
        assertThat(Instant.parse((String) saved.get("updatedAt"))).isBefore(Instant.now().plus(Duration.ofMinutes(5)));

        // A normal edit a moment later still wins (it would not have if the +3 h value had been stored).
        var edit = house("Edited", 12.9, 77.6, null);
        edit.put("updatedAt", Instant.now().plusSeconds(1).toString());
        assertThat(put(id, edit).get("label")).isEqualTo("Edited");

        var absurd = house("Year 2030", 12.9, 77.6, null);
        absurd.put("updatedAt", "2030-01-02T00:00:00Z");
        assertThat(status(() -> put(UUID.randomUUID(), absurd))).isEqualTo(400);
    }

    @Test
    void syncReturnsChangesIncludingDeletions() {
        long cursor = maxVersion("/api/houses");
        var id = UUID.randomUUID();
        put(id, house("To delete", 12.9, 77.6, null));
        api.delete().uri("/api/houses/{id}", id).retrieve().toBodilessEntity();

        var changes = api.get().uri("/api/houses?since={c}", cursor).retrieve().body(LIST);
        assertThat(changes).anySatisfy(h -> {
            assertThat(h.get("id")).isEqualTo(id.toString());
            assertThat(h.get("deleted")).isEqualTo(true);
        });
        var live = api.get().uri("/api/houses").retrieve().body(LIST);
        assertThat(live).noneMatch(h -> h.get("id").equals(id.toString()));
    }

    /** F-16: a deleted house keeps no content; its photos become tombstones and its visits are unlinked. */
    @Test
    void deletingAHousePurgesItsContent() {
        var id = UUID.randomUUID();
        var body = house("Private notes house", 12.9, 77.6, "Secret Street");
        body.put("notes", "landlord is rude");
        body.put("contactPhone", "+91 98450 12345");
        put(id, body);
        var photoId = uploadPhoto(id, ImageSanitizerTest.jpegWithExif());
        var visitId = UUID.randomUUID();
        api.put().uri("/api/visits/{id}", visitId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("houseId", id.toString(), "lat", 12.9, "lon", 77.6,
                        "arrivedAt", Instant.now().minus(Duration.ofHours(1)).toString(), "source", "AUTO"))
                .retrieve().body(MAP);
        long cursor = maxVersion("/api/houses");

        // The Android app deletes with PUT deleted=true; the web app with DELETE. Both must purge.
        var tombstone = house("Private notes house", 12.9, 77.6, "Secret Street");
        tombstone.put("deleted", true);
        put(id, tombstone);

        var changed = api.get().uri("/api/houses?since={c}", cursor - 1).retrieve().body(LIST).stream()
                .filter(h -> h.get("id").equals(id.toString())).findFirst().orElseThrow();
        assertThat(changed.get("deleted")).isEqualTo(true);
        assertThat(changed.get("notes")).isNull();
        assertThat(changed.get("contactPhone")).isNull();
        assertThat(changed.get("street")).isNull();
        assertThat(changed.get("label")).isEqualTo("");

        assertThat(status(() -> api.get().uri("/api/photos/{id}", photoId).retrieve().body(byte[].class)))
                .isEqualTo(404);
        var visit = api.get().uri("/api/visits?since=0").retrieve().body(LIST).stream()
                .filter(v -> v.get("id").equals(visitId.toString())).findFirst().orElseThrow();
        assertThat(visit.get("houseId")).isNull();
    }

    /*
     * Undelete after a synced delete. Android 1.14 (android/shared/README.md section 8, twelfth round; device check
     * 10b) brings a house back from a backup on a phone that has already pulled the server's tombstone. It relies on
     * exactly the server behaviour pinned below, and nothing else caught a regression of it before these tests:
     *   - PUT /api/houses/{id} with deleted=false and a newer updatedAt over the purged tombstone makes the house live
     *     again under its own id and createdAt; an older updatedAt leaves the tombstone as it is and syncs nothing;
     *   - restoring the house does not undo the purge: its visits stay unlinked and its photos stay tombstones;
     *   - a visit is relinked by a PUT with houseId set whose updatedAt beats the unlink stamp (server time of the
     *     delete, not the house tombstone's updatedAt), which is why Android stamps max(now, own + 1 ms);
     *   - a photo upload under the old, tombstoned id answers 200 with that id and stores nothing ("a deleted photo
     *     is never resurrected", PhotoService.upload), while a fresh id is stored, which is why Android gives the
     *     photos of such a house new ids (README section 9 item 18 is the optional alternative);
     *   - a sync pull after the tombstone's version hands out the live house, the relinked visit and the new photo,
     *     and not the old photo id.
     * Android deletes with PUT deleted=true and the web app with DELETE; the phone may have synced either.
     */

    @Test
    void aNewerWriteBringsBackAHouseDeletedWithPutAndSyncsIt() {
        restoreAfterASyncedDelete(true);
    }

    @Test
    void aNewerWriteBringsBackAHouseDeletedWithDeleteAndSyncsIt() {
        restoreAfterASyncedDelete(false);
    }

    private void restoreAfterASyncedDelete(boolean deleteWithPut) {
        var jpeg = ImageSanitizerTest.jpegWithExif();
        var id = UUID.randomUUID();
        var street = "Temple Street " + id;
        // Millisecond values so that comparisons after the database round trip (microseconds) are exact.
        var createdAt = Instant.now().minus(Duration.ofDays(3)).truncatedTo(ChronoUnit.MILLIS);
        var original = house("Bring me back", 12.9, 77.6, street);
        original.put("notes", "south-facing balcony");
        original.put("createdAt", createdAt.toString());
        var editedAt = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.MILLIS);
        original.put("updatedAt", editedAt.toString());
        put(id, original);
        var oldPhotoId = uploadPhoto(id, UUID.randomUUID(), jpeg);
        var visitId = UUID.randomUUID();
        var arrivedAt = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.MILLIS);
        putVisit(visitId, visit(id, arrivedAt, Instant.now().minus(Duration.ofMinutes(10))));

        // 1. The delete reaches the server, which purges the house, tombstones its photo and unlinks its visit.
        Map<String, Object> tombstone;
        if (deleteWithPut) {
            var body = house("Bring me back", 12.9, 77.6, street);
            body.put("deleted", true);
            body.put("updatedAt", Instant.now().minus(Duration.ofMinutes(5)).truncatedTo(ChronoUnit.MILLIS).toString());
            tombstone = put(id, body);
        } else {
            api.delete().uri("/api/houses/{id}", id).retrieve().toBodilessEntity();
            tombstone = api.get().uri("/api/houses/{id}", id).retrieve().body(MAP);
        }
        assertThat(tombstone).containsEntry("deleted", true).containsEntry("label", "");
        long cursor = version(tombstone);
        var tombstonedAt = instant(tombstone, "updatedAt");
        // What the phone pulled: the unlinked visit and the photo tombstone carry the tombstone's version.
        var unlinked = row(changes("/api/visits", cursor - 1), visitId);
        assertThat(unlinked).isNotNull();
        assertThat(unlinked.get("houseId")).isNull();
        var unlinkedAt = instant(unlinked, "updatedAt");
        assertThat(row(changes("/api/photos", cursor - 1), oldPhotoId)).isNotNull().containsEntry("deleted", true);

        // 2. A live write older than the tombstone (a stale edit, or a backup row not stamped anew) changes nothing.
        var stale = house("Bring me back", 12.9, 77.6, street);
        stale.put("updatedAt", tombstonedAt.minus(Duration.ofMinutes(1)).toString());
        assertThat(put(id, stale)).containsEntry("deleted", true).containsEntry("label", "");
        assertThat(row(changes("/api/houses", cursor), id)).isNull();
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST))
                .noneMatch(h -> id.toString().equals(h.get("id")));

        // 3. The undelete: same id, deleted=false, updatedAt newer than the tombstone.
        var restore = house("Bring me back", 12.9, 77.6, street);
        restore.put("notes", "south-facing balcony");
        restore.put("createdAt", createdAt.toString());
        restore.put("updatedAt", later(Instant.now(), tombstonedAt).toString());
        var restored = put(id, restore);
        assertThat(restored).containsEntry("deleted", false).containsEntry("label", "Bring me back")
                .containsEntry("street", street).containsEntry("notes", "south-facing balcony");
        assertThat(instant(restored, "createdAt")).isEqualTo(createdAt);
        assertThat(version(restored)).isGreaterThan(cursor);
        // Bringing the house back does not undo the purge.
        assertThat(api.get().uri("/api/visits?houseId={h}", id).retrieve().body(LIST)).isEmpty();
        assertThat(api.get().uri("/api/houses/{id}/photos", id).retrieve().body(List.class)).isEmpty();

        // 4. Visits: a relink has to beat the server's unlink stamp; being newer than the house delete is not enough.
        assertThat(putVisit(visitId, visit(id, arrivedAt, unlinkedAt.minusMillis(1))).get("houseId")).isNull();
        assertThat(putVisit(visitId, visit(id, arrivedAt, later(Instant.now(), unlinkedAt))))
                .containsEntry("houseId", id.toString()).containsEntry("deleted", false);
        assertThat(api.get().uri("/api/visits?houseId={h}", id).retrieve().body(LIST))
                .singleElement().satisfies(v -> assertThat(v.get("id")).isEqualTo(visitId.toString()));

        // 5. Photos: an upload under the tombstoned id is accepted and ignored; a fresh id is stored.
        uploadPhoto(id, oldPhotoId, jpeg);
        assertThat(status(() -> api.get().uri("/api/photos/{id}", oldPhotoId).retrieve().body(byte[].class)))
                .isEqualTo(404);
        var freshPhotoId = uploadPhoto(id, UUID.randomUUID(), jpeg);
        assertThat(api.get().uri("/api/houses/{id}/photos", id).retrieve().body(List.class))
                .containsExactly(freshPhotoId.toString());
        assertThat(api.get().uri("/api/photos/{id}", freshPhotoId).retrieve().body(byte[].class)).isNotEmpty();

        // 6. The next pull from the tombstone's version brings every other device the restored state.
        assertThat(row(changes("/api/houses", cursor), id)).isNotNull()
                .containsEntry("deleted", false).containsEntry("label", "Bring me back");
        assertThat(row(changes("/api/visits", cursor), visitId)).isNotNull()
                .containsEntry("houseId", id.toString()).containsEntry("deleted", false);
        var photoChanges = changes("/api/photos", cursor);
        assertThat(row(photoChanges, freshPhotoId)).isNotNull()
                .containsEntry("houseId", id.toString()).containsEntry("deleted", false);
        assertThat(row(photoChanges, oldPhotoId)).isNull();
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST))
                .anyMatch(h -> id.toString().equals(h.get("id")));
    }

    private Map<String, Object> visit(UUID houseId, Instant arrivedAt, Instant updatedAt) {
        var body = new HashMap<String, Object>();
        body.put("houseId", houseId.toString());
        body.put("lat", 12.9);
        body.put("lon", 77.6);
        body.put("arrivedAt", arrivedAt.toString());
        body.put("source", "AUTO");
        body.put("updatedAt", updatedAt.toString());
        return body;
    }

    private Map<String, Object> putVisit(UUID id, Map<String, Object> body) {
        return api.put().uri("/api/visits/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(MAP);
    }

    /** A sync pull: every row of {@code path} whose version is greater than {@code since}. */
    private List<Map<String, Object>> changes(String path, long since) {
        return api.get().uri(path + "?since={c}", since).retrieve().body(LIST);
    }

    /** The row with this id, or null. */
    private static Map<String, Object> row(List<Map<String, Object>> rows, UUID id) {
        return rows.stream().filter(r -> id.toString().equals(r.get("id"))).findFirst().orElse(null);
    }

    private static Instant instant(Map<String, Object> row, String field) {
        return Instant.parse((String) row.get(field));
    }

    private static long version(Map<String, Object> row) {
        return ((Number) row.get("syncVersion")).longValue();
    }

    /** max(now, previous + 1 ms): how the apps stamp a write that must win over {@code previous}. */
    private static Instant later(Instant now, Instant previous) {
        var next = previous.plusMillis(1);
        return now.isAfter(next) ? now : next;
    }

    /** F-15, F-07, F-06: photo tombstones sync, metadata is stripped, the count is capped. */
    @Test
    void photoUploadStripsMetadataAndDeletesSyncAsTombstones() {
        var houseId = UUID.randomUUID();
        put(houseId, house("Photo house", 12.9, 77.6, null));
        long cursor = maxVersion("/api/houses");

        var photoId = uploadPhoto(houseId, ImageSanitizerTest.jpegWithExif());
        var stored = api.get().uri("/api/photos/{id}", photoId).retrieve().toEntity(byte[].class);
        assertThat(stored.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(new String(stored.getBody(), StandardCharsets.ISO_8859_1)).doesNotContain("GPSLatitude");

        // Retrying the same upload (same client id) does not create a second photo.
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("id", photoId.toString());
        form.add("file", new ByteArrayResource(ImageSanitizerTest.jpegWithExif()) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        });
        api.post().uri("/api/houses/{id}/photos", houseId).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form).retrieve().body(MAP);
        assertThat(api.get().uri("/api/houses/{id}/photos", houseId).retrieve().body(List.class)).hasSize(1);

        api.delete().uri("/api/photos/{id}", photoId).retrieve().toBodilessEntity();
        var changes = api.get().uri("/api/photos?since={c}", cursor).retrieve().body(LIST);
        assertThat(changes).anySatisfy(p -> {
            assertThat(p.get("id")).isEqualTo(photoId.toString());
            assertThat(p.get("deleted")).isEqualTo(true);
        });
        assertThat(api.get().uri("/api/houses/{id}/photos", houseId).retrieve().body(List.class)).isEmpty();

        // Not an image -> 400; more than max-photos-per-house (3 in this test) -> 409.
        assertThat(status(() -> uploadPhoto(houseId, "<svg onload=alert(1)>".getBytes()))).isEqualTo(400);
        for (int i = 0; i < 3; i++) uploadPhoto(houseId, ImageSanitizerTest.jpegWithExif());
        assertThat(status(() -> uploadPhoto(houseId, ImageSanitizerTest.jpegWithExif()))).isEqualTo(409);
    }

    @Test
    void validatesInput() {
        var bad = house("", 200, 77.6, null);
        assertThatThrownBy(() -> put(UUID.randomUUID(), bad))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(400)));

        // label is required, but "" is a value: an unnamed house must be syncable and restorable (HouseDto).
        var noLabel = house("Nameless", 12.9, 77.6, null);
        noLabel.remove("label");
        assertThat(status(() -> put(UUID.randomUUID(), noLabel))).isEqualTo(400);
        var emptyLabel = house("", 12.9, 77.6, null);
        assertThat(put(UUID.randomUUID(), emptyLabel).get("label")).isEqualTo("");
    }

    @Test
    void rejectsOversizedJsonBodies() {
        var big = "{\"label\":\"Big\",\"lat\":12.9,\"lon\":77.6,\"notes\":\"" + "x".repeat(300_000) + "\"}";
        assertThat(status(() -> api.put().uri("/api/houses/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).body(big).retrieve().body(String.class))).isIn(400, 413);
    }

    @Test
    void recordsVisits() {
        var houseId = UUID.randomUUID();
        put(houseId, house("Visited", 12.9, 77.6, "MG Road"));
        var visitId = UUID.randomUUID();
        api.put().uri("/api/visits/{id}", visitId).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("houseId", houseId.toString(), "lat", 12.9, "lon", 77.6,
                        "arrivedAt", "2026-09-01T10:00:00Z", "source", "AUTO"))
                .retrieve().body(MAP);
        var visits = api.get().uri("/api/visits?houseId={h}", houseId).retrieve().body(LIST);
        assertThat(visits).hasSize(1);
        var stats = api.get().uri("/api/stats").retrieve().body(MAP);
        assertThat(((Number) stats.get("visits")).longValue()).isGreaterThanOrEqualTo(1);
    }

    /**
     * The export speaks the shared backup format ({@code doorprints-backup/1}), the same object the Android and web
     * backups carry as {@code data.json}. Its schema is pinned against the canonical sample in
     * {@link com.househunt.backup.BackupApiTest}; this test only checks that the endpoint still hands it out.
     */
    @Test
    void exportContainsLiveDataAsAnAttachment() {
        var id = UUID.randomUUID();
        put(id, house("Exported house", 12.9, 77.6, null));
        var response = api.get().uri("/api/export").retrieve().toEntity(MAP);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment")
                .matches("attachment; filename=\"Doorprints-backup-\\d{4}-\\d{2}-\\d{2}\\.json\"");
        assertThat(response.getBody()).containsEntry("format", "doorprints-backup/1");
        assertThat((List<?>) response.getBody().get("houses"))
                .anySatisfy(h -> assertThat(((Map<?, ?>) h).get("id")).isEqualTo(id.toString()));
        assertThat(response.getBody()).containsKeys("visits", "photos", "exportedAt");
    }

    @Test
    void deleteAllNeedsTheConfirmationHeader() {
        assertThat(status(() -> api.delete().uri("/api/data").retrieve().toBodilessEntity())).isEqualTo(428);
        assertThat(status(() -> api.delete().uri("/api/data").header("X-Confirm-Delete", "yes").retrieve()
                .toBodilessEntity())).isEqualTo(428);

        put(UUID.randomUUID(), house("Soon gone", 12.9, 77.6, null));
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST)).isEmpty();
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
    }

    @Test
    void aiIsOffByDefault() {
        var status = api.get().uri("/api/ai/status").retrieve().body(MAP);
        assertThat(status).containsEntry("enabled", false).containsEntry("mcpEnabled", false);
        assertThatThrownBy(() -> api.post().uri("/api/ai/ask").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("question", "which house is cheapest?")).retrieve().body(String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode().value()).isIn(404, 405));
    }

    @Test
    void mcpPathRequiresKey() {
        assertThatThrownBy(() -> anonymous.get().uri("/mcp").retrieve().body(String.class))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(401)));
    }
}
