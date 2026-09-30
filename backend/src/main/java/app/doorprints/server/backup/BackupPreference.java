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

/**
 * One preference in the shared backup format ({@code doorprints-backup/2}, docs/11 section 5.4).
 *
 * <p>{@code key} is the preference id (e.g., {@code score.ratingShare}), {@code value} is a string ≤500 chars,
 * and {@code updatedAt} is epoch milliseconds UTC. On the server a preference lives in the {@code record} table
 * as type {@code preference}; the payload holds the {@code value}.
 */
@JsonPropertyOrder({"key", "value", "updatedAt"})
public record BackupPreference(
        String key,
        String value,
        Long updatedAt
) {
    public static final String TYPE = "preference";
    public static final int MAX_VALUE = 500;
}
