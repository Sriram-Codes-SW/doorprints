package app.doorprints.server.backup;

import app.doorprints.server.house.House;
import app.doorprints.server.photo.PhotoDto;
import app.doorprints.server.visit.Visit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds {@link BackupData} from the server's rows in the format's fixed order (docs/schemas/README.md).
 *
 * <p>Ordering — the agreed rule of docs/schemas/README.md section 5, so that a copy made on a phone, in a browser and
 * on the server lists the same data in the same order. All three writers follow it: the web writer flat-maps over
 * its houses, and the Android writer ({@code BackupData.of} in {@code :shared}) groups the same way since ticket
 * S4-00/a:
 * <ul>
 *   <li>houses by {@code createdAt}, then {@code id} (as text, like the clients' code-unit comparison);</li>
 *   <li>visits grouped by house in that house order, each group by {@code arrivedAt} then {@code id};</li>
 *   <li>photos grouped the same way, each group by {@code createdAt} then {@code id};</li>
 *   <li>rows whose house is not in the export (a visit unlinked by a house delete) come last, in the same order.
 *       The device writers walk visits through their house and so have nothing to put here.</li>
 *   <li>checklist keys alphabetically ({@link TreeMap}).</li>
 * </ul>
 *
 * <p>Tombstones are never exported; callers pass live rows only.
 */
final class BackupMapper {

    // The lambdas are explicitly typed: with an implicit one, thenComparing(Function) and
    // thenComparing(Comparator) are both applicable and javac calls the reference ambiguous.
    private static final Comparator<House> HOUSE_ORDER =
            Comparator.comparing(House::getCreatedAt).thenComparing((House h) -> h.getId().toString());
    private static final Comparator<Visit> VISIT_ORDER =
            Comparator.comparing(Visit::getArrivedAt).thenComparing((Visit v) -> v.getId().toString());
    private static final Comparator<PhotoDto> PHOTO_ORDER =
            Comparator.comparing(PhotoDto::createdAt).thenComparing((PhotoDto p) -> p.id().toString());

    private BackupMapper() {
    }

    static BackupData toBackup(List<House> houses, List<Visit> visits, List<PhotoDto> photos, Instant exportedAt) {
        var liveHouses = houses.stream().filter(h -> !h.isDeleted()).sorted(HOUSE_ORDER).toList();
        var houseOrder = new LinkedHashSet<UUID>();
        for (var house : liveHouses) houseOrder.add(house.getId());

        var liveVisits = visits.stream().filter(v -> !v.isDeleted()).toList();
        var livePhotos = photos.stream().filter(p -> !p.deleted()).toList();

        return new BackupData(
                BackupFormat.ID,
                exportedAt.toEpochMilli(),
                liveHouses.stream().map(BackupMapper::house).toList(),
                groupByHouse(liveVisits, Visit::getHouseId, VISIT_ORDER, houseOrder).stream()
                        .map(BackupMapper::visit).toList(),
                groupByHouse(livePhotos, PhotoDto::houseId, PHOTO_ORDER, houseOrder).stream()
                        .map(BackupMapper::photo).toList());
    }

    /**
     * Rows grouped by their house in {@code houseOrder}, each group sorted by {@code within}; rows with no house or
     * with a house that is not being exported are appended at the end in the same sort order.
     */
    private static <T> List<T> groupByHouse(List<T> rows, Function<T, UUID> houseIdOf, Comparator<T> within,
                                            Set<UUID> houseOrder) {
        var sorted = new ArrayList<>(rows);
        sorted.sort(within);
        var byHouse = new HashMap<UUID, List<T>>();
        var orphans = new ArrayList<T>();
        for (var row : sorted) {
            var houseId = houseIdOf.apply(row);
            if (houseId != null && houseOrder.contains(houseId)) {
                byHouse.computeIfAbsent(houseId, key -> new ArrayList<>()).add(row);
            } else {
                orphans.add(row);
            }
        }
        var out = new ArrayList<T>(sorted.size());
        for (var houseId : houseOrder) {
            var group = byHouse.get(houseId);
            if (group != null) out.addAll(group);
        }
        out.addAll(orphans);
        return out;
    }

    private static BackupHouse house(House h) {
        return new BackupHouse(h.getId(), h.getLabel(), h.getAddress(), h.getStreet(), h.getLocality(),
                h.getLat(), h.getLon(), h.getStatus(), h.getPrice(), h.getPriceType(), h.getBedrooms(),
                h.getRating(), h.getContactName(), h.getContactPhone(), h.getListingUrl(), h.getNotes(),
                sortedChecklist(h.getChecklist()), millis(h.getCreatedAt()), millis(h.getUpdatedAt()));
    }

    private static BackupVisit visit(Visit v) {
        return new BackupVisit(v.getId(), v.getHouseId(), v.getLat(), v.getLon(), v.getStreet(),
                millis(v.getArrivedAt()), millis(v.getLeftAt()), v.getSource(), millis(v.getUpdatedAt()));
    }

    private static BackupPhoto photo(PhotoDto p) {
        return new BackupPhoto(p.id(), p.houseId(), BackupFormat.photoFileName(p.id()), millis(p.createdAt()));
    }

    /** Checklist scores with the keys in alphabetical order, so the JSON is byte-stable. */
    private static Map<String, Integer> sortedChecklist(Map<String, Integer> checklist) {
        return checklist == null || checklist.isEmpty() ? Map.of() : new TreeMap<>(checklist);
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
