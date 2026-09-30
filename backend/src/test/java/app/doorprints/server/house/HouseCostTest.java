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

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The cost object of slice 1a: its text form for the entity, its null semantics and its ranges. */
class HouseCostTest {

    private static final HouseCost EMPTY = new HouseCost(null, null, null, null, null, null, null, null, null, null,
            null);

    @Test
    void aCostWithNoFieldIsWrittenAbsentAndAnEmptyObjectReadsAsNoCost() {
        assertThat(EMPTY.isEmpty()).isTrue();
        assertThat(HouseCost.write(EMPTY)).isNull();
        assertThat(HouseCost.write(null)).isNull();
        assertThat(HouseCost.orNull(EMPTY)).isNull();
        assertThat(HouseCost.parse("{}")).isNull();
        assertThat(HouseCost.parse(null)).isNull();
    }

    /** The entity text is compact, leaves absent fields out and keeps the contract's property order. */
    @Test
    void theEntityTextIsCompactInPropertyOrderAndRoundTrips() {
        var cost = new HouseCost(64000L, null, 2500L, false, null, 1, 11, 2, "2026-10-15", 30000L, 31000L);
        var json = HouseCost.write(cost);
        assertThat(json).isEqualTo("{\"deposit\":64000,\"maintenance\":2500,\"maintenanceIncluded\":false,"
                + "\"brokerageMonths\":1,\"lockInMonths\":11,\"noticeMonths\":2,\"availableFrom\":\"2026-10-15\","
                + "\"myOffer\":30000,\"agreedPrice\":31000}");
        assertThat(HouseCost.parse(json)).isEqualTo(cost);
        assertThat(HouseCost.write(new HouseCost(null, 2, null, null, 25000L, null, null, null, null, null, null)))
                .isEqualTo("{\"depositMonths\":2,\"brokerage\":25000}");
    }

    @Test
    void availableFromIsACalendarDate() {
        assertThat(HouseCost.isCalendarDate("2026-10-15")).isTrue();
        assertThat(HouseCost.isCalendarDate("2028-02-29")).isTrue(); // a leap day
        assertThat(HouseCost.isCalendarDate("2026-02-30")).isFalse();
        assertThat(HouseCost.isCalendarDate("2026-13-01")).isFalse();
        assertThat(HouseCost.isCalendarDate("15-10-2026")).isFalse();
        assertThat(HouseCost.isCalendarDate("2026-10-15T00:00")).isFalse();
        assertThat(HouseCost.isCalendarDate("")).isFalse();
        assertThat(EMPTY.isAvailableFromADate()).isTrue();
        assertThat(new HouseCost(null, null, null, null, null, null, null, null, "2026-02-30", null, null)
                .isAvailableFromADate()).isFalse();
    }

    /** What the PUT's 400 comes from: the annotations, the calendar check included, cascaded from the house. */
    @Test
    void beanValidationCascadesFromTheHouseAndRunsTheCalendarCheck() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var bad = new HouseCost(-1L, null, null, null, null, 121, null, null, "2026-02-30", null, null);
            var house = new HouseDto(UUID.randomUUID(), "Bad", null, null, null, 12.9, 77.6, HouseStatus.NEW, null,
                    null, null, null, null, null, null, null, 0, "GUESS", bad, null, Map.of(), null, null, false, 0, null);
            assertThat(validator.validate(house)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("areaSqft", "locationSource", "cost.deposit", "cost.brokerageMonths",
                            "cost.availableFromADate");
            assertThat(validator.validate(bad)).extracting(ConstraintViolation::getMessage)
                    .contains("availableFrom must be a calendar date, YYYY-MM-DD");
            var fine = new HouseCost(64000L, null, 2500L, false, null, 1, 11, 2, "2026-10-15", 30000L, 31000L);
            assertThat(validator.validate(fine)).isEmpty();
        }
    }

    /** The ranges a reader without a validator (the backup import) checks, named in property order. */
    @Test
    void problemsNameEveryFieldOutOfRange() {
        assertThat(EMPTY.problems()).isEmpty();
        assertThat(new HouseCost(0L, 0, 0L, true, HouseCost.MAX_RUPEES, 120, 120, 0, "2026-10-15", 0L, 0L)
                .problems()).isEmpty();
        assertThat(new HouseCost(-1L, 121, 1_000_000_000_001L, null, -5L, -1, 200, 121, "2026-02-30", -1L,
                HouseCost.MAX_RUPEES + 1).problems())
                .containsExactly("deposit", "depositMonths", "maintenance", "brokerage", "brokerageMonths",
                        "lockInMonths", "noticeMonths", "availableFrom", "myOffer", "agreedPrice");
    }
}
