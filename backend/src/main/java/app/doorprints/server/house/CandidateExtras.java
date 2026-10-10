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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * A candidate's typed extras (docs/03 ADR-36 §18.4 door 5, the format reset of S4b-BL-206): the {@code extras} object
 * on the wire and in a backup, keyed by extra id. For the house kind ({@code docs/schemas/kinds/house.json}) these are
 * {@code bedrooms}, {@code areaSqft}, {@code floor} and the eleven cost fields of docs/11 section 5.21, with the four
 * rupee fields and the two offers as {@link Money} ({@code deposit} and {@code brokerage} {@code once},
 * {@code maintenance} {@code month}; {@code myOffer} and {@code agreedPrice} as often as the candidate's own money).
 *
 * <p><b>An id this server does not know is kept</b> ({@link #other}) and written back after the known ones, in id
 * order, so a newer app's field survives this server's sync. Such an id must be a field id
 * ({@value #ID_PATTERN}) and its value a number, a string of at most {@value #MAX_TEXT} characters, a boolean or a
 * money object; at most {@value #MAX_OTHER} of them. Anything else is a 400 on the sync API and refuses an import.
 *
 * <p>Null semantics as for {@link HouseCost}: a field with no value is left out; extras with no field at all are
 * written absent, never {@code {}}, and an empty object read is the same as none ({@link #orNull}). The entity keeps
 * the object as compact JSON text (the {@code house.extras jsonb} column, Flyway V1), which {@link #write} and
 * {@link #parse} produce. The property order is the kind file's and part of the backup contract, so it is pinned.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"bedrooms", "areaSqft", "floor", "deposit", "depositMonths", "maintenance", "maintenanceIncluded",
        "brokerage", "brokerageMonths", "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice"})
public final class CandidateExtras {

    /** An extra id ({@code fieldId} of the kind schema). */
    public static final String ID_PATTERN = "^[a-z][A-Za-z0-9]{0,39}$";
    /** At most this many extras this server does not know on one candidate. */
    public static final int MAX_OTHER = 30;
    /** The longest text value of an extra this server does not know. */
    public static final int MAX_TEXT = 2000;
    /** The carpet area's range in square feet, as the clients' {@code HouseValues.areaSqft}. */
    public static final int MIN_AREA = 1;
    public static final int MAX_AREA = 100_000;
    /** The known ids, in the kind file's order. */
    public static final List<String> KNOWN = List.of("bedrooms", "areaSqft", "floor", "deposit", "depositMonths",
            "maintenance", "maintenanceIncluded", "brokerage", "brokerageMonths", "lockInMonths", "noticeMonths",
            "availableFrom", "myOffer", "agreedPrice");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile(ID_PATTERN);

    @JsonProperty @Min(0) private Integer bedrooms;
    @JsonProperty @Min(MIN_AREA) @Max(MAX_AREA) private Integer areaSqft;
    @JsonProperty @Min(House.MIN_FLOOR) @Max(House.MAX_FLOOR) private Integer floor;
    @JsonProperty @Valid private Money deposit;
    @JsonProperty @Min(0) @Max(HouseCost.MAX_MONTHS) private Integer depositMonths;
    @JsonProperty @Valid private Money maintenance;
    @JsonProperty private Boolean maintenanceIncluded;
    @JsonProperty @Valid private Money brokerage;
    @JsonProperty @Min(0) @Max(HouseCost.MAX_MONTHS) private Integer brokerageMonths;
    @JsonProperty @Min(0) @Max(HouseCost.MAX_MONTHS) private Integer lockInMonths;
    @JsonProperty @Min(0) @Max(HouseCost.MAX_MONTHS) private Integer noticeMonths;
    @JsonProperty @Pattern(regexp = HouseCost.DATE_PATTERN) private String availableFrom;
    @JsonProperty @Valid private Money myOffer;
    @JsonProperty @Valid private Money agreedPrice;
    /** The extras this server does not know, by id; kept and written back as they came. */
    private final Map<String, JsonNode> other = new TreeMap<>();

    public CandidateExtras() {
    }

    /**
     * The house's extras from today's typed values: the cost's rupees become money with the field's cadence, the two
     * offers take {@code offerCadence} (the candidate's own money's, or {@code month} when it has none).
     */
    public static CandidateExtras of(Integer bedrooms, Integer areaSqft, Integer floor, HouseCost cost, String offerCadence) {
        var e = new CandidateExtras();
        e.bedrooms = bedrooms;
        e.areaSqft = areaSqft;
        e.floor = floor;
        if (cost != null) {
            var follows = offerCadence == null ? "month" : offerCadence;
            e.deposit = money(cost.deposit(), "once");
            e.depositMonths = cost.depositMonths();
            e.maintenance = money(cost.maintenance(), "month");
            e.maintenanceIncluded = cost.maintenanceIncluded();
            e.brokerage = money(cost.brokerage(), "once");
            e.brokerageMonths = cost.brokerageMonths();
            e.lockInMonths = cost.lockInMonths();
            e.noticeMonths = cost.noticeMonths();
            e.availableFrom = cost.availableFrom();
            e.myOffer = money(cost.myOffer(), follows);
            e.agreedPrice = money(cost.agreedPrice(), follows);
        }
        return e;
    }

    private static Money money(Long amount, String cadence) {
        return amount == null ? null : new Money(amount, cadence);
    }

    private static Long amount(Money m) {
        return m == null ? null : m.amount();
    }

    /** Jackson's way in for an id this class has no field for. */
    @JsonAnySetter
    public void put(String id, JsonNode value) {
        other.put(id, value);
    }

    /** The extras this server does not know, by id, in id order (written after the known ones). */
    @JsonAnyGetter
    public Map<String, JsonNode> other() {
        return Collections.unmodifiableMap(other);
    }

    // Plain accessors, not bean getters, so Jackson reads and writes the annotated fields only.
    public Integer bedrooms() { return bedrooms; }
    public Integer areaSqft() { return areaSqft; }
    public Integer floor() { return floor; }
    public Money myOffer() { return myOffer; }
    public Money agreedPrice() { return agreedPrice; }

    /** The cost fields as the AI documents and the readable copies read them: rupees and months. */
    public HouseCost cost() {
        return HouseCost.orNull(new HouseCost(amount(deposit), depositMonths, amount(maintenance), maintenanceIncluded,
                amount(brokerage), brokerageMonths, lockInMonths, noticeMonths, availableFrom, amount(myOffer),
                amount(agreedPrice)));
    }

    /** True when no extra is set: such extras are written absent, never as {@code {}}. */
    @JsonIgnore
    public boolean isEmpty() {
        return other.isEmpty() && Stream.of(bedrooms, areaSqft, floor, deposit, depositMonths, maintenance,
                maintenanceIncluded, brokerage, brokerageMonths, lockInMonths, noticeMonths, availableFrom, myOffer,
                agreedPrice).allMatch(Objects::isNull);
    }

    /** {@code null} for null or empty extras (an empty object read is none), otherwise the extras themselves. */
    public static CandidateExtras orNull(CandidateExtras extras) {
        return extras == null || extras.isEmpty() ? null : extras;
    }

    /** A getter constraint so that Bean Validation runs {@link #problems} with the field annotations (the 400). */
    @JsonIgnore
    @AssertTrue(message = "extras must hold valid values: a money extra with its amount and cadence, a calendar date, "
            + "and at most 30 unknown ids, each a field id with a number, a short text, a boolean or a money value")
    public boolean isWellFormed() {
        return problems().isEmpty();
    }

    /**
     * The ids whose values break the house kind's rules, in the kind file's order and then the unknown ids: a range
     * (the same as the annotations, for the backup import that has no validator at hand), a money extra without its
     * amount or with a cadence it may not have, a date that is not on the calendar, an unknown id that is not a field
     * id or holds a value of no extras type, or more than {@value #MAX_OTHER} unknown ids ({@code "extras"}).
     * {@code myOffer} and {@code agreedPrice} follow the candidate's own money: any cadence of the vocabulary is
     * accepted for them (the clients read only the amount).
     */
    public List<String> problems() {
        var out = new ArrayList<String>();
        if (bedrooms != null && bedrooms < 0) out.add("bedrooms");
        if (areaSqft != null && (areaSqft < MIN_AREA || areaSqft > MAX_AREA)) out.add("areaSqft");
        if (floor != null && (floor < House.MIN_FLOOR || floor > House.MAX_FLOOR)) out.add("floor");
        fixed(out, "deposit", deposit, "once");
        months(out, "depositMonths", depositMonths);
        fixed(out, "maintenance", maintenance, "month");
        fixed(out, "brokerage", brokerage, "once");
        months(out, "brokerageMonths", brokerageMonths);
        months(out, "lockInMonths", lockInMonths);
        months(out, "noticeMonths", noticeMonths);
        if (availableFrom != null && !HouseCost.isCalendarDate(availableFrom)) out.add("availableFrom");
        follows(out, "myOffer", myOffer);
        follows(out, "agreedPrice", agreedPrice);
        for (var entry : other.entrySet()) {
            if (!ID.matcher(entry.getKey()).matches() || KNOWN.contains(entry.getKey()) || !isExtraValue(entry.getValue())) {
                out.add(entry.getKey());
            }
        }
        if (other.size() > MAX_OTHER) out.add("extras");
        return out;
    }

    private static void fixed(List<String> out, String id, Money m, String cadence) {
        if (m != null && (m.amount() == null || !m.isValid() || !cadence.equals(m.cadence()))) out.add(id);
    }

    private static void follows(List<String> out, String id, Money m) {
        if (m != null && (m.amount() == null || !m.isValid())) out.add(id);
    }

    private static void months(List<String> out, String id, Integer value) {
        if (value != null && (value < 0 || value > HouseCost.MAX_MONTHS)) out.add(id);
    }

    /**
     * A value of one of the six extras types as the wire spells them (docs/03 §18.4 door 5): a finite number (number),
     * a string of at most {@value #MAX_TEXT} characters (text, an enum option id, a date), a boolean (bool), or a money
     * object with exactly an integer amount in range and a cadence of the vocabulary.
     */
    static boolean isExtraValue(JsonNode v) {
        if (v == null || v.isNull()) return false;
        if (v.isBoolean()) return true;
        if (v.isNumber()) return Double.isFinite(v.asDouble());
        if (v.isString()) return v.asString().length() <= MAX_TEXT;
        if (v.isObject()) {
            var amount = v.path("amount");
            var cadence = v.path("cadence");
            return v.size() == 2 && (amount.isInt() || amount.isLong()) && amount.asLong() >= 0
                    && amount.asLong() <= Money.MAX_RUPEES && cadence.isString()
                    && Money.CADENCES.contains(cadence.asString());
        }
        return false;
    }

    /** The entity's text: compact JSON with the set extras in the kind file's order, or {@code null} for none. */
    public static String write(CandidateExtras extras) {
        var e = orNull(extras);
        return e == null ? null : JSON.writeValueAsString(e);
    }

    /** The extras the entity holds, or {@code null} for no text or an empty object. */
    public static CandidateExtras parse(String json) {
        return json == null || json.isBlank() ? null : orNull(JSON.readValue(json, CandidateExtras.class));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CandidateExtras e)) return false;
        return Objects.equals(bedrooms, e.bedrooms) && Objects.equals(areaSqft, e.areaSqft)
                && Objects.equals(floor, e.floor) && Objects.equals(deposit, e.deposit)
                && Objects.equals(depositMonths, e.depositMonths) && Objects.equals(maintenance, e.maintenance)
                && Objects.equals(maintenanceIncluded, e.maintenanceIncluded) && Objects.equals(brokerage, e.brokerage)
                && Objects.equals(brokerageMonths, e.brokerageMonths) && Objects.equals(lockInMonths, e.lockInMonths)
                && Objects.equals(noticeMonths, e.noticeMonths) && Objects.equals(availableFrom, e.availableFrom)
                && Objects.equals(myOffer, e.myOffer) && Objects.equals(agreedPrice, e.agreedPrice)
                && other.equals(e.other);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bedrooms, areaSqft, floor, deposit, depositMonths, maintenance, maintenanceIncluded,
                brokerage, brokerageMonths, lockInMonths, noticeMonths, availableFrom, myOffer, agreedPrice, other);
    }

    @Override
    public String toString() {
        return write(this) == null ? "{}" : write(this);
    }
}
