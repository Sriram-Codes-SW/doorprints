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

import java.util.List;

/**
 * What the AI document keeps of the person's areas, places and area notes (slice 4a). The server does not read inside
 * a record elsewhere, so a payload that is not usable is skipped, and a radius outside 200..2000 reads as 500, as the
 * clients read it. {@code lat}/{@code lon} of a place are used to compute a distance and are never written out.
 */
final class AreaLines {

    static final int MIN_RADIUS = 200;
    static final int MAX_RADIUS = 2000;
    static final int DEFAULT_RADIUS = 500;

    private AreaLines() {
    }

    record Area(String id, String name, double lat, double lon, int radiusM) {
        /** {@code null} for a record that is not a usable area (no name or point, not JSON). */
        static Area from(Record r, ObjectMapper json) {
            var p = read(r, json);
            if (p == null) return null;
            var name = p.path("name");
            var lat = coordinate(p, "lat", 90);
            var lon = coordinate(p, "lon", 180);
            if (!name.isString() || name.asString().isBlank() || lat == null || lon == null) return null;
            var radius = p.path("radiusM");
            return new Area(r.getKey().id(), name.asString(), lat, lon,
                    radius.isInt() && radius.asInt() >= MIN_RADIUS && radius.asInt() <= MAX_RADIUS
                            ? radius.asInt() : DEFAULT_RADIUS);
        }
    }

    record Place(String name, double lat, double lon) {
        static Place from(Record r, ObjectMapper json) {
            var p = read(r, json);
            if (p == null) return null;
            var name = p.path("name");
            var lat = coordinate(p, "lat", 90);
            var lon = coordinate(p, "lon", 180);
            if (!name.isString() || name.asString().isBlank() || lat == null || lon == null) return null;
            return new Place(name.asString(), lat, lon);
        }
    }

    /** Exactly one of {@code areaId} and {@code street} is set. */
    record Note(String id, String areaId, String street, String text, long updatedAt) {
        static Note from(Record r, ObjectMapper json) {
            var p = read(r, json);
            if (p == null) return null;
            var areaId = p.path("areaId");
            var street = p.path("street");
            var text = p.path("text");
            if (!text.isString() || text.asString().isBlank()
                    || p.has("areaId") == p.has("street")
                    || (p.has("areaId") && (!areaId.isString() || areaId.asString().isBlank()))
                    || (p.has("street") && (!street.isString() || street.asString().isBlank()))) {
                return null;
            }
            return new Note(r.getKey().id(), p.has("areaId") ? areaId.asString() : null,
                    p.has("street") ? street.asString() : null, text.asString(),
                    r.getUpdatedAt() == null ? 0 : r.getUpdatedAt().toEpochMilli());
        }
    }

    /** All three lists, read once per index run. */
    record All(List<Area> areas, List<Place> places, List<Note> notes) {
        static final All NONE = new All(List.of(), List.of(), List.of());
    }

    /**
     * The record's JSON payload, or null when it cannot be parsed (such records are skipped).
     */
    private static JsonNode read(Record r, ObjectMapper json) {
        try {
            return json.readTree(r.getPayload());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A numeric coordinate within +-limit, or null when absent, not a number or out of range.
     */
    private static Double coordinate(JsonNode p, String key, double limit) {
        var node = p.path(key);
        if (!node.isNumber()) return null;
        var value = node.asDouble();
        return Double.isFinite(value) && Math.abs(value) <= limit ? value : null;
    }
}
