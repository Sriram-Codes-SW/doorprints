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

package app.doorprints.server.privacy;

import app.doorprints.server.config.AppProperties;
import app.doorprints.server.house.HouseRepository;
import app.doorprints.server.photo.PhotoRepository;
import app.doorprints.server.record.RecordRepository;
import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Data-subject rights for the single user (FR-032 erase, PRV-004/PRV-005 retention). The export half of FR-031
 * moved to {@link app.doorprints.server.backup.BackupService}, which speaks the shared backup format; this class keeps the
 * destructive and the scheduled work.
 */
@Service
public class DataService {

    private static final Logger log = LoggerFactory.getLogger(DataService.class);

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final PhotoRepository photos;
    private final RecordRepository records;
    private final SyncVersions versions;
    private final Duration retention;

    @PersistenceContext
    private EntityManager em;

    public DataService(HouseRepository houses, VisitRepository visits, PhotoRepository photos, RecordRepository records,
                       SyncVersions versions, AppProperties props) {
        this.houses = houses;
        this.visits = visits;
        this.photos = photos;
        this.records = records;
        this.versions = versions;
        this.retention = Duration.ofDays(props.privacy().tombstoneRetentionDays());
    }

    /**
     * Hard-deletes every house, visit, photo, record and AI index row. Devices keep their local copies (they only receive
     * changes, and hard deletes leave no tombstones), so the user clears app data on each device separately.
     */
    @Transactional
    public void deleteAll() {
        versions.lock(); // no sync write can interleave
        em.createNativeQuery("delete from photo").executeUpdate();
        em.createNativeQuery("delete from visit").executeUpdate();
        em.createNativeQuery("delete from house_checklist").executeUpdate();
        em.createNativeQuery("delete from house").executeUpdate();
        em.createNativeQuery("delete from record").executeUpdate();
        // The optional pgvector table (V2) only exists when the extension is installed.
        var hasVectorStore = em.createNativeQuery("select to_regclass('public.vector_store') is not null")
                .getSingleResult();
        if (Boolean.TRUE.equals(hasVectorStore)) em.createNativeQuery("delete from vector_store").executeUpdate();
        log.warn("privacy.delete-all: all houses, visits, photos, records and AI index rows were deleted");
    }

    /** Daily purge of tombstones older than the retention period (default 90 days). */
    @Scheduled(cron = "${app.privacy.purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeTombstones() {
        versions.lock();
        var before = Instant.now().minus(retention);
        int p = photos.purgeTombstonesBefore(before);
        int v = visits.purgeTombstonesBefore(before);
        int h = houses.purgeTombstonesBefore(before);
        int r = records.purgeTombstonesBefore(before);
        if (p + v + h + r > 0) {
            log.info("privacy.purge tombstones: houses={} visits={} photos={} records={}", h, v, p, r);
        }
    }
}
