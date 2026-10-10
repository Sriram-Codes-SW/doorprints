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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Set;

/**
 * An amount of money and how often it is paid (docs/03 ADR-36 §18.4 door 7, the format reset of S4b-BL-206): whole
 * rupees ({@code 0..}{@value #MAX_RUPEES}, no currency key: India only) and a cadence, one of {@link #CADENCES}. On
 * the wire {@code {"amount": 32000, "cadence": "month"}}. It replaces the house's {@code price} and {@code priceType}:
 * {@code RENT} is {@code month} and {@code SALE} is {@code once} ({@link #of}, {@link #priceType}).
 *
 * <p>The candidate's own money may carry a cadence and no amount: a house the person marked as a rent before the
 * rent is known (the form's toggle and the question scopes read it). A money extra ({@code deposit}, {@code myOffer}
 * ...) always has its amount ({@link CandidateExtras}). {@code amount} is left out when unknown; {@code cadence} is
 * always written.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"amount", "cadence"})
public record Money(
        @Min(0) @Max(MAX_RUPEES) Long amount,
        @NotNull @Pattern(regexp = CADENCE_PATTERN) String cadence
) {
    /** One lakh crore, as {@link HouseCost#MAX_RUPEES}: more than any house in the country costs. */
    public static final long MAX_RUPEES = HouseCost.MAX_RUPEES;
    /** The cadence vocabulary of the kind schema ({@code docs/schemas/kinds/kind.schema.json}, {@code $defs.cadence}). */
    public static final List<String> CADENCES = List.of("once", "day", "week", "month", "quarter", "year");
    public static final String CADENCE_PATTERN = "once|day|week|month|quarter|year";
    /** The cadences of a house's own money ({@code docs/schemas/kinds/house.json}): a rent or a sale. */
    public static final Set<String> HOUSE_CADENCES = Set.of("month", "once");

    /** The house's money from today's two values: {@code null} when neither is known. */
    public static Money of(Long amount, String priceType) {
        var cadence = cadenceOf(priceType);
        if (amount == null && cadence == null) return null;
        return new Money(amount, cadence == null ? "month" : cadence);
    }

    /** {@code RENT} as {@code month}, {@code SALE} as {@code once}, anything else as {@code null}. */
    public static String cadenceOf(String priceType) {
        if ("RENT".equals(priceType)) return "month";
        if ("SALE".equals(priceType)) return "once";
        return null;
    }

    /** {@code month} as {@code RENT}, {@code once} as {@code SALE}: what the AI documents and filters still read. */
    @JsonIgnore
    public String priceType() {
        if ("month".equals(cadence)) return "RENT";
        if ("once".equals(cadence)) return "SALE";
        return null;
    }

    /** True for an amount in range (or none) and a cadence of the vocabulary. */
    @JsonIgnore
    public boolean isValid() {
        return (amount == null || (amount >= 0 && amount <= MAX_RUPEES)) && cadence != null && CADENCES.contains(cadence);
    }
}
