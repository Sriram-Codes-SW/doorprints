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

import app.doorprints.server.house.HouseService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HouseSearchServiceTest {

    private static HouseDto house(HouseStatus status, Long price, Integer bedrooms, Integer rating, String notes) {
        return new HouseDto(UUID.randomUUID(), "Blue gate", "12 MG Road", "MG Road", "Indiranagar", 12.97, 77.64,
                status, price, "RENT", bedrooms, rating, null, null, null, notes, null, null, null, null, null, null, null, null, Map.of(), null, null, false, 1,
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

    // ---- S4b-BL-201: a search gives the same houses in the same order every run, and the planner can see them all

    private static final Instant SAME = Instant.parse("2026-10-10T08:00:00Z");

    private static HouseDto at(UUID id, Instant updatedAt, Double distance) {
        return new HouseDto(id, "H-" + id, "addr", "street", "Indiranagar", 12.97, 77.64, HouseStatus.NEW, 30000L,
                "RENT", 2, null, null, null, null, null, null, null, null, null, null, null, null, null, Map.of(), null, updatedAt, false, 1, distance);
    }

    private static List<HouseDto> thirtyWithEqualTimestamps() {
        var list = new ArrayList<HouseDto>();
        for (int i = 0; i < 30; i++) list.add(at(UUID.nameUUIDFromBytes(("eq" + i).getBytes()), SAME, null));
        return list;
    }

    private static HouseSearchService serviceReturning(List<HouseDto> rows) {
        var houses = mock(HouseService.class);
        when(houses.list(any())).thenReturn(rows);
        when(houses.nearby(anyDouble(), anyDouble(), anyDouble())).thenReturn(rows);
        return new HouseSearchService(houses);
    }

    @Test
    void housesWithEqualTimestampsComeBackInIdOrderWhateverOrderTheStoreGaveThem() {
        var rows = thirtyWithEqualTimestamps();
        var expected = rows.stream().map(HouseDto::id).sorted().toList();
        var shuffled = new ArrayList<>(rows);
        for (int seed = 0; seed < 5; seed++) {
            java.util.Collections.shuffle(shuffled, new java.util.Random(seed));
            var got = serviceReturning(shuffled).search(new Criteria(null, null, null, null, null, null, null, null, 50))
                    .stream().map(HouseSearchService.HouseSummary::id).toList();
            assertThat(got).as("seed %d", seed).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void theLimitKeepsTheFirstHousesOfTheStableOrderNotAnArbitraryOnes() {
        var rows = thirtyWithEqualTimestamps();
        var shuffled = new ArrayList<>(rows);
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));
        var expected = rows.stream().map(HouseDto::id).sorted().limit(7).toList();
        var got = serviceReturning(shuffled).search(new Criteria(null, null, null, null, null, null, null, null, 7))
                .stream().map(HouseSearchService.HouseSummary::id).toList();
        assertThat(got).containsExactlyElementsOf(expected);
    }

    @Test
    void theNewestEditStillComesFirstAndTheIdOnlyBreaksTies() {
        var low = new UUID(0, 1);
        var high = new UUID(0, 2);
        var newer = new UUID(0, 9);
        var rows = List.of(at(high, SAME, null), at(low, SAME, null), at(newer, SAME.plusSeconds(5), null));
        var got = serviceReturning(rows).search(null).stream().map(HouseSearchService.HouseSummary::id).toList();
        assertThat(got).containsExactly(newer, low, high);
    }

    @Test
    void nearbyHousesAtTheSameDistanceComeBackInIdOrder() {
        var a = new UUID(0, 1);
        var b = new UUID(0, 2);
        var c = new UUID(0, 3);
        var rows = List.of(at(c, SAME, 50.0), at(b, SAME, 50.0), at(a, SAME, 80.0));
        var got = serviceReturning(rows).nearby(12.97, 77.64, 500).stream().map(HouseSearchService.HouseSummary::id).toList();
        assertThat(got).containsExactly(b, c, a);
    }

    @Test
    void aSearchWithoutALimitReturnsEveryHouseUpToTheCap() {
        assertThat(HouseSearchService.DEFAULT_RESULTS).isEqualTo(HouseSearchService.MAX_RESULTS).isGreaterThanOrEqualTo(50);
        var thirty = serviceReturning(thirtyWithEqualTimestamps());
        assertThat(thirty.search(null)).hasSize(30);
        var many = new ArrayList<HouseDto>();
        for (int i = 0; i < 80; i++) many.add(at(UUID.nameUUIDFromBytes(("m" + i).getBytes()), SAME, null));
        assertThat(serviceReturning(many).search(null)).hasSize(HouseSearchService.MAX_RESULTS);
    }
}
