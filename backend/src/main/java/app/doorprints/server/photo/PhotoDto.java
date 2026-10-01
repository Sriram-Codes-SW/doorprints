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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Photo metadata for sync (never the bytes; those come from {@code GET /api/photos/{id}}).
 *
 * <p>Null semantics as in {@link app.doorprints.server.house.HouseDto}: every field is written. {@code sizeBytes} is
 * {@code null} for a tombstone, whose bytes are gone; {@code contentType} keeps its value so a client can tell
 * what the photo was. Slice 5 adds the person's {@code roomId}, {@code tags}, {@code caption} and
 * {@code metaUpdatedAt} (0 = never edited), written like the rest (null, not absent; the tags {@code null} when none), so
 * the change feed carries an edit. Read-only: photos are created by the multipart upload, not by sending this record.
 */
public record PhotoDto(UUID id, UUID houseId, String contentType, Integer sizeBytes, Instant createdAt,
                       Instant updatedAt, boolean deleted, long syncVersion,
                       String roomId, List<String> tags, String caption, long metaUpdatedAt) {

    /** For the JPQL constructor expressions: {@code tags} arrives as the column's JSON text. */
    public PhotoDto(UUID id, UUID houseId, String contentType, Integer sizeBytes, Instant createdAt,
                    Instant updatedAt, boolean deleted, long syncVersion,
                    String roomId, String tagsJson, String caption, long metaUpdatedAt) {
        this(id, houseId, contentType, sizeBytes, createdAt, updatedAt, deleted, syncVersion, roomId,
                PhotoMeta.parseTags(tagsJson), caption, metaUpdatedAt);
    }
}
