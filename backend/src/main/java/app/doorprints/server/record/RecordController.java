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

import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.common.ConflictException;
import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.house.HouseChangedEvent;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.sync.Upsert;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * The one sync endpoint pair for every record kind (docs/11 section 5.30, ADR-28): same cursor rule and
 * last-write-wins as {@link app.doorprints.server.visit.VisitController}, no per-kind code, with one exception: a
 * viewing is part of its house's AI document, so a viewing that is written or deleted publishes a
 * {@code HouseChangedEvent} for its house, and for the house it named before (S4b-BL-92d).
 */
@RestController
@RequestMapping("/api/records")
public class RecordController {

    /** A payload larger than this, compact, is refused (400): records are small; photos and bytes go elsewhere. */
    public static final int MAX_PAYLOAD_BYTES = 65_536;
    /** Live rows per kind (409 beyond it): keeps the free database within its plan (NFR-028). */
    public static final long MAX_LIVE_ROWS_PER_TYPE = 5_000;
    private static final String EMPTY = "{}";

    private final RecordRepository repo;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final ObjectMapper json;
    private final ApplicationEventPublisher events;

    public RecordController(RecordRepository repo, SyncVersions versions, ClientClock clock, ObjectMapper json,
                            ApplicationEventPublisher events) {
        this.repo = repo;
        this.versions = versions;
        this.clock = clock;
        this.json = json;
        this.events = events;
    }

    /** The record type a house's AI document reads (S4b-BL-92d). */
    static final String VIEWING_TYPE = "viewing";

    /** The house a viewing payload names, or null (another type, no house, not a UUID). */
    private UUID viewingHouse(String type, String payload) {
        if (!VIEWING_TYPE.equals(type) || payload == null) return null;
        try {
            var houseId = json.readTree(payload).path("houseId");
            return houseId.isString() ? UUID.fromString(houseId.asString()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Re-indexes the houses a viewing named before and after a write; after the commit, as for a house. */
    private void viewingChanged(UUID before, UUID after) {
        if (before != null) events.publishEvent(new HouseChangedEvent(before));
        if (after != null && !after.equals(before)) events.publishEvent(new HouseChangedEvent(after));
    }

    /** Every record changed after {@code since} (all kinds, or one with {@code type}), tombstones included. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<RecordDto> list(@RequestParam @Min(0) long since,
                                @RequestParam(required = false) @Pattern(regexp = RecordDto.TYPE_PATTERN) String type) {
        var records = type == null ? repo.findBySyncVersionGreaterThanOrderBySyncVersion(since)
                : repo.findByKeyTypeAndSyncVersionGreaterThanOrderBySyncVersion(type, since);
        return records.stream().map(r -> RecordDto.from(r, json)).toList();
    }

    /**
     * Creates or updates one record (idempotent by type and id).
     * The sync lock is taken before the row is read, so the last-write-wins check and the write are one step; a
     * client stamp older than the stored one changes nothing and the stored record is returned; so does the same
     * stamp with the same content (a retried PUT takes no sync version, {@link Upsert}). A tombstone stores an
     * empty payload. A new live record is refused with 409 once its type holds {@link #MAX_LIVE_ROWS_PER_TYPE}. The
     * payload is stored as sent, compacted, without being interpreted.
     * @throws IllegalArgumentException if the path and body disagree, or the payload is not a JSON object within
     * {@link #MAX_PAYLOAD_BYTES}
     */
    @PutMapping("/{type}/{id}")
    @Transactional
    public RecordDto upsert(@PathVariable @Pattern(regexp = RecordDto.TYPE_PATTERN) String type,
                            @PathVariable @Pattern(regexp = RecordDto.ID_PATTERN) String id,
                            @Valid @RequestBody RecordDto dto) {
        if (!type.equals(dto.type()) || !id.equals(dto.id())) {
            throw new BadRequestException("type and id in the path and in the body must agree");
        }
        var payload = dto.deleted() ? EMPTY : compact(dto.payload());
        versions.lock(); // before reading: last-write-wins check and write are atomic (F-09)
        // Truncated to what timestamptz keeps (microseconds), so the answer to this PUT is the row every later read
        // returns, byte for byte: a client compares the two (SyncRules.pushShowsReset) and must never see a difference.
        var incomingUpdatedAt = clock.accept(dto.updatedAt(), "updatedAt").truncatedTo(ChronoUnit.MICROS);
        var key = new RecordKey(type, id);
        var stored = repo.findById(key).orElse(null);
        var decision = Upsert.decide(stored == null ? null : stored.getUpdatedAt(), incomingUpdatedAt,
                () -> stored.isDeleted() == dto.deleted() && stored.getPayload().equals(payload));
        if (decision == Upsert.Decision.KEEP_STORED || decision == Upsert.Decision.UNCHANGED) {
            return RecordDto.from(stored, json);
        }
        var record = stored == null ? new Record(key) : stored;
        var becomesLive = !dto.deleted() && (record.getUpdatedAt() == null || record.isDeleted());
        if (becomesLive && repo.countByKeyTypeAndDeletedFalse(type) >= MAX_LIVE_ROWS_PER_TYPE) {
            throw new ConflictException("Record limit reached: at most " + MAX_LIVE_ROWS_PER_TYPE + " " + type
                    + " records");
        }
        var houseBefore = record.isDeleted() ? null : viewingHouse(type, record.getPayload());
        record.setPayload(payload);
        record.setDeleted(dto.deleted());
        record.setUpdatedAt(incomingUpdatedAt);
        record.setSyncVersion(versions.next());
        var saved = repo.save(record);
        viewingChanged(houseBefore, dto.deleted() ? null : viewingHouse(type, payload));
        return RecordDto.from(saved, json);
    }

    /**
     * Turns a record into a tombstone (empty payload, new sync version) so other devices learn of the deletion;
     * deleting twice is fine.
     * @throws NotFoundException if the record never existed
     */
    @DeleteMapping("/{type}/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable @Pattern(regexp = RecordDto.TYPE_PATTERN) String type,
                                       @PathVariable @Pattern(regexp = RecordDto.ID_PATTERN) String id) {
        versions.lock();
        var record = repo.findById(new RecordKey(type, id))
                .orElseThrow(() -> new NotFoundException("Record " + type + "/" + id + " not found"));
        if (!record.isDeleted()) {
            viewingChanged(viewingHouse(type, record.getPayload()), null);
            record.setDeleted(true);
            record.setPayload(EMPTY);
            record.setUpdatedAt(clock.now());
            record.setSyncVersion(versions.next());
        }
        return ResponseEntity.noContent().build();
    }

    /** The payload as stored: a JSON object, compact, within the size cap; anything else is a 400. */
    private static String compact(JsonNode payload) {
        if (payload == null || !payload.isObject()) throw new BadRequestException("payload must be a JSON object");
        var text = payload.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            throw new BadRequestException("payload too large (max " + MAX_PAYLOAD_BYTES + " bytes)");
        }
        return text;
    }
}
