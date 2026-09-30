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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface RecordRepository extends JpaRepository<Record, RecordKey> {

    /** The change feed of every kind: everything after a cursor, tombstones included, oldest change first. */
    List<Record> findBySyncVersionGreaterThanOrderBySyncVersion(long syncVersion);

    /** The change feed of one kind. */
    List<Record> findByKeyTypeAndSyncVersionGreaterThanOrderBySyncVersion(String type, long syncVersion);

    /** Live rows of one kind, for a backup export. */
    List<Record> findByKeyTypeAndDeletedFalse(String type);

    /** Live rows of one kind, for the per-type cap. */
    long countByKeyTypeAndDeletedFalse(String type);

    @Modifying
    @Query("delete from Record r where r.deleted = true and r.updatedAt < :before")
    int purgeTombstonesBefore(@Param("before") Instant before);
}
