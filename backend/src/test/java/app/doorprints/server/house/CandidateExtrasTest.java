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

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The format reset of S4b-BL-206 (docs/03 ADR-36 §18.4 doors 3, 5 and 7): the candidate's {@link Money} and its
 * {@link CandidateExtras} as the entity stores them, their null semantics, the unknown ids a newer app may send, and
 * the rules Bean Validation and a reader without a validator apply. Expected texts are written out from the kind file
 * ({@code docs/schemas/kinds/house.json}), not computed with the code under test.
 */
class CandidateExtrasTest {

    private static CandidateExtras parse(String json) {
        return CandidateExtras.parse(json);
    }

    @Test
    void noExtrasAreWrittenAbsentAndAnEmptyObjectReadsAsNone() {
        assertThat(CandidateExtras.write(null)).isNull();
        assertThat(CandidateExtras.write(new CandidateExtras())).isNull();
        assertThat(parse("{}")).isNull();
        assertThat(parse(null)).isNull();
        assertThat(parse(" ")).isNull();
        assertThat(CandidateExtras.orNull(new CandidateExtras())).isNull();
    }

    /** The known ids in the kind file's order whatever order they came in, then the unknown ones in id order. */
    @Test
    void theTextKeepsTheKindFilesOrderAndTheUnknownIdsAfterIt() {
        var json = "{\"zLast\":true,\"agreedPrice\":{\"cadence\":\"month\",\"amount\":31000},\"aFirst\":\"x\","
                + "\"floor\":-1,\"bedrooms\":0,\"deposit\":{\"amount\":64000,\"cadence\":\"once\"}}";
        assertThat(CandidateExtras.write(parse(json))).isEqualTo("{\"bedrooms\":0,\"floor\":-1,\"deposit\":"
                + "{\"amount\":64000,\"cadence\":\"once\"},\"agreedPrice\":{\"amount\":31000,\"cadence\":\"month\"},"
                + "\"aFirst\":\"x\",\"zLast\":true}");
        assertThat(parse(json)).isEqualTo(parse(CandidateExtras.write(parse(json))));
    }

    @Test
    void theHousesTodayValuesBecomeExtrasWithTheCadencesOfTheKindFile() {
        var cost = new HouseCost(64000L, 2, 2500L, false, 25000L, 1, 11, 2, "2026-10-15", 30000L, 31000L);
        var extras = CandidateExtras.of(2, 1150, 3, cost, "once");
        assertThat(CandidateExtras.write(extras)).isEqualTo("{\"bedrooms\":2,\"areaSqft\":1150,\"floor\":3,"
                + "\"deposit\":{\"amount\":64000,\"cadence\":\"once\"},\"depositMonths\":2,"
                + "\"maintenance\":{\"amount\":2500,\"cadence\":\"month\"},\"maintenanceIncluded\":false,"
                + "\"brokerage\":{\"amount\":25000,\"cadence\":\"once\"},\"brokerageMonths\":1,\"lockInMonths\":11,"
                + "\"noticeMonths\":2,\"availableFrom\":\"2026-10-15\",\"myOffer\":{\"amount\":30000,\"cadence\":\"once\"},"
                + "\"agreedPrice\":{\"amount\":31000,\"cadence\":\"once\"}}");
        assertThat(extras.cost()).isEqualTo(cost);
        assertThat(extras.bedrooms()).isEqualTo(2);
        assertThat(extras.areaSqft()).isEqualTo(1150);
        assertThat(extras.floor()).isEqualTo(3);
        // With no money of its own, an offer is monthly.
        assertThat(CandidateExtras.write(CandidateExtras.of(null, null, null,
                new HouseCost(null, null, null, null, null, null, null, null, null, 30000L, null), null)))
                .isEqualTo("{\"myOffer\":{\"amount\":30000,\"cadence\":\"month\"}}");
        assertThat(CandidateExtras.of(null, null, null, null, null).cost()).isNull();
    }

