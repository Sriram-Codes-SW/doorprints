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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Queries on visits: the sync change feed, live listings (all, or per house), and the purge of old tombstones.
 */
public interface VisitRepository extends JpaRepository<Visit, UUID> {

    List<Visit> findByDeletedFalseOrderByArrivedAtDesc();

    List<Visit> findBySyncVersionGreaterThanOrderBySyncVersion(long syncVersion);

    List<Visit> findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(UUID houseId);

    /** The live visits of several houses in one query (the reindex's batches), newest first. */
    List<Visit> findByDeletedFalseAndHouseIdInOrderByArrivedAtDesc(Collection<UUID> houseIds);

    /**
     * The ids of the houses with at least one live visit, the most recently visited first, equal times by house id (Ask: a question about visits
     * must reach these houses whatever the vector similarity says, S4b-BL-194).
     */
    @Query("""
            select v.houseId from Visit v where v.deleted = false and v.houseId is not null
            group by v.houseId order by max(v.arrivedAt) desc, v.houseId asc""")
    List<UUID> visitedHouseIds();

    long countByDeletedFalse();

    /** A deleted house's visits stay in the history but lose the link (and get a new sync version). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Visit v set v.houseId = null, v.updatedAt = :now, v.syncVersion = :version
            where v.houseId = :houseId""")
    int unlinkHouse(@Param("houseId") UUID houseId, @Param("now") Instant now, @Param("version") long version);

    /**
     * Permanently removes tombstones last updated before the cut-off; returns how many.
     */
    @Modifying
    @Query("delete from Visit v where v.deleted = true and v.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);
}
