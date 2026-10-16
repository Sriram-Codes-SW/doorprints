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

package app.doorprints.server.sync;

import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * What a push endpoint does with a row that arrives for an id (S4b-BL-163, docs/03 section 10.1). One rule for houses,
 * visits and records:
 * <ul>
 *   <li>nothing stored: {@link Decision#CREATE};</li>
 *   <li>the stored {@code updatedAt} is later: {@link Decision#KEEP_STORED}, answer with the stored row;</li>
 *   <li>the incoming one is later: {@link Decision#OVERWRITE};</li>
 *   <li>the same stamp and the same content (a retried PUT, a second device): {@link Decision#UNCHANGED}, answer with
 *       the stored row and write nothing, so no sync version is burnt, no device pulls the row again and no event is
 *       published;</li>
 *   <li>the same stamp and other content: {@link Decision#OVERWRITE}, the incoming row wins, as a pulled row wins a
 *       tie on the clients ({@code SyncRules.keepLocal}), so every device ends on the last pusher's content.</li>
 * </ul>
 */
public final class Upsert {

    /** The four things a push can come to. */
    public enum Decision { CREATE, KEEP_STORED, OVERWRITE, UNCHANGED }

    private Upsert() {
    }

    /**
     * @param stored the stored {@code updatedAt}, or null when no row is stored
     * @param incoming the (clamped) {@code updatedAt} of the pushed row
     * @param sameContent asked only on a tie: whether the pushed row would change nothing that is stored
     */
    public static Decision decide(Instant stored, Instant incoming, BooleanSupplier sameContent) {
        if (stored == null) return Decision.CREATE;
        if (stored.isAfter(incoming)) return Decision.KEEP_STORED;
        if (stored.equals(incoming) && sameContent.getAsBoolean()) return Decision.UNCHANGED;
        return Decision.OVERWRITE;
    }
}
