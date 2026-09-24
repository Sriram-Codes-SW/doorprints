package app.doorprints.server.backup;

import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.photo.PhotoDto;
import app.doorprints.server.visit.Visit;
import app.doorprints.server.visit.VisitSource;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ordering and the field mapping of the shared backup format (docs/schemas/README.md section 5), without a
 * database or HTTP. {@link BackupApiTest} checks the same rules end to end against the canonical sample.
 */
class BackupMapperTest {

    private static final Instant EXPORTED_AT = Instant.parse("2026-09-22T10:15:30Z");

    private static final UUID FIRST = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID THIRD = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static House house(UUID id, Instant createdAt, boolean deleted) {
        var house = new House(id);
        house.setLabel(deleted ? "" : "House " + id);
        house.setLat(12.9);
        house.setLon(77.6);
        house.setStatus(HouseStatus.NEW);
        house.setCreatedAt(createdAt);
        house.setUpdatedAt(createdAt.plusSeconds(60));
        house.setDeleted(deleted);
        return house;
    }

    private static Visit visit(UUID id, UUID houseId, Instant arrivedAt, boolean deleted) {
        var visit = new Visit(id);
        visit.setHouseId(houseId);
        visit.setLat(12.9);
        visit.setLon(77.6);
        visit.setArrivedAt(arrivedAt);
        visit.setSource(VisitSource.MANUAL);
        visit.setUpdatedAt(arrivedAt);
        visit.setDeleted(deleted);
        return visit;
    }

    private static PhotoDto photo(UUID id, UUID houseId, Instant createdAt, boolean deleted) {
        return new PhotoDto(id, houseId, "image/jpeg", 3, createdAt, createdAt, deleted, 1);
    }

    @Test
    void housesAreOrderedByCreatedAtThenIdAndTombstonesAreLeftOut() {
        var sameMoment = Instant.parse("2026-09-02T06:00:00Z");
        var data = BackupMapper.toBackup(List.of(
                        house(THIRD, sameMoment, false),
                        house(SECOND, sameMoment, false),
                        house(FIRST, Instant.parse("2026-09-01T06:00:00Z"), false),
                        house(UUID.randomUUID(), Instant.parse("2026-08-01T06:00:00Z"), true)),
                List.of(), List.of(), EXPORTED_AT);

        assertThat(data.format()).isEqualTo(BackupFormat.ID);
        assertThat(data.exportedAt()).isEqualTo(EXPORTED_AT.toEpochMilli());
        assertThat(data.houses()).extracting(BackupHouse::id).containsExactly(FIRST, SECOND, THIRD);
        assertThat(data.houses().getFirst().createdAt()).isEqualTo(Instant.parse("2026-09-01T06:00:00Z").toEpochMilli());
    }

    @Test
    void checklistKeysAreSortedAndEmptyStaysEmpty() {
        var house = house(FIRST, EXPORTED_AT, false);
        house.setChecklist(Map.of("water", 5, "parking", 4, "newItemFromNewerApp", 2, "power", 3));
        var bare = house(SECOND, EXPORTED_AT, false);

        var data = BackupMapper.toBackup(List.of(house, bare), List.of(), List.of(), EXPORTED_AT);

        assertThat(data.houses().getFirst().checklist().keySet())
                .containsExactly("newItemFromNewerApp", "parking", "power", "water");
        assertThat(data.houses().get(1).checklist()).isEmpty();
    }

    /** Visits and photos follow their house; a visit whose house was deleted (houseId null) comes last. */
    @Test
    void visitsAndPhotosAreGroupedByHouseAndOrphansComeLast() {
        var houses = List.of(house(FIRST, Instant.parse("2026-09-01T06:00:00Z"), false),
                house(SECOND, Instant.parse("2026-09-02T06:00:00Z"), false));
        var early = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1");
        var late = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2");
        var forSecondHouse = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3");
        var orphan = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4");
        var deleted = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa5");
        var visits = List.of(
                visit(orphan, null, Instant.parse("2026-09-04T04:00:00Z"), false),
                visit(late, FIRST, Instant.parse("2026-09-09T04:00:00Z"), false),
                visit(forSecondHouse, SECOND, Instant.parse("2026-09-03T04:00:00Z"), false),
                visit(deleted, FIRST, Instant.parse("2026-09-02T04:00:00Z"), true),
                visit(early, FIRST, Instant.parse("2026-09-05T04:00:00Z"), false));

        var photoId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1");
        var photos = List.of(photo(UUID.randomUUID(), FIRST, EXPORTED_AT, true),
                photo(photoId, SECOND, EXPORTED_AT, false));

        var data = BackupMapper.toBackup(houses, visits, photos, EXPORTED_AT);

        assertThat(data.visits()).extracting(BackupVisit::id)
                .containsExactly(early, late, forSecondHouse, orphan);
        assertThat(data.visits().getFirst().leftAt()).as("an open visit has no leftAt").isNull();
        assertThat(data.visits().getLast().houseId()).isNull();
        assertThat(data.photos()).singleElement().satisfies(p -> {
            assertThat(p.id()).isEqualTo(photoId);
            assertThat(p.houseId()).isEqualTo(SECOND);
            assertThat(p.fileName()).isEqualTo(photoId + ".jpg");
        });
    }

