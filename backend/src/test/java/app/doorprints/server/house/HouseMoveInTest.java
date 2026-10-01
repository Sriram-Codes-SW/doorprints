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
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 5: a house's moving-in card, its limits, its text form for the entity, the new statuses and the tombstone. */
class HouseMoveInTest {

    private static HouseMoveIn.Item item(String id) {
        return new HouseMoveIn.Item(id, "Police verification done", null, 0);
    }

    private static HouseMoveIn moveIn(List<HouseMoveIn.Item> items) {
        return new HouseMoveIn(1790812800000L, "Keys handed over", items);
    }

    private static HouseDto house(HouseMoveIn moveIn) {
        return new HouseDto(UUID.randomUUID(), "Green View", null, null, null, 12.9, 77.6, HouseStatus.TAKEN, null, null,
                null, null, null, null, null, null, null, null, null, null, null, moveIn, null, null, Map.of(), null, null,
                false, 0, null);
    }

    private static List<String> violations(Validator validator, HouseMoveIn moveIn) {
        return validator.validate(house(moveIn)).stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
    }

    @Test
    void theTwoNewStatusesExistAndAHouseCanHoldThem() {
        assertThat(HouseStatus.valueOf("TAKEN")).isEqualTo(HouseStatus.TAKEN);
        assertThat(HouseStatus.valueOf("NOT_CHOSEN")).isEqualTo(HouseStatus.NOT_CHOSEN);
        var entity = new House(UUID.randomUUID());
        house(null).applyTo(entity);
        assertThat(HouseDto.from(entity).status()).isEqualTo(HouseStatus.TAKEN);
    }

