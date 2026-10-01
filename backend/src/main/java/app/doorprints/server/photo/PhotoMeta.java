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

package app.doorprints.server.photo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a person says about a photo (slice 5, docs/11 section 5.7): the room of its house it shows, up to ten tags, a
 * caption, and when that was last edited ({@code metaUpdatedAt}, epoch ms, 0 = never) for last-write-wins. This is
 * the body of {@code PUT /api/photos/{id}/meta}, and its rules are the ones the backup import applies to a photo row.
 *
 * <p>A tag is one of the fixed keys {@link #FIXED} or a custom text of 1..30 characters. Tags are distinct ignoring
 * case, and a custom tag may not spell a fixed key in another case ({@code kitchen_fittings}): the clients translate
 * the fixed keys, so a look-alike would show twice. The clients coerce a bad value on read; the server refuses it.
 * Every message names the field and never the value.
 */
public record PhotoMeta(
        @Size(max = MAX_ROOM_ID) String roomId,
        @Size(max = MAX_TAGS) List<@NotNull @Size(min = 1, max = MAX_TAG) String> tags,
        @Size(max = MAX_CAPTION) String caption,
        @PositiveOrZero long metaUpdatedAt
) {
    public static final int MAX_ROOM_ID = 64;
    public static final int MAX_TAGS = 10;
    public static final int MAX_TAG = 30;
    public static final int MAX_CAPTION = 200;
    /** The fixed tag keys, in the order the apps list them. */
    public static final Set<String> FIXED = Set.of("EXTERIOR", "ENTRANCE", "KITCHEN_FITTINGS", "BATHROOM_FITTINGS",
            "DAMP", "CRACK", "LEAK", "VIEW", "WATER_TANK", "METER", "PARKING", "LIFT", "GOOD_POINT", "PROBLEM",
            "MOVE_IN");

    /** Its own mapper: the entity's text form must not follow the web layer's settings. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<String>> LIST = new TypeReference<>() { };

    /** The tag rules beyond length and count, a getter constraint so that Bean Validation runs it with the record. */
    @JsonIgnore
    @AssertTrue(message = "tags must not repeat, and a custom tag must not spell a fixed key in another case")
    public boolean isTagsClean() {
        return tagProblem(tags) == null;
    }

    /** {@code null} when the tags are fine, else the reason; used by the import, where no validator is at hand. */
    static String tagProblem(List<String> tags) {
        if (tags == null) return null;
        var seen = new HashSet<String>();
        for (var t : tags) {
            if (t == null) continue; // the length rule reports it
            if (!seen.add(t.toLowerCase(Locale.ROOT))) return "repeats a tag";
            if (!FIXED.contains(t) && FIXED.contains(t.toUpperCase(Locale.ROOT))) return "spells a fixed key in another case";
        }
        return null;
    }

    /** True when nothing is set (the entity then holds null columns and 0). */
    public boolean isBlank() {
        return (roomId == null || roomId.isEmpty()) && (tags == null || tags.isEmpty())
                && (caption == null || caption.isEmpty());
    }

    /** {@code null} for no tags, else compact JSON: the entity's text form. */
    public static String writeTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? null : JSON.writeValueAsString(tags);
    }

    /** The tags the entity holds, or {@code null} for none. */
    public static List<String> parseTags(String json) {
        if (json == null || json.isBlank()) return null;
        var list = JSON.readValue(json, LIST);
        return list.isEmpty() ? null : list;
    }

    /** An empty string is no value: stored and written as absent. */
    public static String orNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /**
     * The same rules as the annotations, for the backup import: the problems of photo row {@code at} as
     * {@code at.field ...} reasons, empty when the row's meta is fine. A row with no meta has none.
     */
    public static List<String> problems(String at, String roomId, List<String> tags, String caption, Long metaUpdatedAt) {
        var out = new ArrayList<String>();
        if (roomId != null && roomId.length() > MAX_ROOM_ID) out.add(at + ".roomId is out of range");
        if (caption != null && caption.length() > MAX_CAPTION) out.add(at + ".caption is out of range");
        if (metaUpdatedAt != null && metaUpdatedAt < 0) out.add(at + ".metaUpdatedAt must not be negative");
        if (tags != null) {
            if (tags.size() > MAX_TAGS) out.add(at + ".tags has more than " + MAX_TAGS + " tags");
            for (int j = 0; j < tags.size(); j++) {
                var t = tags.get(j);
                if (t == null || t.isEmpty() || t.length() > MAX_TAG) out.add(at + ".tags[" + j + "] is out of range");
            }
            var reason = tagProblem(tags);
            if (reason != null) out.add(at + ".tags " + reason);
        }
        return out;
    }
}
