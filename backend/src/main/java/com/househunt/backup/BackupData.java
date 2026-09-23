package com.househunt.backup;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code data.json}: the rows of a backup, in the copy's fixed order (docs/schemas/README.md).
 *
 * <p>{@code GET /api/export} returns exactly this object and {@code POST /api/import} accepts exactly this object.
 * Ordering is part of the format so that two exports of the same data are identical: houses by {@code createdAt}
 * then {@code id}; visits and photos grouped by their house in that same house order, each group by
 * {@code arrivedAt} / {@code createdAt} then {@code id}; rows whose house is not in the file come last. Importers
 * must not depend on the order — it exists to make exports comparable, not to carry meaning.
 *
 * <p>The lists are unmodifiable copies that may contain nulls: a hand-written file can hold {@code [null]}, and
 * {@link BackupService} answers that with a 400 instead of failing here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"format", "exportedAt", "houses", "visits", "photos"})
public record BackupData(
        String format,
        /* When the copy was made, epoch milliseconds UTC. The only value in the file that is not user data. */
        Long exportedAt,
        List<BackupHouse> houses,
        List<BackupVisit> visits,
        List<BackupPhoto> photos
) {
    public BackupData {
        houses = copy(houses);
        visits = copy(visits);
        photos = copy(photos);
    }

    private static <T> List<T> copy(List<T> rows) {
        return rows == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rows));
    }

    /** Total rows, the number the import size limit is measured in. */
    public int rowCount() {
        return houses.size() + visits.size() + photos.size();
    }
}
