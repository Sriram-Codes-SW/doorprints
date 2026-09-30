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
import app.doorprints.server.house.HouseAnswer;
import app.doorprints.server.house.HouseCost;
import app.doorprints.server.house.HouseRoom;
import app.doorprints.server.photo.PhotoDto;
import app.doorprints.server.record.Record;
import app.doorprints.server.visit.Visit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds {@link BackupData} from the server's rows in the format's fixed order (docs/schemas/README.md).
 *
 * <p>Ordering — the agreed rule of docs/schemas/README.md section 5, so that a copy made on a phone, in a browser and
 * on the server lists the same data in the same order. All three writers follow it: the web writer flat-maps over
 * its houses, and the Android writer ({@code BackupData.of} in {@code :shared}) groups the same way since ticket
 * S4-00/a:
 * <ul>
 *   <li>houses by {@code createdAt}, then {@code id} (as text, like the clients' code-unit comparison);</li>
 *   <li>visits grouped by house in that house order, each group by {@code arrivedAt} then {@code id};</li>
 *   <li>photos grouped the same way, each group by {@code createdAt} then {@code id};</li>
 *   <li>rows whose house is not in the export (a visit unlinked by a house delete) come last, in the same order.
 *       The device writers walk visits through their house and so have nothing to put here.</li>
 *   <li>checklist keys alphabetically ({@link TreeMap});</li>
 *   <li>brokers by {@code updatedAt}, then {@code id}; the format id is {@code /2} only when the copy has a broker, a
 *       room, a criterion, a preference, a question or a house with answers.</li>
 *   <li>questions by {@code updatedAt}, then {@code id}; viewings the same, and a live viewing makes the copy {@code /2}.</li>
 * </ul>
 *
 * <p>Tombstones are never exported; callers pass live rows only.
 */
final class BackupMapper {

    // The lambdas are explicitly typed: with an implicit one, thenComparing(Function) and
    // thenComparing(Comparator) are both applicable and javac calls the reference ambiguous.
    private static final Comparator<House> HOUSE_ORDER =
            Comparator.comparing(House::getCreatedAt).thenComparing((House h) -> h.getId().toString());
    private static final Comparator<Visit> VISIT_ORDER =
            Comparator.comparing(Visit::getArrivedAt).thenComparing((Visit v) -> v.getId().toString());
    private static final Comparator<PhotoDto> PHOTO_ORDER =
            Comparator.comparing(PhotoDto::createdAt).thenComparing((PhotoDto p) -> p.id().toString());

    private BackupMapper() {
    }

    private static final Comparator<BackupBroker> BROKER_ORDER =
            Comparator.comparing(BackupBroker::updatedAt).thenComparing(BackupBroker::id);
    private static final Comparator<BackupCriterion> CRITERION_ORDER =
            Comparator.comparing(BackupCriterion::updatedAt).thenComparing(BackupCriterion::key);
    private static final Comparator<BackupPreference> PREFERENCE_ORDER =
            Comparator.comparing(BackupPreference::updatedAt).thenComparing(BackupPreference::key);

    private static final Comparator<BackupQuestion> QUESTION_ORDER =
            Comparator.comparing(BackupQuestion::updatedAt).thenComparing(BackupQuestion::id);

    private static final Comparator<BackupViewing> VIEWING_ORDER =
            Comparator.comparing(BackupViewing::updatedAt).thenComparing(BackupViewing::id);

    private static final Comparator<BackupArea> AREA_ORDER =
            Comparator.comparing(BackupArea::updatedAt).thenComparing(BackupArea::id);

    private static final Comparator<BackupPlace> PLACE_ORDER =
            Comparator.comparing(BackupPlace::updatedAt).thenComparing(BackupPlace::id);

    private static final Comparator<BackupAreaNote> AREA_NOTE_ORDER =
            Comparator.comparing(BackupAreaNote::updatedAt).thenComparing(BackupAreaNote::id);

    static BackupData toBackup(List<House> houses, List<Visit> visits, List<PhotoDto> photos, Instant exportedAt) {
        return toBackup(houses, visits, photos, List.of(), List.of(), List.of(), null, exportedAt);
    }

    /** {@code brokerRecords}, {@code criterionRecords}, and {@code preferenceRecords} are the live rows of their respective types; {@code json} reads their payloads. */
    static BackupData toBackup(List<House> houses, List<Visit> visits, List<PhotoDto> photos,
                               List<Record> brokerRecords, List<Record> criterionRecords, List<Record> preferenceRecords,
                               ObjectMapper json, Instant exportedAt) {
        return toBackup(houses, visits, photos, brokerRecords, criterionRecords, preferenceRecords, List.of(), json,
                exportedAt);
    }

    /** As above, with the live {@code question} records (slice 3a). */
    static BackupData toBackup(List<House> houses, List<Visit> visits, List<PhotoDto> photos,
                               List<Record> brokerRecords, List<Record> criterionRecords, List<Record> preferenceRecords,
                               List<Record> questionRecords, ObjectMapper json, Instant exportedAt) {
        return toBackup(houses, visits, photos, brokerRecords, criterionRecords, preferenceRecords, questionRecords,
                List.of(), json, exportedAt);
    }

    /** As above, with the live {@code viewing} records (slice 3b-1). */
    static BackupData toBackup(List<House> houses, List<Visit> visits, List<PhotoDto> photos,
                               List<Record> brokerRecords, List<Record> criterionRecords, List<Record> preferenceRecords,
                               List<Record> questionRecords, List<Record> viewingRecords, ObjectMapper json,
                               Instant exportedAt) {
        return toBackup(houses, visits, photos, brokerRecords, criterionRecords, preferenceRecords, questionRecords,
                viewingRecords, List.of(), List.of(), List.of(), json, exportedAt);
    }

    /** As above, with the live {@code area}, {@code place} and {@code areanote} records (slice 4a). */
    static BackupData toBackup(List<House> houses, List<Visit> visits, List<PhotoDto> photos,
                               List<Record> brokerRecords, List<Record> criterionRecords, List<Record> preferenceRecords,
                               List<Record> questionRecords, List<Record> viewingRecords, List<Record> areaRecords,
                               List<Record> placeRecords, List<Record> areaNoteRecords, ObjectMapper json,
                               Instant exportedAt) {
        var liveHouses = houses.stream().filter(h -> !h.isDeleted()).sorted(HOUSE_ORDER).toList();
        var houseOrder = new LinkedHashSet<UUID>();
        for (var house : liveHouses) houseOrder.add(house.getId());

        var liveVisits = visits.stream().filter(v -> !v.isDeleted()).toList();
        var livePhotos = photos.stream().filter(p -> !p.deleted()).toList();

        var brokers = brokerRecords.stream().filter(r -> !r.isDeleted()).map(r -> broker(r, json))
                .filter(java.util.Objects::nonNull).sorted(BROKER_ORDER).toList();

        var criteria = criterionRecords.stream().filter(r -> !r.isDeleted()).map(r -> criterion(r, json))
                .filter(java.util.Objects::nonNull).sorted(CRITERION_ORDER).toList();

        var preferences = preferenceRecords.stream().filter(r -> !r.isDeleted()).map(r -> preference(r, json))
                .filter(java.util.Objects::nonNull).sorted(PREFERENCE_ORDER).toList();

        var questions = questionRecords.stream().filter(r -> !r.isDeleted()).map(r -> question(r, json))
                .filter(java.util.Objects::nonNull).sorted(QUESTION_ORDER).toList();

        var viewings = viewingRecords.stream().filter(r -> !r.isDeleted()).map(r -> viewing(r, json))
                .filter(java.util.Objects::nonNull).sorted(VIEWING_ORDER).toList();

        var areas = areaRecords.stream().filter(r -> !r.isDeleted()).map(r -> area(r, json))
                .filter(java.util.Objects::nonNull).sorted(AREA_ORDER).toList();
        var places = placeRecords.stream().filter(r -> !r.isDeleted()).map(r -> place(r, json))
                .filter(java.util.Objects::nonNull).sorted(PLACE_ORDER).toList();
        var areaNotes = areaNoteRecords.stream().filter(r -> !r.isDeleted()).map(r -> areaNote(r, json))
                .filter(java.util.Objects::nonNull).sorted(AREA_NOTE_ORDER).toList();

        var backupHouses = liveHouses.stream().map(BackupMapper::house).toList();
        // The lowest number that holds the copy: /2 once there is a broker, a room, a criterion, a preference, a question, a viewing, or a house with answers; else /1.
        var needsV2 = !brokers.isEmpty() || backupHouses.stream().anyMatch(h -> h.rooms() != null)
                || !criteria.isEmpty() || !preferences.isEmpty() || !questions.isEmpty() || !viewings.isEmpty()
                || !areas.isEmpty() || !places.isEmpty() || !areaNotes.isEmpty()
                || backupHouses.stream().anyMatch(h -> h.answers() != null);

        return new BackupData(
                needsV2 ? BackupFormat.ID_WITH_BROKERS : BackupFormat.ID,
                exportedAt.toEpochMilli(),
                backupHouses,
                groupByHouse(liveVisits, Visit::getHouseId, VISIT_ORDER, houseOrder).stream()
                        .map(BackupMapper::visit).toList(),
                groupByHouse(livePhotos, PhotoDto::houseId, PHOTO_ORDER, houseOrder).stream()
                        .map(BackupMapper::photo).toList(),
                brokers,
                criteria,
                preferences,
                questions,
                viewings,
                areas,
                places,
                areaNotes);
    }

    /**
     * A broker record as a backup row. The server never reads inside a record, so a client may have stored anything:
     * a payload without a usable name is left out, and a field of the wrong type or outside its limit is written as
     * unknown, so that the export always imports again.
     */
    private static BackupBroker broker(Record r, ObjectMapper json) {
        JsonNode p;
        try {
            p = json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
        var name = text(p, "name", BackupBroker.MAX_NAME);
        if (name == null || name.isBlank()) return null;
        var rating = p.path("rating");
        return new BackupBroker(r.getKey().id(), name, text(p, "phone", BackupBroker.MAX_PHONE),
                text(p, "agency", BackupBroker.MAX_AGENCY), text(p, "feeTerms", BackupBroker.MAX_FEE_TERMS),
                text(p, "notes", BackupBroker.MAX_NOTES),
                rating.isInt() && rating.asInt() >= 1 && rating.asInt() <= 5 ? rating.asInt() : null,
                r.getUpdatedAt().toEpochMilli());
    }

    /**
     * A criterion record as a backup row. The server never reads inside a record, so a client may have stored anything:
     * a field of the wrong type or outside its limit is dropped, so the export always imports again.
     */
    private static BackupCriterion criterion(Record r, ObjectMapper json) {
        JsonNode p;
        try {
            p = json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
        var weight = p.path("weight");
        var mustHave = p.path("mustHave");
        var minScore = p.path("minScore");
        var sort = p.path("sort");
        var archived = p.path("archived");

        if (!weight.isInt() || weight.asInt() < BackupCriterion.MIN_WEIGHT || weight.asInt() > BackupCriterion.MAX_WEIGHT) {
            return null;
        }
        if (!mustHave.isBoolean()) {
            return null;
        }
        if (!minScore.isInt() || minScore.asInt() < BackupCriterion.MIN_SCORE || minScore.asInt() > BackupCriterion.MAX_SCORE) {
            return null;
        }

        var builtInKeys = Set.of("water", "power", "parking", "sunlight", "ventilation", "noise", "security",
                "maintenance", "neighbourhood", "commute");
        var key = r.getKey().id();
        var label = builtInKeys.contains(key) ? null : text(p, "label", BackupCriterion.MAX_LABEL);

        return new BackupCriterion(
                key,
                label,
                weight.asInt(),
                mustHave.asBoolean(),
                minScore.asInt(),
                sort.isInt() && sort.asInt() >= 0 ? sort.asInt() : null,
                archived.isBoolean() && archived.asBoolean() ? true : null,
                r.getUpdatedAt().toEpochMilli()
        );
    }

    /**
     * A preference record as a backup row. The server never reads inside a record, so a client may have stored anything:
     * a payload without a usable value is left out, so the export always imports again.
     */
    private static BackupPreference preference(Record r, ObjectMapper json) {
        JsonNode p;
        try {
            p = json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
        var value = text(p, "value", BackupPreference.MAX_VALUE);
        if (value == null) return null;
        return new BackupPreference(r.getKey().id(), value, r.getUpdatedAt().toEpochMilli());
    }

    /**
     * A question record as a backup row. The server never reads inside a record, so a client may have stored anything:
     * a payload without a usable text (1..300 characters, not blank) is left out, and a field of the wrong type or
     * outside its range is read as the clients read it (unknown category as OTHER, unknown scope as BOTH, a bad
     * sort as 0, a non-boolean switch as off), so that the export always imports again.
     */
    private static BackupQuestion question(Record r, ObjectMapper json) {
        JsonNode p;
        try {
            p = json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
        var text = text(p, "text", BackupQuestion.MAX_TEXT);
        if (text == null || text.isBlank()) return null;
        var category = p.path("category");
        var appliesTo = p.path("appliesTo");
        var defaultOn = p.path("defaultOn");
        var sort = p.path("sort");
        var archived = p.path("archived");
        return new BackupQuestion(r.getKey().id(), text,
                category.isString() && BackupQuestion.CATEGORIES.contains(category.asString()) ? category.asString() : "OTHER",
                appliesTo.isString() && BackupQuestion.SCOPES.contains(appliesTo.asString()) ? appliesTo.asString() : "BOTH",
                defaultOn.isBoolean() && defaultOn.asBoolean(),
                sort.isInt() && sort.asInt() >= 0 ? sort.asInt() : 0,
                archived.isBoolean() && archived.asBoolean() ? true : null,
                r.getUpdatedAt().toEpochMilli());
    }

    /**
     * A viewing record as a backup row. The server never reads inside a record, so a client may have stored anything:
     * a payload without a usable house or start time is left out, and a field of the wrong type or outside its range
     * is read as the clients read it (duration 30, kind FIRST, status PLANNED, reminder 60; a text past its limit is
     * dropped), so that the export always imports again.
     */
    private static BackupViewing viewing(Record r, ObjectMapper json) {
        JsonNode p;
        try {
            p = json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
        var houseId = text(p, "houseId", BackupViewing.MAX_REF);
        var startsAt = p.path("startsAt");
        if (houseId == null || houseId.isBlank() || !startsAt.isIntegralNumber() || startsAt.asLong() <= 0) return null;
        var duration = p.path("durationMin");
        var kind = p.path("kind");
        var status = p.path("status");
        var remind = p.path("remindMin");
        var hunt = p.path("huntReminder");
        return new BackupViewing(r.getKey().id(), houseId, startsAt.asLong(),
                duration.isInt() && duration.asInt() >= BackupViewing.MIN_DURATION
                        && duration.asInt() <= BackupViewing.MAX_DURATION ? duration.asInt() : BackupViewing.DEFAULT_DURATION,
                kind.isString() && BackupViewing.KINDS.contains(kind.asString()) ? kind.asString() : "FIRST",
                status.isString() && BackupViewing.STATUSES.contains(status.asString()) ? status.asString() : "PLANNED",
                remind.isInt() && BackupViewing.REMINDERS.contains(remind.asInt()) ? remind.asInt() : 60,
                hunt.isBoolean() && hunt.asBoolean() ? true : null,
                text(p, "withWhom", BackupViewing.MAX_WITH_WHOM), text(p, "notes", BackupViewing.MAX_NOTES),
                text(p, "visitId", BackupViewing.MAX_REF), r.getUpdatedAt().toEpochMilli());
    }

    /**
     * An area record as a backup row (slice 4a). A payload without a usable name or point is left out; a radius outside
     * 200..2000 reads as 500; {@code enabled} is written only when false. The export always imports again.
     */
    private static BackupArea area(Record r, ObjectMapper json) {
        JsonNode p = payload(r, json);
        if (p == null) return null;
        var name = name(p, BackupArea.MAX_NAME);
        var lat = coordinate(p, "lat", 90);
        var lon = coordinate(p, "lon", 180);
        if (name == null || lat == null || lon == null) return null;
        var radius = p.path("radiusM");
        var enabled = p.path("enabled");
        return new BackupArea(r.getKey().id(), name, lat, lon,
                radius.isInt() && radius.asInt() >= BackupArea.MIN_RADIUS && radius.asInt() <= BackupArea.MAX_RADIUS
                        ? radius.asInt() : BackupArea.DEFAULT_RADIUS,
                enabled.isBoolean() && !enabled.asBoolean() ? false : null, r.getUpdatedAt().toEpochMilli());
    }

    private static BackupPlace place(Record r, ObjectMapper json) {
        JsonNode p = payload(r, json);
        if (p == null) return null;
        var name = name(p, BackupPlace.MAX_NAME);
        var lat = coordinate(p, "lat", 90);
        var lon = coordinate(p, "lon", 180);
        if (name == null || lat == null || lon == null) return null;
        return new BackupPlace(r.getKey().id(), name, lat, lon, r.getUpdatedAt().toEpochMilli());
    }

    /** A note with neither or both targets, or a blank or too long text, is left out (the file would be refused). */
    private static BackupAreaNote areaNote(Record r, ObjectMapper json) {
        JsonNode p = payload(r, json);
        if (p == null) return null;
        var areaId = name(p, "areaId", BackupAreaNote.MAX_REF);
        var street = name(p, "street", BackupAreaNote.MAX_STREET);
        var text = name(p, "text", BackupAreaNote.MAX_TEXT);
        var hasArea = p.has("areaId");
        var hasStreet = p.has("street");
        if (text == null || hasArea == hasStreet || (hasArea && areaId == null) || (hasStreet && street == null)) {
            return null;
        }
        return new BackupAreaNote(r.getKey().id(), areaId, street, text, r.getUpdatedAt().toEpochMilli());
    }

    private static JsonNode payload(Record r, ObjectMapper json) {
        try {
            return json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String name(JsonNode payload, int max) {
        return name(payload, "name", max);
    }

    /** A non-blank string within {@code max}, else null. */
    private static String name(JsonNode payload, String key, int max) {
        var value = text(payload, key, max);
        return value == null || value.isBlank() ? null : value;
    }

    private static Double coordinate(JsonNode payload, String key, double limit) {
        var node = payload.path(key);
        if (!node.isNumber()) return null;
        var value = node.asDouble();
        return Double.isFinite(value) && Math.abs(value) <= limit ? value : null;
    }

    private static String text(JsonNode payload, String key, int max) {
        var node = payload.path(key);
        return node.isString() && node.asString().length() <= max && !node.asString().isEmpty() ? node.asString() : null;
    }

    /**
     * Rows grouped by their house in {@code houseOrder}, each group sorted by {@code within}; rows with no house or
     * with a house that is not being exported are appended at the end in the same sort order.
     */
    private static <T> List<T> groupByHouse(List<T> rows, Function<T, UUID> houseIdOf, Comparator<T> within,
                                            Set<UUID> houseOrder) {
        var sorted = new ArrayList<>(rows);
        sorted.sort(within);
        var byHouse = new HashMap<UUID, List<T>>();
        var orphans = new ArrayList<T>();
        for (var row : sorted) {
            var houseId = houseIdOf.apply(row);
            if (houseId != null && houseOrder.contains(houseId)) {
                byHouse.computeIfAbsent(houseId, key -> new ArrayList<>()).add(row);
            } else {
                orphans.add(row);
            }
        }
        var out = new ArrayList<T>(sorted.size());
        for (var houseId : houseOrder) {
            var group = byHouse.get(houseId);
            if (group != null) out.addAll(group);
        }
        out.addAll(orphans);
        return out;
    }

    private static BackupHouse house(House h) {
        return new BackupHouse(h.getId(), h.getLabel(), h.getAddress(), h.getStreet(), h.getLocality(),
                h.getLat(), h.getLon(), h.getStatus(), h.getPrice(), h.getPriceType(), h.getBedrooms(),
                h.getRating(), h.getContactName(), h.getContactPhone(), h.getListingUrl(), h.getNotes(),
                h.getAreaSqft(), h.getLocationSource(), HouseCost.parse(h.getCost()), HouseRoom.parse(h.getRooms()),
                HouseAnswer.parse(h.getAnswers()), h.getBrokerId(),
                sortedChecklist(h.getChecklist()), millis(h.getCreatedAt()), millis(h.getUpdatedAt()));
    }

    private static BackupVisit visit(Visit v) {
        return new BackupVisit(v.getId(), v.getHouseId(), v.getLat(), v.getLon(), v.getStreet(),
                millis(v.getArrivedAt()), millis(v.getLeftAt()), v.getSource(), millis(v.getUpdatedAt()));
    }

    private static BackupPhoto photo(PhotoDto p) {
        return new BackupPhoto(p.id(), p.houseId(), BackupFormat.photoFileName(p.id()), millis(p.createdAt()));
    }

    /** Checklist scores with the keys in alphabetical order, so the JSON is byte-stable. */
    private static Map<String, Integer> sortedChecklist(Map<String, Integer> checklist) {
        return checklist == null || checklist.isEmpty() ? Map.of() : new TreeMap<>(checklist);
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
