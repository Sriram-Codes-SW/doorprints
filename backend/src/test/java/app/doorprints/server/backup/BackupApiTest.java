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

import app.doorprints.server.photo.ImageSanitizerTest;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.skyscreamer.jsonassert.Customization;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.comparator.CustomComparator;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code GET /api/export} and {@code POST /api/import} against the canonical sample
 * ({@code docs/schemas/backup-sample.json}, story S4-00). The client halves of the same golden are Android's
 * {@code CanonicalSampleTest} (parsed JSON, like this suite) and the web writer's byte golden, which
 * {@code BackupParityTest} keeps identical to the sample (docs/schemas/README.md section 8.1).
 *
 * <p>Runs against a real PostGIS database like {@code ApiIntegrationTest}; every test starts from an empty
 * database, because the export is "everything the server holds" and cannot be checked against leftovers.
 *
 * <p><b>The wipe is shared-state.</b> {@link #setUp} calls {@code DELETE /api/data}, which hard-deletes every
 * house, visit and photo in the database that {@code DB_URL} names — the same database {@code ApiIntegrationTest}
 * uses (CI runs one PostGIS service container, not one per test class). That is safe only while Surefire runs test
 * classes one after another, which it does today: there is no {@code junit-platform.properties} and no parallel
 * configuration in {@code backend/pom.xml}. {@code @ResourceLock("database")} on both classes is a no-op until
 * then and becomes the actual guard the moment JUnit parallel execution is switched on; any new test class that
 * writes to that database should carry it too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Small caps so the 413 paths can be exercised with modest bodies. The sample is ~2.1 kB and 8
                // rows, so it still fits: max-json-bytes only applies to the other endpoints. The row cap is 60
                // rather than a handful so that the AI fan-out guard (BackupService.MAX_INDEX_EVENTS = 50) can be
                // crossed in one import without a second Spring context.
                "app.limits.max-json-bytes=2048",
                "app.limits.max-import-bytes=16384",
                "app.limits.max-import-rows=60",
                // The refusal tests each make a handful of calls; keep the per-client throttle (600/min, burst 300)
                // out of their way, it has its own tests.
                "app.rate-limit.requests-per-minute=60000",
                "app.rate-limit.burst=6000"})
@ResourceLock("database")
class BackupApiTest {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    /** Generated per run (never a literal in source, so secret scanners have nothing to flag); >= 32 chars. */
    private static final String KEY = "backup-it-" + UUID.randomUUID();

    /** The shared golden file, read once. */
    private static final String SAMPLE = CanonicalSample.json();

    @DynamicPropertySource
    static void apiKey(DynamicPropertyRegistry registry) {
        registry.add("app.api-key", () -> KEY);
    }

    @Value("${local.server.port}")
    int port;

    RestClient api;
    RestClient anonymous;

    @BeforeEach
    void setUp() {
        api = RestClient.builder().baseUrl("http://localhost:" + port).defaultHeader("X-API-Key", KEY).build();
        anonymous = RestClient.create("http://localhost:" + port);
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
    }

