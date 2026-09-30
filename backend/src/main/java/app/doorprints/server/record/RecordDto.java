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

package app.doorprints.server.record;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * The record envelope of the sync API (docs/11 section 5.30, ADR-28): the same shape on the phone, in the browser,
 * in a shared file and here. {@code type} names the kind (a lower-camel name such as {@code huntingArea}) and
 * {@code id} is the client's id; {@code payload} is a JSON object the server stores without reading, at most
 * {@link RecordController#MAX_PAYLOAD_BYTES} compact, and {@code {}} on a tombstone. {@code updatedAt} defaults to
 * server time; {@code syncVersion} is server-managed.
 */
public record RecordDto(
        @NotNull @Pattern(regexp = TYPE_PATTERN) String type,
        @NotNull @Pattern(regexp = ID_PATTERN) String id,
        JsonNode payload,
        Instant updatedAt,
        boolean deleted,
        long syncVersion
) {
    public static final String TYPE_PATTERN = "[a-z][a-zA-Z0-9]{0,39}";
    /** The characters of a record id; `.` and `..` alone are refused because they are path segments in the URL. */
    public static final String ID_PATTERN = "(?!\\.\\.?$)[A-Za-z0-9._-]{1,64}";

    /** The stored row as sent to clients; {@code json} turns the stored text back into a tree. */
    public static RecordDto from(Record r, ObjectMapper json) {
        return new RecordDto(r.getKey().type(), r.getKey().id(), json.readTree(r.getPayload()), r.getUpdatedAt(),
                r.isDeleted(), r.getSyncVersion());
    }
}