    @Test
    void anUnknownIdIsKeptOnlyAsAFieldIdWithAValueOfAnExtrasType() {
        for (var ok : new String[] {"{\"schoolFee\":{\"amount\":90000,\"cadence\":\"year\"}}", "{\"open\":true}",
                "{\"seats\":40}", "{\"ratio\":1.5}", "{\"board\":\"CBSE\"}", "{\"since\":\"2026-01-01\"}"}) {
            assertThat(parse(ok).problems()).as(ok).isEmpty();
            assertThat(CandidateExtras.write(parse(ok))).as(ok).isEqualTo(ok);
        }
        assertThat(parse("{\"Bad-id\":1}").problems()).containsExactly("Bad-id");
        assertThat(parse("{\"list\":[1,2]}").problems()).containsExactly("list");
        assertThat(parse("{\"gone\":null}").problems()).containsExactly("gone");
        assertThat(parse("{\"fee\":{\"amount\":1,\"cadence\":\"hour\"}}").problems()).containsExactly("fee");
        assertThat(parse("{\"fee\":{\"amount\":1.5,\"cadence\":\"year\"}}").problems()).containsExactly("fee");
        assertThat(parse("{\"fee\":{\"amount\":1,\"cadence\":\"year\",\"currency\":\"INR\"}}").problems())
                .containsExactly("fee");
        assertThat(parse("{\"note\":\"" + "x".repeat(CandidateExtras.MAX_TEXT + 1) + "\"}").problems())
                .containsExactly("note");
        var many = new StringBuilder("{");
        for (int i = 0; i <= CandidateExtras.MAX_OTHER; i++) many.append(i == 0 ? "" : ",").append("\"x").append(i).append("\":1");
        assertThat(parse(many.append("}").toString()).problems()).containsExactly("extras");
    }

    /** What a reader without a validator (the backup import of the next step) checks, named in the kind file's order. */
    @Test
    void problemsNameEveryKnownExtraThatBreaksTheHouseKind() {
        var bad = parse("{\"bedrooms\":-1,\"areaSqft\":0,\"floor\":201,\"deposit\":{\"amount\":64000,\"cadence\":\"month\"},"
                + "\"depositMonths\":121,\"maintenance\":{\"cadence\":\"month\"},\"brokerage\":{\"amount\":-1,\"cadence\":\"once\"},"
                + "\"brokerageMonths\":-1,\"lockInMonths\":121,\"noticeMonths\":121,\"availableFrom\":\"2026-02-30\","
                + "\"myOffer\":{\"cadence\":\"month\"},\"agreedPrice\":{\"amount\":1000000000001,\"cadence\":\"once\"}}");
        assertThat(bad.problems()).containsExactly("bedrooms", "areaSqft", "floor", "deposit", "depositMonths",
                "maintenance", "brokerage", "brokerageMonths", "lockInMonths", "noticeMonths", "availableFrom", "myOffer",
                "agreedPrice");
        var edges = parse("{\"bedrooms\":0,\"areaSqft\":100000,\"floor\":-5,\"deposit\":{\"amount\":0,\"cadence\":\"once\"},"
                + "\"lockInMonths\":120,\"myOffer\":{\"amount\":1000000000000,\"cadence\":\"year\"}}");
        assertThat(edges.problems()).isEmpty();
    }

    /** Bean Validation names a value out of range once, by its path; {@code extras.wellFormed} only for the rest. */
    @Test
    void theSyncApiNamesEachBrokenRuleOnce() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var range = TestHouses.dto(UUID.randomUUID(), "Bad", null, null, null, 12.9, 77.6, HouseStatus.NEW, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null, 201, null, null, null,
                    null, false, 0, null);
            assertThat(validator.validate(range)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("extras.floor");
            var shape = new HouseDto(UUID.randomUUID(), "house", "Bad", null, null, null, 12.9, 77.6, HouseStatus.NEW,
                    null, null, null, null, null, null, null, parse("{\"deposit\":{\"amount\":1,\"cadence\":\"month\"}}"),
                    null, null, null, null, null, null, null, false, 0, null);
            assertThat(validator.validate(shape)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactly("extras.wellFormed");
        }
    }

