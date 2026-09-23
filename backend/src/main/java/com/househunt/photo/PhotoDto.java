package com.househunt.photo;

import java.time.Instant;
import java.util.UUID;

/**
 * Photo metadata for sync (never the bytes; those come from {@code GET /api/photos/{id}}).
 *
 * <p>Null semantics as in {@link com.househunt.house.HouseDto}: every field is written. {@code sizeBytes} is
 * {@code null} for a tombstone, whose bytes are gone; {@code contentType} keeps its value so a client can tell
 * what the photo was. Read-only: photos are created by the multipart upload, not by sending this record.
 */
public record PhotoDto(UUID id, UUID houseId, String contentType, Integer sizeBytes, Instant createdAt,
                       Instant updatedAt, boolean deleted, long syncVersion) {
}
