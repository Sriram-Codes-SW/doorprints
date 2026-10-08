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

import app.doorprints.server.sync.SyncVersions;
import app.doorprints.server.visit.VisitRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Summary counts of the hunt, and the highest sync version, which lets an app notice a server that was reset.
 */
@RestController
public class StatsController {

    /**
     * The counts, and {@code maxSyncVersion}: the highest sync version this server has handed out
     * ({@link SyncVersions#highest()}). A client whose stored pull cursor is above it is talking to a server that was
     * reset or restored from an older dump, and re-sends everything (S4b-BL-20). Added 2026-09-24; clients treat a
     * missing field (an older server) as unknown.
     */
    public record Stats(long houses, long shortlisted, long rejected, long visits, long streets, long maxSyncVersion) {
    }

    private final HouseRepository houses;
    private final VisitRepository visits;
    private final SyncVersions versions;

    public StatsController(HouseRepository houses, VisitRepository visits, SyncVersions versions) {
        this.houses = houses;
        this.visits = visits;
        this.versions = versions;
    }

    /**
     * Counts of live houses, shortlisted and rejected ones, visits and distinct streets, plus the highest sync
     * version.
     */
    @GetMapping("/api/stats")
    public Stats stats() {
        return new Stats(
                houses.countByDeletedFalse(),
                houses.countByDeletedFalseAndStatus(HouseStatus.SHORTLISTED),
                houses.countByDeletedFalseAndStatus(HouseStatus.REJECTED),
                visits.countByDeletedFalse(),
                houses.countDistinctStreets(),
                versions.highest());
    }
}
