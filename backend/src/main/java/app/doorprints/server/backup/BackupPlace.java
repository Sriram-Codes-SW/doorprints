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
 * One of the person's places (office, family home) in the shared backup format ({@code doorprints-backup/2},
 * docs/11 section 5.22, slice 4a). On the server a record of type {@code place}; the payload is
 * {@code name, lat, lon}. A place is the person's own data, not a contact, so every copy keeps it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "lat", "lon", "updatedAt"})
public record BackupPlace(
        String id,
        String name,
        Double lat,
        Double lon,
        Long updatedAt
) {
    public static final String TYPE = "place";
    public static final int MAX_NAME = 60;
    /** At most this many live places in a copy: the apps' cap. */
    public static final int MAX = 10;
}
