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
import app.doorprints.server.ai.kind.AiKind;
import app.doorprints.server.ai.kind.AiKinds;
import app.doorprints.server.ai.rag.KindDocuments;
import app.doorprints.server.common.BadRequestException;
import app.doorprints.server.house.House;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseService;
import app.doorprints.server.house.HouseStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final AiKinds kinds;

    public HouseSearchService(HouseService houses) {
        this(houses, AiKinds.OFF);
    }

    @Autowired
    public HouseSearchService(HouseService houses, AiKinds kinds) {
        this.houses = houses;
        this.kinds = kinds;
    }

    /**
     * The candidate as the Plan tools see it (kinds PR 7, S4b-BL-211): a house as it is; a candidate of a built-in kind
     * other than the house, while the server's developer flag allows it, only with what its kind's {@code ai.plan} slot
     * names ({@link AiKind#view}), so a filter on a field the kind does not show (a school's hidden price, its BHK)
     * never keeps it and its summary has none.
     */
    HouseDto forPlan(HouseDto h) {
        var k = kinds.of(h.kind());
        return k == null ? h : k.view(h, k.plan());
    }

    /** As {@link #forPlan}, through the {@code ai.ask} slot: what {@code houseDetails} may give a model of a candidate. */
    HouseDto forDetails(HouseDto h) {
        var k = kinds.of(h.kind());
        return k == null ? h : k.view(h, k.ask());
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
                               Double distanceMeters,
                               @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> extras) {
        /** A house's summary: {@code extras} null, and then neither the JSON nor {@link #toString} has the key (S4b-BL-270). */
        public HouseSummary(UUID id, String label, String locality, String street, HouseStatus status, Long price,
                            String priceType, Integer bedrooms, Integer rating, double lat, double lon,
                            Double distanceMeters) {
            this(id, label, locality, street, status, price, priceType, bedrooms, rating, lat, lon, distanceMeters, null);
        }

        public static HouseSummary of(HouseDto h) {
            return of(h, null);
        }

        /**
         * {@code extras}: the kind's extras its {@code ai.plan} slot names, as {@link KindDocuments#extras} reads them (words
         * to value, at most the slot's), for a candidate of a built-in kind other than the house; null for a house.
         */
        public static HouseSummary of(HouseDto h, Map<String, String> extras) {
            var r = ContactRedactor.forHouse(h);
            return new HouseSummary(h.id(), r.freeText(h.label()), r.place(h.locality()), r.place(h.street()), h.status(),
                    h.price(), h.priceType(), h.bedrooms(), h.rating(), h.lat(), h.lon(), h.distanceMeters(), extras);
        }

        /** The text the house pins hash: today's record text while {@code extras} is null (a house). */
        @Override
        public String toString() {
            var s = "HouseSummary[id=" + id + ", label=" + label + ", locality=" + locality + ", street=" + street
                    + ", status=" + status + ", price=" + price + ", priceType=" + priceType + ", bedrooms=" + bedrooms
                    + ", rating=" + rating + ", lat=" + lat + ", lon=" + lon + ", distanceMeters=" + distanceMeters;
            return extras == null ? s + "]" : s + ", extras=" + extras + "]";
        }
    }

    /** The summary of a candidate already seen through {@link #forPlan}: a kind's carries its {@code ai.plan} extras. */
    HouseSummary summary(HouseDto viewed) {
        var k = kinds.of(viewed.kind());
        return k == null ? HouseSummary.of(viewed) : HouseSummary.of(viewed, KindDocuments.extras(viewed, k, k.plan()));
    }

    /** The extras {@code houseDetails} gives of a candidate seen through {@link #forDetails} (its {@code ai.ask} slot), null for a house. */
    Map<String, String> detailsExtras(HouseDto viewed) {
        var k = kinds.of(viewed.kind());
        return k == null ? null : KindDocuments.extras(viewed, k, k.ask());
    }

    /**
     * Finds saved houses that match every given filter, newest edit first and equal timestamps by id.
     * The result is capped: the limit defaults to {@link #DEFAULT_RESULTS} and is clamped to 1..{@link #MAX_RESULTS}. Summaries are
     * redacted, see {@link HouseSummary#of}.
     */
    public List<HouseSummary> search(Criteria c) {
        return search(c, null);
    }

    /**
     * As {@link #search(Criteria)}, over the candidates of one kind only (S4b-BL-290; {@code kind} null is every candidate,
     * as before). The kind is compared with the stored row's own kind and was checked against {@code app.kinds.available}
     * by the caller; it is never part of a query.
     */
    public List<HouseSummary> search(Criteria c, String kind) {
        int limit = c == null || c.limit() == null ? DEFAULT_RESULTS : Math.clamp(c.limit(), 1, MAX_RESULTS);
        return houses.list(null).stream()
                .filter(h -> inKind(h, kind))
                .map(this::forPlan)
                .filter(h -> matches(h, c))
                .sorted(NEWEST_FIRST)
                .limit(limit)
                .map(this::summary)
                .toList();
    }

    /**
     * Houses within the radius of a point; the radius is clamped to 1..5000 m.
     * @throws IllegalArgumentException if the latitude or longitude is out of range
     */
    public List<HouseSummary> nearby(double lat, double lon, double radiusMeters) {
        return nearby(lat, lon, radiusMeters, null);
    }

    /** As {@link #nearby(double, double, double)}, over the candidates of one kind only (S4b-BL-290; null is every candidate). */
    public List<HouseSummary> nearby(double lat, double lon, double radiusMeters, String kind) {
        if (!(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180)) throw new BadRequestException("invalid coordinates");
        return houses.nearby(lat, lon, Math.clamp(radiusMeters, 1, 5000)).stream().filter(h -> inKind(h, kind))
                .sorted(NEAREST_FIRST).map(this::forPlan).map(this::summary).toList();
    }

    /** Whether {@code h} is of {@code kind}: every candidate when {@code kind} is null. */
    public static boolean inKind(HouseDto h, String kind) {
        return kind == null || kind.equals(AiKinds.kindOf(h.kind()));
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
