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

import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.photo.PhotoDto;
import app.doorprints.server.record.Record;
import app.doorprints.server.record.RecordKey;
import app.doorprints.server.visit.Visit;
import app.doorprints.server.visit.VisitSource;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    private static final ObjectMapper JSON = JsonMapper.builder().build();
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

    private static Record broker(String id, String payload, Instant updatedAt, boolean deleted) {
        var record = new Record(new RecordKey("broker", id));
        record.setPayload(payload);
        record.setUpdatedAt(updatedAt);
        record.setDeleted(deleted);
        return record;
    }

    /** No live broker: the copy is a /1 document with no brokers key at all, exactly as before slice 1b. */
    @Test
    void withoutBrokersTheCopyIsVersionOneAndHasNoBrokersKey() {
        var data = BackupMapper.toBackup(List.of(house(FIRST, EXPORTED_AT, false)), List.of(), List.of(),
                List.of(broker("gone", "{}", EXPORTED_AT, true)), List.of(), List.of(), JSON, EXPORTED_AT);
        assertThat(data.format()).isEqualTo("doorprints-backup/1");
        assertThat(data.brokers()).isEmpty();
        assertThat(JSON.writeValueAsString(data)).doesNotContain("brokers");
    }

    /** Brokers by updatedAt then id, the record's fields in the format's order, and /2 as soon as there is one. */
    @Test
    void brokersMakeItVersionTwoAndAreOrderedByUpdatedAtThenId() {
        var early = EXPORTED_AT.minusSeconds(60);
        var data = BackupMapper.toBackup(List.of(), List.of(), List.of(), List.of(
                broker("b-2", "{\"name\":\"Second\",\"rating\":5}", EXPORTED_AT, false),
                broker("b-1", "{\"notes\":\"n\",\"name\":\"First\",\"agency\":\"A\",\"phone\":\"98400\","
                        + "\"feeTerms\":\"15 days\"}", EXPORTED_AT, false),
                broker("a-0", "{\"name\":\"Oldest\"}", early, false),
                broker("x-9", "{\"name\":\"Deleted\"}", early, true)), List.of(), List.of(), JSON, EXPORTED_AT);

        assertThat(data.format()).isEqualTo("doorprints-backup/2");
        assertThat(data.brokers()).extracting(BackupBroker::id).containsExactly("a-0", "b-1", "b-2");
        assertThat(JSON.writeValueAsString(data.brokers().get(1))).isEqualTo("{\"id\":\"b-1\",\"name\":\"First\","
                + "\"phone\":\"98400\",\"agency\":\"A\",\"feeTerms\":\"15 days\",\"notes\":\"n\","
                + "\"updatedAt\":" + EXPORTED_AT.toEpochMilli() + "}");
        assertThat(data.brokers().get(2).rating()).isEqualTo(5);
        assertThat(data.brokers().getFirst().updatedAt()).isEqualTo(early.toEpochMilli());
    }

    /** The server never reads inside a record, so an export drops what would not import again instead of failing. */
    @Test
    void aBrokerPayloadThatWouldNotImportIsCoercedOrLeftOut() {
        var data = BackupMapper.toBackup(List.of(), List.of(), List.of(), List.of(
                broker("no-name", "{\"phone\":\"1\"}", EXPORTED_AT, false),
                broker("blank", "{\"name\":\"  \"}", EXPORTED_AT, false),
                broker("not-json", "not json", EXPORTED_AT, false),
                broker("long-name", "{\"name\":\"" + "n".repeat(201) + "\"}", EXPORTED_AT, false),
                broker("odd", "{\"name\":\"Odd\",\"phone\":7,\"rating\":6,\"agency\":\"" + "a".repeat(201)
                        + "\",\"notes\":null}", EXPORTED_AT, false)), List.of(), List.of(), JSON, EXPORTED_AT);

        assertThat(data.brokers()).singleElement().satisfies(b -> {
            assertThat(b.id()).isEqualTo("odd");
            assertThat(b.name()).isEqualTo("Odd");
            assertThat(b.phone()).isNull();
            assertThat(b.rating()).isNull();
            assertThat(b.agency()).isNull();
            assertThat(b.notes()).isNull();
        });
    }

    private static String roomsJson() {
        return "[{\"id\":\"c2\",\"type\":\"KITCHEN\",\"name\":\"Kitchen\",\"lengthCm\":300,\"widthCm\":244,\"sort\":1},"
                + "{\"id\":\"c1\",\"type\":\"BEDROOM\",\"name\":\"Master bedroom\",\"lengthCm\":396,\"widthCm\":366,"
                + "\"condition\":4,\"notes\":\"Damp\",\"sort\":0}]";
    }

    /** Slice 1c: rooms alone make the copy /2, are written after cost and before brokerId, and only when there are some. */
    @Test
    void roomsMakeItVersionTwoAndAreWrittenOnlyWhenThereAreSome() {
        var withRooms = house(FIRST, EXPORTED_AT, false);
        withRooms.setRooms(roomsJson());
        withRooms.setCost("{\"deposit\":64000}");
        withRooms.setBrokerId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var withEmpty = house(SECOND, EXPORTED_AT.plusSeconds(1), false);
        withEmpty.setRooms("[]");
        var none = BackupMapper.toBackup(List.of(withEmpty), List.of(), List.of(), EXPORTED_AT);
        assertThat(none.format()).as("an empty list is no rooms").isEqualTo("doorprints-backup/1");
        assertThat(none.houses().getFirst().rooms()).isNull();
        assertThat(JSON.writeValueAsString(none)).doesNotContain("rooms");

        var data = BackupMapper.toBackup(List.of(withRooms, withEmpty), List.of(), List.of(), EXPORTED_AT);
        assertThat(data.format()).isEqualTo("doorprints-backup/2");
        assertThat(data.brokers()).isEmpty();
        assertThat(data.houses().getFirst().rooms()).extracting(r -> r.id()).containsExactly("c2", "c1");
        assertThat(data.houses().get(1).rooms()).isNull();
        var written = JSON.readTree(JSON.writeValueAsString(data.houses().getFirst()));
        assertThat(new ArrayList<String>(written.propertyNames())).containsSubsequence("cost", "rooms", "brokerId", "checklist");
        assertThat(new ArrayList<String>(written.get("rooms").get(1).propertyNames()))
                .containsExactly("id", "type", "name", "lengthCm", "widthCm", "condition", "notes", "sort");
        assertThat(new ArrayList<String>(written.get("rooms").get(0).propertyNames()))
                .containsExactly("id", "type", "name", "lengthCm", "widthCm", "sort");
    }

    /** A tombstone's rooms are never exported, and do not make the copy /2. */
    @Test
    void aDeletedHousesRoomsAreNotExported() {
        var gone = house(FIRST, EXPORTED_AT, true);
        gone.setRooms(roomsJson());
        assertThat(BackupMapper.toBackup(List.of(gone), List.of(), List.of(), EXPORTED_AT).format())
                .isEqualTo("doorprints-backup/1");
    }

    @Test
    void aHouseKeepsItsBrokerIdEvenWhenNoSuchBrokerExists() {
        var withBroker = house(FIRST, EXPORTED_AT, false);
        withBroker.setBrokerId("nobody-yet");
        var data = BackupMapper.toBackup(List.of(withBroker), List.of(), List.of(), EXPORTED_AT);
        assertThat(data.houses().getFirst().brokerId()).isEqualTo("nobody-yet");
    }

    private static Record question(String id, String payload, Instant updatedAt, boolean deleted) {
        var record = new Record(new RecordKey("question", id));
        record.setPayload(payload);
        record.setUpdatedAt(updatedAt);
        record.setDeleted(deleted);
        return record;
    }

    private static String answersJson() {
        return "[{\"id\":\"a2\",\"text\":\"Is the terrace open?\",\"status\":\"OPEN\",\"sort\":1},"
                + "{\"id\":\"a1\",\"questionId\":\"qd_maintenance\",\"text\":\"Maintenance?\","
                + "\"answer\":\"Rs 2,500\",\"status\":\"ANSWERED\",\"sort\":0}]";
    }

    /** Slice 3a: no question and no answers is a /1 document with neither key. */
    @Test
    void withoutQuestionsOrAnswersTheCopyIsVersionOneAndHasNeitherKey() {
        var withEmpty = house(FIRST, EXPORTED_AT, false);
        withEmpty.setAnswers("[]");
        var data = BackupMapper.toBackup(List.of(withEmpty), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(question("gone", "{\"text\":\"Old?\"}", EXPORTED_AT, true)), JSON, EXPORTED_AT);
        assertThat(data.format()).isEqualTo(BackupFormat.ID);
        assertThat(data.questions()).isEmpty();
        assertThat(data.houses().getFirst().answers()).isNull();
        assertThat(JSON.writeValueAsString(data)).doesNotContain("questions").doesNotContain("answers");
    }

    /** A live question alone makes the copy /2. */
    @Test
    void aQuestionAloneMakesItVersionTwo() {
        var data = BackupMapper.toBackup(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(question("q_9f8e7d6c", "{\"text\":\"Water meter?\"}", EXPORTED_AT, false)), JSON, EXPORTED_AT);
        assertThat(data.format()).isEqualTo(BackupFormat.ID_WITH_BROKERS);
    }

    /** A house with answers alone makes the copy /2; the answers follow rooms and precede brokerId, in key order. */
    @Test
    void answersAloneMakeItVersionTwoAndAreWrittenAfterRoomsInKeyOrder() {
        var withAnswers = house(FIRST, EXPORTED_AT, false);
        withAnswers.setRooms(roomsJson());
        withAnswers.setAnswers(answersJson());
        withAnswers.setBrokerId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var data = BackupMapper.toBackup(List.of(withAnswers), List.of(), List.of(), EXPORTED_AT);
        assertThat(data.format()).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(data.questions()).isEmpty();
        var written = JSON.readTree(JSON.writeValueAsString(data.houses().getFirst()));
        assertThat(new ArrayList<String>(written.propertyNames())).containsSubsequence("rooms", "answers", "brokerId");
        assertThat(new ArrayList<String>(written.get("answers").get(1).propertyNames()))
                .containsExactly("id", "questionId", "text", "answer", "status", "sort");
        assertThat(new ArrayList<String>(written.get("answers").get(0).propertyNames()))
                .containsExactly("id", "text", "status", "sort");
    }

    /** A tombstone's answers are never exported, and do not make the copy /2. */
    @Test
    void aDeletedHousesAnswersAreNotExported() {
        var gone = house(FIRST, EXPORTED_AT, true);
        gone.setAnswers(answersJson());
        assertThat(BackupMapper.toBackup(List.of(gone), List.of(), List.of(), EXPORTED_AT).format())
                .isEqualTo(BackupFormat.ID);
    }

    /** Questions by updatedAt then id, the payload in the format's order, archived only when true. */
    @Test
    void questionsAreOrderedByUpdatedAtThenIdAndArchivedIsOnlyWrittenWhenTrue() {
        var early = EXPORTED_AT.minusSeconds(60);
        var data = BackupMapper.toBackup(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(
                question("q_b", "{\"text\":\"Second\",\"category\":\"RULES\",\"appliesTo\":\"SALE\","
                        + "\"defaultOn\":true,\"sort\":2,\"archived\":false}", EXPORTED_AT, false),
                question("q_a", "{\"text\":\"First\",\"category\":\"MONEY\",\"appliesTo\":\"RENT\","
                        + "\"defaultOn\":false,\"sort\":1,\"archived\":true}", EXPORTED_AT, false),
                question("q_0", "{\"text\":\"Oldest\",\"category\":\"LEGAL\",\"appliesTo\":\"BOTH\","
                        + "\"defaultOn\":true,\"sort\":0}", early, false),
                question("q_x", "{\"text\":\"Deleted\"}", early, true)), JSON, EXPORTED_AT);

        assertThat(data.format()).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(data.questions()).extracting(BackupQuestion::id).containsExactly("q_0", "q_a", "q_b");
        assertThat(JSON.writeValueAsString(data.questions().get(1))).isEqualTo("{\"id\":\"q_a\",\"text\":\"First\","
                + "\"category\":\"MONEY\",\"appliesTo\":\"RENT\",\"defaultOn\":false,\"sort\":1,\"archived\":true,"
                + "\"updatedAt\":" + EXPORTED_AT.toEpochMilli() + "}");
        assertThat(JSON.writeValueAsString(data.questions().get(2))).doesNotContain("archived");
        assertThat(data.questions().getFirst().updatedAt()).isEqualTo(early.toEpochMilli());
    }

    /** The server never reads inside a record, so an export drops what would not import again instead of failing. */
    @Test
    void aQuestionPayloadThatWouldNotImportIsCoercedOrLeftOut() {
        var data = BackupMapper.toBackup(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(
                question("no-text", "{\"category\":\"MONEY\"}", EXPORTED_AT, false),
                question("blank", "{\"text\":\"  \"}", EXPORTED_AT, false),
                question("not-json", "not json", EXPORTED_AT, false),
                question("long", "{\"text\":\"" + "t".repeat(301) + "\"}", EXPORTED_AT, false),
                question("odd", "{\"text\":\"Odd\",\"category\":\"PETS\",\"appliesTo\":\"EVERYONE\","
                        + "\"defaultOn\":\"yes\",\"sort\":-4,\"archived\":\"true\"}", EXPORTED_AT, false)),
                JSON, EXPORTED_AT);

        assertThat(data.questions()).singleElement().satisfies(q -> {
            assertThat(q.id()).isEqualTo("odd");
            assertThat(q.category()).isEqualTo("OTHER");
            assertThat(q.appliesTo()).isEqualTo("BOTH");
            assertThat(q.defaultOn()).isFalse();
            assertThat(q.sort()).isZero();
            assertThat(q.archived()).isNull();
        });
    }

    private static Record viewing(String id, String payload, Instant updatedAt, boolean deleted) {
        var record = new Record(new RecordKey("viewing", id));
        record.setPayload(payload);
        record.setUpdatedAt(updatedAt);
        record.setDeleted(deleted);
        return record;
    }

    private static BackupData withViewings(Record... records) {
        return BackupMapper.toBackup(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(records), JSON, EXPORTED_AT);
    }

    /** Slice 3b-1: no viewing (or only a tombstone) is a /1 document without the key. */
    @Test
    void withoutViewingsTheCopyIsVersionOneAndHasNoViewingsKey() {
        var data = withViewings(viewing("v_gone", "{\"houseId\":\"h\",\"startsAt\":5}", EXPORTED_AT, true));
        assertThat(data.format()).isEqualTo(BackupFormat.ID);
        assertThat(data.viewings()).isEmpty();
        assertThat(JSON.writeValueAsString(data)).doesNotContain("viewings");
    }

    /** A live viewing alone makes the copy /2; ordered by updatedAt then id, payload in the format's order. */
    @Test
    void viewingsAreOrderedByUpdatedAtThenIdAndOptionalKeysOnlyWhenSet() {
        var early = EXPORTED_AT.minusSeconds(60);
        var data = withViewings(
                viewing("v_b", "{\"houseId\":\"h1\",\"startsAt\":2000,\"durationMin\":45,\"kind\":\"SECOND\","
                        + "\"status\":\"DONE\",\"remindMin\":30,\"huntReminder\":true,\"withWhom\":\"Ravi\","
                        + "\"notes\":\"Tape\",\"visitId\":\"vis\"}", EXPORTED_AT, false),
                viewing("v_a", "{\"houseId\":\"h1\",\"startsAt\":1000,\"durationMin\":30,\"kind\":\"FIRST\","
                        + "\"status\":\"PLANNED\",\"remindMin\":60,\"huntReminder\":false,\"withWhom\":\"\","
                        + "\"notes\":\"\"}", EXPORTED_AT, false),
                viewing("v_0", "{\"houseId\":\"h2\",\"startsAt\":500}", early, false));

        assertThat(data.format()).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(data.viewings()).extracting(BackupViewing::id).containsExactly("v_0", "v_a", "v_b");
        assertThat(JSON.writeValueAsString(data.viewings().get(2))).isEqualTo("{\"id\":\"v_b\",\"houseId\":\"h1\","
                + "\"startsAt\":2000,\"durationMin\":45,\"kind\":\"SECOND\",\"status\":\"DONE\",\"remindMin\":30,"
                + "\"huntReminder\":true,\"withWhom\":\"Ravi\",\"notes\":\"Tape\",\"visitId\":\"vis\","
                + "\"updatedAt\":" + EXPORTED_AT.toEpochMilli() + "}");
        assertThat(JSON.writeValueAsString(data.viewings().get(1))).isEqualTo("{\"id\":\"v_a\",\"houseId\":\"h1\","
                + "\"startsAt\":1000,\"durationMin\":30,\"kind\":\"FIRST\",\"status\":\"PLANNED\",\"remindMin\":60,"
                + "\"updatedAt\":" + EXPORTED_AT.toEpochMilli() + "}");
        // A bare payload reads with the defaults of the clients.
        assertThat(data.viewings().getFirst()).extracting(BackupViewing::durationMin, BackupViewing::kind,
                BackupViewing::status, BackupViewing::remindMin).containsExactly(30, "FIRST", "PLANNED", 60);
    }

    /** The server never reads inside a record, so an export drops what would not import again instead of failing. */
    @Test
    void aViewingPayloadThatWouldNotImportIsCoercedOrLeftOut() {
        var data = withViewings(
                viewing("no-house", "{\"startsAt\":5}", EXPORTED_AT, false),
                viewing("blank-house", "{\"houseId\":\" \",\"startsAt\":5}", EXPORTED_AT, false),
                viewing("no-start", "{\"houseId\":\"h\"}", EXPORTED_AT, false),
                viewing("zero-start", "{\"houseId\":\"h\",\"startsAt\":0}", EXPORTED_AT, false),
                viewing("text-start", "{\"houseId\":\"h\",\"startsAt\":\"5\"}", EXPORTED_AT, false),
                viewing("not-json", "not json", EXPORTED_AT, false),
                viewing("odd", "{\"houseId\":\"h\",\"startsAt\":9,\"durationMin\":9000,\"kind\":\"THIRD\","
                        + "\"status\":\"MISSED\",\"remindMin\":45,\"huntReminder\":\"yes\",\"withWhom\":\""
                        + "w".repeat(201) + "\",\"notes\":\"" + "n".repeat(2001) + "\",\"visitId\":7}",
                        EXPORTED_AT, false));

        assertThat(data.viewings()).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo("odd");
            assertThat(v.durationMin()).isEqualTo(30);
            assertThat(v.kind()).isEqualTo("FIRST");
            assertThat(v.status()).isEqualTo("PLANNED");
            assertThat(v.remindMin()).isEqualTo(60);
            assertThat(v.huntReminder()).isNull();
            assertThat(v.withWhom()).isNull();
            assertThat(v.notes()).isNull();
            assertThat(v.visitId()).isNull();
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
        // The sample holds brokers, so it is the /2 document; a copy without any is /1 (see the tests below).
        assertThat(sample).startsWith("{\"format\":\"" + BackupFormat.ID_WITH_BROKERS + "\"");
        assertThat(CanonicalSample.keysInOrder(sample)).startsWith("format", "exportedAt", "houses", "id", "label");
        assertThat(sample).doesNotContain("\"deleted\"").doesNotContain("\"syncVersion\"").doesNotContain("null");
    }

    /** Slice 2: criteria and preferences alone make the copy /2, and are ordered by updatedAt then key. */
    @Test
    void criteriaAndPreferencesMakeItVersionTwoAndAreOrderedByUpdatedAtThenKey() throws org.json.JSONException {
        var early = EXPORTED_AT.minusSeconds(60);
        var water = criterion("water", 3, true, 4, 0, EXPORTED_AT);
        var noise = criterion("noise", 0, false, 3, 5, early);
        var custom = criterion("c_1a2b3c4d", "Pets", 2, false, 3, 10, EXPORTED_AT);
        var pref = preference("score.ratingShare", "0.4", early);

        var data = BackupMapper.toBackup(List.of(house(FIRST, EXPORTED_AT, false)), List.of(), List.of(),
                List.of(), List.of(noise, water, custom), List.of(pref), JSON, EXPORTED_AT);

        assertThat(data.format()).isEqualTo(BackupFormat.ID_WITH_BROKERS);
        assertThat(data.criteria()).extracting(BackupCriterion::key).containsExactly("noise", "c_1a2b3c4d", "water");
        assertThat(data.preferences()).extracting(BackupPreference::key).containsExactly("score.ratingShare");
        // Noise is first (earlier updatedAt), then c_1a2b3c4d and water (same updatedAt, sorted by key)
        assertThat(data.criteria().get(0).updatedAt()).isEqualTo(early.toEpochMilli());
        assertThat(data.criteria().get(1).updatedAt()).isEqualTo(EXPORTED_AT.toEpochMilli());
        assertThat(data.criteria().get(2).updatedAt()).isEqualTo(EXPORTED_AT.toEpochMilli());
    }

    /** Built-in keys never export a label, even if the record holds one (coercion). */
    @Test
    void builtInKeysNeverExportLabel() {
        var withLabel = new Record(new RecordKey("criterion", "water"));
        withLabel.setPayload("{\"weight\":3,\"mustHave\":true,\"minScore\":4,\"sort\":0,\"label\":\"Water\"}");
        withLabel.setUpdatedAt(EXPORTED_AT);
        withLabel.setDeleted(false);

        var data = BackupMapper.toBackup(List.of(house(FIRST, EXPORTED_AT, false)), List.of(), List.of(),
                List.of(), List.of(withLabel), List.of(), JSON, EXPORTED_AT);

        var water = data.criteria().getFirst();
        assertThat(water.key()).isEqualTo("water");
        assertThat(water.label()).isNull();
    }

    /** No criteria or preferences: the copy is a /1 document with no criteria or preferences keys at all. */
    @Test
    void withoutCriteriaOrPreferencesTheCopyIsVersionOne() {
        var data = BackupMapper.toBackup(List.of(house(FIRST, EXPORTED_AT, false)), List.of(), List.of(),
                List.of(), List.of(), List.of(), JSON, EXPORTED_AT);
        assertThat(data.format()).isEqualTo(BackupFormat.ID);
        assertThat(data.criteria()).isEmpty();
        assertThat(data.preferences()).isEmpty();
        assertThat(JSON.writeValueAsString(data)).doesNotContain("criteria").doesNotContain("preferences");
    }

    private static Record criterion(String key, int weight, boolean mustHave, int minScore, int sort, Instant updatedAt) {
        var record = new Record(new RecordKey("criterion", key));
        record.setPayload("{\"weight\":" + weight + ",\"mustHave\":" + mustHave + ",\"minScore\":" + minScore + ",\"sort\":" + sort + "}");
        record.setUpdatedAt(updatedAt);
        record.setDeleted(false);
        return record;
    }

    private static Record criterion(String key, String label, int weight, boolean mustHave, int minScore, int sort, Instant updatedAt) throws org.json.JSONException {
        var record = criterion(key, weight, mustHave, minScore, sort, updatedAt);
        var payload = new JSONObject(record.getPayload());
        payload.put("label", label);
        record.setPayload(payload.toString());
        return record;
    }

    private static Record preference(String key, String value, Instant updatedAt) {
        var record = new Record(new RecordKey("preference", key));
        record.setPayload("{\"value\":\"" + value + "\"}");
        record.setUpdatedAt(updatedAt);
        record.setDeleted(false);
        return record;
    }
}
