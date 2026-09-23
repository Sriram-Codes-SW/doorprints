package com.househunt.backup;

import com.househunt.photo.ImageSanitizerTest;
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
                "app.limits.max-import-rows=60"})
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
        assertThat(preview).containsEntry("dryRun", true).containsEntry("format", BackupFormat.ID);
        assertThat(count(preview, "houses", "created")).isEqualTo(3);
        assertThat(count(preview, "visits", "created")).isEqualTo(3);
        assertThat(count(preview, "photos", "skipped")).isEqualTo(2);
        assertThat(api.get().uri("/api/houses").retrieve().body(LIST)).as("dry run writes nothing").isEmpty();

        var applied = postImport(SAMPLE, false);
        assertThat(applied).containsEntry("dryRun", false);
        assertThat(count(applied, "houses", "created")).isEqualTo(3);
        assertThat(count(applied, "visits", "created")).isEqualTo(3);
        var problems = problems(applied);
        assertThat(problems).anyMatch(p -> p.contains("photo"));
        assertThat(problems).as("a three-house import still updates the AI index row by row")
                .noneMatch(p -> p.contains("AI index"));

        // Photo rows carry no bytes over JSON, so the restored server has none; everything else must match.
        var expected = new JSONObject(SAMPLE).put("photos", new JSONArray()).toString();
        assertExportEquals(expected, export());
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
        var visitEndsBeforeItStarts = backup(good, "{\"id\":\"" + UUID.randomUUID() + "\",\"houseId\":\"" + id
                + "\",\"lat\":12.9,\"lon\":77.6,\"arrivedAt\":" + now.toEpochMilli() + ",\"leftAt\":"
                + now.minus(Duration.ofHours(1)).toEpochMilli() + ",\"source\":\"AUTO\",\"updatedAt\":"
                + now.toEpochMilli() + "}");

        for (var body : List.of(wrongFormat, duplicateIds, noLabel, badLatitude, noLatitude, nullLongitude,
                visitWithoutLatitude, absurdClock, visitEndsBeforeItStarts)) {
            assertThat(status(() -> postImport(body, false))).as("import of %s", body).isEqualTo(400);
            assertThat(status(() -> postImport(body, true))).as("dry run validates too").isEqualTo(400);
        }
        // ... and for the reason given, not some other one (a missing coordinate is not read as 0).
        assertThat(errorBody(() -> postImport(noLatitude, false))).contains("houses[0].lat is required");
        assertThat(errorBody(() -> postImport(nullLongitude, false))).contains("houses[0].lon is required");
        assertThat(errorBody(() -> postImport(visitWithoutLatitude, false))).contains("visits[0].lat is required");
        assertThat(api.get().uri("/api/houses?since=0").retrieve().body(LIST)).isEmpty();
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
}
