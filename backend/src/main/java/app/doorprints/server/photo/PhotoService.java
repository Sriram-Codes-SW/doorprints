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

import app.doorprints.server.common.ConflictException;
import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.config.AppProperties;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.sync.ClientClock;
import app.doorprints.server.sync.SyncVersions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Photo storage rules: per-house limit, retry-safe upload, tombstoned delete, and last-write-wins edits of the
 * person's room, tags and caption. Every write takes the sync lock first (see {@link SyncVersions}) and gives the
 * photo a new sync version.
 */
@Service
public class PhotoService {

    private final PhotoRepository photos;
    private final HouseRepository houses;
    private final SyncVersions versions;
    private final ClientClock clock;
    private final int maxPerHouse;

    public PhotoService(PhotoRepository photos, HouseRepository houses, SyncVersions versions, ClientClock clock,
                        AppProperties props) {
        this.photos = photos;
        this.houses = houses;
        this.versions = versions;
        this.clock = clock;
        this.maxPerHouse = props.limits().maxPhotosPerHouse();
    }

    /**
     * Stores a photo. The client picks the id, so a retried upload (dropped connection) is not stored twice: an
     * existing id, live or deleted, returns without change (a deleted photo is never resurrected).
     */
    @Transactional
    public UUID upload(UUID houseId, UUID requestedId, byte[] bytes) {
        versions.lock();
        var photoId = requestedId == null ? UUID.randomUUID() : requestedId;
        var existing = photos.findMetadataById(photoId);
        if (existing != null) {
            if (!existing.houseId().equals(houseId)) throw new ConflictException("Photo id already used by another house");
            return photoId;
        }
        var house = houses.findById(houseId).filter(h -> !h.isDeleted())
                .orElseThrow(() -> new NotFoundException("House " + houseId + " not found"));
        if (photos.countLiveByHouseId(house.getId()) >= maxPerHouse) {
            throw new ConflictException("Photo limit reached: at most " + maxPerHouse + " photos per house");
        }
        var clean = ImageSanitizer.sanitize(bytes);
        photos.save(new Photo(photoId, houseId, clean.contentType(), clean.data(), Instant.now(), versions.next()));
        return photoId;
    }

    /**
     * The photo with its bytes.
     * @throws NotFoundException if it does not exist or is deleted
     */
    @Transactional(readOnly = true)
    public Photo getLive(UUID id) {
        return photos.findById(id).filter(p -> !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("Photo " + id + " not found"));
    }

    /** Leaves a tombstone so other devices remove their copy (F-15). Deleting twice is fine. */
    @Transactional
    public void delete(UUID id) {
        versions.lock();
        var photo = photos.findById(id).orElse(null);
        if (photo == null || photo.isDeleted()) return;
        photo.markDeleted(Instant.now(), versions.next());
    }

    /**
     * Sets the person's room, tags and caption on a live photo (slice 5, docs/11 section 5.7): last write wins on
     * {@code metaUpdatedAt}, so an edit that is not newer than the stored one changes nothing. Either way the answer
     * is the photo's metadata as the server now holds it. A gone or deleted photo is a 404.
     */
    @Transactional
    public PhotoDto updateMeta(UUID id, PhotoMeta meta) {
        versions.lock();
        var current = photos.findMetadataById(id);
        if (current == null || current.deleted()) throw new NotFoundException("Photo " + id + " not found");
        applyMeta(current, meta.roomId(), meta.tags(), meta.caption(), meta.metaUpdatedAt());
        return photos.findMetadataById(id);
    }

    /**
     * Writes one edit when it is newer (the caller holds the writer lock); {@code true} when it was written. The
     * time is checked like every client stamp (absurd values are refused, a fast clock is clamped to now), and a
     * refusal never echoes it.
     */
    public boolean applyMeta(PhotoDto current, String roomId, List<String> tags, String caption, long metaUpdatedAt) {
        if (metaUpdatedAt <= current.metaUpdatedAt()) return false;
        var now = clock.now();
        long stamp;
        try {
            stamp = clock.accept(Instant.ofEpochMilli(metaUpdatedAt), "metaUpdatedAt").toEpochMilli();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("metaUpdatedAt is out of range (check the device clock)");
        }
        return photos.applyMeta(current.id(), PhotoMeta.orNull(roomId), PhotoMeta.writeTags(tags),
                PhotoMeta.orNull(caption), Math.max(stamp, current.metaUpdatedAt() + 1), now, versions.next()) > 0;
    }

    /** Metadata of one photo (live or tombstone), or null. */
    @Transactional(readOnly = true)
    public PhotoDto metadata(UUID id) {
        return photos.findMetadataById(id);
    }

    /**
     * Ids of a house's live photos, oldest first.
     */
    @Transactional(readOnly = true)
    public List<UUID> liveIds(UUID houseId) {
        return photos.findIdsByHouseId(houseId);
    }

    /**
     * Metadata of photos changed after a sync version, deletions included, without the image bytes.
     */
    @Transactional(readOnly = true)
    public List<PhotoDto> changesSince(long since) {
        return photos.findChangesSince(since);
    }
}
