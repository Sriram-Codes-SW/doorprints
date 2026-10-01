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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * What a person does after they take a house (slice 5, docs/11 section 5.24): the optional move-in date, notes and a
 * ticked list, nested in the house after {@code answers} and kept as one JSON object on {@code house.move_in} like
 * {@link HouseCost}. The server stores, validates, syncs and exports it; nothing computes with it but the AI text
 * ({@code Moving in: <done> of <total> done}).
 *
 * <p>A move-in with no date, no notes and no items is no move-in ({@link #orNull}): it is written absent, never
 * {@code {}}. The clients coerce a bad value on read; the server refuses it (a PUT with 400, an import as a whole
 * file). Messages name the field and never the value, since the text is the person's own.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"date", "notes", "items"})
public record HouseMoveIn(
        @Positive Long date,
        @Size(max = MAX_NOTES) String notes,
        @Valid @Size(max = MAX_ITEMS) List<@NotNull @Valid Item> items
) {
    public static final int MAX_ITEMS = 30;
    public static final int MAX_TEXT = 200;
    public static final int MAX_NOTES = 2000;
    /** The characters of an item id; {@code .} and {@code ..} alone are refused (same rule as a record id). */
    public static final String ID_PATTERN = "(?!\\.\\.?$)[A-Za-z0-9._-]{1,64}";

    /** One ticked line; {@code done} is written only when true. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "text", "done", "sort"})
    public record Item(
            @NotNull @Pattern(regexp = ID_PATTERN) String id,
            @NotBlank @Size(max = MAX_TEXT) String text,
            Boolean done,
            @Min(0) Integer sort
    ) {
    }

    /** Its own mapper: the entity's text form must not follow the web layer's settings. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** True when there is no date, no notes and no items. */
    @JsonIgnore
    public boolean isEmpty() {
        return date == null && (notes == null || notes.isEmpty()) && (items == null || items.isEmpty());
    }

    /** {@code null} for a null or empty move-in, otherwise the move-in with its empty parts left out and {@code done:false} dropped. */
    public static HouseMoveIn orNull(HouseMoveIn m) {
        if (m == null || m.isEmpty()) return null;
        var items = m.items == null || m.items.isEmpty() ? null : m.items.stream()
                .map(i -> i == null || !Boolean.FALSE.equals(i.done) ? i : new Item(i.id, i.text, null, i.sort)).toList();
        return new HouseMoveIn(m.date, m.notes == null || m.notes.isEmpty() ? null : m.notes, items);
    }

    /** Two items with one id cannot be told apart by the clients; a getter constraint so Bean Validation runs it. */
    @JsonIgnore
    @AssertTrue(message = "items must not repeat an id")
    public boolean isItemIdsUnique() {
        if (items == null) return true;
        var seen = new HashSet<String>();
        for (var i : items) if (i != null && i.id != null && !seen.add(i.id)) return false;
        return true;
    }

    /** The entity's text: compact JSON in property order, or {@code null} for no move-in. */
    public static String write(HouseMoveIn moveIn) {
        var m = orNull(moveIn);
        return m == null ? null : JSON.writeValueAsString(m);
    }

    /** The move-in the entity holds, or {@code null} for no text or an empty object. */
    public static HouseMoveIn parse(String json) {
        return json == null || json.isBlank() ? null : orNull(JSON.readValue(json, HouseMoveIn.class));
    }

    /** Ticked items, for the AI text; 0 when there are none. */
    public int doneCount() {
        return items == null ? 0 : (int) items.stream().filter(i -> i != null && Boolean.TRUE.equals(i.done)).count();
    }

    /**
     * The same rules as the annotations, for a reader that has no validator at hand (the backup import): the
     * problems as {@code moveIn...} paths with the reason, empty when the move-in is fine. No message holds a value.
     */
    public static List<String> problems(HouseMoveIn m) {
        var out = new ArrayList<String>();
        if (m == null) return out;
        if (m.date != null && m.date <= 0) out.add("moveIn.date is out of range");
        if (m.notes != null && m.notes.length() > MAX_NOTES) out.add("moveIn.notes is out of range");
        if (m.items == null) return out;
        if (m.items.size() > MAX_ITEMS) out.add("moveIn.items has more than " + MAX_ITEMS + " items");
        var seen = new HashSet<String>();
        for (int j = 0; j < m.items.size(); j++) {
            var i = m.items.get(j);
            var at = "moveIn.items[" + j + "]";
            if (i == null) {
                out.add(at + ": missing");
                continue;
            }
            if (i.id == null || !i.id.matches(ID_PATTERN)) out.add(at + ".id is out of range");
            else if (!seen.add(i.id)) out.add(at + ".id is repeated");
            if (i.text == null || i.text.isBlank() || i.text.length() > MAX_TEXT) out.add(at + ".text is out of range");
            if (i.sort != null && i.sort < 0) out.add(at + ".sort is out of range");
        }
        return out;
    }
}
