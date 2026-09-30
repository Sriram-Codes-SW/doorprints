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

package app.doorprints.server.ai.rag;

import app.doorprints.server.record.Record;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the AI document keeps of one viewing record (slice 3b-1): when, kind, status and notes. Never {@code withWhom}
 * (contact data) and never the reminder or the visit link. The server does not read inside a record elsewhere, so a
 * payload that is not a viewing of a house is skipped, and a bad kind or status reads as the clients read it.
 */
record ViewingLine(String id, String houseId, long startsAt, String kind, String status, String notes) {

    /** {@code null} for a record that is not a usable viewing (no house, no positive start, not JSON). */
    static ViewingLine from(Record r, ObjectMapper json) {
        JsonNode p;
        try {
            p = json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
        var houseId = p.path("houseId");
        var startsAt = p.path("startsAt");
        if (!houseId.isString() || houseId.asString().isBlank() || !startsAt.isIntegralNumber()
                || startsAt.asLong() <= 0) {
            return null;
        }
        var kind = p.path("kind");
        var status = p.path("status");
        var notes = p.path("notes");
        return new ViewingLine(r.getKey().id(), houseId.asString(), startsAt.asLong(),
                kind.isString() && ("SECOND".equals(kind.asString()) || "FOLLOW_UP".equals(kind.asString()))
                        ? kind.asString() : "FIRST",
                status.isString() && ("DONE".equals(status.asString()) || "CANCELLED".equals(status.asString()))
                        ? status.asString() : "PLANNED",
                notes.isString() ? notes.asString() : null);
    }
}
