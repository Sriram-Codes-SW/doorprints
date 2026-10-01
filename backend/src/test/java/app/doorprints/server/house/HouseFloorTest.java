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

/** S4b-BL-87: the house's floor, -5..200 on the sync API, kept through the entity and blanked in the tombstone. */
class HouseFloorTest {

    private static HouseDto house(Integer floor) {
        return new HouseDto(UUID.randomUUID(), "Green View", null, null, null, 12.9, 77.6, HouseStatus.NEW, null, null,
                2, null, null, null, null, null, null, null, null, null, null, null, floor, null, Map.of(), null, null,
                false, 0, null);
    }

    @Test
    void theFloorIsMinusFiveToTwoHundred() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (var ok : new Integer[] {null, -5, 0, 3, 200}) assertThat(validator.validate(house(ok))).as("%s", ok).isEmpty();
            for (var bad : new int[] {-6, 201}) {
                assertThat(validator.validate(house(bad))).extracting(v -> v.getPropertyPath().toString()).containsExactly("floor");
            }
        }
    }

    @Test
    void theFloorRoundTripsThroughTheEntityAndTheTombstoneDropsIt() {
        var entity = new House(UUID.randomUUID());
        house(0).applyTo(entity);
        assertThat(HouseDto.from(entity).floor()).isEqualTo(0);
        entity.purgeContent();
        assertThat(HouseDto.from(entity).floor()).isNull();
    }
}