    /** The merge rule: newer in the file wins, newer here is kept, equal writes nothing (S4-00, docs/11 5.2). */
    @Test
    void mergeDecisionIsLastWriteWins() {
        var now = Instant.parse("2026-09-22T10:15:30Z");
        assertThat(ImportReport.decide(null, now)).isEqualTo(ImportReport.Outcome.CREATED);
        assertThat(ImportReport.decide(now.minusSeconds(1), now)).isEqualTo(ImportReport.Outcome.UPDATED);
        assertThat(ImportReport.decide(now.plusSeconds(1), now)).isEqualTo(ImportReport.Outcome.KEPT_NEWER);
        assertThat(ImportReport.decide(now, now)).isEqualTo(ImportReport.Outcome.UNCHANGED);
    }

    /**
     * The canonical sample's own row order is the grouped one, and this mapper reproduces it from rows handed
     * over in the order a <em>global</em> sort would produce.
     *
     * <p>The last two assertions are the point of the fixture: they fail if the sample is ever edited back into
     * one whose rows do not interleave. A fixture where every visit belongs to the same house agrees with a
     * global sort by accident, so it pins nothing and the contract in docs/schemas/README.md section 5 becomes
     * unverifiable — which is exactly what story S4-00 exists to prevent.
     */
    @Test
    void canonicalSampleOrderIsGroupedByHouseAndTheMapperReproducesIt() throws JSONException {
        var sample = new JSONObject(CanonicalSample.json());

        var houses = new ArrayList<House>();
        var houseRows = sample.getJSONArray("houses");
        for (int i = 0; i < houseRows.length(); i++) {
            var row = houseRows.getJSONObject(i);
            houses.add(house(UUID.fromString(row.getString("id")),
                    Instant.ofEpochMilli(row.getLong("createdAt")), false));
        }

        var visits = new ArrayList<Visit>();
        var visitOrderInFile = new ArrayList<UUID>();
        var visitRows = sample.getJSONArray("visits");
        for (int i = 0; i < visitRows.length(); i++) {
            var row = visitRows.getJSONObject(i);
            var id = UUID.fromString(row.getString("id"));
            visitOrderInFile.add(id);
            visits.add(visit(id, UUID.fromString(row.getString("houseId")),
                    Instant.ofEpochMilli(row.getLong("arrivedAt")), false));
        }

        var photos = new ArrayList<PhotoDto>();
        var photoOrderInFile = new ArrayList<UUID>();
        var photoRows = sample.getJSONArray("photos");
        for (int i = 0; i < photoRows.length(); i++) {
            var row = photoRows.getJSONObject(i);
            var id = UUID.fromString(row.getString("id"));
            photoOrderInFile.add(id);
            photos.add(photo(id, UUID.fromString(row.getString("houseId")),
                    Instant.ofEpochMilli(row.getLong("createdAt")), false));
        }

        // Hand the rows over globally sorted, which is what a writer that forgets to group produces.
        // Explicitly typed lambdas: with an implicit one both thenComparing overloads apply and javac balks.
        visits.sort(Comparator.comparing(Visit::getArrivedAt).thenComparing((Visit v) -> v.getId().toString()));
        photos.sort(Comparator.comparing(PhotoDto::createdAt).thenComparing((PhotoDto p) -> p.id().toString()));

        var data = BackupMapper.toBackup(houses, visits, photos, EXPORTED_AT);

        assertThat(data.visits()).extracting(BackupVisit::id).containsExactlyElementsOf(visitOrderInFile);
        assertThat(data.photos()).extracting(BackupPhoto::id).containsExactlyElementsOf(photoOrderInFile);
        assertThat(visits.stream().map(Visit::getId).toList())
                .as("the sample must interleave, or grouped and globally sorted look the same")
                .isNotEqualTo(visitOrderInFile);
        assertThat(photos.stream().map(PhotoDto::id).toList())
                .as("the sample must interleave, or grouped and globally sorted look the same")
                .isNotEqualTo(photoOrderInFile);
    }

    /** The canonical sample is readable and is the format this code writes. */
    @Test
    void canonicalSampleIsTheSameFormat() {
        var sample = CanonicalSample.json();
        assertThat(sample).startsWith("{\"format\":\"" + BackupFormat.ID + "\"");
        assertThat(CanonicalSample.keysInOrder(sample)).startsWith("format", "exportedAt", "houses", "id", "label");
        assertThat(sample).doesNotContain("\"deleted\"").doesNotContain("\"syncVersion\"").doesNotContain("null");
    }
}
