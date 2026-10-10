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

import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseService;
import app.doorprints.server.house.HouseStatus;
import app.doorprints.server.visit.VisitRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S4b-BL-201: the planner's {@code searchHouses} tool must show it every house a user has, up to a stated cap. With the
 * old default of 20, a status-only search over the 30 golden-set houses lost 10 of them, in an order that changed from
 * run to run. Real HouseQueries and HouseSearchService; only the house service is mocked.
 */
class HouseQueriesSearchTest {

    private static List<HouseDto> houses(int n, HouseStatus status) {
        var list = new ArrayList<HouseDto>();
        var now = Instant.parse("2026-10-10T08:00:00Z");
        for (int i = 0; i < n; i++) {
            list.add(new HouseDto(UUID.nameUUIDFromBytes(("q" + i).getBytes()), "House " + i, "addr", "street",
                    "Indiranagar", 12.97, 77.64, status, 30000L, "RENT", 2, null, null, null, null, null, null, null, null, null, null, null, null, null, Map.of(), null, now.minusSeconds(i), false, 1, null));
        }
        return list;
    }

    private static HouseQueries queriesOver(List<HouseDto> rows) {
        var service = mock(HouseService.class);
        when(service.list(any())).thenReturn(rows);
        return new HouseQueries(new HouseSearchService(service), mock(VisitRepository.class));
    }

    @Test
    void anUnfilteredSearchOverThirtyFiveHousesReturnsAllOfThem() {
        var found = queriesOver(houses(35, HouseStatus.NEW)).searchHouses(null, null, null, null, null, null, null);
        assertThat(found).hasSize(35);
        assertThat(found.stream().map(HouseSearchService.HouseSummary::id).distinct()).hasSize(35);
    }

    @Test
    void aStatusOnlySearchOverTheThirtyGoldenSetHousesLosesNone() {
        var found = queriesOver(houses(30, HouseStatus.NEW)).searchHouses(null, "new", null, null, null, null, null);
        assertThat(found).hasSize(30);
    }

    @Test
    void theCapIsFiftyHousesAndAnExplicitLimitStillWins() {
        var queries = queriesOver(houses(80, HouseStatus.NEW));
        assertThat(queries.searchHouses(null, null, null, null, null, null, null)).hasSize(HouseSearchService.MAX_RESULTS);
        assertThat(queries.searchHouses(null, null, null, null, null, null, 12)).hasSize(12);
    }

    @Test
    void theSummaryStillCarriesNoAddressNotesOrContactFields() {
        var names = Arrays.stream(HouseSearchService.HouseSummary.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(names).containsExactly("id", "label", "locality", "street", "status", "price", "priceType",
                "bedrooms", "rating", "lat", "lon", "distanceMeters");
    }
}