    /**
     * S4b-BL-20: deleting all data is not a reset. The rows go, but {@code maxSyncVersion} in {@code GET /api/stats}
     * comes from the sequence, which carries on, so a client's cursors stay at or below it and it does not re-send
     * everything as it would to a server restored from an older dump.
     */
    @Test
    void deleteAllKeepsTheHighestSyncVersion() {
        var saved = api.put().uri("/api/houses/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("label", "Before the wipe", "lat", 12.9, "lon", 77.6)).retrieve().body(MAP);
        var cursor = ((Number) saved.get("syncVersion")).longValue();
        api.delete().uri("/api/data").header("X-Confirm-Delete", "DELETE-ALL-MY-DATA").retrieve().toBodilessEntity();
        var stats = api.get().uri("/api/stats").retrieve().body(MAP);
        assertThat(((Number) stats.get("houses")).longValue()).isZero();
        assertThat(((Number) stats.get("maxSyncVersion")).longValue()).isGreaterThanOrEqualTo(cursor);
    }

    /**
     * ADR-28 (docs/11 section 5.30 item 3): the reader accepts {@code doorprints-backup/1} and {@code /2}, tolerates
     * the {@code /2} lists it does not know yet, and refuses a newer format with "update the app" instead of
     * dropping its lists in silence. The export still writes {@code /1} while the server holds no broker.
     */
    @Test
    void aVersionTwoBackupImportsAndANewerOneIsRefused() {
        var id = UUID.randomUUID();
        var v2 = backup(houseRow(id, "From a /2 file", Instant.now()), "")
                .replace(BackupFormat.ID, "doorprints-backup/2")
                .replace("\"photos\":[]", "\"photos\":[],\"brokers\":[],\"criteria\":[]");
        var report = postImport(v2, false);
        assertThat(report).containsEntry("format", "doorprints-backup/2");
        assertThat(count(report, "houses", "created")).isEqualTo(1);
        assertThat(export()).startsWith("{\"format\":\"" + BackupFormat.ID + "\"").contains("From a /2 file");

        var v3 = v2.replace("doorprints-backup/2", "doorprints-backup/3");
        assertThat(status(() -> postImport(v3, false))).isEqualTo(400);
        assertThat(status(() -> postImport(v3, true))).isEqualTo(400);
        assertThat(errorBody(() -> postImport(v3, false))).contains("update the app");
        assertThat(errorBody(() -> postImport(v2.replace("doorprints-backup/2", "doorprints-backup/x"), false)))
                .contains("Not a " + BackupFormat.ID);
    }

    /**
     * S4b-BL-89: a refusal never echoes the caller's text, not even the format id. The ZAP API scan read the answer
     * that changed with its payload as a "SQL Injection" (rule 40018) on {@code POST /api/import} and failed the gate.
     */
    @Test
    void theRefusalOfAFormatDoesNotEchoIt() {
        var v2 = backup(houseRow(UUID.randomUUID(), "Any", Instant.now()), "");
        var one = errorBody(() -> postImport(v2.replace(BackupFormat.ID, "x' AND 1=1 -- "), false));
        var other = errorBody(() -> postImport(v2.replace(BackupFormat.ID, "x' AND 1=2 -- "), false));
        assertThat(one).contains("Not a " + BackupFormat.ID).doesNotContain("AND 1=");
        assertThat(one).isEqualTo(other);
    }

    /**
     * S4b-BL-89 (PR 78): a body that cannot be read is answered with the same bytes every time, with no timestamp.
     * Spring's default error page carries one, and the ZAP API scan read the changing answer as a "SQL Injection".
     */
    @Test
    void aBodyThatCannotBeReadIsAnsweredTheSameEveryTime() throws Exception {
        var first = errorBody(() -> postImport("{\"format\":\"x\",\"exportedAt\":\"10 AND 1=1 -- \"}", false));
        Thread.sleep(20);
        var second = errorBody(() -> postImport("{\"format\":\"x\",\"exportedAt\":\"10 AND 1=2 -- \"}", false));
        assertThat(first).contains("Malformed request").doesNotContain("timestamp").doesNotContain("AND 1=");
        assertThat(second).isEqualTo(first);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The body of the error response {@code call} provoked (a ProblemDetail), or "" if it succeeded. */
    private static String errorBody(Supplier<?> call) {
        try {
            call.get();
            return "";
        } catch (RestClientResponseException e) {
            return e.getResponseBodyAsString();
        }
    }

    private static int status(Supplier<?> call) {
        try {
            call.get();
            return 200;
        } catch (RestClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    private String export() {
        return api.get().uri("/api/export").retrieve().body(String.class);
    }

    private Map<String, Object> postImport(String body, boolean dryRun) {
        return api.post().uri("/api/import?dryRun={d}", dryRun).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(MAP);
    }

    /**
     * Asserts that the export holds exactly what {@code expected} says, ignoring the values the server sets: the
     * export time, and each photo's {@code createdAt} (the server stamps it when the bytes are uploaded, so it
     * cannot be the sample's). Photo <em>order</em> is still checked, because the two photos belong to different
     * houses and the format orders them by house, not by {@code createdAt}.
     */
    private static void assertExportEquals(String expected, String actual) throws JSONException {
        JSONAssert.assertEquals(expected, actual, new CustomComparator(JSONCompareMode.STRICT,
                new Customization("exportedAt", (a, e) -> true),
                new Customization("photos[0].createdAt", (a, e) -> true),
                new Customization("photos[1].createdAt", (a, e) -> true)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> problems(Map<String, Object> report) {
        return (List<String>) report.get("problems");
    }

    private static int count(Map<String, Object> report, String entity, String field) {
        @SuppressWarnings("unchecked")
        var counts = (Map<String, Object>) report.get(entity);
        return ((Number) counts.get(field)).intValue();
    }

    /** One backup file with the given rows, ready to POST. */
    private static String backup(String houses, String visits) {
        return "{\"format\":\"" + BackupFormat.ID + "\",\"exportedAt\":" + Instant.now().toEpochMilli()
                + ",\"houses\":[" + houses + "],\"visits\":[" + visits + "],\"photos\":[]}";
    }

    private static String houseRow(UUID id, String label, Instant updatedAt) {
        return houseRow(id, label, updatedAt, updatedAt);
    }

    private static String houseRow(UUID id, String label, Instant createdAt, Instant updatedAt) {
        return "{\"id\":\"" + id + "\",\"label\":\"" + label + "\",\"lat\":12.9,\"lon\":77.6,\"status\":\"NEW\","
                + "\"checklist\":{},\"createdAt\":" + createdAt.toEpochMilli()
                + ",\"updatedAt\":" + updatedAt.toEpochMilli() + "}";
    }

    /** The sample's row as an API body: same fields, but the sync DTOs take ISO-8601 instants. */
    private static String asApiBody(JSONObject row, String... timeFields) throws JSONException {
        var copy = new JSONObject(row.toString());
        for (var field : timeFields) {
            if (copy.has(field)) copy.put(field, Instant.ofEpochMilli(copy.getLong(field)).toString());
        }
        return copy.toString();
    }

    // ---- export ------------------------------------------------------------------------------------------------

    /**
     * The golden-file test: data written through the normal API comes back out as the canonical sample, field for
     * field <em>and</em> key for key, with numbers compared as numbers (Jackson writes house 2's {@code 0} as
     * {@code 0.0}; docs/schemas/README.md section 8.1). The client equivalents are Android's
     * {@code CanonicalSampleTest} and the web byte golden (checked against the sample by {@code BackupParityTest}).
     *
     * <p>The comparison is order-sensitive, and the sample is built so that order means something: house 3's visit
     * arrived <em>between</em> house 1's two visits and house 3's photo was taken <em>before</em> house 1's, so
     * this passes only for a writer that groups visits and photos by house (docs/schemas/README.md section 5). A
     * writer that sorted all visits globally by {@code arrivedAt} would fail here.
     */
    @Test
    void exportMatchesTheCanonicalSample() throws JSONException {
        var sample = new JSONObject(SAMPLE);
        var houses = sample.getJSONArray("houses");
        for (int i = 0; i < houses.length(); i++) {
            var row = houses.getJSONObject(i);
            api.put().uri("/api/houses/{id}", row.getString("id")).contentType(MediaType.APPLICATION_JSON)
                    .body(asApiBody(row, "createdAt", "updatedAt")).retrieve().toBodilessEntity();
        }
        var visits = sample.getJSONArray("visits");
        for (int i = 0; i < visits.length(); i++) {
            var row = visits.getJSONObject(i);
            api.put().uri("/api/visits/{id}", row.getString("id")).contentType(MediaType.APPLICATION_JSON)
                    .body(asApiBody(row, "arrivedAt", "leftAt", "updatedAt")).retrieve().toBodilessEntity();
        }
        var brokers = sample.getJSONArray("brokers");
        for (int i = 0; i < brokers.length(); i++) putBroker(brokers.getJSONObject(i));
        var criteria = sample.getJSONArray("criteria");
        for (int i = 0; i < criteria.length(); i++) putCriterion(criteria.getJSONObject(i));
        var preferences = sample.getJSONArray("preferences");
        for (int i = 0; i < preferences.length(); i++) putPreference(preferences.getJSONObject(i));
        var questions = sample.getJSONArray("questions");
        for (int i = 0; i < questions.length(); i++) putQuestion(questions.getJSONObject(i));
        var viewings = sample.getJSONArray("viewings");
        for (int i = 0; i < viewings.length(); i++) putViewing(viewings.getJSONObject(i));
        var photos = sample.getJSONArray("photos");
        for (int i = 0; i < photos.length(); i++) {
            var photo = photos.getJSONObject(i);
            var form = new LinkedMultiValueMap<String, Object>();
            form.add("id", photo.getString("id"));
            form.add("file", new ByteArrayResource(ImageSanitizerTest.jpegWithExif()) {
                @Override
                public String getFilename() {
                    return "photo.jpg";
                }
            });
            api.post().uri("/api/houses/{id}/photos", photo.getString("houseId"))
                    .contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().toBodilessEntity();
        }

        var exported = export();
        assertExportEquals(SAMPLE, exported);
        // Property order is part of the format, so it is checked separately (JSONAssert ignores it).
        assertThat(CanonicalSample.keysInOrder(exported)).isEqualTo(CanonicalSample.keysInOrder(SAMPLE));
        // Nulls are never written; a missing value is a missing key (docs/schemas/README.md section 4).
        assertThat(exported).doesNotContain("null");
    }

    @Test
    void exportIsAnAttachmentNamedLikeTheDeviceBackups() {
        var response = api.get().uri("/api/export").retrieve().toEntity(String.class);
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .matches("attachment; filename=\"Doorprints-backup-\\d{4}-\\d{2}-\\d{2}\\.json\"");
        assertThat(response.getBody()).startsWith("{\"format\":\"" + BackupFormat.ID + "\"");
    }

    // ---- import ------------------------------------------------------------------------------------------------

    /** A backup file restored to an empty server comes back out of the export unchanged (minus the photo bytes). */
    @Test
    void importRestoresTheCanonicalSample() throws JSONException {
        var preview = postImport(SAMPLE, true);
        assertThat(preview).containsEntry("dryRun", true).containsEntry("format", BackupFormat.ID_WITH_BROKERS);
        assertThat(count(preview, "houses", "created")).isEqualTo(3);
        assertThat(count(preview, "brokers", "created")).isEqualTo(2);
        assertThat(count(preview, "questions", "created")).isEqualTo(3);
        assertThat(count(preview, "viewings", "created")).isEqualTo(2);
        assertThat(viewingRecords()).as("dry run writes no viewing").isEmpty();
        assertThat(questionRecords()).as("dry run writes no question").isEmpty();
        assertThat(brokerRecords()).as("dry run writes no broker").isEmpty();
        assertThat(count(preview, "visits", "created")).isEqualTo(3);
        assertThat(count(preview, "photos", "skipped")).isEqualTo(2);
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST)).as("dry run writes nothing").isEmpty();

        var applied = postImport(SAMPLE, false);
        assertThat(applied).containsEntry("dryRun", false);
        assertThat(count(applied, "houses", "created")).isEqualTo(3);
        assertThat(count(applied, "visits", "created")).isEqualTo(3);
        assertThat(count(applied, "brokers", "created")).isEqualTo(2);
        assertThat(count(applied, "questions", "created")).isEqualTo(3);
        assertThat(count(applied, "viewings", "created")).isEqualTo(2);
        var problems = problems(applied);
        assertThat(problems).anyMatch(p -> p.contains("photo"));
        assertThat(problems).as("a three-house import still updates the AI index row by row")
                .noneMatch(p -> p.contains("AI index"));

        // Photo rows carry no bytes over JSON, so the restored server has none; everything else must match.
        var expected = new JSONObject(SAMPLE).put("photos", new JSONArray()).toString();
        assertExportEquals(expected, export());
    }

    /** Slice 1b: a broker is a {@code broker} record, put the way the phone and the browser put it. */
    private void putBroker(JSONObject row) throws JSONException {
        var payload = new JSONObject(row.toString());
        payload.remove("id");
        payload.remove("updatedAt");
        var body = new JSONObject().put("type", "broker").put("id", row.getString("id")).put("payload", payload)
                .put("updatedAt", Instant.ofEpochMilli(row.getLong("updatedAt")).toString());
        api.put().uri("/api/records/broker/{id}", row.getString("id")).contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()).retrieve().toBodilessEntity();
    }

    /** The live and deleted rows of record type broker, as the sync API lists them. */
    private List<Map<String, Object>> brokerRecords() {
        return api.get().uri("/api/records?since=0&type=broker").retrieve().body(LIST);
    }

    private static String brokerRow(String id, String name, Instant updatedAt) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"updatedAt\":" + updatedAt.toEpochMilli() + "}";
    }

    /** A {@code /2} file with the given brokers and houses. */
    private static String backupWithBrokers(String houses, String brokers) {
        return backup(houses, "").replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS)
                .replace("\"photos\":[]", "\"photos\":[],\"brokers\":[" + brokers + "]");
    }

    /**
     * Slice 1b: brokers import into the record table, merge by id (last write wins on updatedAt, equal writes
     * nothing), a house keeps a brokerId no broker answers to, and the export writes {@code /2} with the list back,
     * equal to what came in, until the last broker is deleted, when it is {@code /1} again.
     */
    @Test
    void brokersImportMergeAndExportBack() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        var ravi = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        var meena = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
        var full = "{\"id\":\"" + ravi + "\",\"name\":\"Ravi Kumar\",\"phone\":\"+91 98400 11111\","
                + "\"agency\":\"Adyar Homes\",\"feeTerms\":\"15 days' rent, once\",\"notes\":\"Replies fast\","
                + "\"rating\":4,\"updatedAt\":" + now.toEpochMilli() + "}";
        var houseId = UUID.randomUUID();
        var house = houseRow(houseId, "Has a broker", now).replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"brokerId\":\"" + meena + "\"");
        var file = backupWithBrokers(house, brokerRow(meena, "Meena Iyer", now.plusSeconds(1)) + "," + full);

        var preview = postImport(file, true);
        assertThat(count(preview, "brokers", "created")).isEqualTo(2);
        assertThat(brokerRecords()).isEmpty();

        assertThat(count(postImport(file, false), "brokers", "created")).isEqualTo(2);
        var stored = brokerRecords();
        assertThat(stored).extracting(r -> r.get("id")).containsExactlyInAnyOrder(ravi, meena);
        var raviRecord = stored.stream().filter(r -> ravi.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(raviRecord).containsEntry("type", "broker").containsEntry("deleted", false);
        assertThat(raviRecord.get("payload")).isEqualTo(Map.of("name", "Ravi Kumar", "phone", "+91 98400 11111",
                "agency", "Adyar Homes", "feeTerms", "15 days' rent, once", "notes", "Replies fast", "rating", 4));

        // Written back: /2, the brokers by updatedAt (Ravi is older), the house's brokerId as given.
        var exported = new JSONObject(export());
        assertThat(exported.getString("format")).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        JSONAssert.assertEquals(new JSONObject(file).getJSONArray("brokers").toString(),
                exported.getJSONArray("brokers").toString(), JSONCompareMode.LENIENT);
        assertThat(exported.getJSONArray("brokers").getJSONObject(0).getString("id")).isEqualTo(ravi);
        assertThat(exported.getJSONArray("houses").getJSONObject(0).getString("brokerId")).isEqualTo(meena);
        assertThat(api.get().uri("/api/houses/{id}", houseId).retrieve().body(MAP)).containsEntry("brokerId", meena);

        // Last write wins; an equal updatedAt writes nothing (no sync version burned).
        long version = ((Number) brokerRecords().stream().filter(r -> ravi.equals(r.get("id"))).findFirst()
                .orElseThrow().get("syncVersion")).longValue();
        var older = postImport(backupWithBrokers("", brokerRow(ravi, "Older", now.minusSeconds(60))), false);
        assertThat(count(older, "brokers", "keptNewer")).isEqualTo(1);
        var same = postImport(backupWithBrokers("", full), false);
        assertThat(count(same, "brokers", "unchanged")).isEqualTo(1);
        var newer = postImport(backupWithBrokers("", brokerRow(ravi, "Ravi K", now.plusSeconds(90))), false);
        assertThat(count(newer, "brokers", "updated")).isEqualTo(1);
        var after = brokerRecords().stream().filter(r -> ravi.equals(r.get("id"))).findFirst().orElseThrow();
        assertThat(((Number) after.get("syncVersion")).longValue()).isGreaterThan(version);
        assertThat(after.get("payload")).as("the whole row wins: the other fields are gone")
                .isEqualTo(Map.of("name", "Ravi K"));

        // Deleted brokers are not exported; with none left the copy is /1 with no brokers key.
        for (var id : List.of(ravi, meena)) api.delete().uri("/api/records/broker/{id}", id).retrieve().toBodilessEntity();
        var bare = new JSONObject(export());
        assertThat(bare.getString("format")).isEqualTo(BackupFormat.ID);
        assertThat(bare.has("brokers")).isFalse();

        // A newer file brings a deleted broker back, like a house.
        var back = postImport(backupWithBrokers("", brokerRow(meena, "Meena again", Instant.now().plusSeconds(30))),
                false);
        assertThat(count(back, "brokers", "updated")).isEqualTo(1);
        assertThat(new JSONObject(export()).getJSONArray("brokers").getJSONObject(0).getString("name"))
                .isEqualTo("Meena again");
    }

    /**
     * Slice 1c: rooms are part of the house row. A file with rooms is /2 even with no broker, imports with the house,
     * exports back equal (rooms in the format's key order), and a /1 file's house has none. The dry run writes nothing.
     */
    @Test
    void roomsImportWithTheirHouseAndExportBackAsVersionTwo() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        var id = UUID.randomUUID();
        var rooms = "\"rooms\":[{\"id\":\"c1\",\"type\":\"BEDROOM\",\"name\":\"Master bedroom\",\"lengthCm\":396,"
                + "\"widthCm\":366,\"condition\":4,\"notes\":\"Damp\",\"sort\":0},{\"id\":\"c2\",\"type\":\"KITCHEN\","
                + "\"lengthCm\":300,\"sort\":1}]";
        var file = backup(houseRow(id, "With rooms", now).replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\"," + rooms), "").replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS);

        assertThat(postImport(file, true)).containsEntry("format", BackupFormat.ID_WITH_BROKERS);
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST)).as("dry run writes nothing").isEmpty();
        assertThat(count(postImport(file, false), "houses", "created")).isEqualTo(1);

        var stored = api.get().uri("/api/houses/{id}", id).retrieve().body(MAP);
        assertThat((List<?>) stored.get("rooms")).hasSize(2);
        var exported = new JSONObject(export());
        assertThat(exported.getString("format")).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(exported.has("brokers")).isFalse();
        JSONAssert.assertEquals("{" + rooms + "}", exported.getJSONArray("houses").getJSONObject(0).toString(),
                JSONCompareMode.LENIENT);
        // The order is read from the text as written: org.json's objects do not keep the order of their keys.
        var written = export();
        assertThat(written.indexOf("\"label\"")).isPositive()
                .isLessThan(written.indexOf("\"rooms\""))
                .isLessThan(written.indexOf("\"checklist\""));
        assertThat(written.indexOf("\"rooms\"")).isLessThan(written.indexOf("\"checklist\""));
        assertThat(exported.toString()).doesNotContain("\"rooms\":[]");

        // A newer file without rooms replaces the house as a whole: the rooms go, and the copy is /1 again.
        var newer = backup(houseRow(id, "No rooms now", now.plusSeconds(60)), "");
        assertThat(count(postImport(newer, false), "houses", "updated")).isEqualTo(1);
        assertThat(api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("rooms")).isNull();
        assertThat(new JSONObject(export()).getString("format")).isEqualTo(BackupFormat.ID);

        // An empty array in a file is no rooms.
        var emptied = backup(houseRow(id, "Empty list", now.plusSeconds(120)).replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"rooms\":[]"), "");
        assertThat(count(postImport(emptied, false), "houses", "updated")).isEqualTo(1);
        assertThat(api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("rooms")).isNull();
    }

    /** Slice 1c: a bad room refuses the whole file and writes nothing, the good house in the same file included. */
    @Test
    void aBadRoomRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        var bad = houseRow(UUID.randomUUID(), "Bad", now);
        var good = "{\"id\":\"r1\",\"type\":\"HALL\",\"lengthCm\":300,\"widthCm\":200,\"condition\":3,\"sort\":0}";
        var cases = Map.of(
                "rooms[0].type", good.replace("HALL", "GARAGE"),
                "rooms[0].lengthCm", good.replace("\"lengthCm\":300", "\"lengthCm\":5001"),
                "rooms[0].widthCm", good.replace("\"widthCm\":200", "\"widthCm\":-1"),
                "rooms[0].condition", good.replace("\"condition\":3", "\"condition\":6"),
                "rooms[0].sort", good.replace("\"sort\":0", "\"sort\":-1"),
                "rooms[0].id", good.replace("\"r1\"", "\"..\""),
                "rooms[0].name", good.replace("\"sort\"", "\"name\":\"" + "n".repeat(61) + "\",\"sort\""),
                "rooms[0].notes", good.replace("\"sort\"", "\"notes\":\"" + "n".repeat(2001) + "\",\"sort\""));
        for (var entry : cases.entrySet()) {
            var body = backup(fine + "," + bad.replace("\"status\":\"NEW\"",
                    "\"status\":\"NEW\",\"rooms\":[" + entry.getValue() + "]"), "");
            assertThat(status(() -> postImport(body, false))).as(entry.getKey()).isEqualTo(400);
            assertThat(status(() -> postImport(body, true))).as("dry run " + entry.getKey()).isEqualTo(400);
            assertThat(errorBody(() -> postImport(body, false))).contains("houses[1]." + entry.getKey()
                    + " is out of range");
        }
        var thirtyOne = new StringBuilder();
        for (int i = 0; i <= 30; i++) {
            thirtyOne.append(i == 0 ? "" : ",").append(good.replace("\"r1\"", "\"r" + i + "\""));
        }
        var many = backup(bad.replace("\"status\":\"NEW\"", "\"status\":\"NEW\",\"rooms\":[" + thirtyOne + "]"), "");
        assertThat(errorBody(() -> postImport(many, false))).contains("houses[0].rooms has more than 30 rooms");
        var twins = backup(bad.replace("\"status\":\"NEW\"", "\"status\":\"NEW\",\"rooms\":[" + good + "," + good + "]"), "");
        assertThat(errorBody(() -> postImport(twins, false))).contains("houses[0].rooms[1].id is repeated");
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
    }

    /** A bad broker refuses the whole file and writes nothing, house rows in the same file included. */
    @Test
    void aBadBrokerRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
        var house = houseRow(UUID.randomUUID(), "Fine", now);
        var good = brokerRow(id, "Fine", now);
        var at = now.toEpochMilli();
        var badRating = "{\"id\":\"" + id + "\",\"name\":\"N\",\"rating\":6,\"updatedAt\":" + at + "}";
        var zeroRating = badRating.replace("\"rating\":6", "\"rating\":0");
        var blankName = brokerRow(id, "   ", now);
        var noName = "{\"id\":\"" + id + "\",\"updatedAt\":" + at + "}";
        var longName = brokerRow(id, "n".repeat(201), now);
        var longPhone = "{\"id\":\"" + id + "\",\"name\":\"N\",\"phone\":\"" + "1".repeat(51)
                + "\",\"updatedAt\":" + at + "}";
        var longNotes = "{\"id\":\"" + id + "\",\"name\":\"N\",\"notes\":\"" + "n".repeat(2001)
                + "\",\"updatedAt\":" + at + "}";
        var badId = brokerRow("a/b", "N", now);
        var noTime = "{\"id\":\"" + id + "\",\"name\":\"N\"}";
        var duplicate = good + "," + brokerRow(id, "Twin", now);

        var refused = List.of(badRating, zeroRating, blankName, noName, longName, longPhone, longNotes, badId, noTime,
                duplicate);
        for (var brokers : refused) {
            var body = backupWithBrokers(house, brokers);
            assertThat(status(() -> postImport(body, false))).as("import of %s", brokers).isEqualTo(400);
            assertThat(status(() -> postImport(body, true))).as("dry run of %s", brokers).isEqualTo(400);
        }
        assertThat(errorBody(() -> postImport(backupWithBrokers(house, badRating), false)))
                .contains("brokers[0].rating must be 1..5");
        assertThat(errorBody(() -> postImport(backupWithBrokers(house, blankName), false)))
                .contains("brokers[0].name is required");
        assertThat(errorBody(() -> postImport(backupWithBrokers(house, duplicate), false)))
                .contains("brokers[1].id " + id + " appears twice");
        assertThat(errorBody(() -> postImport(backupWithBrokers(house, "null"), false))).contains("brokers[0]");
        assertThat(errorBody(() -> postImport(backup(house.replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"brokerId\":\"a b\""), ""), false))).contains("houses[0].brokerId");
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
        assertThat(brokerRecords()).isEmpty();
        assertThat(status(() -> postImport(backupWithBrokers(house, good), false))).isEqualTo(200);
    }

    /** Merge by id, last write wins on updatedAt; an equal timestamp writes nothing at all. */
    @Test
    void mergeIsLastWriteWinsAndEqualTimestampsWriteNothing() {
        var id = UUID.randomUUID();
        var now = Instant.now().minus(Duration.ofMinutes(5));
        assertThat(count(postImport(backup(houseRow(id, "First", now), ""), false), "houses", "created")).isEqualTo(1);

        var older = postImport(backup(houseRow(id, "Older", now.minus(Duration.ofMinutes(1))), ""), false);
        assertThat(count(older, "houses", "keptNewer")).isEqualTo(1);
        assertThat(label(id)).isEqualTo("First");

        var newer = postImport(backup(houseRow(id, "Newer", now.plus(Duration.ofMinutes(1))), ""), false);
        assertThat(count(newer, "houses", "updated")).isEqualTo(1);
        assertThat(label(id)).isEqualTo("Newer");

        long version = syncVersion(id);
        var again = postImport(backup(houseRow(id, "Newer", now.plus(Duration.ofMinutes(1))), ""), false);
        assertThat(count(again, "houses", "unchanged")).isEqualTo(1);
        assertThat(syncVersion(id)).as("an unchanged row burns no sync version").isEqualTo(version);
    }

    /** A visit needs its house: one that is neither in the file nor on the server is reported, not a 500. */
    @Test
    void visitWithoutItsHouseIsSkipped() {
        var visit = "{\"id\":\"" + UUID.randomUUID() + "\",\"houseId\":\"" + UUID.randomUUID()
                + "\",\"lat\":12.9,\"lon\":77.6,\"arrivedAt\":" + Instant.now().toEpochMilli()
                + ",\"source\":\"MANUAL\",\"updatedAt\":" + Instant.now().toEpochMilli() + "}";
        var report = postImport(backup("", visit), false);
        assertThat(count(report, "visits", "skipped")).isEqualTo(1);
        assertThat(api.get().uri("/api/visits?since=0").retrieve().body(LIST)).isEmpty();
    }

    /**
     * An update takes the file's {@code createdAt} as well: the whole row wins or loses together
     * (docs/schemas/README.md section 4.2), and the export is ordered by {@code createdAt}, so keeping the
     * server's would let a restore re-order the very file it was made from.
     */
    @Test
    void anUpdateTakesTheFilesCreatedAt() throws JSONException {
        var id = UUID.randomUUID();
        var firstCreated = Instant.now().minus(Duration.ofDays(30));
        var secondCreated = Instant.now().minus(Duration.ofDays(2));
        var firstUpdated = Instant.now().minus(Duration.ofMinutes(10));
        postImport(backup(houseRow(id, "First", firstCreated, firstUpdated), ""), false);

        var newer = backup(houseRow(id, "Second", secondCreated, firstUpdated.plus(Duration.ofMinutes(1))), "");
        assertThat(count(postImport(newer, false), "houses", "updated")).isEqualTo(1);

        var exported = new JSONObject(export()).getJSONArray("houses").getJSONObject(0);
        assertThat(exported.getLong("createdAt")).as("the file's createdAt, not the one this server had")
                .isEqualTo(secondCreated.toEpochMilli());
    }

    private static String visitRow(UUID id, UUID houseId, Instant arrivedAt, Instant updatedAt) {
        return "{\"id\":\"" + id + "\",\"houseId\":\"" + houseId + "\",\"lat\":12.9,\"lon\":77.6,\"arrivedAt\":"
                + arrivedAt.toEpochMilli() + ",\"source\":\"MANUAL\",\"updatedAt\":" + updatedAt.toEpochMilli() + "}";
    }

    private Object houseIdOfVisit(UUID visitId) {
        return api.get().uri("/api/visits?since=0").retrieve().body(LIST).stream()
                .filter(v -> visitId.toString().equals(v.get("id"))).findFirst().orElseThrow().get("houseId");
    }

    /**
     * Importing a house this server has deleted makes the row live again, but the delete purged its content
     * (F-16): the photos were tombstoned with their bytes dropped, and no JSON backup carries bytes back. The
     * visits were only <em>unlinked</em>, and that unlink is an ordinary write — so a visit row in the same file
     * that is newer than the delete wins and re-links. The report must say exactly that much: the photos are gone,
     * the links are not necessarily. This test restores a full backup over a delete and checks both halves: the
     * wording, and that the visit really is linked again.
     */
    @Test
    void restoringADeletedHouseSaysItsPhotosAreGoneAndItsVisitsCanReLink() {
        var id = UUID.randomUUID();
        var visitId = UUID.randomUUID();
        var created = Instant.now().minus(Duration.ofHours(1));
        postImport(backup(houseRow(id, "Gone", created, created), visitRow(visitId, id, created, created)), false);
        api.delete().uri("/api/houses/{id}", id).retrieve().toBodilessEntity();
        assertThat(houseIdOfVisit(visitId)).as("the delete unlinked the visit").isNull();

        var afterDelete = Instant.now().plus(Duration.ofSeconds(30)); // inside the 300 s clock-skew allowance
        var back = backup(houseRow(id, "Back", created, afterDelete), visitRow(visitId, id, created, afterDelete));
        var report = postImport(back, false);

        assertThat(count(report, "houses", "updated")).isEqualTo(1);
        assertThat(count(report, "visits", "updated")).isEqualTo(1);
        assertThat(problems(report)).anyMatch(p -> p.contains(id.toString()) && p.contains("restored")
                && p.contains("photos and their bytes are gone for good") && p.contains("re-links"));
        assertThat(problems(report)).as("the report must not claim the visit links are lost")
                .noneMatch(p -> p.contains("cannot be recovered"));
        assertThat(label(id)).isEqualTo("Back");
        assertThat(houseIdOfVisit(visitId)).as("the newer visit row in the file re-linked it")
                .isEqualTo(id.toString());
    }

    /**
     * The report's per-row lines are capped like the 400 message: {@link BackupService#MAX_REPORTED_PROBLEMS}
     * lines and one "and N more" tail, not one line per row. Restoring many deleted houses is the case that would
     * otherwise grow without bound (one ~200-character line per house, 20 000 rows allowed by default).
     */
    @Test
    void aLargeRestoreReportsATailLineInsteadOfOneLinePerHouse() {
        int restored = BackupService.MAX_REPORTED_PROBLEMS + 5;
        var created = Instant.now().minus(Duration.ofHours(1));
        var ids = new ArrayList<UUID>();
        var rows = new StringBuilder();
        for (int i = 0; i < restored; i++) {
            var id = UUID.randomUUID();
            ids.add(id);
            if (i > 0) rows.append(',');
            rows.append(houseRow(id, "House " + i, created, created));
        }
        postImport(backup(rows.toString(), ""), false);
        for (var id : ids) api.delete().uri("/api/houses/{id}", id).retrieve().toBodilessEntity();

        var afterDelete = Instant.now().plus(Duration.ofSeconds(30));
        var again = new StringBuilder();
        for (int i = 0; i < restored; i++) {
            if (i > 0) again.append(',');
            again.append(houseRow(ids.get(i), "House " + i, created, afterDelete));
        }
        var body = backup(again.toString(), "");
        assertThat(body.length()).as("under the 16 KiB import cap of this suite").isLessThan(16384);

        for (var dryRun : List.of(true, false)) {
            var report = postImport(body, dryRun);
            assertThat(count(report, "houses", "updated")).isEqualTo(restored);
            var lines = problems(report);
            assertThat(lines).as("dryRun=%s: %d restore lines, then the tail", dryRun,
                    BackupService.MAX_REPORTED_PROBLEMS).hasSize(BackupService.MAX_REPORTED_PROBLEMS + 1);
            assertThat(lines.subList(0, BackupService.MAX_REPORTED_PROBLEMS))
                    .allMatch(p -> p.contains("was deleted here"));
            assertThat(lines.getLast()).isEqualTo("and 5 more row problem(s) not listed");
        }
    }

    /**
     * A restore must not turn one HTTP request into one embedding call per row. Above
     * {@link BackupService#MAX_INDEX_EVENTS} houses no {@code HouseChangedEvent} is published at all and the
     * report says the index is stale, instead of reporting a clean success while the provider quota drains. AI is
     * off in this suite, so what is pinned here is the report — the behaviour the operator sees either way.
     */
    @Test
    void anImportTooLargeToIndexSaysSoInsteadOfFanningOut() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var rows = new StringBuilder();
        for (int i = 0; i <= BackupService.MAX_INDEX_EVENTS; i++) {
            if (i > 0) rows.append(',');
            rows.append(houseRow(UUID.randomUUID(), "House " + i, now));
        }
        var body = backup(rows.toString(), "");
        assertThat(body.length()).as("under both import caps, so it is the fan-out guard that speaks")
                .isLessThan(16384);

        assertThat(problems(postImport(body, true))).as("the preview reports what the import would report")
                .anyMatch(p -> p.contains("AI index not updated"));

        var report = postImport(body, false);

        assertThat(count(report, "houses", "created")).isEqualTo(BackupService.MAX_INDEX_EVENTS + 1);
        assertThat(problems(report)).anyMatch(p -> p.contains("AI index not updated")
                && p.contains("POST /api/ai/reindex"));
    }

    /** One bad row rejects the whole file, and nothing is written (400, not a partial import). */
    @Test
    void invalidBackupsAreRefusedWhole() {
        var now = Instant.now();
        var id = UUID.randomUUID();
        var good = houseRow(id, "Fine", now);

        var wrongFormat = backup(good, "").replace(BackupFormat.ID, "seenhouse-backup/1");
        var duplicateIds = backup(good + "," + houseRow(id, "Twin", now), "");
        var noLabel = backup("{\"id\":\"" + UUID.randomUUID() + "\",\"lat\":12.9,\"lon\":77.6,\"status\":\"NEW\","
                + "\"checklist\":{},\"createdAt\":" + now.toEpochMilli() + ",\"updatedAt\":" + now.toEpochMilli()
                + "}", "");
        var badLatitude = backup(good.replace("\"lat\":12.9", "\"lat\":95.0"), "");
        // Always-present fields may not be left out or nulled: a defaulted 0, 0 would be a real place.
        var noLatitude = backup(good.replace("\"lat\":12.9,", ""), "");
        var nullLongitude = backup(good.replace("\"lon\":77.6", "\"lon\":null"), "");
        var visitWithoutLatitude = backup(good,
                visitRow(UUID.randomUUID(), id, now, now).replace("\"lat\":12.9,", ""));
        var absurdClock = backup(good.replace("\"updatedAt\":" + now.toEpochMilli(),
                "\"updatedAt\":" + Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()), "");
        // Slice 1a: the house's own values are range-checked like the rest (docs/11 section 5.21).
        var badArea = backup(good.replace("\"status\":\"NEW\"", "\"status\":\"NEW\",\"areaSqft\":0"), "");
        var badSource = backup(good.replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"locationSource\":\"GUESS\""), "");
        var negativeDeposit = backup(good.replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"cost\":{\"deposit\":-1}"), "");
        var noSuchDay = backup(good.replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"cost\":{\"availableFrom\":\"2026-02-30\"}"), "");
        var visitEndsBeforeItStarts = backup(good, "{\"id\":\"" + UUID.randomUUID() + "\",\"houseId\":\"" + id
                + "\",\"lat\":12.9,\"lon\":77.6,\"arrivedAt\":" + now.toEpochMilli() + ",\"leftAt\":"
                + now.minus(Duration.ofHours(1)).toEpochMilli() + ",\"source\":\"AUTO\",\"updatedAt\":"
                + now.toEpochMilli() + "}");

        for (var body : List.of(wrongFormat, duplicateIds, noLabel, badLatitude, noLatitude, nullLongitude,
                visitWithoutLatitude, absurdClock, badArea, badSource, negativeDeposit, noSuchDay,
                visitEndsBeforeItStarts)) {
            assertThat(status(() -> postImport(body, false))).as("import of %s", body).isEqualTo(400);
            assertThat(status(() -> postImport(body, true))).as("dry run validates too").isEqualTo(400);
        }
        // ... and for the reason given, not some other one (a missing coordinate is not read as 0).
        assertThat(errorBody(() -> postImport(noLatitude, false))).contains("houses[0].lat is required");
        assertThat(errorBody(() -> postImport(nullLongitude, false))).contains("houses[0].lon is required");
        assertThat(errorBody(() -> postImport(visitWithoutLatitude, false))).contains("visits[0].lat is required");
        assertThat(errorBody(() -> postImport(negativeDeposit, false)))
                .contains("houses[0].cost.deposit is out of range");
        assertThat(errorBody(() -> postImport(noSuchDay, false)))
                .contains("houses[0].cost.availableFrom is out of range");
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
    }

    /** A {@code cost} of {@code {}} in a file is read as no cost, so the export leaves it out (never writes {}). */
    @Test
    void anEmptyCostObjectReadsAsNoCostAndIsNotWrittenBack() throws JSONException {
        var id = UUID.randomUUID();
        var row = houseRow(id, "Empty cost", Instant.now()).replace("\"status\":\"NEW\"",
                "\"status\":\"NEW\",\"areaSqft\":900,\"locationSource\":\"MAP\",\"cost\":{}");
        assertThat(count(postImport(backup(row, ""), false), "houses", "created")).isEqualTo(1);

        var exported = new JSONObject(export()).getJSONArray("houses").getJSONObject(0);
        assertThat(exported.getInt("areaSqft")).isEqualTo(900);
        assertThat(exported.getString("locationSource")).isEqualTo("MAP");
        assertThat(exported.has("cost")).isFalse();
        assertThat(api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("cost")).isNull();
    }

    /**
     * {@code checklist} is the one always-present field a reader is lenient about (docs/schemas/README.md sections
     * 3.1 and 4.4): left out, or written as {@code null}, it is read as {@code {}} — no scores — instead of refusing
     * the file, which is also what the Android reader in {@code :shared} does for an absent one. Because the whole
     * row wins (section 4.2), a newer file without a checklist clears the scores this server has; the report
     * names that house, in the preview and in the applied import, so the wipe is not silent. A create loses
     * nothing and gets no line, and neither does an explicit {@code {}}.
     */
    @Test
    void aMissingChecklistReadsAsNoScoresAndTheReportSaysWhatItClears() {
        var id = UUID.randomUUID();
        var created = Instant.now().minus(Duration.ofHours(1));
        var scored = houseRow(id, "Scored", created, created)
                .replace("\"checklist\":{}", "\"checklist\":{\"water\":5}");
        assertThat(count(postImport(backup(scored, ""), false), "houses", "created")).isEqualTo(1);
        assertThat(checklist(id)).containsEntry("water", 5);

        var later = created.plus(Duration.ofMinutes(1));
        var withoutChecklist = backup(houseRow(id, "Scored", created, later).replace("\"checklist\":{},", ""), "");
        assertThat(withoutChecklist).doesNotContain("checklist");

        var preview = postImport(withoutChecklist, true);
        assertThat(count(preview, "houses", "updated")).isEqualTo(1);
        assertThat(problems(preview)).anyMatch(p -> p.contains(id.toString()) && p.contains("no checklist")
                && p.contains("1 checklist score(s) this server had are cleared"));
        assertThat(checklist(id)).as("a preview writes nothing").containsEntry("water", 5);

        var applied = postImport(withoutChecklist, false);
        assertThat(count(applied, "houses", "updated")).isEqualTo(1);
        assertThat(problems(applied)).anyMatch(p -> p.contains(id.toString()) && p.contains("no checklist"));
        assertThat(checklist(id)).as("the whole row won, and its checklist was 'no scores'").isNullOrEmpty();

        // An explicit null means the same as absent; on a new house nothing is cleared, so nothing is said.
        var fresh = UUID.randomUUID();
        var nullChecklist = backup(houseRow(fresh, "Fresh", created, created)
                .replace("\"checklist\":{}", "\"checklist\":null"), "");
        var created2 = postImport(nullChecklist, false);
        assertThat(count(created2, "houses", "created")).isEqualTo(1);
        assertThat(problems(created2)).noneMatch(p -> p.contains("checklist"));
        assertThat(checklist(fresh)).isNullOrEmpty();

        // An explicit {} is a value, not a missing one: it clears scores too, but that is what the file says.
        var rescored = houseRow(fresh, "Fresh", created, later)
                .replace("\"checklist\":{}", "\"checklist\":{\"power\":3}");
        postImport(backup(rescored, ""), false);
        var emptiedRow = houseRow(fresh, "Fresh", created, later.plus(Duration.ofMinutes(1)));
        var emptied = postImport(backup(emptiedRow, ""), false);
        assertThat(count(emptied, "houses", "updated")).isEqualTo(1);
        assertThat(problems(emptied)).noneMatch(p -> p.contains("checklist"));
        assertThat(checklist(fresh)).isNullOrEmpty();
    }

    /** Both import caps answer 413: too many rows and a body that is too large to read at all. */
    @Test
    void oversizedImportsAreRefused() {
        var now = Instant.now();
        var rows = new StringBuilder();
        for (int i = 0; i < 61; i++) { // app.limits.max-import-rows=60 in this test
            if (i > 0) rows.append(',');
            rows.append(houseRow(UUID.randomUUID(), "House " + i, now));
        }
        var tooManyRows = backup(rows.toString(), "");
        assertThat(tooManyRows.length()).as("stays under the byte cap so the row cap is what fails").isLessThan(16384);
        assertThat(status(() -> postImport(tooManyRows, false))).isEqualTo(413);

        var hugeNotes = backup(houseRow(UUID.randomUUID(), "Big", now)
                .replace("\"checklist\":{}", "\"notes\":\"" + "x".repeat(20_000) + "\",\"checklist\":{}"), "");
        assertThat(status(() -> postImport(hugeNotes, false))).isIn(400, 413);
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
    }

    @Test
    void importNeedsTheApiKey() {
        var body = backup(houseRow(UUID.randomUUID(), "Sneaky", Instant.now()), "");
        assertThat(status(() -> anonymous.post().uri("/api/import").contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().body(String.class))).isEqualTo(401);
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
    }

    /** Slice 2: criteria and preferences import into the record table like brokers and export back /2. */
    @Test
    void criteriaAndPreferencesImportMergeAndExportBack() throws JSONException {
        var sample = new JSONObject(SAMPLE);
        var criteria = sample.getJSONArray("criteria");
        var preferences = sample.getJSONArray("preferences");
        var houses = sample.getJSONArray("houses");

        // Put sample data on the server
        for (int i = 0; i < houses.length(); i++) {
            var row = houses.getJSONObject(i);
            api.put().uri("/api/houses/{id}", row.getString("id")).contentType(MediaType.APPLICATION_JSON)
                    .body(asApiBody(row, "createdAt", "updatedAt")).retrieve().toBodilessEntity();
        }
        for (int i = 0; i < criteria.length(); i++) putCriterion(criteria.getJSONObject(i));
        for (int i = 0; i < preferences.length(); i++) putPreference(preferences.getJSONObject(i));

        var exported = new JSONObject(export());
        assertThat(exported.getString("format")).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(exported.getJSONArray("criteria").length()).isEqualTo(3);
        assertThat(exported.getJSONArray("preferences").length()).isEqualTo(1);
        // Ordered by updatedAt then key
        assertThat(exported.getJSONArray("criteria").getJSONObject(0).getString("key")).isEqualTo("noise");
        assertThat(exported.getJSONArray("preferences").getJSONObject(0).getString("key"))
                .isEqualTo("score.ratingShare");
    }

    /** A bad criterion refuses the whole file and writes nothing. */
    @Test
    void aBadCriterionRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var house = houseRow(UUID.randomUUID(), "Fine", now);
        var good = criterionRow("c_12345678", "Custom", 2, false, 3, 0, now);

        var badKey = criterionRow("bad key!", "Custom", 2, false, 3, 0, now);
        var weight4 = criterionRow("c_12345679", "Custom", 4, false, 3, 0, now);
        var weight_neg = criterionRow("c_1234567a", "Custom", -1, false, 3, 0, now);
        var minScore0 = criterionRow("c_1234567b", "Custom", 2, false, 0, 0, now);
        var minScore6 = criterionRow("c_1234567c", "Custom", 2, false, 6, 0, now);
        var negSort = criterionRow("c_1234567d", "Custom", 2, false, 3, -1, now);
        var longLabel = criterionRow("c_1234567e", "x".repeat(61), 2, false, 3, 0, now);
        var labelOnBuiltin = criterionRow("water", "Has label", 2, false, 3, 0, now);
        var duplicate = good + "," + criterionRow("c_12345678", "Dup", 2, false, 3, 1, now);

        for (var criterion : List.of(badKey, weight4, weight_neg, minScore0, minScore6, negSort, longLabel,
                labelOnBuiltin, duplicate)) {
            var body = backupWithCriteria(backup(house, ""), criterion);
            assertThat(status(() -> postImport(body, false))).as("import of bad criterion").isEqualTo(400);
            assertThat(status(() -> postImport(body, true))).as("dry run validates").isEqualTo(400);
        }
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
        assertThat(criterionRecords()).isEmpty();
    }

    /** Bad preference values refuse the whole file. */
    @Test
    void aBadPreferenceRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var house = houseRow(UUID.randomUUID(), "Fine", now);
        var good = preferenceRow("score.ratingShare", "0.5", now);

        var badKey = preferenceRow("bad key!", "0.5", now);
        var longValue = preferenceRow("score.ratingShare", "x".repeat(501), now);
        var duplicate = good + "," + preferenceRow("score.ratingShare", "0.3", now);

        for (var pref : List.of(badKey, longValue, duplicate)) {
            var body = backupWithPreferences(backup(house, ""), pref);
            assertThat(status(() -> postImport(body, false))).isEqualTo(400);
        }
        assertThat(preferenceRecords()).isEmpty();
    }

    /** Slice 2: At most 40 criteria; adding the 41st is refused. */
    @Test
    void moreThan40CriteriaIsRefused() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var house = houseRow(UUID.randomUUID(), "Fine", now);
        var criteria = new StringBuilder();
        for (int i = 0; i <= 40; i++) {  // 41 total (10 built-ins + 31 custom already, so just 1 more)
            if (i > 0) criteria.append(",");
            criteria.append(criterionRow("c_" + String.format("%08x", i), "Criterion " + i, 2, false, 3, i, now));
        }
        var body = backupWithCriteria(backup(house, ""), criteria.toString());
        assertThat(status(() -> postImport(body, false))).isEqualTo(400);
        assertThat(errorBody(() -> postImport(body, false))).contains("at most 40 criteria");
    }

    /** Built-in criteria never export a label; only custom ones do. */
    @Test
    void builtInKeysNeverExportLabel() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        api.put().uri("/api/houses/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"label\":\"Test\",\"lat\":12.9,\"lon\":77.6,\"status\":\"NEW\",\"createdAt\":\""
                    + now + "\",\"updatedAt\":\"" + now + "\"}").retrieve().toBodilessEntity();

        // Put a built-in criterion
        putCriterion(new JSONObject()
                .put("key", "water")
                .put("weight", 3)
                .put("mustHave", true)
                .put("minScore", 4)
                .put("sort", 0)
                .put("updatedAt", now.toEpochMilli()));

        var exported = new JSONObject(export());
        var waterCriterion = exported.getJSONArray("criteria").getJSONObject(0);
        assertThat(waterCriterion.has("label")).isFalse();
        assertThat(waterCriterion.getString("key")).isEqualTo("water");
    }

    private void putCriterion(JSONObject row) throws JSONException {
        var payload = new JSONObject(row.toString());
        var key = payload.remove("key");
        var updatedAt = payload.remove("updatedAt");
        var body = new JSONObject()
                .put("type", "criterion")
                .put("id", key)
                .put("payload", payload)
                .put("updatedAt", Instant.ofEpochMilli(((Number) updatedAt).longValue()).toString());
        api.put().uri("/api/records/criterion/{id}", key.toString()).contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()).retrieve().toBodilessEntity();
    }

    private void putPreference(JSONObject row) throws JSONException {
        var payload = new JSONObject();
        var key = row.remove("key");
        var value = row.remove("value");
        var updatedAt = row.remove("updatedAt");
        payload.put("value", value);
        var body = new JSONObject()
                .put("type", "preference")
                .put("id", key)
                .put("payload", payload)
                .put("updatedAt", Instant.ofEpochMilli(((Number) updatedAt).longValue()).toString());
        api.put().uri("/api/records/preference/{id}", key.toString()).contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()).retrieve().toBodilessEntity();
    }

    private List<Map<String, Object>> criterionRecords() {
        return api.get().uri("/api/records?since=0&type=criterion").retrieve().body(LIST);
    }

    private List<Map<String, Object>> preferenceRecords() {
        return api.get().uri("/api/records?since=0&type=preference").retrieve().body(LIST);
    }

    private static String criterionRow(String key, String label, int weight, boolean mustHave, int minScore, int sort, Instant updatedAt) {
        var at = updatedAt.toEpochMilli();
        return "{\"key\":\"" + key + "\",\"label\":\"" + label + "\",\"weight\":" + weight
                + ",\"mustHave\":" + mustHave + ",\"minScore\":" + minScore
                + ",\"sort\":" + sort + ",\"updatedAt\":" + at + "}";
    }

    private static String preferenceRow(String key, String value, Instant updatedAt) {
        return "{\"key\":\"" + key + "\",\"value\":\"" + value + "\",\"updatedAt\":" + updatedAt.toEpochMilli() + "}";
    }

    private static String backupWithCriteria(String baseBackup, String criteria) {
        return baseBackup.replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS)
                .replace("\"photos\":[]", "\"photos\":[],\"criteria\":[" + criteria + "]");
    }

    private static String backupWithPreferences(String baseBackup, String preferences) {
        return baseBackup.replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS)
                .replace("\"photos\":[]", "\"photos\":[],\"preferences\":[" + preferences + "]");
    }

    // ---- slice 3a: viewing questions and answers ------------------------------------------------------------------

    @Autowired
    BackupService backupService;

    private static final String GOOD_ANSWER =
            "{\"id\":\"a1\",\"questionId\":\"qd_water\",\"text\":\"Water?\",\"answer\":\"Borewell\","
                    + "\"status\":\"ANSWERED\",\"sort\":0}";

    private void putQuestion(JSONObject row) throws JSONException {
        var payload = new JSONObject(row.toString());
        var id = payload.remove("id");
        var updatedAt = payload.remove("updatedAt");
        var body = new JSONObject().put("type", "question").put("id", id).put("payload", payload)
                .put("updatedAt", Instant.ofEpochMilli(((Number) updatedAt).longValue()).toString());
        api.put().uri("/api/records/question/{id}", id.toString()).contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()).retrieve().toBodilessEntity();
    }

    private List<Map<String, Object>> questionRecords() {
        return api.get().uri("/api/records?since=0&type=question").retrieve().body(LIST);
    }

    private static String questionRow(String id, String text, String category, String appliesTo, int sort,
                                      Instant updatedAt) {
        return "{\"id\":\"" + id + "\",\"text\":\"" + text + "\",\"category\":\"" + category
                + "\",\"appliesTo\":\"" + appliesTo + "\",\"defaultOn\":true,\"sort\":" + sort
                + ",\"updatedAt\":" + updatedAt.toEpochMilli() + "}";
    }

    private static String backupWithQuestions(String houses, String questions) {
        return backup(houses, "").replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS)
                .replace("\"photos\":[]", "\"photos\":[],\"questions\":[" + questions + "]");
    }

    private static String houseWithAnswers(UUID id, String label, Instant at, String answers) {
        return houseRow(id, label, at).replace("\"status\":\"NEW\"", "\"status\":\"NEW\",\"answers\":[" + answers + "]");
    }

    /** Refused as a whole file (dry run too), naming the rule, never echoing the user's text, and nothing written. */
    private void assertRefused(String body, String message, String userText) {
        assertThat(status(() -> postImport(body, false))).as(message).isEqualTo(400);
        assertThat(status(() -> postImport(body, true))).as("dry run " + message).isEqualTo(400);
        var error = errorBody(() -> postImport(body, false));
        assertThat(error).contains(message);
        if (userText != null) assertThat(error).doesNotContain(userText);
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).as("no house written").isEmpty();
        assertThat(questionRecords()).as("no question written").isEmpty();
        assertThat(viewingRecords()).as("no viewing written").isEmpty();
    }

    /** Questions are records of type question: merged by id, last write wins, exported back as /2 in payload order. */
    @Test
    void questionsImportMergeByIdLastWriteWinsAndExportBack() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        var file = backupWithQuestions("",
                questionRow("qd_deposit", "Deposit?", "MONEY", "RENT", 1, now) + ","
                        + questionRow("q_1a2b3c4d", "Water meter?", "WATER_POWER", "BOTH", 20, now.minusSeconds(60)));
        assertThat(postImport(file, true)).containsEntry("format", BackupFormat.ID_WITH_BROKERS);
        assertThat(questionRecords()).as("dry run writes nothing").isEmpty();
        var applied = postImport(file, false);
        assertThat(count(applied, "questions", "created")).isEqualTo(2);
        assertThat(count(applied, "questions", "total")).isEqualTo(2);
        assertThat(questionRecords()).hasSize(2);

        var exported = new JSONObject(export());
        assertThat(exported.getString("format")).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        var questions = exported.getJSONArray("questions");
        assertThat(questions.length()).isEqualTo(2);
        assertThat(questions.getJSONObject(0).getString("id")).as("ordered by updatedAt then id").isEqualTo("q_1a2b3c4d");
        assertThat(export()).contains("{\"id\":\"qd_deposit\",\"text\":\"Deposit?\",\"category\":\"MONEY\","
                + "\"appliesTo\":\"RENT\",\"defaultOn\":true,\"sort\":1,\"updatedAt\":" + now.toEpochMilli() + "}");
        assertThat(export()).doesNotContain("\"archived\"");

        // The same file again changes nothing; an older row is kept newer here; a newer row wins, archived kept.
        assertThat(count(postImport(file, false), "questions", "unchanged")).isEqualTo(2);
        var older = backupWithQuestions("", questionRow("qd_deposit", "Older", "LEGAL", "SALE", 9, now.minusSeconds(30)));
        assertThat(count(postImport(older, false), "questions", "keptNewer")).isEqualTo(1);
        var newer = backupWithQuestions("", questionRow("qd_deposit", "Newer", "LEGAL", "SALE", 9, now.plusSeconds(30))
                .replace("\"sort\"", "\"archived\":true,\"sort\""));
        assertThat(count(postImport(newer, false), "questions", "updated")).isEqualTo(1);
        var after = new JSONObject(export()).getJSONArray("questions");
        JSONObject deposit = null;
        for (int i = 0; i < after.length(); i++) if ("qd_deposit".equals(after.getJSONObject(i).getString("id"))) deposit = after.getJSONObject(i);
        assertThat(deposit).isNotNull();
        assertThat(deposit.getString("text")).isEqualTo("Newer");
        assertThat(deposit.getString("category")).isEqualTo("LEGAL");
        assertThat(deposit.getString("appliesTo")).isEqualTo("SALE");
        assertThat(deposit.getBoolean("archived")).isTrue();
    }

    /** A deleted question (a tombstone in the record table) is made live again by a newer file, not by an older one. */
    @Test
    void aNewerFileRevivesADeletedQuestion() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        postImport(backupWithQuestions("", questionRow("qd_pets", "Pets?", "RULES", "RENT", 3, now)), false);
        api.delete().uri("/api/records/question/{id}", "qd_pets").retrieve().toBodilessEntity();
        assertThat(new JSONObject(export()).has("questions")).as("a tombstone is not exported").isFalse();
        assertThat(new JSONObject(export()).getString("format")).isEqualTo(BackupFormat.ID);

        var older = backupWithQuestions("", questionRow("qd_pets", "Old file", "RULES", "RENT", 3, now.plusSeconds(1)));
        assertThat(count(postImport(older, false), "questions", "keptNewer")).isEqualTo(1);
        assertThat(new JSONObject(export()).has("questions")).isFalse();

        var newer = backupWithQuestions("", questionRow("qd_pets", "Back again", "RULES", "RENT", 3,
                Instant.now().plusSeconds(20)));
        assertThat(count(postImport(newer, false), "questions", "updated")).isEqualTo(1);
        var questions = new JSONObject(export()).getJSONArray("questions");
        assertThat(questions.length()).isEqualTo(1);
        assertThat(questions.getJSONObject(0).getString("text")).isEqualTo("Back again");
    }

    /** Answers are part of the house row: /2 even with no question, imported with the house, exported back equal. */
    @Test
    void answersImportWithTheirHouseAndExportBackAsVersionTwo() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        var id = UUID.randomUUID();
        var answers = GOOD_ANSWER + ",{\"id\":\"a2\",\"text\":\"Terrace open?\",\"status\":\"OPEN\",\"sort\":1}";
        var file = backup(houseWithAnswers(id, "With answers", now, answers), "")
                .replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS);

        assertThat(postImport(file, true)).containsEntry("format", BackupFormat.ID_WITH_BROKERS);
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST)).as("dry run writes nothing").isEmpty();
        assertThat(count(postImport(file, false), "houses", "created")).isEqualTo(1);

        assertThat((List<?>) api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("answers")).hasSize(2);
        var exported = new JSONObject(export());
        assertThat(exported.getString("format")).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(exported.has("questions")).isFalse();
        JSONAssert.assertEquals("{\"answers\":[" + answers + "]}", exported.getJSONArray("houses").getJSONObject(0).toString(),
                JSONCompareMode.LENIENT);
        var written = export();
        assertThat(written.indexOf("\"label\"")).isPositive().isLessThan(written.indexOf("\"answers\""));
        assertThat(written.indexOf("\"answers\"")).isLessThan(written.indexOf("\"checklist\""));
        assertThat(written).doesNotContain("\"answers\":[]");

        // A newer file without answers replaces the house as a whole: the answers go, and the copy is /1 again.
        var newer = backup(houseRow(id, "No answers now", now.plusSeconds(60)), "");
        assertThat(count(postImport(newer, false), "houses", "updated")).isEqualTo(1);
        assertThat(api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("answers")).isNull();
        assertThat(new JSONObject(export()).getString("format")).isEqualTo(BackupFormat.ID);

        // An empty array in a file is no answers.
        var emptied = backup(houseWithAnswers(id, "Empty list", now.plusSeconds(120), ""), "");
        assertThat(count(postImport(emptied, false), "houses", "updated")).isEqualTo(1);
        assertThat(api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("answers")).isNull();
    }

    /** A copy made without contact details keeps questions and answers: the file is the person's own data. */
    @Test
    void answersKeepAPhoneNumberInTheirText() throws JSONException {
        var id = UUID.randomUUID();
        var answer = GOOD_ANSWER.replace("Borewell", "Call the caretaker on 98450 12345");
        postImport(backup(houseWithAnswers(id, "Phone in answer", Instant.now().minusSeconds(60), answer), ""), false);
        assertThat(export()).contains("Call the caretaker on 98450 12345");
    }

    @Test
    void aQuestionWithABadIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var house = houseRow(UUID.randomUUID(), "Fine", now);
        assertRefused(backupWithQuestions(house, questionRow("bad id!", "Text", "MONEY", "BOTH", 0, now)),
                "questions[0].id is not a valid record id", null);
        assertRefused(backupWithQuestions(house, questionRow("..", "Text", "MONEY", "BOTH", 0, now)),
                "questions[0].id is not a valid record id", null);
    }

    @Test
    void aQuestionWithBlankTextRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backupWithQuestions(houseRow(UUID.randomUUID(), "Fine", now),
                questionRow("q_1", "   ", "MONEY", "BOTH", 0, now)), "questions[0].text is required", null);
    }

    @Test
    void aQuestionWithOverLongTextRefusesTheWholeFileWithoutEchoingIt() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backupWithQuestions(houseRow(UUID.randomUUID(), "Fine", now),
                questionRow("q_1", "t".repeat(301), "MONEY", "BOTH", 0, now)),
                "questions[0].text is longer than 300 characters", "ttttttttttttttttttttttttttttt");
    }

    @Test
    void aQuestionWithAnUnknownCategoryRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backupWithQuestions(houseRow(UUID.randomUUID(), "Fine", now),
                questionRow("q_1", "Text", "PETS", "BOTH", 0, now)), "questions[0].category is out of range", null);
    }

    @Test
    void aQuestionWithAnUnknownAppliesToRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backupWithQuestions(houseRow(UUID.randomUUID(), "Fine", now),
                questionRow("q_1", "Text", "MONEY", "EVERYONE", 0, now)), "questions[0].appliesTo is out of range", null);
    }

    @Test
    void aQuestionWithANegativeSortRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backupWithQuestions(houseRow(UUID.randomUUID(), "Fine", now),
                questionRow("q_1", "Text", "MONEY", "BOTH", -1, now)), "questions[0].sort must not be negative", null);
    }

    @Test
    void aRepeatedQuestionIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backupWithQuestions(houseRow(UUID.randomUUID(), "Fine", now),
                questionRow("q_1", "One", "MONEY", "BOTH", 0, now) + "," + questionRow("q_1", "Two", "MONEY", "BOTH", 1, now)),
                "questions[1].id appears twice", null);
    }

    /** The row cap of this suite (60) would answer 413 first over HTTP, so the service is called directly. */
    @Test
    void moreThanAHundredQuestionsRefuseTheWholeFile() {
        var at = Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli();
        var rows = new ArrayList<BackupQuestion>();
        for (int i = 0; i <= BackupQuestion.MAX; i++) {
            rows.add(new BackupQuestion("q_" + i, "Question " + i, "OTHER", "BOTH", false, i, null, at));
        }
        var data = new BackupData(BackupFormat.ID_WITH_BROKERS, at, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), rows, List.of());
        assertThatThrownBy(() -> backupService.importBackup(data, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 100 questions");
        var hundred = new BackupData(BackupFormat.ID_WITH_BROKERS, at, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), rows.subList(0, 100), List.of());
        assertThat(backupService.importBackup(hundred, true).questions().created()).isEqualTo(100);
    }

    @Test
    void anAnswerWithABadIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        for (var id : List.of("..", "has space")) {
            assertRefused(backup(fine + "," + houseWithAnswers(UUID.randomUUID(), "Bad", now,
                    GOOD_ANSWER.replace("\"a1\"", "\"" + id + "\"")), ""), "houses[1].answers[0].id is out of range", null);
        }
    }

    @Test
    void anAnswerWithABadQuestionIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now,
                GOOD_ANSWER.replace("qd_water", "has space")), ""), "houses[0].answers[0].questionId is out of range", null);
    }

    @Test
    void aRepeatedAnswerIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now, GOOD_ANSWER + "," + GOOD_ANSWER), ""),
                "houses[0].answers[1].id is repeated", null);
    }

    @Test
    void anAnswerWithBlankQuestionTextRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now,
                GOOD_ANSWER.replace("Water?", "  ")), ""), "houses[0].answers[0].text is out of range", null);
    }

    @Test
    void anAnswerWithOverLongQuestionTextRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now,
                GOOD_ANSWER.replace("Water?", "t".repeat(301))), ""), "houses[0].answers[0].text is out of range",
                "ttttttttttttttttttttttttttttt");
    }

    @Test
    void anOverLongAnswerRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now,
                GOOD_ANSWER.replace("Borewell", "a".repeat(2001))), ""), "houses[0].answers[0].answer is out of range",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    @Test
    void anAnswerWithAnUnknownStatusRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now,
                GOOD_ANSWER.replace("ANSWERED", "DONE")), ""), "houses[0].answers[0].status is out of range", null);
    }

    @Test
    void anAnswerWithANegativeSortRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now,
                GOOD_ANSWER.replace("\"sort\":0", "\"sort\":-1")), ""), "houses[0].answers[0].sort is out of range", null);
    }

    @Test
    void aSixtyFirstAnswerRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var many = new StringBuilder();
        for (int i = 0; i <= 60; i++) many.append(i == 0 ? "" : ",").append(GOOD_ANSWER.replace("\"a1\"", "\"a" + i + "\""));
        assertRefused(backup(houseWithAnswers(UUID.randomUUID(), "Bad", now, many.toString()), ""),
                "houses[0].answers has more than 60 answers", null);
    }

    // ---- small readers ------------------------------------------------------------------------------------------

    private String label(UUID id) {
        return (String) api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("label");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> checklist(UUID id) {
        return (Map<String, Object>) api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("checklist");
    }

    private long syncVersion(UUID id) {
        return ((Number) api.get().uri("/api/houses/{id}", id).retrieve().body(MAP).get("syncVersion")).longValue();
    }

    // ---- slice 3b-1: viewings ---------------------------------------------------------------------------------------

    @Autowired
    app.doorprints.server.record.RecordRepository recordRepository;

    private void putViewing(JSONObject row) throws JSONException {
        var payload = new JSONObject(row.toString());
        var id = payload.remove("id");
        var updatedAt = payload.remove("updatedAt");
        var body = new JSONObject().put("type", "viewing").put("id", id).put("payload", payload)
                .put("updatedAt", Instant.ofEpochMilli(((Number) updatedAt).longValue()).toString());
        api.put().uri("/api/records/viewing/{id}", id.toString()).contentType(MediaType.APPLICATION_JSON)
                .body(body.toString()).retrieve().toBodilessEntity();
    }

    private List<Map<String, Object>> viewingRecords() {
        return api.get().uri("/api/records?since=0&type=viewing").retrieve().body(LIST);
    }

    private static String viewingRow(String id, String houseId, long startsAt, Instant updatedAt) {
        return "{\"id\":\"" + id + "\",\"houseId\":\"" + houseId + "\",\"startsAt\":" + startsAt
                + ",\"durationMin\":30,\"kind\":\"FIRST\",\"status\":\"PLANNED\",\"remindMin\":60,\"updatedAt\":"
                + updatedAt.toEpochMilli() + "}";
    }

    private static String backupWithViewings(String houses, String viewings) {
        return backup(houses, "").replace(BackupFormat.ID, BackupFormat.ID_WITH_BROKERS)
                .replace("\"photos\":[]", "\"photos\":[],\"viewings\":[" + viewings + "]");
    }

    /** The key names of the export's viewings list in the order written (JSONObject does not keep the order). */
    private static List<String> viewingKeysInOrder(String exported) {
        var keys = CanonicalSample.keysInOrder(exported.substring(exported.indexOf("\"viewings\":")));
        return keys.subList(1, keys.size()); // the first key is "viewings" itself
    }

    private static final String SOME_HOUSE = "11111111-1111-4111-8111-111111111111";

    /** A copy with no viewing is a /1 document without the key; a viewing alone makes it /2 (a dangling house is fine). */
    @Test
    void aCopyWithoutViewingsIsVersionOneAndAViewingAloneMakesItVersionTwo() throws JSONException {
        var none = new JSONObject(export());
        assertThat(none.getString("format")).isEqualTo(BackupFormat.ID);
        assertThat(none.has("viewings")).isFalse();
        var now = Instant.now().minus(Duration.ofMinutes(5));
        postImport(backupWithViewings("", viewingRow("v_0000aaaa", SOME_HOUSE, 1_790_000_000_000L, now)), false);
        var exported = new JSONObject(export());
        assertThat(exported.getString("format")).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(exported.getJSONArray("viewings").length()).isEqualTo(1);
    }

    /** Viewings are records of type viewing: merged by id, last write wins, exported back in payload order. */
    @Test
    void viewingsImportMergeByIdLastWriteWinsAndExportBack() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        var file = backupWithViewings("",
                viewingRow("v_0000bbbb", SOME_HOUSE, 1_790_000_100_000L, now) + ","
                        + viewingRow("v_0000aaaa", SOME_HOUSE, 1_790_000_000_000L, now.minusSeconds(60)));
        assertThat(count(postImport(file, true), "viewings", "created")).isEqualTo(2);
        assertThat(viewingRecords()).as("dry run writes nothing").isEmpty();
        var applied = postImport(file, false);
        assertThat(count(applied, "viewings", "created")).isEqualTo(2);
        assertThat(count(applied, "viewings", "total")).isEqualTo(2);
        var rows = new JSONObject(export()).getJSONArray("viewings");
        assertThat(rows.getJSONObject(0).getString("id")).as("ordered by updatedAt then id").isEqualTo("v_0000aaaa");
        assertThat(viewingKeysInOrder(export())).containsExactly("id", "houseId", "startsAt", "durationMin", "kind",
                "status", "remindMin", "updatedAt", "id", "houseId", "startsAt", "durationMin", "kind", "status",
                "remindMin", "updatedAt");

        assertThat(count(postImport(file, false), "viewings", "unchanged")).isEqualTo(2);
        var older = backupWithViewings("", viewingRow("v_0000bbbb", SOME_HOUSE, 1L, now.minusSeconds(30)));
        assertThat(count(postImport(older, false), "viewings", "keptNewer")).isEqualTo(1);
        var newer = backupWithViewings("", viewingRow("v_0000bbbb", SOME_HOUSE, 1_790_000_200_000L, now.plusSeconds(30))
                .replace("\"PLANNED\"", "\"DONE\"").replace("\"remindMin\":60", "\"remindMin\":60,\"huntReminder\":true,"
                        + "\"withWhom\":\"Ravi\",\"notes\":\"Bring a tape\",\"visitId\":\"vis-1\""));
        assertThat(count(postImport(newer, false), "viewings", "updated")).isEqualTo(1);
        var after = new JSONObject(export()).getJSONArray("viewings");
        var changed = after.getJSONObject(1);
        assertThat(viewingKeysInOrder(export()).subList(8, 20)).containsExactly("id", "houseId", "startsAt",
                "durationMin", "kind", "status", "remindMin", "huntReminder", "withWhom", "notes", "visitId", "updatedAt");
        assertThat(changed.getString("status")).isEqualTo("DONE");
        assertThat(changed.getLong("startsAt")).isEqualTo(1_790_000_200_000L);
    }

    /** A deleted viewing (a tombstone) is made live again by a newer file, not by an older one. */
    @Test
    void aNewerFileRevivesADeletedViewing() throws JSONException {
        var now = Instant.now().minus(Duration.ofMinutes(5));
        postImport(backupWithViewings("", viewingRow("v_0000cccc", SOME_HOUSE, 1_790_000_000_000L, now)), false);
        api.delete().uri("/api/records/viewing/{id}", "v_0000cccc").retrieve().toBodilessEntity();
        assertThat(new JSONObject(export()).has("viewings")).as("a tombstone is not exported").isFalse();

        var older = backupWithViewings("", viewingRow("v_0000cccc", SOME_HOUSE, 1_790_000_000_000L, now.plusSeconds(1)));
        assertThat(count(postImport(older, false), "viewings", "keptNewer")).isEqualTo(1);
        assertThat(new JSONObject(export()).has("viewings")).isFalse();

        var newer = backupWithViewings("", viewingRow("v_0000cccc", SOME_HOUSE, 1_790_000_900_000L,
                Instant.now().plusSeconds(20)));
        assertThat(count(postImport(newer, false), "viewings", "updated")).isEqualTo(1);
        assertThat(new JSONObject(export()).getJSONArray("viewings").getJSONObject(0).getLong("startsAt"))
                .isEqualTo(1_790_000_900_000L);
    }

    @Test
    void aViewingWithABadIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        for (var id : List.of("bad id!", "..")) {
            assertRefused(backupWithViewings(houseRow(UUID.randomUUID(), "Fine", now),
                    viewingRow(id, SOME_HOUSE, 1_790_000_000_000L, now)), "viewings[0].id is not a valid record id", null);
        }
    }

    @Test
    void aRepeatedViewingIdRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var row = viewingRow("v_0000dddd", SOME_HOUSE, 1_790_000_000_000L, now);
        assertRefused(backupWithViewings(houseRow(UUID.randomUUID(), "Fine", now), row + "," + row),
                "viewings[1].id appears twice", null);
    }

    @Test
    void aViewingWithABlankOrMissingHouseRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        assertRefused(backupWithViewings(fine, viewingRow("v_0000dddd", "  ", 1_790_000_000_000L, now)),
                "viewings[0].houseId is required", null);
        assertRefused(backupWithViewings(fine, viewingRow("v_0000dddd", SOME_HOUSE, 1_790_000_000_000L, now)
                .replace("\"houseId\":\"" + SOME_HOUSE + "\",", "")), "viewings[0].houseId is required", null);
    }

    @Test
    void aViewingWithAMissingOrNonPositiveStartRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        for (var start : List.of(0L, -5L)) {
            assertRefused(backupWithViewings(fine, viewingRow("v_0000dddd", SOME_HOUSE, start, now)),
                    "viewings[0].startsAt must be a positive time", null);
        }
        assertRefused(backupWithViewings(fine, viewingRow("v_0000dddd", SOME_HOUSE, 1L, now)
                .replace("\"startsAt\":1,", "")), "viewings[0].startsAt must be a positive time", null);
    }

    @Test
    void aViewingWithADurationOutOfRangeRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        for (var minutes : List.of("4", "481", "-30")) {
            assertRefused(backupWithViewings(fine, viewingRow("v_0000dddd", SOME_HOUSE, 1_790_000_000_000L, now)
                    .replace("\"durationMin\":30", "\"durationMin\":" + minutes)),
                    "viewings[0].durationMin must be 5..480", null);
        }
    }

    @Test
    void aViewingWithAnUnknownKindStatusOrReminderRefusesTheWholeFile() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        var row = viewingRow("v_0000dddd", SOME_HOUSE, 1_790_000_000_000L, now);
        assertRefused(backupWithViewings(fine, row.replace("\"FIRST\"", "\"THIRD\"")),
                "viewings[0].kind is out of range", "THIRD");
        assertRefused(backupWithViewings(fine, row.replace("\"PLANNED\"", "\"MISSED\"")),
                "viewings[0].status is out of range", "MISSED");
        for (var minutes : List.of("45", "-15", "1441")) {
            assertRefused(backupWithViewings(fine, row.replace("\"remindMin\":60", "\"remindMin\":" + minutes)),
                    "viewings[0].remindMin is out of range", null);
        }
    }

    @Test
    void aViewingWithTooLongWithWhomOrNotesRefusesTheWholeFileWithoutEchoingIt() {
        var now = Instant.now().minus(Duration.ofMinutes(1));
        var fine = houseRow(UUID.randomUUID(), "Fine", now);
        var row = viewingRow("v_0000dddd", SOME_HOUSE, 1_790_000_000_000L, now);
        assertRefused(backupWithViewings(fine, row.replace("\"remindMin\":60", "\"remindMin\":60,\"withWhom\":\""
                + "w".repeat(201) + "\"")), "viewings[0].withWhom is longer than 200 characters", "wwwwwwwwwwwwwwww");
        assertRefused(backupWithViewings(fine, row.replace("\"remindMin\":60", "\"remindMin\":60,\"notes\":\""
                + "n".repeat(2001) + "\"")), "viewings[0].notes is longer than 2000 characters", "nnnnnnnnnnnnnnnn");
    }

    /** The row cap of this suite (60) would answer 413 first over HTTP, so the service is called directly. */
    @Test
    void moreThanFiveThousandViewingsRefuseTheWholeFile() {
        var at = Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli();
        var rows = new ArrayList<BackupViewing>();
        for (int i = 0; i <= BackupViewing.MAX; i++) {
            rows.add(new BackupViewing("v_" + i, SOME_HOUSE, 1_790_000_000_000L + i, 30, "FIRST", "PLANNED", 60, null,
                    null, null, null, at));
        }
        var data = new BackupData(BackupFormat.ID_WITH_BROKERS, at, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), rows);
        assertThatThrownBy(() -> backupService.importBackup(data, true)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 5000 viewings");
        var exactly = new BackupData(BackupFormat.ID_WITH_BROKERS, at, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), rows.subList(0, BackupViewing.MAX));
        assertThat(backupService.importBackup(exactly, true).viewings().created()).isEqualTo(BackupViewing.MAX);
    }

    /** A server already holding 5 000 live viewings skips a new one (and says so) but still updates an existing one. */
    @Test
    void aServerHoldingFiveThousandViewingsSkipsAnAdditionalOne() {
        var stamp = Instant.now().minus(Duration.ofHours(2));
        var stored = new ArrayList<app.doorprints.server.record.Record>();
        for (int i = 0; i < 5_000; i++) {
            var record = new app.doorprints.server.record.Record(
                    new app.doorprints.server.record.RecordKey("viewing", "v_full" + i));
            record.setPayload("{\"houseId\":\"" + SOME_HOUSE + "\",\"startsAt\":1790000000000}");
            record.setUpdatedAt(stamp);
            record.setSyncVersion(1);
            stored.add(record);
        }
        recordRepository.saveAll(stored);
        try {
            var at = Instant.now().minus(Duration.ofMinutes(1)).toEpochMilli();
            var extra = new BackupViewing("v_extra", SOME_HOUSE, 1_790_000_000_000L, 30, "FIRST", "PLANNED", 60, null,
                    null, null, null, at);
            var existing = new BackupViewing("v_full0", SOME_HOUSE, 1_790_000_000_000L, 30, "FIRST", "DONE", 60, null,
                    null, null, null, at);
            var report = backupService.importBackup(new BackupData(BackupFormat.ID_WITH_BROKERS, at, List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(extra, existing)), true);
            assertThat(report.viewings().skipped()).isEqualTo(1);
            assertThat(report.viewings().updated()).isEqualTo(1);
            assertThat(report.problems()).anyMatch(p -> p.contains("the most viewings it keeps (5000)"))
                    .noneMatch(p -> p.contains("v_extra"));
        } finally {
            recordRepository.deleteAll(stored);
        }
    }
}
