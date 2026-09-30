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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Slice 1b: a house's {@code brokerId}, its validation, its copy to and from the entity, and the tombstone. */
class HouseBrokerTest {

    private static HouseDto house(String brokerId) {
        return new HouseDto(UUID.randomUUID(), "Green View", null, null, null, 12.9, 77.6, HouseStatus.NEW, null, null,
                null, null, "Ravi Kumar", "+91 98400 11111", null, null, null, null, null, null, null, brokerId, Map.of(), null,
                null, false, 0, null);
    }

    @Test
    void aBrokerIdIsARecordIdOrAbsent() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(house(null))).isEmpty();
            assertThat(validator.validate(house(UUID.randomUUID().toString()))).isEmpty();
            assertThat(validator.validate(house("broker_1.a-b"))).isEmpty();
            assertThat(validator.validate(house("x".repeat(64)))).isEmpty();
            for (var bad : new String[]{"", "x".repeat(65), "has space", "a/b", "..", "."}) {
                assertThat(validator.validate(house(bad))).as("brokerId '%s'", bad)
                        .extracting(v -> v.getPropertyPath().toString()).containsExactly("brokerId");
            }
        }
    }

    @Test
    void theBrokerIdGoesToTheEntityAndBackAndAPutWithoutOneClearsIt() {
        var id = UUID.randomUUID().toString();
        var entity = new House(UUID.randomUUID());
        house(id).applyTo(entity);
        assertThat(entity.getBrokerId()).isEqualTo(id);
        assertThat(HouseDto.from(entity).brokerId()).isEqualTo(id);
        house(null).applyTo(entity);
        assertThat(entity.getBrokerId()).isNull();
    }

    /** F-16 and PRV-005: a tombstone keeps no reference to a broker, like it keeps no phone number. */
    @Test
    void aTombstoneBlanksTheBrokerIdAndTheContactCopies() {
        var entity = new House(UUID.randomUUID());
        house(UUID.randomUUID().toString()).applyTo(entity);
        entity.purgeContent();
        assertThat(entity.getBrokerId()).isNull();
        assertThat(entity.getContactName()).isNull();
        assertThat(entity.getContactPhone()).isNull();
        assertThat(HouseDto.from(entity).brokerId()).isNull();
    }
}
