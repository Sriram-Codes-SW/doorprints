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

package app.doorprints.server.backup;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.List;

/**
 * What {@code POST /api/import} did, or (with {@code ?dryRun=true}) what it would do — the preview of docs/11
 * section 5.2: "<em>a</em> new, <em>b</em> newer in file, <em>c</em> newer here".
 *
 * @param dryRun      true when nothing was written
 * @param houses      per-entity outcome counts (also visits, photos and, from {@code doorprints-backup/2}, brokers,
 *                    criteria, and preferences)
 * @param problems    human-readable notes for the operator; never a reason to fail the request. The whole-file notes
 *                    (photo bytes, AI index) come first; the per-row lines after them are capped at
 *                    {@code BackupService.MAX_REPORTED_PROBLEMS} plus one "and N more" tail, so the list is not
 *                    exhaustive and is not meant to be parsed
 */
@JsonPropertyOrder({"format", "dryRun", "houses", "visits", "photos", "brokers", "criteria", "preferences", "questions",
        "problems"})
public record ImportReport(String format, boolean dryRun, Entity houses, Entity visits, Entity photos,
                           Entity brokers, Entity criteria, Entity preferences, Entity questions,
                           List<String> problems) {

    /**
     * Outcome counts for one kind of row. {@code total} is what the file held;
     * {@code created + updated + keptNewer + unchanged + skipped} adds up to it.
     *
     * @param created    the row is new here ("a new")
     * @param updated    the file's row is newer and was written ("b newer in file")
     * @param keptNewer  this server's row is newer and was kept ("c newer here")
     * @param unchanged  same {@code updatedAt} on both sides; nothing written, so no sync version is burned
     * @param skipped    the row could not be merged (see {@link ImportReport#problems()})
     */
    @JsonPropertyOrder({"total", "created", "updated", "keptNewer", "unchanged", "skipped"})
    public record Entity(int total, int created, int updated, int keptNewer, int unchanged, int skipped) {
    }

    /** Mutable tally used while merging; turned into an {@link Entity} at the end. */
    static final class Tally {
        private int total;
        private int created;
        private int updated;
        private int keptNewer;
        private int unchanged;
        private int skipped;

        void count(Outcome outcome) {
            total++;
            switch (outcome) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case KEPT_NEWER -> keptNewer++;
                case UNCHANGED -> unchanged++;
                case SKIPPED -> skipped++;
            }
        }

        Entity toEntity() {
            return new Entity(total, created, updated, keptNewer, unchanged, skipped);
        }
    }

    /** What happened to one row. */
    enum Outcome { CREATED, UPDATED, KEPT_NEWER, UNCHANGED, SKIPPED }

    /**
     * The merge rule of docs/11 section 5.2: merge by id, last write wins on {@code updatedAt}. An equal
     * {@code updatedAt} means the two sides hold the same version, so nothing is written.
     */
    static Outcome decide(Instant here, Instant inFile) {
        if (here == null) return Outcome.CREATED;
        if (here.isAfter(inFile)) return Outcome.KEPT_NEWER;
        if (here.equals(inFile)) return Outcome.UNCHANGED;
        return Outcome.UPDATED;
    }
}
