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
import app.doorprints.server.ai.agent.HouseSearchService.Criteria;
import app.doorprints.server.ai.agent.HouseSearchService.HouseSummary;
import app.doorprints.server.common.NotFoundException;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.visit.VisitRepository;
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

    /**
     * One past visit as the model sees it: arrival, departure and the minutes between them (null while the visit is
     * still open).
     */
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

    /**
     * Lets the model filter the saved houses by text status and price type; both are parsed here, then the search
     * runs on the user's live houses.
     * Unknown status or price type values raise IllegalArgumentException, whose message names the allowed values so
     * the model can retry.
     */
    public List<HouseSummary> searchHouses(String text, String status, String priceType, Long maxPrice,
                                           Integer minBedrooms, Integer minRating, Integer limit) {
        return search.search(new Criteria(text, parseStatus(status), parsePriceType(priceType), null, maxPrice,
                minBedrooms, null, minRating, limit));
    }

    /**
     * Saved houses around a point; the radius defaults to 1000 m when the model leaves it out.
     */
    public List<HouseSummary> nearbyHouses(double lat, double lon, Double radiusMeters) {
        return search.nearby(lat, lon, radiusMeters == null ? 1000 : radiusMeters);
    }

    /**
     * One house with contact names and phone numbers removed from every free-text field (F-30), safe to hand to the
     * model.
     * @throws NotFoundException if the house has been deleted
     */
    public HouseDetails houseDetails(String houseId) {
        var h = search.details(parseId(houseId));
        if (h.deleted()) throw new NotFoundException("House " + houseId + " not found");
        return HouseDetails.of(h);
    }

    /**
     * The last 20 visits to a house, newest first, so the model can say how long the user stayed. Deleted visits are
     * left out.
     */
    public List<VisitInfo> visitHistory(String houseId) {
        return visits.findByDeletedFalseAndHouseIdOrderByArrivedAtDesc(parseId(houseId)).stream()
                .limit(20)
                .map(v -> new VisitInfo(v.getArrivedAt(), v.getLeftAt(),
                        v.getLeftAt() == null ? null : Duration.between(v.getArrivedAt(), v.getLeftAt()).toMinutes(),
                        v.getSource().name()))
                .toList();
    }

    /**
     * Reads a house id given as text by the model. The error message tells the model where valid ids come from.
     */
    static UUID parseId(String id) {
        try {
            return UUID.fromString(id == null ? "" : id.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("houseId must be a UUID taken from searchHouses/nearbyHouses results");
        }
    }

    /**
     * Case-insensitive status name; null or blank means no filter.
     */
    static HouseStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return HouseStatus.valueOf(s.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be NEW, SHORTLISTED, REJECTED, TAKEN or NOT_CHOSEN");
        }
    }

    /**
     * Normalises RENT or SALE to upper case; null or blank means no filter.
     */
    static String parsePriceType(String s) {
        if (s == null || s.isBlank()) return null;
        var v = s.strip().toUpperCase(Locale.ROOT);
        if (!v.equals("RENT") && !v.equals("SALE")) throw new IllegalArgumentException("priceType must be RENT or SALE");
        return v;
    }
}
