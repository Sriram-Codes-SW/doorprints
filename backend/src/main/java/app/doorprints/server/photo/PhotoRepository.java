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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Photo queries. Listings return metadata only, so image bytes are loaded just when one photo is fetched.
 */
public interface PhotoRepository extends JpaRepository<Photo, UUID> {

    /** Live photo ids of a house, oldest first. */
    @Query("select p.id from Photo p where p.houseId = :houseId and p.deleted = false order by p.createdAt")
    List<UUID> findIdsByHouseId(@Param("houseId") UUID houseId);

    @Query("select count(p) from Photo p where p.houseId = :houseId and p.deleted = false")
    long countLiveByHouseId(@Param("houseId") UUID houseId);

    /** Metadata changes (uploads and tombstones) after a sync version, without loading the bytes. */
    @Query("""
            select new app.doorprints.server.photo.PhotoDto(p.id, p.houseId, p.contentType, p.sizeBytes, p.createdAt,
                   p.updatedAt, p.deleted, p.syncVersion, p.roomId, p.tags, p.caption, p.metaUpdatedAt)
            from Photo p where p.syncVersion > :since order by p.syncVersion""")
    List<PhotoDto> findChangesSince(@Param("since") long since);

    /** Metadata of one photo (live or tombstone) without its bytes, or null. */
    @Query("""
            select new app.doorprints.server.photo.PhotoDto(p.id, p.houseId, p.contentType, p.sizeBytes, p.createdAt,
                   p.updatedAt, p.deleted, p.syncVersion, p.roomId, p.tags, p.caption, p.metaUpdatedAt)
            from Photo p where p.id = :id""")
    PhotoDto findMetadataById(@Param("id") UUID id);

    @Query("""
            select new app.doorprints.server.photo.PhotoDto(p.id, p.houseId, p.contentType, p.sizeBytes, p.createdAt,
                   p.updatedAt, p.deleted, p.syncVersion, p.roomId, p.tags, p.caption, p.metaUpdatedAt)
            from Photo p where p.deleted = false order by p.createdAt""")
    List<PhotoDto> findAllLiveMetadata();

    /** Tombstones every live photo of a house (house deleted). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Photo p set p.deleted = true, p.data = null, p.roomId = null, p.tags = null, p.caption = null,
                   p.metaUpdatedAt = 0, p.updatedAt = :now, p.syncVersion = :version
            where p.houseId = :houseId and p.deleted = false""")
    int tombstoneAllOfHouse(@Param("houseId") UUID houseId, @Param("now") Instant now, @Param("version") long version);

    /**
     * Writes the person's room, tags and caption on a live photo when {@code metaUpdatedAt} is newer than what is
     * there (last write wins, slice 5), and takes a new sync version so the change feed carries it. 1 row when
     * written, 0 when the photo is gone, deleted or already has this edit or a newer one.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Photo p set p.roomId = :roomId, p.tags = :tags, p.caption = :caption, p.metaUpdatedAt = :meta,
                   p.updatedAt = :now, p.syncVersion = :version
            where p.id = :id and p.deleted = false and p.metaUpdatedAt < :meta""")
    int applyMeta(@Param("id") UUID id, @Param("roomId") String roomId, @Param("tags") String tags,
                  @Param("caption") String caption, @Param("meta") long meta, @Param("now") Instant now,
                  @Param("version") long version);

    @Modifying
    @Query("delete from Photo p where p.deleted = true and p.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);
}
