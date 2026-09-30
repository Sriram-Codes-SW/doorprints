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
 * One area note in the shared backup format ({@code doorprints-backup/2}, docs/11 section 5.23, slice 4a). On the
 * server a record of type {@code areanote}; the payload holds exactly one of {@code areaId} (an area's id, which may
 * name an area that is gone) or {@code street}, then {@code text}. A file with neither or both targets, or a blank
 * text, is refused.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "areaId", "street", "text", "updatedAt"})
public record BackupAreaNote(
        String id,
        String areaId,
        String street,
        String text,
        Long updatedAt
) {
    public static final String TYPE = "areanote";
    /** An area id is a record id; the limit keeps a stored row importable again. */
    public static final int MAX_REF = 64;
    public static final int MAX_STREET = 100;
    public static final int MAX_TEXT = 1000;
    /** At most this many live notes in a copy: the apps' cap. */
    public static final int MAX = 200;
}
