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

/**
 * One criterion in the shared backup format ({@code doorprints-backup/2}, docs/11 section 5.4).
 *
 * <p>{@code key} is the criterion id (a built-in key like {@code water} or a custom key like {@code c_ab12cd34}),
 * {@code weight} is 0..3 (Ignore, Low, Medium, High), {@code minScore} is 1..5, {@code sort} is the display order,
 * {@code updatedAt} is epoch milliseconds UTC. {@code label} (≤60 chars) is present only for custom criteria and
 * omitted for built-ins. {@code archived} is written only when true. {@code mustHave} is a boolean.
 * On the server a criterion lives in the {@code record} table as type {@code criterion}; the payload holds every
 * field here except {@code key} and {@code updatedAt}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"key", "label", "weight", "mustHave", "minScore", "sort", "archived", "updatedAt"})
public record BackupCriterion(
        String key,
        String label,
        Integer weight,
        Boolean mustHave,
        Integer minScore,
        Integer sort,
        Boolean archived,
        Long updatedAt
) {
    public static final String TYPE = "criterion";
    public static final int MAX_LABEL = 60;
    public static final int MIN_WEIGHT = 0;
    public static final int MAX_WEIGHT = 3;
    public static final int MIN_SCORE = 1;
    public static final int MAX_SCORE = 5;
}
