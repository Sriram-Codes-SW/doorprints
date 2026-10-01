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

import java.util.List;
import java.util.UUID;

/**
 * One photo in the shared backup format: metadata only, every field always present.
 *
 * <p>{@code fileName} is {@code <id>.jpg}, the name the bytes have inside a backup ZIP's {@code photos/} folder
 * ({@link BackupFormat#photoEntry}). A JSON-only copy (what {@code GET /api/export} returns) carries no bytes: they
 * are fetched from {@code GET /api/photos/{id}} and uploaded with {@code POST /api/houses/{id}/photos}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "houseId", "fileName", "createdAt", "roomId", "tags", "caption", "metaUpdatedAt"})
public record BackupPhoto(UUID id, UUID houseId, String fileName, Long createdAt,
                          /* Slice 5 (docs/11 section 5.7), each written only when set and absent otherwise: a room id of
                             the house (may dangle), at most 10 tags, a caption, and when they were last edited. */
                          String roomId, List<String> tags, String caption, Long metaUpdatedAt) {
}
