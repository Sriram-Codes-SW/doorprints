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

import app.doorprints.server.ai.HouseAiPins;
import app.doorprints.server.ai.agent.HouseSearchService.Criteria;
import app.doorprints.server.ai.agent.HouseSearchService.HouseSummary;
import app.doorprints.server.house.HouseDto;
import app.doorprints.server.house.HouseStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Plan half of the house pins of kinds PR 7 (S4b-BL-211; {@link HouseAiPins}): what the agent's and the MCP
 * server's tools return for each house (the summary, the details), which houses each filter of {@code searchHouses}
 * keeps, the wrap-up notice and the system prompt. Pinned on main before the kind-aware code; a house's output never
 * changes.
 */
class HousePlanPinsTest {

    private static final Map<String, Criteria> FILTERS = new LinkedHashMap<>();

    static {
        FILTERS.put("none", null);
        FILTERS.put("status", new Criteria(null, HouseStatus.SHORTLISTED, null, null, null, null, null, null, null));
        FILTERS.put("rent", new Criteria(null, null, "RENT", null, null, null, null, null, null));
        FILTERS.put("sale", new Criteria(null, null, "sale", null, null, null, null, null, null));
        FILTERS.put("price", new Criteria(null, null, null, 15000L, 30000L, null, null, null, null));
        FILTERS.put("bedrooms", new Criteria(null, null, null, null, null, 2, 3, null, null));
        FILTERS.put("rating", new Criteria(null, null, null, null, null, null, null, 4, null));
        FILTERS.put("text", new Criteria("metro", null, null, null, null, null, null, null, null));
        FILTERS.put("contact", new Criteria("ramesh", null, null, null, null, null, null, null, null));
        FILTERS.put("all", new Criteria("road", HouseStatus.NEW, "RENT", 0L, 60000L, 1, 4, 3, 10));
    }

    @Test
    void aHousesPlanSummariesDetailsAndFiltersAreThoseOfMain() throws Exception {
        var houses = new ArrayList<>(HouseAiPins.fixtureHouses());
        houses.add(HouseAiPins.richHouse());
        var out = new LinkedHashMap<String, String>();
        var seen = new LinkedHashMap<UUID, HouseSummary>();
        for (HouseDto h : houses) {
            var summary = HouseSummary.of(h);
            seen.put(h.id(), summary);
            out.put("summary." + h.id(), summary.toString());
            out.put("details." + h.id(), HouseQueries.HouseDetails.of(h).toString());
        }
        FILTERS.forEach((name, c) -> out.put("filter." + name,
                houses.stream().filter(h -> HouseSearchService.matches(h, c)).map(h -> h.id().toString()).toList().toString()));
        // The block's nonce is random per call; the rest of the notice is pinned.
        out.put("notice", VisitPlannerService.limitNotice(java.util.List.of("searchHouses", "searchHouses"), seen)
                .replaceAll("houses-[0-9a-f]{6}", "houses-NONCE"));
        out.put("prompt.system", VisitPlannerService.systemPrompt(12.9716, 77.5946, 5));
        HouseAiPins.check("plan", out);
    }
}
