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

package app.doorprints.server.ai.agent;

import app.doorprints.server.ai.ContactRedactor;
import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseService;
import app.doorprints.server.house.HouseStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
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

    /** The most houses one search (or one tool call) returns. */
    public static final int MAX_RESULTS = 50;

    /**
     * What a search returns when the caller names no limit: the cap, so the planner sees every house a user has up to
     * {@link #MAX_RESULTS} (it used to be 20, which hid 10 of the 30 houses of the golden set, S4b-BL-201). Cost: a
     * summary is about 60 tokens, so a full result is about 3,000 tokens in the tool result and again in each later
     * request of the same plan; the privacy-safe {@link HouseSummary} fields are what keep that small. Beyond the cap
     * the model narrows with the filters.
     */
    public static final int DEFAULT_RESULTS = MAX_RESULTS;

    /**
     * The order every list the tools return has: newest edit first, equal timestamps by id. The house list is already
     * sorted by time, but a database gives no order among equal times, so a limit would keep an arbitrary few houses
     * from run to run.
     */
    private static final Comparator<HouseDto> NEWEST_FIRST = Comparator
            .comparing(HouseDto::updatedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(HouseDto::id);

    /** Nearest first, equal distances by id. */
    private static final Comparator<HouseDto> NEAREST_FIRST = Comparator
            .comparing(HouseDto::distanceMeters, Comparator.nullsLast(Comparator.<Double>naturalOrder()))
            .thenComparing(HouseDto::id);

    private final HouseService houses;

    public HouseSearchService(HouseService houses) {
        this.houses = houses;
    }

    /**
     * Optional filters for {@link #search}; a null field does not filter. Prices are in rupees; the text is matched
     * case-insensitively.
     */
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

    /**
     * Finds saved houses that match every given filter, newest edit first and equal timestamps by id.
     * The result is capped: the limit defaults to {@link #DEFAULT_RESULTS} and is clamped to 1..{@link #MAX_RESULTS}. Summaries are
     * redacted, see {@link HouseSummary#of}.
     */
    public List<HouseSummary> search(Criteria c) {
        int limit = c == null || c.limit() == null ? DEFAULT_RESULTS : Math.clamp(c.limit(), 1, MAX_RESULTS);
        return houses.list(null).stream()
                .filter(h -> matches(h, c))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .map(HouseSummary::of)
                .toList();
    }

    /**
     * Houses within the radius of a point; the radius is clamped to 1..5000 m.
     * @throws IllegalArgumentException if the latitude or longitude is out of range
     */
    public List<HouseSummary> nearby(double lat, double lon, double radiusMeters) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new BadRequestException("invalid coordinates");
        return houses.nearby(lat, lon, Math.clamp(radiusMeters, 1, 5000)).stream().sorted(NEAREST_FIRST).map(HouseSummary::of).toList();
    }

    public HouseDto details(UUID id) {
        return houses.get(id);
    }

    /**
     * True when a live house passes every non-null filter. A null price, bedroom count or rating never satisfies a
     * bound on it. The text filter looks only at the redacted label, address, street, locality and notes.
     */
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
