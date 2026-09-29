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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface HouseRepository extends JpaRepository<House, UUID> {

    List<House> findByDeletedFalseOrderByUpdatedAtDesc();

    List<House> findBySyncVersionGreaterThanOrderBySyncVersion(long syncVersion);

    /** Case-insensitive street match written as {@code lower(street) = lower(?)} so it uses house_street_idx. */
    @Query(value = "select * from house where not deleted and lower(street) = lower(:street)", nativeQuery = true)
    List<House> findLiveOnStreet(@Param("street") String street);

    @Modifying
    @Query("delete from House h where h.deleted = true and h.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);

    /** Houses within {@code radius} metres, nearest first. Row = [id, distanceMetres]. */
    @Query(value = """
            select cast(h.id as varchar) as id,
                   ST_Distance(h.geog, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography) as dist
            from house h
            where not h.deleted
              and ST_DWithin(h.geog, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, :radius)
            order by dist
            limit 50
            """, nativeQuery = true)
    List<Object[]> findNearby(@Param("lat") double lat, @Param("lon") double lon, @Param("radius") double radius);

    @Query(value = "select count(distinct lower(street)) from house where not deleted and street is not null",
            nativeQuery = true)
    long countDistinctStreets();

    long countByDeletedFalse();

    long countByDeletedFalseAndStatus(HouseStatus status);
}