    @Test
    void aGoodMoveInOrNoneAndTheLimitsAreInclusive() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(violations(validator, null)).isEmpty();
            assertThat(violations(validator, new HouseMoveIn(null, null, null))).isEmpty();
            assertThat(violations(validator, moveIn(List.of(item("mi_agreement"))))).isEmpty();
            var edge = new HouseMoveIn(1L, "n".repeat(2000), List.of(
                    new HouseMoveIn.Item("a.b_c-9".repeat(9) + "x", "t".repeat(200), true, 0)));
            assertThat(edge.items().getFirst().id()).hasSize(64);
            assertThat(violations(validator, edge)).isEmpty();
            var thirty = new ArrayList<HouseMoveIn.Item>();
            for (int i = 0; i < HouseMoveIn.MAX_ITEMS; i++) thirty.add(item("i" + i));
            assertThat(violations(validator, moveIn(thirty))).isEmpty();
        }
    }

    @Test
    void eachFieldOutOfRangeIsNamed() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(violations(validator, new HouseMoveIn(0L, null, null))).containsExactly("moveIn.date");
            assertThat(violations(validator, new HouseMoveIn(-5L, null, null))).containsExactly("moveIn.date");
            assertThat(violations(validator, new HouseMoveIn(null, "n".repeat(2001), null)))
                    .containsExactly("moveIn.notes");
            var bad = List.of(
                    new HouseMoveIn.Item("a", null, null, 0),
                    new HouseMoveIn.Item("a", "  ", null, 0),
                    new HouseMoveIn.Item("a", "t".repeat(201), null, 0),
                    new HouseMoveIn.Item("a", "Text", null, -1));
            var paths = List.of("moveIn.items[0].text", "moveIn.items[0].text", "moveIn.items[0].text",
                    "moveIn.items[0].sort");
            for (int i = 0; i < bad.size(); i++) {
                assertThat(violations(validator, moveIn(List.of(bad.get(i))))).as("case %d", i)
                        .containsExactly(paths.get(i));
            }
        }
    }

    @Test
    void aBadItemIdIsRefused() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var id : new String[]{null, "", "x".repeat(65), "has space", "a/b", "..", "."}) {
                assertThat(violations(validator, moveIn(List.of(item(id))))).as("id '%s'", id)
                        .containsExactly("moveIn.items[0].id");
            }
        }
    }

    @Test
    void aThirtyFirstItemAndARepeatedIdAreRefused() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var many = new ArrayList<HouseMoveIn.Item>();
            for (int i = 0; i <= HouseMoveIn.MAX_ITEMS; i++) many.add(item("i" + i));
            assertThat(violations(validator, moveIn(many))).containsExactly("moveIn.items");
            assertThat(violations(validator, moveIn(List.of(item("same"), item("same")))))
                    .containsExactly("moveIn.itemIdsUnique");
            assertThat(validator.validate(house(moveIn(List.of(item("same"), item("same"))))))
                    .extracting(v -> v.getMessage()).containsExactly("items must not repeat an id");
            assertThat(violations(validator, moveIn(List.of(item("a"), item("A"))))).isEmpty();
        }
    }

    @Test
    void aMoveInWithNoDateNoNotesAndNoItemsIsNoMoveIn() {
        assertThat(HouseMoveIn.write(null)).isNull();
        assertThat(HouseMoveIn.write(new HouseMoveIn(null, null, null))).isNull();
        assertThat(HouseMoveIn.write(new HouseMoveIn(null, "", List.of()))).isNull();
        assertThat(HouseMoveIn.parse("{}")).isNull();
        assertThat(HouseMoveIn.parse(null)).isNull();
        assertThat(HouseMoveIn.parse(" ")).isNull();
    }

    @Test
    void theTextFormIsCompactInPropertyOrderAndDoneIsWrittenOnlyWhenTrue() {
        var m = new HouseMoveIn(1790812800000L, "Keys handed over", List.of(
                new HouseMoveIn.Item("mi_agreement", "Rental agreement signed and registered", true, 0),
                new HouseMoveIn.Item("mi_police", "Police verification done", false, 1)));
        var json = HouseMoveIn.write(m);
        assertThat(json).isEqualTo("{\"date\":1790812800000,\"notes\":\"Keys handed over\",\"items\":["
                + "{\"id\":\"mi_agreement\",\"text\":\"Rental agreement signed and registered\",\"done\":true,\"sort\":0},"
                + "{\"id\":\"mi_police\",\"text\":\"Police verification done\",\"sort\":1}]}");
        var back = HouseMoveIn.parse(json);
        assertThat(back.items()).hasSize(2);
        assertThat(back.doneCount()).isEqualTo(1);
        assertThat(HouseMoveIn.write(new HouseMoveIn(null, null, List.of(item("a"))))).isEqualTo(
                "{\"items\":[{\"id\":\"a\",\"text\":\"Police verification done\",\"sort\":0}]}");
    }

    @Test
    void problemsNameEveryFieldOutOfRangeAndNeverEchoUserText() {
        assertThat(HouseMoveIn.problems(null)).isEmpty();
        assertThat(HouseMoveIn.problems(moveIn(List.of(item("a"), item("b"))))).isEmpty();
        var many = new ArrayList<HouseMoveIn.Item>();
        for (int i = 0; i <= HouseMoveIn.MAX_ITEMS; i++) many.add(item("i" + i));
        assertThat(HouseMoveIn.problems(moveIn(many))).containsExactly("moveIn.items has more than 30 items");
        assertThat(HouseMoveIn.problems(moveIn(List.of(item("a"), item("a")))))
                .containsExactly("moveIn.items[1].id is repeated");
        assertThat(HouseMoveIn.problems(new HouseMoveIn(0L, "secret ".repeat(400), List.of(
                new HouseMoveIn.Item("a b", "x".repeat(201), null, -1)))))
                .containsExactly("moveIn.date is out of range", "moveIn.notes is out of range",
                        "moveIn.items[0].id is out of range", "moveIn.items[0].text is out of range",
                        "moveIn.items[0].sort is out of range");
        assertThat(String.join(" ", HouseMoveIn.problems(new HouseMoveIn(null, "secret ".repeat(400), null))))
                .doesNotContain("secret");
    }

    @Test
    void theMoveInGoesToTheEntityAndBackAndAPutWithoutItClearsIt() {
        var entity = new House(UUID.randomUUID());
        var m = moveIn(List.of(item("a"), item("b")));
        house(m).applyTo(entity);
        assertThat(entity.getMoveIn()).startsWith("{\"date\":1790812800000");
        assertThat(HouseDto.from(entity).moveIn()).isEqualTo(m);
        house(new HouseMoveIn(null, "", List.of())).applyTo(entity);
        assertThat(entity.getMoveIn()).isNull();
        assertThat(HouseDto.from(entity).moveIn()).isNull();
    }

    /** F-16 and PRV-005: a tombstone keeps no move-in, whose notes and items are the person's own words. */
    @Test
    void aTombstoneBlanksTheMoveIn() {
        var entity = new House(UUID.randomUUID());
        house(moveIn(List.of(item("a")))).applyTo(entity);
        entity.purgeContent();
        assertThat(entity.getMoveIn()).isNull();
        assertThat(HouseDto.from(entity).moveIn()).isNull();
    }
}
