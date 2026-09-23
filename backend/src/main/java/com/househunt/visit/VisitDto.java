package com.househunt.visit;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * One visit over the sync API. Same null semantics as {@link com.househunt.house.HouseDto}: every field is written,
 * {@code null} included, and on input absent means "no value" ({@code houseId} absent unlinks the visit,
 * {@code leftAt} absent means the visit has not ended). {@code source} defaults to {@code MANUAL},
 * {@code updatedAt} to server time; {@code syncVersion} is server-managed. A deleted visit keeps only its id,
 * timestamps and sync version — where the user was is purged (PRV-005).
 */
public record VisitDto(
        UUID id,
        UUID houseId,
        @DecimalMin("-90") @DecimalMax("90") double lat,
        @DecimalMin("-180") @DecimalMax("180") double lon,
        @Size(max = 200) String street,
        @NotNull Instant arrivedAt,
        Instant leftAt,
        VisitSource source,
        Instant updatedAt,
        boolean deleted,
        long syncVersion
) {
    public static VisitDto from(Visit v) {
        return new VisitDto(v.getId(), v.getHouseId(), v.getLat(), v.getLon(), v.getStreet(), v.getArrivedAt(),
                v.getLeftAt(), v.getSource(), v.getUpdatedAt(), v.isDeleted(), v.getSyncVersion());
    }
}
