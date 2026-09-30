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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * One room of a house (docs/11 section 5.6, slice 1c of the Sprint 4b data model): the same object on the phone, in
 * the browser, in a backup and here. Sizes are whole centimetres (0..5000), {@code condition} is 1..5, absent when
 * the room has not been checked, {@code sort} is the order shown. A house has at most {@value #MAX} rooms with
 * distinct ids. The clients coerce what is out of range to "unknown"; the server refuses it (a 400 on the PUT, the
 * whole file on an import), like every other house value.
 *
 * <p>Null semantics as for {@link HouseCost}: an absent field is left out, on the sync API as in a backup; no rooms
 * is written absent, never {@code []}, and an empty list read is the same as none ({@link #orNull}). The entity keeps
 * the list as compact JSON text (a {@code jsonb} column), which {@link #write} and {@link #parse} produce. The
 * server never reads inside the room notes, except that the AI index leaves them out (they may hold contact details).
 *
 * <p>The property order is part of the backup contract (docs/schemas/README.md section 8.1), so it is pinned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "type", "name", "lengthCm", "widthCm", "condition", "notes", "sort"})
public record HouseRoom(
        @NotNull @Pattern(regexp = ID_PATTERN) String id,
        @NotNull @Pattern(regexp = TYPES) String type,
        @Size(max = MAX_NAME) String name,
        @Min(0) @Max(MAX_CM) Integer lengthCm,
        @Min(0) @Max(MAX_CM) Integer widthCm,
        @Min(1) @Max(5) Integer condition,
        @Size(max = MAX_NOTES) String notes,
        @Min(0) Integer sort
) {
    public static final int MAX = 30;
    public static final int MAX_NAME = 60;
    public static final int MAX_NOTES = 2000;
    public static final int MAX_CM = 5000;
    /** The characters of a room id; {@code .} and {@code ..} alone are refused (same rule as a record id). */
    public static final String ID_PATTERN = "(?!\\.\\.?$)[A-Za-z0-9._-]{1,64}";
    public static final String TYPES = "BEDROOM|HALL|KITCHEN|BATHROOM|BALCONY|POOJA|STUDY|UTILITY|STORE|OTHER";

    /** Its own mapper: the entity's text form must not follow the web layer's settings. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<HouseRoom>> LIST = new TypeReference<>() { };

    /** {@code null} for a null or empty list (an empty array read is no rooms), otherwise the list itself. */
    public static List<HouseRoom> orNull(List<HouseRoom> rooms) {
        return rooms == null || rooms.isEmpty() ? null : rooms;
    }

    /** The entity's text: compact JSON in the order given, or {@code null} for no rooms. */
    public static String write(List<HouseRoom> rooms) {
        var r = orNull(rooms);
        return r == null ? null : JSON.writeValueAsString(r);
    }

    /** The rooms the entity holds, or {@code null} for no text or an empty array. */
    public static List<HouseRoom> parse(String json) {
        return json == null || json.isBlank() ? null : orNull(JSON.readValue(json, LIST));
    }

    /** True when no two rooms share an id (a null room or id is another rule's business). */
    public static boolean idsAreUnique(List<HouseRoom> rooms) {
        if (rooms == null) return true;
        var seen = new HashSet<String>();
        for (var room : rooms) if (room != null && room.id() != null && !seen.add(room.id())) return false;
        return true;
    }

    /**
     * The same rules as the annotations, for a reader that has no validator at hand (the backup import): the
     * problems of a house's room list as {@code rooms...} paths with the reason, empty when the list is fine.
     * Room ids and notes are user text, so a message names the room by its index, never by its content.
     */
    public static List<String> problems(List<HouseRoom> rooms) {
        var out = new ArrayList<String>();
        if (rooms == null) return out;
        if (rooms.size() > MAX) out.add("rooms has more than " + MAX + " rooms");
        var seen = new HashSet<String>();
        for (int j = 0; j < rooms.size(); j++) {
            var room = rooms.get(j);
            var at = "rooms[" + j + "]";
            if (room == null) {
                out.add(at + ": missing");
                continue;
            }
            if (room.id() == null || !room.id().matches(ID_PATTERN)) out.add(at + ".id is out of range");
            else if (!seen.add(room.id())) out.add(at + ".id is repeated");
            if (room.type() == null || !room.type().matches(TYPES)) out.add(at + ".type is out of range");
            if (room.name() != null && room.name().length() > MAX_NAME) out.add(at + ".name is out of range");
            cm(out, at + ".lengthCm", room.lengthCm());
            cm(out, at + ".widthCm", room.widthCm());
            if (room.condition() != null && (room.condition() < 1 || room.condition() > 5)) {
                out.add(at + ".condition is out of range");
            }
            if (room.notes() != null && room.notes().length() > MAX_NOTES) out.add(at + ".notes is out of range");
            if (room.sort() != null && room.sort() < 0) out.add(at + ".sort is out of range");
        }
        return out;
    }

    private static void cm(List<String> out, String name, Integer value) {
        if (value != null && (value < 0 || value > MAX_CM)) out.add(name + " is out of range");
    }
}
