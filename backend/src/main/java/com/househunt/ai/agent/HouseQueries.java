package com.househunt.ai.agent;

import com.househunt.ai.ContactRedactor;
import com.househunt.ai.agent.HouseSearchService.Criteria;
import com.househunt.ai.agent.HouseSearchService.HouseSummary;
import com.househunt.common.NotFoundException;
import com.househunt.house.HouseDto;
import com.househunt.house.HouseStatus;
import com.househunt.visit.VisitRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only query logic behind the agent tools and the MCP tools. Validates model-supplied arguments (ids, enums,
 * ranges) and returns small records. Deliberately has no {@code @Tool} annotations: Spring AI's
 * {@code MethodToolCallbackProvider} only discovers methods declared directly on the tool object's class, so each
 * tool class ({@link VisitPlannerTools}, {@code McpHouseTools}) declares its own annotated methods and delegates here.
 */
@Component
public class HouseQueries {

    public record VisitInfo(Instant arrivedAt, Instant leftAt, Long minutes, String source) {
    }

    /**
     * One house as the agent and MCP clients see it. No contact name or phone (they stay in the app, F-30); every
     * free-text field goes through {@link ContactRedactor}: label, checklist keys, listing URL and notes lose the
     * whole name and every name part, address, street and locality the whole name.
     */
    public record HouseDetails(UUID id, String label, String address, String street, String locality, double lat,
                               double lon, HouseStatus status, Long price, String priceType, Integer bedrooms,
                               Integer rating, Map<String, Integer> checklist, String listingUrl, String notes) {
        static final int NOTES_MAX = 2000;

        static HouseDetails of(HouseDto h) {
            var r = ContactRedactor.forHouse(h);
            var notes = h.notes() == null || h.notes().length() <= NOTES_MAX ? h.notes()
                    : h.notes().substring(0, NOTES_MAX) + " …";
            Map<String, Integer> checklist = null;
            if (h.checklist() != null) {
                var m = new LinkedHashMap<String, Integer>();
                h.checklist().forEach((k, v) -> m.put(r.freeText(k), v));
                checklist = m;
            }
            return new HouseDetails(h.id(), r.freeText(h.label()), r.place(h.address()), r.place(h.street()),
                    r.place(h.locality()), h.lat(), h.lon(), h.status(), h.price(), h.priceType(), h.bedrooms(),
                    h.rating(), checklist, r.freeText(h.listingUrl()), r.freeText(notes));
        }
    }

    private final HouseSearchService search;
    private final VisitRepository visits;

    public HouseQueries(HouseSearchService search, VisitRepository visits) {
        this.search = search;
        this.visits = visits;
    }

    public List<HouseSummary> searchHouses(String text, String status, String priceType, Long maxPrice,
                                           Integer minBedrooms, Integer minRating, Integer limit) {
        return search.search(new Criteria(text, parseStatus(status), parsePriceType(priceType), null, maxPrice,
                minBedrooms, null, minRating, limit));
    }

    public List<HouseSummary> nearbyHouses(double lat, double lon, Double radiusMeters) {
        return search.nearby(lat, lon, radiusMeters == null ? 1000 : radiusMeters);
    }

    public HouseDetails houseDetails(String houseId) {
        var h = search.details(parseId(houseId));
        if (h.deleted()) throw new NotFoundException("House " + houseId + " not found");
        return HouseDetails.of(h);
    }

    public List<VisitInfo> visitHistory(String houseId) {
        return visits.findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(parseId(houseId)).stream()
                .limit(20)
                .map(v -> new VisitInfo(v.getArrivedAt(), v.getLeftAt(),
                        v.getLeftAt() == null ? null : Duration.between(v.getArrivedAt(), v.getLeftAt()).toMinutes(),
                        v.getSource().name()))
                .toList();
    }

    static UUID parseId(String id) {
        try {
            return UUID.fromString(id == null ? "" : id.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("houseId must be a UUID taken from searchHouses/nearbyHouses results");
        }
    }

    static HouseStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return HouseStatus.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be NEW, SHORTLISTED or REJECTED");
        }
    }

    static String parsePriceType(String s) {
        if (s == null || s.isBlank()) return null;
        var v = s.strip().toUpperCase(Locale.ROOT);
        if (!v.equals("RENT") && !v.equals("SALE")) throw new IllegalArgumentException("priceType must be RENT or SALE");
        return v;
    }
}
