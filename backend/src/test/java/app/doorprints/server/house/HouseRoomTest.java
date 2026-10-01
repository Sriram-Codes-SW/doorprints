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

/** Slice 1c: a house's rooms, their ranges, their text form for the entity and the tombstone. No database. */
class HouseRoomTest {

    private static HouseRoom room(String id) {
        return new HouseRoom(id, "BEDROOM", "Master bedroom", 396, 366, 4, "Damp patch", 0);
    }

    private static HouseDto house(List<HouseRoom> rooms) {
        return new HouseDto(UUID.randomUUID(), "Green View", null, null, null, 12.9, 77.6, HouseStatus.NEW, null, null,
                null, null, null, null, null, null, null, null, null, rooms, null, null, null, Map.of(), null, null, false, 0,
                null);
    }

    private static List<String> violations(Validator validator, List<HouseRoom> rooms) {
        return validator.validate(house(rooms)).stream().map(v -> v.getPropertyPath().toString()).sorted().toList();
    }

    @Test
    void aGoodListOrNoListPassesAndTheLimitsAreInclusive() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(violations(validator, null)).isEmpty();
            assertThat(violations(validator, List.of())).isEmpty();
            assertThat(violations(validator, List.of(room("c1111111-1111-4111-8111-111111111111")))).isEmpty();
            var edge = new HouseRoom("a.b_c-9".repeat(9) + "x", "OTHER", "n".repeat(60), 0, 5000, 1,
                    "n".repeat(2000), 0);
            assertThat(edge.id()).hasSize(64);
            assertThat(violations(validator, List.of(edge))).isEmpty();
            // Everything but the id and the type may be absent (an unnamed, unmeasured, unchecked room).
            assertThat(violations(validator, List.of(new HouseRoom("r", "STORE", null, null, null, null, null,
                    null)))).isEmpty();
            var thirty = new ArrayList<HouseRoom>();
            for (int i = 0; i < HouseRoom.MAX; i++) thirty.add(room("r" + i));
            assertThat(violations(validator, thirty)).isEmpty();
        }
    }

    @Test
    void eachFieldOutOfRangeIsNamed() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var bad = List.of(
                    new HouseRoom("r", "GARAGE", null, null, null, null, null, null),
                    new HouseRoom("r", null, null, null, null, null, null, null),
                    new HouseRoom("r", "HALL", "n".repeat(61), null, null, null, null, null),
                    new HouseRoom("r", "HALL", null, 5001, null, null, null, null),
                    new HouseRoom("r", "HALL", null, null, -1, null, null, null),
                    new HouseRoom("r", "HALL", null, null, null, 6, null, null),
                    new HouseRoom("r", "HALL", null, null, null, 0, null, null),
                    new HouseRoom("r", "HALL", null, null, null, null, "n".repeat(2001), null),
                    new HouseRoom("r", "HALL", null, null, null, null, null, -1));
            var paths = List.of("rooms[0].type", "rooms[0].type", "rooms[0].name", "rooms[0].lengthCm",
                    "rooms[0].widthCm", "rooms[0].condition", "rooms[0].condition", "rooms[0].notes",
                    "rooms[0].sort");
            for (int i = 0; i < bad.size(); i++) {
                assertThat(violations(validator, List.of(bad.get(i)))).as("case %d", i).containsExactly(paths.get(i));
            }
        }
    }

    @Test
    void aBadIdIsRefused() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var id : new String[]{null, "", "x".repeat(65), "has space", "a/b", "..", "."}) {
                assertThat(violations(validator, List.of(room(id)))).as("id '%s'", id)
                        .containsExactly("rooms[0].id");
            }
        }
    }

    @Test
    void aThirtyFirstRoomAndADuplicateIdAreRefused() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var many = new ArrayList<HouseRoom>();
            for (int i = 0; i <= HouseRoom.MAX; i++) many.add(room("r" + i));
            assertThat(violations(validator, many)).containsExactly("rooms");
            assertThat(violations(validator, List.of(room("same"), room("same")))).containsExactly("roomIdsUnique");
            assertThat(validator.validate(house(List.of(room("same"), room("same")))))
                    .extracting(v -> v.getMessage()).containsExactly("rooms must not repeat an id");
            // Ids are compared exactly: case matters, like a record id.
            assertThat(violations(validator, List.of(room("a"), room("A")))).isEmpty();
        }
    }

    @Test
    void anEmptyListIsNoRoomsAndTheTextFormIsCompactInPropertyOrder() {
        assertThat(HouseRoom.write(List.of())).isNull();
        assertThat(HouseRoom.write(null)).isNull();
        assertThat(HouseRoom.parse("[]")).isNull();
        assertThat(HouseRoom.parse(null)).isNull();
        assertThat(HouseRoom.parse(" ")).isNull();
        var rooms = List.of(room("a"), new HouseRoom("b", "KITCHEN", null, 300, null, null, null, 1));
        var json = HouseRoom.write(rooms);
        assertThat(json).isEqualTo("[{\"id\":\"a\",\"type\":\"BEDROOM\",\"name\":\"Master bedroom\","
                + "\"lengthCm\":396,\"widthCm\":366,\"condition\":4,\"notes\":\"Damp patch\",\"sort\":0},"
                + "{\"id\":\"b\",\"type\":\"KITCHEN\",\"lengthCm\":300,\"sort\":1}]");
        assertThat(HouseRoom.parse(json)).isEqualTo(rooms);
    }

    @Test
    void problemsNameEveryFieldOutOfRangeByIndexAndNeverEchoUserText() {
        assertThat(HouseRoom.problems(null)).isEmpty();
        assertThat(HouseRoom.problems(List.of(room("a"), room("b")))).isEmpty();
        var many = new ArrayList<HouseRoom>();
        for (int i = 0; i <= HouseRoom.MAX; i++) many.add(room("r" + i));
        assertThat(HouseRoom.problems(many)).containsExactly("rooms has more than 30 rooms");
        assertThat(HouseRoom.problems(List.of(room("a"), room("a"))))
                .containsExactly("rooms[1].id is repeated");
        assertThat(HouseRoom.problems(List.of(new HouseRoom("a b", "GARAGE", "n".repeat(61), 5001, -1, 6,
                "n".repeat(2001), -1))))
                .containsExactly("rooms[0].id is out of range", "rooms[0].type is out of range",
                        "rooms[0].name is out of range", "rooms[0].lengthCm is out of range",
                        "rooms[0].widthCm is out of range", "rooms[0].condition is out of range",
                        "rooms[0].notes is out of range", "rooms[0].sort is out of range");
    }

    @Test
    void theRoomsGoToTheEntityAndBackAndAPutWithoutThemClearsThem() {
        var entity = new House(UUID.randomUUID());
        var rooms = List.of(room("a"), room("b"));
        house(rooms).applyTo(entity);
        assertThat(entity.getRooms()).startsWith("[{\"id\":\"a\"");
        assertThat(HouseDto.from(entity).rooms()).isEqualTo(rooms);
        house(List.of()).applyTo(entity);
        assertThat(entity.getRooms()).isNull();
        assertThat(HouseDto.from(entity).rooms()).isNull();
    }

    /** F-16 and PRV-005: a tombstone keeps no room, whose notes may name people. */
    @Test
    void aTombstoneBlanksTheRooms() {
        var entity = new House(UUID.randomUUID());
        house(List.of(room("a"))).applyTo(entity);
        entity.purgeContent();
        assertThat(entity.getRooms()).isNull();
        assertThat(HouseDto.from(entity).rooms()).isNull();
    }
}
