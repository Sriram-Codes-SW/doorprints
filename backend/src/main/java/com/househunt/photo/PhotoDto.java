package com.househunt.photo;

import java.time.Instant;
import java.util.UUID;

/** Photo metadata for sync and export (never the bytes; those come from {@code GET /api/photos/{id}}). */
public record PhotoDto(UUID id, UUID houseId, String contentType, Integer sizeBytes, Instant createdAt,
                       Instant updatedAt, boolean deleted, long syncVersion) {
}
