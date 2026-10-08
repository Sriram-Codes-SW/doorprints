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

package app.doorprints.server.visit;

import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.house.HouseChangedEvent;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.sync.Upsert;
import jakarta.validation.Valid;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Sync endpoints for visits, the record of when the person was at a place (and usually which house). Apps push each
 * visit with PUT and pull changes with a version cursor; conflicts are settled by last write wins on {@code
 * updatedAt}, as for houses.
 */
@RestController
@RequestMapping("/api/visits")
public class VisitController {

    private final VisitRepository repo;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final ApplicationEventPublisher events;

    public VisitController(VisitRepository repo, SyncVersions versions, ClientClock clock,
                           ApplicationEventPublisher events) {
        this.repo = repo;
        this.versions = versions;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Visits for the apps. With {@code since} it returns everything changed after that sync version, tombstones
     * included (the sync cursor); otherwise the live visits, optionally for one house, newest first.
     */
    @GetMapping
    @Transactional(readOnly = true)
    public List<VisitDto> list(@RequestParam(required = false) Long since,
                               @RequestParam(required = false) UUID houseId) {
        List<Visit> visits;
        if (since != null) visits = repo.findBySyncVersionGreaterThanOrderBySyncVersion(since);
        else if (houseId != null) visits = repo.findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(houseId);
        else visits = repo.findByDeletedFalseOrderByArrivedAtDesc();
        return visits.stream().map(VisitDto::from).toList();
    }

    /**
     * Creates or updates a visit from an app (idempotent by id).
     * The sync lock is taken before the row is read, so the last-write-wins check and the write are one step. An
     * older {@code updatedAt} than the stored one changes nothing and the stored visit is returned; so does the same
     * one with the same content (a retried PUT takes no sync version, {@link Upsert}). A deleted visit
     * has its place wiped. The house the visit now belongs to, and the one it left, are announced so their search
     * entries are rebuilt.
     * @throws IllegalArgumentException if {@code leftAt} is before {@code arrivedAt}, or a time is outside the
     * accepted range
     */
    @PutMapping("/{id}")
    @Transactional
    public VisitDto upsert(@PathVariable UUID id, @Valid @RequestBody VisitDto dto) {
        versions.lock(); // before reading: last-write-wins check and write are atomic (F-09)
        var incomingUpdatedAt = clock.accept(dto.updatedAt(), "updatedAt").truncatedTo(ChronoUnit.MICROS);
        clock.validate(dto.arrivedAt(), "arrivedAt");
        clock.validate(dto.leftAt(), "leftAt");
        if (dto.leftAt() != null && dto.leftAt().isBefore(dto.arrivedAt())) {
            throw new BadRequestException("leftAt must not be before arrivedAt");
        }
        var stored = repo.findById(id).orElse(null);
        var decision = Upsert.decide(stored == null ? null : stored.getUpdatedAt(), incomingUpdatedAt,
                () -> sameContent(stored, dto));
        if (decision == Upsert.Decision.KEEP_STORED || decision == Upsert.Decision.UNCHANGED) {
            return VisitDto.from(stored);
        }
        var visit = stored == null ? new Visit(id) : stored;
        var previousHouseId = visit.getHouseId();
        visit.setHouseId(dto.houseId());
        visit.setLat(dto.lat());
        visit.setLon(dto.lon());
        visit.setStreet(dto.street());
        visit.setArrivedAt(dto.arrivedAt());
        visit.setLeftAt(dto.leftAt());
        visit.setSource(dto.source() == null ? VisitSource.MANUAL : dto.source());
        visit.setDeleted(dto.deleted());
        if (dto.deleted()) visit.purgePlace();
        visit.setUpdatedAt(incomingUpdatedAt);
        visit.setSyncVersion(versions.next());
        var saved = repo.save(visit);
        // The house's AI index includes a visit summary, so tell it which houses changed.
        if (dto.houseId() != null) events.publishEvent(new HouseChangedEvent(dto.houseId()));
        if (previousHouseId != null && !previousHouseId.equals(dto.houseId())) {
            events.publishEvent(new HouseChangedEvent(previousHouseId));
        }
        return VisitDto.from(saved);
    }

    /**
     * True when the pushed visit would change nothing that is stored (the tie rule, {@link Upsert}); two tombstones
     * are the same, because a delete keeps no place.
     */
    private static boolean sameContent(Visit stored, VisitDto dto) {
        if (dto.deleted() && stored.isDeleted()) return true;
        return dto.deleted() == stored.isDeleted()
                && Objects.equals(dto.houseId(), stored.getHouseId())
                && dto.lat() == stored.getLat()
                && dto.lon() == stored.getLon()
                && Objects.equals(dto.street(), stored.getStreet())
                && Objects.equals(dto.arrivedAt(), stored.getArrivedAt())
                && Objects.equals(dto.leftAt(), stored.getLeftAt())
                && (dto.source() == null ? VisitSource.MANUAL : dto.source()) == stored.getSource();
    }

    /**
     * Soft-deletes a visit: it stays as a tombstone so other devices learn of the deletion, but where the person was
     * is wiped.
     * @throws NotFoundException if the visit never existed
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        versions.lock();
        var visit = repo.findById(id).orElseThrow(() -> new NotFoundException("Visit " + id + " not found"));
        if (!visit.isDeleted()) {
            visit.setDeleted(true);
            visit.purgePlace();
            visit.setUpdatedAt(clock.now());
            visit.setSyncVersion(versions.next());
            if (visit.getHouseId() != null) events.publishEvent(new HouseChangedEvent(visit.getHouseId()));
        }
        return ResponseEntity.noContent().build();
    }
}
