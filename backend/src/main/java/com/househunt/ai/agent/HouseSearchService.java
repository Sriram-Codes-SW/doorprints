package com.househunt.ai.agent;

import com.househunt.ai.ContactRedactor;
import com.househunt.house.HouseDto;
import com.househunt.house.HouseService;
import com.househunt.house.HouseStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only structured search used by the agent tools and the MCP server. Filters in memory over the live houses:
 * a personal house hunt has tens to low hundreds of rows, so this is simpler and just as fast as dynamic SQL.
 * Needs no AI, so it is always available.
 */
@Service
public class HouseSearchService {

    public static final int MAX_RESULTS = 50;

    private final HouseService houses;

    public HouseSearchService(HouseService houses) {
        this.houses = houses;
    }

    public record Criteria(String text, HouseStatus status, String priceType, Long minPrice, Long maxPrice,
                           Integer minBedrooms, Integer maxBedrooms, Integer minRating, Integer limit) {
    }

    /**
     * Compact view returned to models (agent and MCP tools): no notes and no contact fields; the label loses the whole
     * contact name and every name part, locality and street the whole name ({@link ContactRedactor}, F-30), so search
     * results stay small and low-risk.
     */
    public record HouseSummary(UUID id, String label, String locality, String street, HouseStatus status, Long price,
                               String priceType, Integer bedrooms, Integer rating, double lat, double lon,
                               Double distanceMeters) {
        public static HouseSummary of(HouseDto h) {
            var r = ContactRedactor.forHouse(h);
            return new HouseSummary(h.id(), r.freeText(h.label()), r.place(h.locality()), r.place(h.street()), h.status(),
                    h.price(), h.priceType(), h.bedrooms(), h.rating(), h.lat(), h.lon(), h.distanceMeters());
        }
    }

    public List<HouseSummary> search(Criteria c) {
        int limit = c == null || c.limit() == null ? 20 : Math.clamp(c.limit(), 1, MAX_RESULTS);
        return houses.list(null).stream()
                .filter(h -> matches(h, c))
                .limit(limit)
                .map(HouseSummary::of)
                .toList();
    }

    public List<HouseSummary> nearby(double lat, double lon, double radiusMeters) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new IllegalArgumentException("invalid coordinates");
        return houses.nearby(lat, lon, Math.clamp(radiusMeters, 1, 5000)).stream().map(HouseSummary::of).toList();
    }

    public HouseDto details(UUID id) {
        return houses.get(id);
    }

    static boolean matches(HouseDto h, Criteria c) {
        if (h.deleted()) return false;
        if (c == null) return true;
        if (c.status() != null && h.status() != c.status()) return false;
        if (c.priceType() != null && !c.priceType().equalsIgnoreCase(String.valueOf(h.priceType()))) return false;
        if (c.minPrice() != null && (h.price() == null || h.price() < c.minPrice())) return false;
        if (c.maxPrice() != null && (h.price() == null || h.price() > c.maxPrice())) return false;
        if (c.minBedrooms() != null && (h.bedrooms() == null || h.bedrooms() < c.minBedrooms())) return false;
        if (c.maxBedrooms() != null && (h.bedrooms() == null || h.bedrooms() > c.maxBedrooms())) return false;
        if (c.minRating() != null && (h.rating() == null || h.rating() < c.minRating())) return false;
        if (c.text() != null && !c.text().isBlank()) {
            var needle = c.text().strip().toLowerCase(Locale.ROOT);
            // Match the redacted text, the same text the model may see, so a search cannot confirm a guessed
            // contact name or phone (F-30).
            var r = ContactRedactor.forHouse(h);
            var hay = String.join(" ", nz(r.freeText(h.label())), nz(r.place(h.address())), nz(r.place(h.street())),
                    nz(r.place(h.locality())), nz(r.freeText(h.notes()))).toLowerCase(Locale.ROOT);
            if (!hay.contains(needle)) return false;
        }
        return true;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
