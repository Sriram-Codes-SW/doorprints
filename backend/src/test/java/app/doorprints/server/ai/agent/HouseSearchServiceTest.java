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

import app.doorprints.server.ai.agent.HouseSearchService.Criteria;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HouseSearchServiceTest {

    private static HouseDto house(HouseStatus status, Long price, Integer bedrooms, Integer rating, String notes) {
        return new HouseDto(UUID.randomUUID(), "Blue gate", "12 MG Road", "MG Road", "Indiranagar", 12.97, 77.64,
                status, price, "RENT", bedrooms, rating, null, null, null, notes, null, null, null, null, null, null, Map.of(), null, null, false, 1,
                null);
    }

    @Test
    void filtersCombine() {
        var h = house(HouseStatus.SHORTLISTED, 30000L, 2, 4, "Great water pressure");
        assertThat(HouseSearchService.matches(h, null)).isTrue();
        assertThat(HouseSearchService.matches(h, new Criteria("water", HouseStatus.SHORTLISTED, "rent", null, 35000L, 2, null, 4, null))).isTrue();
        assertThat(HouseSearchService.matches(h, new Criteria(null, null, null, null, 25000L, null, null, null, null))).isFalse();
        assertThat(HouseSearchService.matches(h, new Criteria(null, HouseStatus.REJECTED, null, null, null, null, null, null, null))).isFalse();
        assertThat(HouseSearchService.matches(h, new Criteria(null, null, "SALE", null, null, null, null, null, null))).isFalse();
        assertThat(HouseSearchService.matches(h, new Criteria(null, null, null, null, null, 3, null, null, null))).isFalse();
        assertThat(HouseSearchService.matches(h, new Criteria("lift", null, null, null, null, null, null, null, null))).isFalse();
    }

    @Test
    void missingValuesDoNotMatchNumericFilters() {
        var h = house(HouseStatus.NEW, null, null, null, null);
        assertThat(HouseSearchService.matches(h, new Criteria(null, null, null, null, 50000L, null, null, null, null))).isFalse();
        assertThat(HouseSearchService.matches(h, new Criteria(null, null, null, null, null, null, null, 3, null))).isFalse();
    }

    @Test
    void toolArgumentsAreValidated() {
        assertThatThrownBy(() -> HouseQueries.parseId("house-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HouseQueries.parseStatus("MAYBE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HouseQueries.parsePriceType("LEASE")).isInstanceOf(IllegalArgumentException.class);
        assertThat(HouseQueries.parseStatus(" shortlisted ")).isEqualTo(HouseStatus.SHORTLISTED);
        assertThat(HouseQueries.parsePriceType("rent")).isEqualTo("RENT");
    }
}
