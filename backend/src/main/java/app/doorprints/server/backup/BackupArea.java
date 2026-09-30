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
 * One hunting area in the shared backup format ({@code doorprints-backup/2}, docs/11 section 5.17, slice 4a). On the
 * server an area lives in the {@code record} table as type {@code area}, the record id being {@code id} (the apps
 * make {@code a_} and 8 hex digits); the payload holds {@code name, lat, lon, radiusM} and {@code enabled} only when
 * false. A file with a radius outside {@value #MIN_RADIUS}..{@value #MAX_RADIUS} is refused; a stored record with
 * one reads as {@value #DEFAULT_RADIUS}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "name", "lat", "lon", "radiusM", "enabled", "updatedAt"})
public record BackupArea(
        String id,
        String name,
        Double lat,
        Double lon,
        Integer radiusM,
        Boolean enabled,
        Long updatedAt
) {
    public static final String TYPE = "area";
    public static final int MAX_NAME = 100;
    public static final int MIN_RADIUS = 200;
    public static final int MAX_RADIUS = 2000;
    public static final int DEFAULT_RADIUS = 500;
    /** At most this many live areas in a copy: the apps' cap (docs/11 slice 4a). */
    public static final int MAX = 20;
}
