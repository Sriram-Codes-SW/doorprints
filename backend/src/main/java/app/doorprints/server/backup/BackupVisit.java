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
import app.doorprints.server.visit.VisitSource;

import java.util.UUID;

/**
 * One visit in the shared backup format. Same null semantics as {@link BackupHouse}: {@code houseId},
 * {@code street} and {@code leftAt} are left out when there is nothing to write, everything else is always present.
 * Timestamps are epoch milliseconds (UTC).
 *
 * <p>{@code houseId} is absent for a visit whose house was deleted (the server unlinks such visits instead of
 * deleting them). The current device writers only walk visits through their house, so they never write such a row;
 * the server does, and their readers keep it because unknown-but-valid rows are simply imported.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "houseId", "lat", "lon", "street", "arrivedAt", "leftAt", "source", "updatedAt"})
public record BackupVisit(
        UUID id,
        UUID houseId,
        /* Boxed for the same reason as BackupHouse.lat/lon: a missing value must be refused, not read as 0. */
        Double lat,
        Double lon,
        String street,
        Long arrivedAt,
        Long leftAt,
        VisitSource source,
        Long updatedAt
) {
}
