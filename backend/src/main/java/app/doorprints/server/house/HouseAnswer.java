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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * One viewing question on a house, and what the owner said (slice 3a, docs/11 section 5.5): part of the house row like
 * a {@link HouseRoom}, kept as one JSON array on {@code house.answers}, at most {@value #MAX} per house.
 *
 * <p>{@code text} is a snapshot of the question as asked, so the copy reads even when the bank question (a record of
 * type {@code question}, named by {@code questionId}, which may dangle) is edited or deleted. The clients coerce a
 * bad value on read; the server refuses it (a PUT with 400, an import as a whole file).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "questionId", "text", "answer", "status", "sort"})
public record HouseAnswer(
        @NotNull @Pattern(regexp = ID_PATTERN) String id,
        @Pattern(regexp = ID_PATTERN) String questionId,
        @NotBlank @Size(max = MAX_TEXT) String text,
        @Size(max = MAX_ANSWER) String answer,
        @Pattern(regexp = STATUSES) String status,
        @Min(0) Integer sort
) {
    public static final int MAX = 60;
    public static final int MAX_TEXT = 300;
    public static final int MAX_ANSWER = 2000;
    /** The characters of an answer id; {@code .} and {@code ..} alone are refused (same rule as a record id). */
    public static final String ID_PATTERN = "(?!\\.\\.?$)[A-Za-z0-9._-]{1,64}";
    public static final String STATUSES = "OPEN|ANSWERED|SKIPPED";

    /** Its own mapper: the entity's text form must not follow the web layer's settings. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<HouseAnswer>> LIST = new TypeReference<>() { };

    /** OPEN first, then {@code sort}, then id: the order the clients and the AI text show them in. */
    private static final Comparator<HouseAnswer> ORDER = Comparator
            .comparing((HouseAnswer a) -> "OPEN".equals(a.reads()) ? 0 : 1)
            .thenComparing(a -> a.sort() == null ? 0 : a.sort())
            .thenComparing(a -> a.id() == null ? "" : a.id());

    /** {@code null} for a null or empty list (an empty array read is no answers), otherwise the list itself. */
    public static List<HouseAnswer> orNull(List<HouseAnswer> answers) {
        return answers == null || answers.isEmpty() ? null : answers;
    }

    /** The entity's text: compact JSON in the order given, or {@code null} for no answers. */
    public static String write(List<HouseAnswer> answers) {
        var a = orNull(answers);
        return a == null ? null : JSON.writeValueAsString(a);
    }

    /** The answers the entity holds, or {@code null} for no text or an empty array. */
    public static List<HouseAnswer> parse(String json) {
        return json == null || json.isBlank() ? null : orNull(JSON.readValue(json, LIST));
    }

    /** True when no two answers share an id (a null answer or id is another rule's business). */
    public static boolean idsAreUnique(List<HouseAnswer> answers) {
        if (answers == null) return true;
        var seen = new HashSet<String>();
        for (var a : answers) if (a != null && a.id() != null && !seen.add(a.id())) return false;
        return true;
    }

    /**
     * The status a reader shows, as the clients coerce it: a non-blank answer with no status or OPEN reads as
     * ANSWERED, ANSWERED with no answer reads as OPEN, an unknown or missing status is OPEN.
     */
    public String reads() {
        boolean has = answer != null && !answer.isBlank();
        if ("SKIPPED".equals(status)) return "SKIPPED";
        return has ? "ANSWERED" : "OPEN";
    }

    /** The answers in reading order (see {@link #ORDER}), null entries dropped. */
    public static List<HouseAnswer> ordered(List<HouseAnswer> answers) {
        if (answers == null) return List.of();
        return answers.stream().filter(Objects::nonNull).sorted(ORDER).toList();
    }

    /**
     * The same rules as the annotations, for a reader that has no validator at hand (the backup import): the
     * problems of a house's answer list as {@code answers...} paths with the reason, empty when the list is fine.
     * Question and answer text are the person's own, so a message names the answer by its index, never by content.
     */
    public static List<String> problems(List<HouseAnswer> answers) {
        var out = new ArrayList<String>();
        if (answers == null) return out;
        if (answers.size() > MAX) out.add("answers has more than " + MAX + " answers");
        var seen = new HashSet<String>();
        for (int j = 0; j < answers.size(); j++) {
            var a = answers.get(j);
            var at = "answers[" + j + "]";
            if (a == null) {
                out.add(at + ": missing");
                continue;
            }
            if (a.id() == null || !a.id().matches(ID_PATTERN)) out.add(at + ".id is out of range");
            else if (!seen.add(a.id())) out.add(at + ".id is repeated");
            if (a.questionId() != null && !a.questionId().matches(ID_PATTERN)) out.add(at + ".questionId is out of range");
            if (a.text() == null || a.text().isBlank() || a.text().length() > MAX_TEXT) {
                out.add(at + ".text is out of range");
            }
            if (a.answer() != null && a.answer().length() > MAX_ANSWER) out.add(at + ".answer is out of range");
            if (a.status() != null && !a.status().matches(STATUSES)) out.add(at + ".status is out of range");
            if (a.sort() != null && a.sort() < 0) out.add(at + ".sort is out of range");
        }
        return out;
    }
}
