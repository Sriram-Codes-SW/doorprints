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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.Set;

/**
 * One viewing question of the question bank in the shared backup format ({@code doorprints-backup/2}, docs/11
 * section 5.5). On the server a question lives in the {@code record} table as type {@code question}, the record id
 * being {@code id} (a seeded default has a fixed id {@code qd_<name>}, a custom one {@code q_} and 8 hex digits);
 * the payload holds the other fields in this order, {@code archived} only when true.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "text", "category", "appliesTo", "defaultOn", "sort", "archived", "updatedAt"})
public record BackupQuestion(
        String id,
        String text,
        String category,
        String appliesTo,
        Boolean defaultOn,
        Integer sort,
        Boolean archived,
        Long updatedAt
) {
    public static final String TYPE = "question";
    public static final int MAX_TEXT = 300;
    /** At most this many questions in a copy (seeded and custom, archived included). */
    public static final int MAX = 100;
    public static final Set<String> CATEGORIES = Set.of("MONEY", "WATER_POWER", "RULES", "BUILDING", "LEGAL", "OTHER");
    public static final Set<String> SCOPES = Set.of("RENT", "SALE", "BOTH");
}