    @Test
    void theKindIsRequiredAndAHousesMoneyIsARentOrASale() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var kind : new String[] {null, "", "House", "h", "9lives", "a".repeat(33)}) {
                var dto = withMoney(kind, null);
                assertThat(validator.validate(dto)).as("%s", kind).extracting(v -> v.getPropertyPath().toString())
                        .containsExactly("kind");
            }
            for (var cadence : new String[] {"month", "once"}) {
                assertThat(validator.validate(withMoney("house", new Money(32000L, cadence)))).as(cadence).isEmpty();
            }
            assertThat(validator.validate(withMoney("house", new Money(null, "month")))).as("a rent not known yet").isEmpty();
            assertThat(validator.validate(withMoney("house", new Money(90000L, "year"))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("moneyOfTheKind");
            assertThat(validator.validate(withMoney("school", new Money(90000L, "year")))).as("another kind").isEmpty();
            // A price whose type is not set: an amount and no cadence (owner, 2026-10-12); neither is refused.
            assertThat(validator.validate(withMoney("house", new Money(1L, null)))).as("a typeless price").isEmpty();
            assertThat(validator.validate(withMoney("house", new Money(null, null))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("money.known");
            assertThat(validator.validate(withMoney("house", new Money(-1L, "month"))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactly("money.amount");
        }
    }

    private static HouseDto withMoney(String kind, Money money) {
        return new HouseDto(UUID.randomUUID(), kind, "x", null, null, null, 0, 0, null, money, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, false, 0, null);
    }

    @Test
    void moneyMapsTodaysPriceTypeBothWays() {
        assertThat(Money.of(32000L, "RENT")).isEqualTo(new Money(32000L, "month"));
        assertThat(Money.of(12500000L, "SALE")).isEqualTo(new Money(12500000L, "once"));
        assertThat(Money.of(null, "RENT")).isEqualTo(new Money(null, "month"));
        assertThat(Money.of(null, null)).isNull();
        // A price without a type is not a monthly rent (owner, 2026-10-12): it keeps no cadence and reads back typeless.
        assertThat(Money.of(1_20_00_000L, null)).isEqualTo(new Money(1_20_00_000L, null));
        assertThat(new Money(1L, null).priceType()).isNull();
        assertThat(new Money(1L, "month").priceType()).isEqualTo("RENT");
        assertThat(new Money(1L, "once").priceType()).isEqualTo("SALE");
        assertThat(new Money(1L, "year").priceType()).isNull();
    }

    /** The entity keeps the money as two columns and the extras as text; a tombstone keeps neither, but its kind. */
    @Test
    void theEntityRoundTripsTheCandidateAndTheTombstoneDropsTheContent() {
        var entity = new House(UUID.randomUUID());
        var dto = new HouseDto(entity.getId(), "house", "Green View", null, null, null, 12.9, 77.6, HouseStatus.NEW,
                new Money(32000L, "month"), null, null, null, null, null, null,
                parse("{\"bedrooms\":2,\"fromNewerApp\":4}"), null, null, null, null, null, null, null, false, 0, null);
        dto.applyTo(entity);
        var back = HouseDto.from(entity);
        assertThat(back.kind()).isEqualTo("house");
        assertThat(back.money()).isEqualTo(new Money(32000L, "month"));
        assertThat(back.extras()).isEqualTo(parse("{\"bedrooms\":2,\"fromNewerApp\":4}"));
        assertThat(back.price()).isEqualTo(32000L);
        assertThat(back.priceType()).isEqualTo("RENT");
        assertThat(back.bedrooms()).isEqualTo(2);
        entity.purgeContent();
        var tombstone = HouseDto.from(entity);
        assertThat(tombstone.kind()).isEqualTo("house");
        assertThat(tombstone.money()).isNull();
        assertThat(tombstone.extras()).isNull();
    }
}
