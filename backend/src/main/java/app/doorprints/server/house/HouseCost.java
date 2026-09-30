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

package app.doorprints.server.house;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Stream;

/**
 * What a house costs beyond its price (docs/11 section 5.21, slice 1a of the Sprint 4b data model): the same
 * eleven optional fields on the phone, in the browser, in a backup and here. Whole rupees are {@link Long}, months
 * {@link Integer}; {@code availableFrom} is a calendar date {@code YYYY-MM-DD} with no time zone. The rent's
 * deposit and brokerage may be given in rupees or in months of rent (both allowed; the clients' arithmetic lets
 * the rupees win). Nothing on the server computes with these: it stores, validates, syncs and exports them, and
 * the AI index reads them (all but {@code myOffer}, a negotiation being the person's own; docs/11 5.30 item 5).
 *
 * <p>Null semantics (docs/schemas/README.md section 4): a field with no value is left out, on the sync API as in a
 * backup, so a client reads absent and null alike; a cost with no field at all is written absent, never {@code {}},
 * and an empty object read is the same as no cost ({@link #orNull}). The entity keeps the object as compact JSON
 * text (a {@code jsonb} column, like a record payload of slice 0), which {@link #write} and {@link #parse} produce.
 *
 * <p>The property order is part of the backup contract (docs/schemas/README.md section 8.1), so it is pinned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"deposit", "depositMonths", "maintenance", "maintenanceIncluded", "brokerage",
        "brokerageMonths", "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice"})
public record HouseCost(
        @Min(0) @Max(MAX_RUPEES) Long deposit,
        @Min(0) @Max(MAX_MONTHS) Integer depositMonths,
        @Min(0) @Max(MAX_RUPEES) Long maintenance,
        Boolean maintenanceIncluded,
        @Min(0) @Max(MAX_RUPEES) Long brokerage,
        @Min(0) @Max(MAX_MONTHS) Integer brokerageMonths,
        @Min(0) @Max(MAX_MONTHS) Integer lockInMonths,
        @Min(0) @Max(MAX_MONTHS) Integer noticeMonths,
        @Pattern(regexp = DATE_PATTERN) String availableFrom,
        @Min(0) @Max(MAX_RUPEES) Long myOffer,
        @Min(0) @Max(MAX_RUPEES) Long agreedPrice
) {
    /** One lakh crore: more than any house in the country costs, small enough to stay a sane {@code long}. */
    public static final long MAX_RUPEES = 1_000_000_000_000L;
    /** Ten years of deposit, brokerage, lock-in or notice. */
    public static final int MAX_MONTHS = 120;
    public static final String DATE_PATTERN = "\\d{4}-\\d{2}-\\d{2}";

    /** Its own mapper: the entity's text form must not follow the web layer's settings (nulls, dates, indentation). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** True when no field is set: such a cost is written absent, never as {@code {}}. */
    @JsonIgnore
    public boolean isEmpty() {
        return Stream.of(deposit, depositMonths, maintenance, maintenanceIncluded, brokerage, brokerageMonths,
                lockInMonths, noticeMonths, availableFrom, myOffer, agreedPrice).allMatch(v -> v == null);
    }

    /** {@code null} for a null or empty cost (an empty object read is no cost), otherwise the cost itself. */
    public static HouseCost orNull(HouseCost cost) {
        return cost == null || cost.isEmpty() ? null : cost;
    }

    /**
     * The shape check above lets {@code 2026-02-30} through; this is the calendar check, a getter constraint so that
     * Bean Validation runs it with the rest of the record (the message names the field the person has to fix).
     */
    @JsonIgnore
    @AssertTrue(message = "availableFrom must be a calendar date, YYYY-MM-DD")
    public boolean isAvailableFromADate() {
        return availableFrom == null || isCalendarDate(availableFrom);
    }

    /** {@code YYYY-MM-DD} and a real day of the calendar ({@code 2026-02-30} is not one). */
    public static boolean isCalendarDate(String value) {
        if (value == null || !value.matches(DATE_PATTERN)) return false;
        try {
            LocalDate.parse(value); // ISO_LOCAL_DATE resolves strictly
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /**
     * The same ranges as the annotations, for a reader that has no validator at hand (the backup import): the
     * names of the fields out of range, in property order, empty when the cost is fine. Ranges are the contract of
     * docs/11 section 5.21: rupees 0..{@value #MAX_RUPEES}, months 0..{@value #MAX_MONTHS}, a calendar date.
     */
    public List<String> problems() {
        var out = new java.util.ArrayList<String>();
        rupees(out, "deposit", deposit);
        months(out, "depositMonths", depositMonths);
        rupees(out, "maintenance", maintenance);
        rupees(out, "brokerage", brokerage);
        months(out, "brokerageMonths", brokerageMonths);
        months(out, "lockInMonths", lockInMonths);
        months(out, "noticeMonths", noticeMonths);
        if (availableFrom != null && !isCalendarDate(availableFrom)) out.add("availableFrom");
        rupees(out, "myOffer", myOffer);
        rupees(out, "agreedPrice", agreedPrice);
        return out;
    }

    private static void rupees(List<String> out, String name, Long value) {
        if (value != null && (value < 0 || value > MAX_RUPEES)) out.add(name);
    }

    private static void months(List<String> out, String name, Integer value) {
        if (value != null && (value < 0 || value > MAX_MONTHS)) out.add(name);
    }

    /** The entity's text: compact JSON with the set fields in property order, or {@code null} for no cost. */
    public static String write(HouseCost cost) {
        var c = orNull(cost);
        return c == null ? null : JSON.writeValueAsString(c);
    }

    /** The cost the entity holds, or {@code null} for no text or an empty object. */
    public static HouseCost parse(String json) {
        return json == null || json.isBlank() ? null : orNull(JSON.readValue(json, HouseCost.class));
    }
}
