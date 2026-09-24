package app.doorprints.server.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.UUID;

/**
 * One photo in the shared backup format: metadata only, every field always present.
 *
 * <p>{@code fileName} is {@code <id>.jpg}, the name the bytes have inside a backup ZIP's {@code photos/} folder
 * ({@link BackupFormat#photoEntry}). A JSON-only copy (what {@code GET /api/export} returns) carries no bytes: they
 * are fetched from {@code GET /api/photos/{id}} and uploaded with {@code POST /api/houses/{id}/photos}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "houseId", "fileName", "createdAt"})
public record BackupPhoto(UUID id, UUID houseId, String fileName, Long createdAt) {
}
