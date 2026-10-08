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

package app.doorprints.server.house;

import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.sync.Upsert;
import app.doorprints.server.visit.VisitRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The house rules shared by the HTTP API and the AI tools: listing, the last-write-wins upsert, deletion that purges
 * private content, and the nearby search.
 */
@Service
public class HouseService {

    private final HouseRepository repo;
    private final VisitRepository visits;
    private final PhotoRepository photos;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final ApplicationEventPublisher events;

    public HouseService(HouseRepository repo, VisitRepository visits, PhotoRepository photos, SyncVersions versions,
                        ClientClock clock, ApplicationEventPublisher events) {
        this.repo = repo;
        this.visits = visits;
        this.photos = photos;
        this.versions = versions;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Live houses, newest edit first; with a sync version, every house changed after it, deletions included.
     */
    @Transactional(readOnly = true)
    public List<HouseDto> list(Long since) {
        var houses = since == null
                ? repo.findByDeletedFalseOrderByUpdatedAtDesc()
                : repo.findBySyncVersionGreaterThanOrderBySyncVersion(since);
        return houses.stream().map(HouseDto::from).toList();
    }

    /**
     * One house.
     * @throws NotFoundException if it does not exist
     */
    @Transactional(readOnly = true)
    public HouseDto get(UUID id) {
        return HouseDto.from(find(id));
    }

    /**
     * Create or update. Conflicts are resolved "last write wins" on the client's updatedAt (clamped by
     * {@link ClientClock}): an older edit arriving late (e.g. from an offline phone) doesn't overwrite a newer one,
     * and the same stamp with the same content (a retried PUT) writes nothing and takes no sync version
     * ({@link Upsert}, docs/03 section 10.1).
     * A body with {@code deleted: true} is a delete (the Android app deletes this way) and purges the content.
     */
    @Transactional
    public HouseDto upsert(UUID id, HouseDto dto) {
        versions.lock(); // before reading, so the last-write-wins check and the write are atomic
        var incomingUpdatedAt = clock.accept(dto.updatedAt(), "updatedAt");
        var stored = repo.findById(id).orElse(null);
        var decision = Upsert.decide(stored == null ? null : stored.getUpdatedAt(), incomingUpdatedAt,
                () -> sameContent(stored, dto));
        if (decision == Upsert.Decision.KEEP_STORED || decision == Upsert.Decision.UNCHANGED) {
            return HouseDto.from(stored);
        }
        boolean created = decision == Upsert.Decision.CREATE;
        var house = stored;
        if (created) {
            house = new House(id);
            house.setCreatedAt(clock.accept(dto.createdAt(), "createdAt"));
        }
        boolean wasDeleted = !created && house.isDeleted();
        dto.applyTo(house);
        house.setUpdatedAt(incomingUpdatedAt);
        long version = versions.next();
        house.setSyncVersion(version);
        if (house.isDeleted() && !wasDeleted) purge(house, version);
        var saved = repo.save(house);
        events.publishEvent(new HouseChangedEvent(id));
        return HouseDto.from(saved);
    }

    /**
     * True when applying {@code dto} to {@code stored} would change nothing a client can see (the tie rule,
     * {@link Upsert}): the dto is applied to a scratch house that carries the stored row's server-managed fields, and
     * the two are compared as the DTOs a client would get. Two tombstones are the same whatever the dto still carries,
     * because a delete keeps no content.
     */
    private static boolean sameContent(House stored, HouseDto dto) {
        if (dto.deleted() && stored.isDeleted()) return true;
        var scratch = new House(stored.getId());
        scratch.setCreatedAt(stored.getCreatedAt());
        scratch.setUpdatedAt(stored.getUpdatedAt());
        scratch.setSyncVersion(stored.getSyncVersion());
        dto.applyTo(scratch);
        return HouseDto.from(scratch).equals(HouseDto.from(stored));
    }

    /**
     * Deletes a house: its content is blanked, its photos are tombstoned and its visits unlinked, and the search
     * index is told. Deleting twice is fine.
     * @throws NotFoundException if it never existed
     */
    @Transactional
    public void delete(UUID id) {
        versions.lock();
        var house = find(id);
        if (house.isDeleted()) return;
        house.setDeleted(true);
        house.setUpdatedAt(clock.now());
        long version = versions.next();
        house.setSyncVersion(version);
        purge(house, version);
        events.publishEvent(new HouseChangedEvent(id));
    }

    /** Tombstone content purge (F-16): blank the row, tombstone its photos, unlink its visits. */
    private void purge(House house, long version) {
        var now = clock.now();
        house.purgeContent();
        photos.tombstoneAllOfHouse(house.getId(), now, version);
        visits.unlinkHouse(house.getId(), now, version);
    }

    /**
     * Live houses within the radius in metres of a point, nearest first (at most 50), each with its distance.
     */
    @Transactional(readOnly = true)
    public List<HouseDto> nearby(double lat, double lon, double radius) {
        var rows = repo.findNearby(lat, lon, radius);
        var ids = rows.stream().map(r -> UUID.fromString((String) r[0])).toList();
        Map<UUID, House> byId = repo.findAllById(ids).stream()
                .collect(Collectors.toMap(House::getId, Function.identity()));
        return rows.stream()
                .map(r -> HouseDto.from(byId.get(UUID.fromString((String) r[0])), ((Number) r[1]).doubleValue()))
                .toList();
    }

    /**
     * Live houses on a street, matched ignoring case.
     */
    @Transactional(readOnly = true)
    public List<HouseDto> onStreet(String street) {
        return repo.findLiveOnStreet(street.trim()).stream().map(HouseDto::from).toList();
    }

    /**
     * The house row, deleted or not.
     * @throws NotFoundException if there is none
     */
    House find(UUID id) {
        return repo.findById(id).orElseThrow(() -> new NotFoundException("House " + id + " not found"));
    }
}
